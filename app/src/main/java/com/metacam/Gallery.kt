package com.metacam

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun deleteAfterExport(c: Context): Boolean = sp(c).getBoolean("del_after_export", false)

    fun setDeleteAfterExport(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("del_after_export", v).apply()
    }
}

object Gallery {
    private const val SUB = "MetaCam"

    private fun dirFor(mime: String): String = when {
        mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
        mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_DOWNLOADS
    }

    fun export(c: Context, f: File): Result<Unit> = runCatching {
        if (!f.isFile) error("arquivo não existe")
        var mime = Cleaner.mimeOf(f)
        if (mime == "*/*") mime = "application/octet-stream"
        if (Build.VERSION.SDK_INT >= 29) viaMediaStore(c, f, mime) else legacy(c, f, mime)
    }

    private fun viaMediaStore(c: Context, f: File, mime: String) {
        val resolver = c.contentResolver
        val base = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dirFor(mime) + "/" + SUB)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(base, values) ?: error("não foi possível criar na galeria")
        try {
            val out = resolver.openOutputStream(uri) ?: error("não foi possível gravar")
            out.use { o -> f.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (e: Exception) {
            }
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun legacy(c: Context, f: File, mime: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(dirFor(mime)), SUB)
        if (!dir.isDirectory && !dir.mkdirs()) error("não foi possível criar a pasta")
        var out = File(dir, f.name)
        var n = 1
        while (out.exists()) {
            val ext = if (f.extension.isEmpty()) "" else "." + f.extension
            out = File(dir, f.nameWithoutExtension + "-" + n + ext)
            n++
        }
        try {
            f.copyTo(out)
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        MediaScannerConnection.scanFile(c, arrayOf(out.absolutePath), arrayOf(mime), null)
    }
}
