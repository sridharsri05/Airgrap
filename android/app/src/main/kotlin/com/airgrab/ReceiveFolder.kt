package com.airgrab

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where received files land, and the user's say in it.
 *
 * The first version used app-private external storage, which needs no
 * permission and sounded tidy. It was also invisible: modern Android refuses
 * to let file managers browse `Android/data`, so a received photo existed
 * somewhere the user could never navigate to. A transfer the user cannot
 * find afterwards did not happen, as far as they are concerned.
 *
 * The choices are the public collections Android still allows direct file
 * writes into (this app's minimum is Android 12, where contributing your own
 * files to Downloads or Pictures needs no permission at all), plus the old
 * private folder for anyone who prefers files nothing else can index.
 */
object ReceiveFolder {

    private const val PREFS = "airgrab-settings"
    private const val KEY = "receive-folder"

    enum class Choice(val label: String) {
        DOWNLOADS("Downloads"),
        PICTURES("Pictures"),
        PRIVATE("App storage (hidden from other apps)"),
    }

    fun current(context: Context): Choice {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        return Choice.entries.firstOrNull { it.name == stored } ?: Choice.DOWNLOADS
    }

    fun set(context: Context, choice: Choice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, choice.name).apply()
    }

    /** The actual directory, created if needed. */
    fun directory(context: Context): File {
        val base = when (current(context)) {
            Choice.DOWNLOADS ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            Choice.PICTURES ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            Choice.PRIVATE ->
                context.getExternalFilesDir(null) ?: context.filesDir
        }
        return File(base, "AirGrab").apply { mkdirs() }
    }

    /**
     * Open the folder in whatever file UI this phone has.
     *
     * Android has no reliable "open this directory" intent across
     * manufacturers, so this is a ladder: the system Downloads screen when
     * that is where files land, then the documents UI pointed at the folder,
     * and if a phone's firmware has neither the caller falls back to showing
     * the path in words.
     */
    fun open(context: Context): Boolean {
        val intents = buildList {
            if (current(context) == Choice.DOWNLOADS) {
                add(android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
            }
            add(
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        android.provider.DocumentsContract.buildRootUri(
                            "com.android.externalstorage.documents", "primary"
                        ),
                        "vnd.android.document/root",
                    )
                }
            )
        }
        for (intent in intents) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** The path as the user would say it, for the screen. */
    fun describe(context: Context): String = when (current(context)) {
        Choice.DOWNLOADS -> "Downloads › AirGrab"
        Choice.PICTURES -> "Pictures › AirGrab"
        Choice.PRIVATE -> "AirGrab's own storage (not visible to other apps)"
    }
}
