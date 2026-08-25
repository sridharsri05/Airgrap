package com.airgrab.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ticket issuance and verified file reception.
 *
 * Ported from `desktop/airgrab/transfer.py`, guaranteeing the same two
 * invariants:
 *
 * 1. A file visible under its real name is complete and hash-verified. Bytes
 *    land in a temporary file and are renamed only after verification, so a
 *    partial or corrupt transfer is never observable as a real file. On a
 *    phone this matters more than on a PC: the media scanner will happily
 *    index a half-written image the moment it appears in the gallery.
 * 2. A ticket authorises exactly one upload, from exactly one peer, for at
 *    most sixty seconds.
 *
 * Deliberately free of any networking dependency. The byte source is an
 * interface, so reception can be tested without a server and the Android
 * service can hand it a socket without this file knowing what a socket is.
 */

const val TICKET_TTL_MILLIS = 60_000L
private const val TICKET_BYTES = 32
const val READ_CHUNK = 256 * 1024

class TicketError(val code: String) : Exception(code)

class TransferError(val code: String, cause: Throwable? = null) : Exception(code, cause)

/**
 * Raised by a [ChunkSource] when the PEER went away, as opposed to when the
 * local disk failed.
 *
 * Python distinguishes these with an isinstance check on `ConnectionError`,
 * and that ordering is fragile: `ConnectionResetError` subclasses `OSError`,
 * so getting the branches the wrong way round reports a phone walking out of
 * Wi-Fi range as a disk permission error. Having the source say which one it
 * means removes the guess entirely.
 */
class PeerDisconnected(cause: Throwable? = null) : IOException("peer disconnected", cause)

/** An asynchronous source of bytes. Returns null when the stream ends. */
fun interface ChunkSource {
    suspend fun next(): ByteArray?
}

data class Ticket(
    val value: String,
    val peerFp: String,
    val expectedSha256: String,
    val size: Long,
    val issuedAt: Long,
)

/**
 * Single-use, peer-bound, time-limited upload authorisations.
 *
 * Expiry uses a monotonic clock so that a system clock change mid-transfer —
 * a phone syncing time on joining a network, say — cannot expire a live
 * ticket or resurrect a dead one.
 */
class TicketStore(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {

    private val random = SecureRandom()
    private val tickets = LinkedHashMap<String, Ticket>()

    fun issue(peerFp: String, expectedSha256: String, size: Long): String {
        val bytes = ByteArray(TICKET_BYTES)
        random.nextBytes(bytes)
        val value = bytes.joinToString("") { "%02x".format(it) }
        synchronized(tickets) {
            tickets[value] = Ticket(value, peerFp, expectedSha256, size, clock())
        }
        return value
    }

    fun redeem(ticket: String, peerFp: String): Ticket {
        val found = synchronized(tickets) { tickets[ticket] }
            ?: throw TicketError(ErrorCode.TICKET_INVALID)

        // Constant-time: a ticket is a secret, and a comparison that returns
        // early on the first differing byte leaks its prefix to anyone able
        // to time the response.
        if (!MessageDigest.isEqual(found.peerFp.toByteArray(), peerFp.toByteArray())) {
            throw TicketError(ErrorCode.TICKET_INVALID)
        }

        synchronized(tickets) { tickets.remove(ticket) }

        if (clock() - found.issuedAt > TICKET_TTL_MILLIS) {
            throw TicketError(ErrorCode.TICKET_EXPIRED)
        }
        return found
    }

    fun discard(ticket: String) {
        synchronized(tickets) { tickets.remove(ticket) }
    }

    fun size(): Int = synchronized(tickets) { tickets.size }
}

/**
 * Reduce a name from elsewhere to something safe to use as a filename.
 *
 * "Elsewhere" is either a peer over the network or another application on
 * this device, and neither is trusted. The basename is taken deliberately: a
 * name containing path separators must never escape the folder it is written
 * into. Backslashes are normalised first, because the peer is very likely the
 * Windows desktop and a Windows-style traversal must not survive on Android
 * either.
 *
 * This lives in one place because it existed in three, and they had drifted:
 * the copy guarding shared content had lost the dots check, so a file called
 * ".." resolved to the containing directory's parent.
 */
fun safeFileName(raw: String, fallback: String = "received"): String {
    val basename = raw.replace("\\", "/").substringAfterLast('/').trim()

    // A name of only dots resolves to the directory itself or its parent.
    if (basename.isEmpty() || basename == "." || basename == "..") return fallback

    // Filtered character by character rather than through an escape
    // sequence, which is how a literal NUL byte ended up in this source
    // once already. A NUL truncates the path in every filesystem call
    // underneath, so a name containing one can address a different file
    // than it appears to.
    return basename.filter { it != '\u0000' && it != '/' }
        .ifEmpty { fallback }
}

/**
 * Space left unused after a copy, so the phone still works afterwards.
 *
 * A device with literally zero bytes free cannot write a log, take a photo,
 * or in some cases finish booting. Filling the last of it to queue one
 * outgoing file would be a poor trade.
 */
const val STORAGE_HEADROOM_BYTES = 256L * 1024 * 1024

/**
 * Whether a file of [size] can be copied into a volume with [usable] bytes
 * free, without eating the headroom.
 *
 * This replaced a flat two-gigabyte ceiling. That number refused a three-hour
 * video on a phone with 200GB free, and accepted a 1.9GB one on a phone with
 * 300MB free — wrong in both directions, because the question was never how
 * big the file is. It is whether it fits.
 */
fun fitsWithHeadroom(
    size: Long,
    usable: Long,
    headroom: Long = STORAGE_HEADROOM_BYTES,
): Boolean = size >= 0 && usable - size >= headroom

/**
 * Resolve a safe, non-colliding path inside [directory].
 */
fun uniqueDestination(directory: File, filename: String): File {
    val cleaned = safeFileName(filename)

    var candidate = File(directory, cleaned)
    if (!candidate.exists()) return candidate

    val dot = cleaned.lastIndexOf('.')
    val stem = if (dot > 0) cleaned.substring(0, dot) else cleaned
    val suffix = if (dot > 0) cleaned.substring(dot) else ""

    var counter = 2
    while (true) {
        candidate = File(directory, "$stem ($counter)$suffix")
        if (!candidate.exists()) return candidate
        counter++
    }
}

/**
 * Stream [chunks] to [destination], verifying the hash before the file is
 * given its real name.
 *
 * Every failure path deletes the partial file. A leftover `.part` is not
 * merely untidy: the user sees a mystery file they cannot open and did not
 * ask for, and on Android it counts against their storage forever.
 */
suspend fun receiveToFile(
    chunks: ChunkSource,
    destination: File,
    expectedSha256: String,
    progress: (Long) -> Unit = {},
): File {
    destination.parentFile?.mkdirs()
    val tempFile = File(destination.parentFile, ".${destination.name}.airgrab-part")

    val digest = MessageDigest.getInstance("SHA-256")
    var written = 0L

    try {
        FileOutputStream(tempFile).use { out ->
            while (true) {
                val chunk = chunks.next() ?: break
                out.write(chunk)
                digest.update(chunk)
                written += chunk.size
                progress(written)
            }
            out.flush()
            // Force to physical storage before the rename. Without this the
            // rename can be durable while the contents are not, leaving a
            // correctly named file full of zeroes after a power loss.
            out.fd.sync()
        }
    } catch (exc: PeerDisconnected) {
        tempFile.delete()
        throw TransferError(ErrorCode.TRANSFER_ABORTED, exc)
    } catch (exc: IOException) {
        tempFile.delete()
        val full = exc.message?.contains("space", ignoreCase = true) == true
        throw TransferError(
            if (full) ErrorCode.STORAGE_FULL else ErrorCode.STORAGE_DENIED, exc
        )
    } catch (exc: Throwable) {
        tempFile.delete()
        throw TransferError(ErrorCode.TRANSFER_ABORTED, exc)
    }

    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (actual != expectedSha256) {
        tempFile.delete()
        throw TransferError(ErrorCode.HASH_MISMATCH)
    }

    moveInto(tempFile, destination)
    return destination
}

/**
 * Rename, preferring an atomic move.
 *
 * `File.renameTo` is not used: it refuses to overwrite on Windows and returns
 * false rather than throwing, so a failure would be silent. The fallback
 * exists because ATOMIC_MOVE is not supported on every Android storage
 * volume, notably across the boundary into shared storage.
 */
private fun moveInto(source: File, destination: File) {
    try {
        Files.move(
            source.toPath(), destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING
        )
    }
}

/** SHA-256 of a file, streamed so a large video need not fit in RAM. */
fun hashFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(READ_CHUNK)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
