package com.teleprompterpro.app.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import com.teleprompterpro.app.util.FileUtil
import com.teleprompterpro.app.util.Logger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Gallery I/O (ported from creator-cam). Final videos land in
 * Movies/TeleprompterPro/ and are immediately visible to gallery apps.
 *
 * API 29+: MediaStore RELATIVE_PATH + IS_PENDING (no storage permission).
 * API 24–28: MediaStore has no RELATIVE_PATH and writing to public Movies/
 * would require WRITE_EXTERNAL_STORAGE. To keep the permission list at
 * CAMERA + RECORD_AUDIO only, we write to the app-owned external Movies dir
 * (no permission needed) and hand it to the media scanner so it shows in
 * the gallery. Listing/deleting always goes through MediaStore rows.
 */
class MediaStoreSaver(private val context: Context) {

    suspend fun saveVideo(source: File, displayName: String): Uri =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveModern(source, displayName)
            else saveLegacy(source, displayName)
        }

    private fun saveModern(source: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "$RELATIVE_DIR/")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri, "w").use { out ->
                requireNotNull(out) { "Could not open MediaStore stream" }
                FileUtil.copyToStream(source, out)
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Logger.i("Media", "Saved to gallery: $uri")
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private suspend fun saveLegacy(source: File, displayName: String): Uri {
        val dir = legacyDir()
        val dest = File(dir, displayName)
        source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        val scanned = suspendCancellableCoroutine<Uri?> { cont ->
            MediaScannerConnection.scanFile(
                context, arrayOf(dest.absolutePath), arrayOf("video/mp4"),
            ) { _, uri -> if (cont.isActive) cont.resume(uri) }
        }
        Logger.i("Media", "Saved (legacy) to ${dest.path} -> $scanned")
        return scanned ?: Uri.fromFile(dest)
    }

    private fun legacyDir(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(base, APP_DIR).apply { mkdirs() }
    }

    /** Newest-first listing of this app's exported videos. */
    suspend fun listRecordings(): List<RecordingItem> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection: Uri
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("$RELATIVE_DIR/%")
        } else {
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Video.Media.DATA} LIKE ?"
            args = arrayOf("${legacyDir().absolutePath}/%")
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val items = mutableListOf<RecordingItem>()
        runCatching {
            resolver.query(collection, projection, selection, args, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val taken = cursor.getLong(takenCol)
                    val added = cursor.getLong(addedCol)
                    items += RecordingItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        id = id,
                        displayName = cursor.getString(nameCol) ?: "video.mp4",
                        dateTakenMs = if (taken > 0) taken else added * 1000,
                        durationMs = cursor.getLong(durCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        width = cursor.getInt(wCol),
                        height = cursor.getInt(hCol),
                    )
                }
            }
        }.onFailure { Logger.w("Media", "List failed", it) }
        items
    }

    suspend fun loadThumbnail(uri: Uri, width: Int = 320, height: Int = 320): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(width, height), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver,
                        ContentUris.parseId(uri),
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null,
                    )
                }
            }.getOrNull()
        }

    suspend fun rename(uri: Uri, newName: String): Boolean = withContext(Dispatchers.IO) {
        val clean = newName.trim().take(120).ifBlank { return@withContext false }
        val finalName = if (clean.endsWith(".mp4", ignoreCase = true)) clean else "$clean.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
        }
        runCatching { context.contentResolver.update(uri, values, null, null) > 0 }
            .getOrDefault(false)
    }

    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Legacy: delete the file we own, then the index row.
            runCatching {
                @Suppress("DEPRECATION")
                context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) File(c.getString(0)).delete() }
            }
        }
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    companion object {
        const val APP_DIR = "TeleprompterPro"
        val RELATIVE_DIR = "${Environment.DIRECTORY_MOVIES}/$APP_DIR"
    }
}
