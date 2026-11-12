/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Adapted from vampirepapi/Metrolist: copy downloaded songs to the public
 * Music/Metrolist folder (e.g. for smartwatch / off-device transfer).
 */
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.metrolist.music.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Exports a downloaded track from the player/download cache to the device's
 * public Music/Metrolist folder so it shows up as a normal audio file.
 */
object MusicFileExporter {
    private const val TAG = "MusicFileExporter"
    private const val SUBFOLDER = "Metrolist"

    /**
     * Export one cached song to Music/Metrolist/.
     * @param context     App context.
     * @param songId      Cache key (== media id == download request id).
     * @param title       Track title (for the filename).
     * @param artist      Artist(s) (for the filename).
     * @param mimeType    Source MIME type (used only on pre-Q direct writes).
     * @param cache       The download/player cache holding the audio bytes.
     */
    fun exportToMusicFolder(
        context: Context,
        songId: String,
        title: String,
        artist: String,
        mimeType: String,
        cache: Cache,
    ) {
        try {
            val sanitizedName = sanitizeFilename("$artist - $title")

            val contentLength = cache.getCachedLength(songId, 0, Long.MAX_VALUE)
            Log.d(TAG, "Cached content length for $songId: $contentLength bytes")
            if (contentLength <= 0) {
                Log.w(TAG, "No cached data found for $songId")
                return
            }

            val filename: String
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mediaStoreMimeType = toMediaStoreMimeType(mimeType)
                filename = "$sanitizedName${mimeTypeToExtension(mediaStoreMimeType)}"
                exportViaMediaStore(context, songId, filename, mediaStoreMimeType, cache)
            } else {
                filename = "$sanitizedName.m4a"
                exportViaDirectFile(songId, filename, cache)
            }

            Log.i(TAG, "Exported '$filename' to Music/$SUBFOLDER/")
            showToast(context, "Saved to Music/$SUBFOLDER/$filename")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export song $songId", e)
            showToast(context, "Export failed: ${e.message}")
        }
    }

    /** Write the cached bytes via MediaStore (API 29+, no storage permission needed). */
    private fun exportViaMediaStore(
        context: Context,
        songId: String,
        filename: String,
        mimeType: String,
        cache: Cache,
    ) {
        val resolver = context.contentResolver

        // Overwrite any existing file of the same name in our subfolder.
        val existingUri = resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(filename, "Music/$SUBFOLDER/"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)),
                )
            } else {
                null
            }
        }
        if (existingUri != null) resolver.delete(existingUri, null, null)

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$SUBFOLDER")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("MediaStore insert returned null URI")

        try {
            resolver.openOutputStream(uri)?.use { out -> readCacheToStream(cache, songId, out) }
                ?: throw Exception("Failed to open output stream for $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Write directly to external storage (pre-Q; requires WRITE_EXTERNAL_STORAGE). */
    @Suppress("DEPRECATION")
    private fun exportViaDirectFile(songId: String, filename: String, cache: Cache) {
        val musicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            SUBFOLDER,
        )
        musicDir.mkdirs()
        FileOutputStream(File(musicDir, filename)).use { out -> readCacheToStream(cache, songId, out) }
    }

    /** Stream the cached audio bytes (read-only, no network) into [outputStream]. */
    private fun readCacheToStream(cache: Cache, songId: String, outputStream: OutputStream) {
        val dataSource = CacheDataSource(cache, null)
        try {
            val dataSpec = DataSpec.Builder().setUri(songId.toUri()).setKey(songId).build()
            dataSource.open(dataSpec)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (dataSource.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
        } finally {
            dataSource.close()
        }
    }

    private fun mimeTypeToExtension(mimeType: String): String =
        when (mimeType) {
            "audio/mp4" -> ".m4a"
            "audio/webm" -> ".webm"
            "audio/ogg" -> ".ogg"
            "audio/mpeg" -> ".mp3"
            "audio/opus" -> ".opus"
            "audio/flac" -> ".flac"
            else -> ".m4a"
        }

    // Downloaded YT audio is packaged as MP4/M4A regardless of source codec hints.
    private fun toMediaStoreMimeType(@Suppress("UNUSED_PARAMETER") mimeType: String): String = "audio/mp4"

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(200)
}
