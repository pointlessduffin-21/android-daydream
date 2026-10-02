package com.daydream.standby.data.immich

import kotlinx.serialization.Serializable

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
)

@Serializable
internal data class RandomSearchRequest(
    val size: Int,
    val type: String = ImmichAsset.TYPE_IMAGE,
    val withExif: Boolean = true,
    val isFavorite: Boolean? = null,
    val albumIds: List<String>? = null,
)

