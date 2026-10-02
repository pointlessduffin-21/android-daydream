package com.daydream.standby.data.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Random photos from the whole MediaStore library (screenshots excluded). */
class LocalPhotoSource(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val batchSize: Int = 200,
) : PhotoSource {

    override suspend fun loadBatch(): List<Photo> = withContext(dispatcher) {
        if (!hasPermission(context)) throw PhotoSourceException("Allow photo access in Settings to show device photos")
        val bucket = MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        // Two primitive columns per row keeps a full-library scan cheap even for 100k photos.
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
            "$bucket IS NULL OR $bucket NOT IN (?, ?)",
            EXCLUDED_BUCKETS,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            // Pick random rows, but visit them in order: forward-only seeks are cheap on provider cursors.
            val positions = (0 until cursor.count).shuffled().take(batchSize).sorted()
            positions.mapNotNull { position ->
                if (!cursor.moveToPosition(position)) return@mapNotNull null
                val id = cursor.getLong(idCol)
                val taken = if (cursor.isNull(takenCol)) null else cursor.getLong(takenCol)
                Photo(
                    id = "local:$id",
                    data = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    takenOn = taken?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() },
                )
            }.shuffled()
        }.orEmpty()
    }

    companion object {
        private val EXCLUDED_BUCKETS = arrayOf("Screenshots", "Screen recordings")

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
