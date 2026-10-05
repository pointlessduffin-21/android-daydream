package com.daydream.standby.data.photos

import com.daydream.standby.data.immich.ImmichAsset
import com.daydream.standby.data.immich.ImmichClient
import com.daydream.standby.data.immich.ImmichServer
import com.daydream.standby.data.settings.ImmichMode
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

class ImmichPhotoSource(
    private val client: ImmichClient,
    private val server: ImmichServer,
    private val mode: ImmichMode,
    private val albumIds: Set<String>,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : PhotoSource {

    override suspend fun loadBatch(): List<Photo> {
        val assets = when (mode) {
            ImmichMode.RANDOM -> client.randomAssets(server, batchSize)
            ImmichMode.FAVORITES -> client.randomAssets(server, batchSize, favoritesOnly = true)
            ImmichMode.ALBUMS -> loadAlbums()
        }
        return assets.asSequence()
            .filter { it.isDisplayableImage && ImmichServer.isValidId(it.id) }
            .distinctBy { it.id }
            .map(::toPhoto)
            .toList()
    }

    /** Cached per source instance, i.e. per server configuration. */
    private var randomFilterSupported: Boolean? = null

    private suspend fun loadAlbums(): List<ImmichAsset> {
        if (albumIds.isEmpty()) throw PhotoSourceException("No Immich albums selected")
        val filterSupported = randomFilterSupported
            ?: client.serverVersion(server).supportsRandomAlbumFilter.also { randomFilterSupported = it }
        var lastError: Exception? = null
        val perAlbum = (batchSize / albumIds.size).coerceAtLeast(MIN_PER_ALBUM)
        val assets = albumIds.flatMap { id ->
            try {
                client.albumSample(server, id, perAlbum, filterSupported)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An album may have been deleted; keep going with the others.
                lastError = e
                emptyList()
            }
        }
        if (assets.isEmpty()) lastError?.let { throw it }
        return assets.shuffled()
    }

    private fun toPhoto(asset: ImmichAsset) = Photo(
        id = "immich:${asset.id}",
        data = server.previewUrl(asset.id).toString(),
        headers = mapOf(ImmichServer.API_KEY_HEADER to server.apiKey),
        location = asset.exifInfo?.let { exif ->
            listOfNotNull(exif.city, exif.country ?: exif.state).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null }
        },
        takenOn = parseDate(asset.exifInfo?.dateTimeOriginal) ?: parseDate(asset.localDateTime) ?: parseDate(asset.fileCreatedAt),
        aspectHint = asset.exifInfo?.let { exif ->
            displayAspect(exif.exifImageWidth, exif.exifImageHeight, rotated90 = exif.isRotated90)
        },
    )

    companion object {
        const val DEFAULT_BATCH_SIZE = 50
        private const val MIN_PER_ALBUM = 10

        internal fun parseDate(raw: String?): LocalDate? {
            if (raw.isNullOrBlank()) return null
            return runCatching { OffsetDateTime.parse(raw).toLocalDate() }
                .recoverCatching { LocalDateTime.parse(raw).toLocalDate() }
                .recoverCatching { LocalDate.parse(raw.take(10)) }
                .getOrNull()
        }
    }
}
