package com.airgrab

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File

/**
 * The user's photos, so a gesture can send the real file.
 *
 * The obvious thing to want is "send the photo I am looking at", and Android
 * will not provide it. No application can ask what another application is
 * displaying — that is a deliberate privacy boundary, and the reason Huawei
 * can do it on their handsets is that their gesture service is part of the
 * operating system rather than an app running on top of one.
 *
 * A screenshot would sidestep the boundary and produce a screen-resolution
 * image with the gallery's buttons in it. What the user asked for was the
 * original file, so this reads the photo library directly: the most recent
 * photo covers "I just took this, send it", and the picker covers the rest.
 * Both hand over the real JPEG, untouched.
 */
object PhotoLibrary {

    const val TAG = "AirGrabPhotos"

    data class Photo(
        val uri: Uri,
        val name: String,
        val takenAt: Long,
        val size: Long,
    )

    /** The permission needed, which changed name in Android 13. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * The most recent photos, newest first.
     *
     * Sorted by DATE_ADDED rather than DATE_TAKEN: a photo saved from a chat
     * has no taken-date at all, and would sort to the beginning of time and
     * never appear.
     */
    fun recent(context: Context, limit: Int = 12): List<Photo> {
        if (!permitted(context)) return emptyList()

        val columns = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
        )

        return runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                columns,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        val id = cursor.getLong(idColumn)
                        add(
                            Photo(
                                uri = ContentUris.withAppendedId(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                                ),
                                name = cursor.getString(nameColumn) ?: "photo-$id.jpg",
                                takenAt = cursor.getLong(dateColumn),
                                size = cursor.getLong(sizeColumn),
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrElse {
            Log.w(TAG, "could not read the photo library: ${it.message}")
            emptyList()
        }
    }

    fun latest(context: Context): Photo? = recent(context, limit = 1).firstOrNull()

    /**
     * Copy the most recent photo into the outgoing slot, ready for a grab.
     *
     * Called when the user makes a fist having shared nothing, which is the
     * case worth optimising: take a photo, make a fist, it is on the PC.
     */
    fun holdLatest(context: Context): File? {
        val photo = latest(context) ?: return null
        Log.i(TAG, "grabbing the latest photo: ${photo.name}")
        return PendingContent.accept(context, photo.uri)
    }

    /**
     * A thumbnail for the picker.
     *
     * `loadThumbnail` reads the embedded preview where one exists rather than
     * decoding a twelve-megapixel JPEG, which is the difference between a grid
     * that appears instantly and one that stutters.
     */
    fun thumbnail(context: Context, photo: Photo, edge: Int): Bitmap? = runCatching {
        context.contentResolver.loadThumbnail(photo.uri, Size(edge, edge), null)
    }.getOrNull()
}
