package com.airgrab

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.airgrab.core.safeFileName
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * What a grab picks up.
 *
 * On the desktop this is a screen capture, because a PC has one obvious thing
 * the user is looking at. A phone does not: the user is in their gallery, or a
 * chat, or a file manager, and the thing they mean is whichever item they
 * chose — not whatever happens to be on screen.
 *
 * So the phone's answer is Android's own share sheet. The user shares a photo
 * or a document to AirGrab, and the next grab picks it up. That reuses a
 * gesture every Android user already knows, needs no extra permission, and
 * avoids MediaProjection's full-screen recording prompt for something the user
 * has already pointed at.
 */
object PendingContent {

    const val TAG = "AirGrabContent"

    /** Room for a video; beyond this the copy is refused rather than attempted. */
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    private val pending = AtomicReference<File?>(null)

    /** The file a grab would send, or null if nothing is waiting. */
    fun current(): File? = pending.get()?.takeIf { it.exists() }

    fun clear() {
        pending.getAndSet(null)?.let { runCatching { it.delete() } }
    }

    /**
     * Copy a shared URI into private storage and hold it ready.
     *
     * The copy is deliberate rather than lazy. A content URI is a temporary
     * grant: it is revoked when the sharing activity finishes, which is long
     * before a gesture happens. Holding the URI would give a transfer that
     * failed with a permission error minutes later, pointing nowhere near the
     * share that caused it.
     */
    fun accept(context: Context, uri: Uri): File? {
        val resolver = context.contentResolver
        val name = displayName(resolver, uri)
        val size = sizeOf(resolver, uri)

        if (size != null && size > MAX_BYTES) {
            Log.w(TAG, "refusing $name: $size bytes")
            return null
        }

        // Released BEFORE the copy, never after.
        //
        // clear() deletes whatever is pending, and re-sharing the same file
        // resolves to the same path — so clearing afterwards deleted the copy
        // that had just been written. The symptom was a share that worked the
        // first time and produced a zero-byte file every time after.
        pending.set(null)

        val directory = File(context.cacheDir, "outgoing").apply {
            mkdirs()
            // Only one item is ever pending, so anything else here is left
            // over from a share the user never completed. This is also what
            // reclaims the space the previous share used.
            listFiles()?.forEach { it.delete() }
        }

        val destination = File(directory, name)
        return try {
            resolver.openInputStream(uri).use { input ->
                if (input == null) return null
                destination.outputStream().use { output -> input.copyTo(output) }
            }

            // A zero-byte result means the provider gave us nothing readable.
            // Holding it would fail later as a transfer of an empty file,
            // which looks like a network problem rather than a share problem.
            if (destination.length() == 0L && (size ?: 0L) > 0L) {
                Log.e(TAG, "copy of $name produced no bytes")
                destination.delete()
                return null
            }

            pending.set(destination)
            Log.i(TAG, "holding ${destination.name} (${destination.length()} bytes)")
            destination
        } catch (exc: Throwable) {
            Log.e(TAG, "could not read the shared file", exc)
            destination.delete()
            null
        }
    }

    /**
     * The file's name as the user knows it.
     *
     * Falls back rather than failing: a provider is not obliged to supply one,
     * and a transfer that refused to start because a name was missing would be
     * a poor trade for a file the user can perfectly well receive as
     * "shared-file".
     */
    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        val queried = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()

        val raw = queried ?: uri.lastPathSegment ?: "shared-file"

        // Same rule the receiving side applies, from the same tested function.
        // The name came from another application, and must not carry path
        // separators into a filename here or on the peer.
        return safeFileName(raw, fallback = "shared-file")
    }

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()
}
