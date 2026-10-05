package com.daydream.standby.data.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** A device photo folder (MediaStore bucket) with its image count and newest image as cover. */
data class LocalFolder(val id: String, val name: String, val count: Int, val coverUri: Uri)

/**
 * Random photos from the device's MediaStore library. With no [bucketIds] that is every folder
 * except screenshots and screen recordings; otherwise only the chosen folders.
 */
class LocalPhotoSource(
    private val context: Context,
    private val bucketIds: Set<String> = emptySet(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val batchSize: Int = 200,
) : PhotoSource {

    override suspend fun loadBatch(): List<Photo> = withContext(dispatcher) {
        if (!hasPermission(context)) throw PhotoSourceException("Allow photo access in Settings to show device photos")
        val filter = LocalPhotoQuery.build(
            bucketIds,
            bucketIdColumn = MediaStore.Images.Media.BUCKET_ID,
            bucketNameColumn = MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        // A handful of primitive columns per row keeps a full-library scan cheap even for 100k photos.
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.ORIENTATION,
            ),
            filter.selection,
            filter.selectionArgs,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val orientationCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            // Pick random rows, but visit them in order: forward-only seeks are cheap on provider cursors.
            val positions = (0 until cursor.count).shuffled().take(batchSize).sorted()
            positions.mapNotNull { position ->
                if (!cursor.moveToPosition(position)) return@mapNotNull null
                val id = cursor.getLong(idCol)
                val taken = if (cursor.isNull(takenCol)) null else cursor.getLong(takenCol)
                val orientation = if (cursor.isNull(orientationCol)) 0 else cursor.getInt(orientationCol)
                Photo(
                    id = "local:$id",
                    data = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    takenOn = taken?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() },
                    aspectHint = displayAspect(
                        width = if (cursor.isNull(widthCol)) null else cursor.getInt(widthCol),
                        height = if (cursor.isNull(heightCol)) null else cursor.getInt(heightCol),
                        rotated90 = orientation == 90 || orientation == 270,
                    ),
                )
            }.shuffled()
        }.orEmpty()
    }

    companion object {
        /**
         * Lists device photo folders (screenshots included), biggest first then by name. Each folder's
         * cover is its newest image. Aggregated in Kotlin because GROUP BY isn't allowed on newer APIs.
         *
         * @throws PhotoSourceException if photo access hasn't been granted.
         */
        suspend fun listFolders(
            context: Context,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): List<LocalFolder> = withContext(dispatcher) {
            if (!hasPermission(context)) throw PhotoSourceException("Allow photo access to choose folders")
            val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val acc = LinkedHashMap<String, FolderAccumulator>()
            context.contentResolver.query(
                base,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    if (cursor.isNull(bucketCol)) continue
                    val bucketId = cursor.getLong(bucketCol).toString()
                    val taken = if (cursor.isNull(takenCol)) 0L else cursor.getLong(takenCol)
                    val addedSeconds = if (cursor.isNull(addedCol)) 0L else cursor.getLong(addedCol)
                    val newest = if (taken > 0) taken else addedSeconds * 1000
                    val entry = acc.getOrPut(bucketId) {
                        FolderAccumulator(cursor.getString(nameCol).orEmpty().ifBlank { "Unnamed" })
                    }
                    entry.count++
                    if (entry.coverId < 0 || newest > entry.coverTime) {
                        entry.coverId = cursor.getLong(idCol)
                        entry.coverTime = newest
                    }
                }
            }
            acc.map { (bucketId, f) ->
                LocalFolder(bucketId, f.name, f.count, ContentUris.withAppendedId(base, f.coverId))
            }.sortedWith(compareByDescending<LocalFolder> { it.count }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

        private class FolderAccumulator(val name: String) {
            var count = 0
            var coverId = -1L
            var coverTime = 0L
        }

        val requiredPermissions: Array<String>
            get() = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

        /** Full or (Android 14+) partial photo access counts as granted. */
        fun hasPermission(context: Context): Boolean = requiredPermissions.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
