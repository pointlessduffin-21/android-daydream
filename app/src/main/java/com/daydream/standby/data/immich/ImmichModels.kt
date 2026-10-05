package com.daydream.standby.data.immich

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class ImmichServerVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ImmichServerVersion> {
    override fun toString() = "v$major.$minor.$patch"

    override fun compareTo(other: ImmichServerVersion): Int =
        compareValuesBy(this, other, ImmichServerVersion::major, ImmichServerVersion::minor, ImmichServerVersion::patch)

    /** Random search honours `albumIds` from this release on; older servers silently ignore it. */
    val supportsRandomAlbumFilter: Boolean get() = this >= RANDOM_ALBUM_FILTER_SINCE

    companion object {
        val RANDOM_ALBUM_FILTER_SINCE = ImmichServerVersion(1, 138, 0)
    }
}

@Serializable
data class ImmichAlbum(
    val id: String,
    val albumName: String,
    val assetCount: Int = 0,
    val albumThumbnailAssetId: String? = null,
    val assets: List<ImmichAsset> = emptyList(),
)

@Serializable
data class ImmichAsset(
    val id: String,
    val type: String = TYPE_IMAGE,
    val originalFileName: String? = null,
    val localDateTime: String? = null,
    val fileCreatedAt: String? = null,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val exifInfo: ImmichExif? = null,
) {
    val isDisplayableImage: Boolean get() = type == TYPE_IMAGE && !isTrashed

    companion object {
        const val TYPE_IMAGE = "IMAGE"
    }
}

@Serializable
data class ImmichExif(
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val dateTimeOriginal: String? = null,
    // Parsed leniently (servers/proxies may send 4032.0, huge values, or numbers vs strings): a
    // malformed hint must never fail decoding of the whole asset list.
    @SerialName("exifImageWidth") private val rawWidth: JsonPrimitive? = null,
    @SerialName("exifImageHeight") private val rawHeight: JsonPrimitive? = null,
    @SerialName("orientation") private val rawOrientation: JsonPrimitive? = null,
) {
    val exifImageWidth: Int? get() = rawWidth.dimension()
    val exifImageHeight: Int? get() = rawHeight.dimension()

    /** True for EXIF orientations 5–8, where the stored image is rotated 90°. */
    val isRotated90: Boolean
        get() = rawOrientation?.contentOrNull?.trim()?.toDoubleOrNull()?.toInt() in 5..8

    private fun JsonPrimitive?.dimension(): Int? =
        this?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1.0 && it <= MAX_DIMENSION }?.toInt()

    private companion object {
        const val MAX_DIMENSION = 1_000_000.0
    }
}

@Serializable
internal data class RandomSearchRequest(
    val size: Int,
    val type: String = ImmichAsset.TYPE_IMAGE,
    val withExif: Boolean = true,
    val isFavorite: Boolean? = null,
    val albumIds: List<String>? = null,
)

