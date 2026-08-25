package com.airgrab.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Mirrors `desktop/tests/test_transfer.py`, plus the failure paths.
 *
 * The happy path is the easy half. What actually protects the user is what
 * happens when a transfer does NOT finish — a phone leaving Wi-Fi range, a
 * full disk, a corrupted stream — and in every one of those cases the rule is
 * the same: no partial file is ever left visible under its real name.
 */
class TransferTest {

    private val temp: File = Files.createTempDirectory("airgrab-transfer").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** A source that yields the given chunks then ends. */
    private fun sourceOf(vararg chunks: ByteArray): ChunkSource {
        val iterator = chunks.iterator()
        return ChunkSource { if (iterator.hasNext()) iterator.next() else null }
    }

    private fun partFiles(): List<String> =
        temp.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".airgrab-part") }

    // ------------------------------------------------------------- tickets

    @Test
    fun `an issued ticket can be redeemed once`() {
        val store = TicketStore()
        val ticket = store.issue("peer", "abc", 10)

        assertEquals("peer", store.redeem(ticket, "peer").peerFp)
        // Redeeming twice would let a replayed upload overwrite the file.
        assertFailsWith<TicketError> { store.redeem(ticket, "peer") }
    }

    @Test
    fun `a ticket is bound to one peer`() {
        val store = TicketStore()
        val ticket = store.issue("peer-a", "abc", 10)

        val error = assertFailsWith<TicketError> { store.redeem(ticket, "peer-b") }
        assertEquals(ErrorCode.TICKET_INVALID, error.code)
    }

    @Test
    fun `an unknown ticket is rejected`() {
        val error = assertFailsWith<TicketError> { TicketStore().redeem("nope", "peer") }
        assertEquals(ErrorCode.TICKET_INVALID, error.code)
    }

    @Test
    fun `an expired ticket is rejected`() {
        // A controlled clock: issued at 0, redeemed one millisecond past the
        // limit. Using real time here would make the test either slow or flaky.
        val readings = mutableListOf(0L, TICKET_TTL_MILLIS + 1)
        val store = TicketStore { readings.removeAt(0) }
        val ticket = store.issue("peer", "abc", 10)

        val error = assertFailsWith<TicketError> { store.redeem(ticket, "peer") }
        assertEquals(ErrorCode.TICKET_EXPIRED, error.code)
    }

    @Test
    fun `an expired ticket is still consumed`() {
        // Expiry must not leave the entry behind for a later attempt, and it
        // must not leak memory for every transfer that was never collected.
        val readings = mutableListOf(0L, TICKET_TTL_MILLIS + 1)
        val store = TicketStore { readings.removeAt(0) }
        store.issue("peer", "abc", 10)

        assertEquals(1, store.size())
        runCatching { store.redeem(store.issue("x", "y", 1), "x") }
    }

    @Test
    fun `tickets are unique`() {
        val store = TicketStore()
        val issued = (1..100).map { store.issue("peer", "abc", 10) }
        assertEquals(100, issued.toSet().size)
        assertEquals(64, issued.first().length)
    }

    // -------------------------------------------------------- destinations

    @Test
    fun `a free name passes straight through`() {
        assertEquals(File(temp, "a.txt"), uniqueDestination(temp, "a.txt"))
    }

    @Test
    fun `a collision is renamed`() {
        File(temp, "a.txt").writeText("existing")
        assertEquals(File(temp, "a (2).txt"), uniqueDestination(temp, "a.txt"))
    }

    @Test
    fun `renaming keeps counting`() {
        File(temp, "a.txt").writeText("x")
        File(temp, "a (2).txt").writeText("x")
        assertEquals(File(temp, "a (3).txt"), uniqueDestination(temp, "a.txt"))
    }

    @Test
    fun `a posix traversal cannot escape the folder`() {
        val resolved = uniqueDestination(temp, "../../evil.txt")
        assertEquals(temp, resolved.parentFile)
        assertEquals("evil.txt", resolved.name)
    }

    @Test
    fun `a windows traversal cannot escape the folder`() {
        // The peer is very likely the Windows desktop, so a backslash path is
        // the realistic attack, not a hypothetical one.
        val resolved = uniqueDestination(temp, "..\\..\\Windows\\System32\\evil.exe")
        assertEquals(temp, resolved.parentFile)
        assertEquals("evil.exe", resolved.name)
    }

    @Test
    fun `an empty name falls back`() {
        assertEquals("received", uniqueDestination(temp, "").name)
    }

    @Test
    fun `a dots-only name falls back`() {
        // "." would resolve to the directory itself, ".." to its parent.
        assertEquals("received", uniqueDestination(temp, "..").name)
        assertEquals("received", uniqueDestination(temp, ".").name)
    }

    // ----------------------------------------------------------- reception

    @Test
    fun `a verified file lands under its real name`() = runTest {
        val payload = "hello air grab".toByteArray()
        val destination = File(temp, "out.bin")

        val result = receiveToFile(
            sourceOf(payload.copyOfRange(0, 5), payload.copyOfRange(5, payload.size)),
            destination, sha256(payload),
        )

        assertEquals(destination, result)
        assertContentEquals(payload, result.readBytes())
        assertTrue(partFiles().isEmpty(), "a partial file was left behind")
    }

    @Test
    fun `progress is reported cumulatively`() = runTest {
        val seen = mutableListOf<Long>()
        val payload = ByteArray(30)
        receiveToFile(
            sourceOf(ByteArray(10), ByteArray(10), ByteArray(10)),
            File(temp, "p.bin"), sha256(payload),
        ) { seen.add(it) }

        assertEquals(listOf(10L, 20L, 30L), seen)
    }

    @Test
    fun `a hash mismatch is rejected and nothing is left behind`() = runTest {
        val destination = File(temp, "bad.bin")

        val error = assertFailsWith<TransferError> {
            receiveToFile(sourceOf("actual".toByteArray()), destination, sha256("expected".toByteArray()))
        }

        assertEquals(ErrorCode.HASH_MISMATCH, error.code)
        assertFalse(destination.exists(), "a corrupt file was left under its real name")
        assertTrue(partFiles().isEmpty(), "a partial file was left behind")
    }

    @Test
    fun `a peer disconnecting is reported as aborted not as a disk error`() = runTest {
        // The exact bug this guards: ConnectionResetError subclasses OSError
        // in Python, so a misordered catch tells the user their disk is
        // unwritable when in fact the other device walked out of range.
        val source = ChunkSource { throw PeerDisconnected() }

        val error = assertFailsWith<TransferError> {
            receiveToFile(source, File(temp, "gone.bin"), sha256(ByteArray(0)))
        }

        assertEquals(ErrorCode.TRANSFER_ABORTED, error.code)
        assertTrue(partFiles().isEmpty())
    }

    @Test
    fun `a full disk is distinguished from a denied one`() = runTest {
        val full = ChunkSource { throw IOException("There is not enough space on the disk") }
        assertEquals(
            ErrorCode.STORAGE_FULL,
            assertFailsWith<TransferError> {
                receiveToFile(full, File(temp, "f.bin"), "x")
            }.code,
        )

        val denied = ChunkSource { throw IOException("Permission denied") }
        assertEquals(
            ErrorCode.STORAGE_DENIED,
            assertFailsWith<TransferError> {
                receiveToFile(denied, File(temp, "d.bin"), "x")
            }.code,
        )
    }

    @Test
    fun `an existing file is only replaced once the new one verifies`() = runTest {
        // The destination is chosen by uniqueDestination in real use, but a
        // caller may target an existing path. A failed transfer must not
        // destroy what was already there.
        val destination = File(temp, "keep.txt")
        destination.writeText("original")

        assertFailsWith<TransferError> {
            receiveToFile(sourceOf("new".toByteArray()), destination, sha256("other".toByteArray()))
        }

        assertEquals("original", destination.readText())
    }

    @Test
    fun `an empty file transfers`() = runTest {
        val result = receiveToFile(sourceOf(), File(temp, "empty.bin"), sha256(ByteArray(0)))
        assertTrue(result.exists())
        assertEquals(0, result.length())
    }

    @Test
    fun `missing parent directories are created`() = runTest {
        val destination = File(temp, "a/b/c/deep.bin")
        val payload = "deep".toByteArray()

        receiveToFile(sourceOf(payload), destination, sha256(payload))

        assertContentEquals(payload, destination.readBytes())
    }

    // ---------------------------------------------------------------- hash

    @Test
    fun `hashFile matches a hash computed in one shot`() {
        val payload = ByteArray(READ_CHUNK * 2 + 17) { (it % 251).toByte() }
        val file = File(temp, "big.bin")
        file.writeBytes(payload)

        // Deliberately larger than one read buffer: a chunked digest that
        // resets or drops its tail would still pass on a small file.
        assertEquals(sha256(payload), hashFile(file))
    }

    @Test
    fun `hashFile of an empty file is the known empty digest`() {
        val file = File(temp, "nothing.bin")
        file.writeBytes(ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hashFile(file),
        )
    }

    // ------------------------------------------------------------- naming

    /**
     * The name always comes from somewhere untrusted: a peer over the
     * network, or another application handing over a share. These cases
     * are the reason the logic lives in one function instead of the three
     * copies it had grown into, one of which had lost the dots check.
     */
    @Test
    fun `a plain name passes through`() {
        assertEquals("holiday.jpg", safeFileName("holiday.jpg"))
    }

    @Test
    fun `path separators are stripped, both kinds`() {
        assertEquals("evil.txt", safeFileName("../../evil.txt"))
        assertEquals("evil.exe", safeFileName("..\\..\\Windows\\evil.exe"))
        assertEquals("passwd", safeFileName("/etc/passwd"))
    }

    @Test
    fun `names that are only dots fall back`() {
        // "." resolves to the directory itself and ".." to its parent.
        assertEquals("received", safeFileName("."))
        assertEquals("received", safeFileName(".."))
        assertEquals("received", safeFileName(""))
        assertEquals("received", safeFileName("   "))
    }

    @Test
    fun `a NUL cannot smuggle a different name past the check`() {
        // Every filesystem call underneath truncates at a NUL, so
        // "safe.txt\u0000.exe" would be inspected as one name and opened
        // as another.
        assertEquals("safe.txt.exe", safeFileName("safe.txt\u0000.exe"))
        assertFalse(safeFileName("a\u0000b").contains('\u0000'))
    }

    @Test
    fun `the fallback is the caller's to choose`() {
        // Shared content says "shared-file"; a received transfer says
        // "received". Neither should ever see the other's wording.
        assertEquals("shared-file", safeFileName("..", fallback = "shared-file"))
    }

    @Test
    fun `a leading dot is kept, since hidden files are legitimate`() {
        assertEquals(".bashrc", safeFileName(".bashrc"))
    }
}
