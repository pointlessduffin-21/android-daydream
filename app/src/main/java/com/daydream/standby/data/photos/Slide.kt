package com.daydream.standby.data.photos

/** Decoded pixel size of a prepared photo (EXIF rotation already applied). */
data class PhotoSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "PhotoSize must be positive, was ${width}x$height" }
    }

    val aspect: Float get() = width.toFloat() / height
}

/**
 * How a slide arranges its photos. Layouts are described for a landscape viewport; on a portrait
 * viewport ([Slide.vertical]) rows and columns swap, so e.g. [DUO] stacks two photos top/bottom.
 */
enum class SlideLayout(val capacity: Int) {
    /** One photo, cropped to fill the screen. */
    SINGLE(1),

    /** One photo shown whole over a blurred copy of itself — for portraits with no partner. */
    SINGLE_PORTRAIT(1),

    /** Two narrow photos side by side. */
    DUO(2),

    /** Three narrow photos side by side (very wide screens). */
    TRIO(3),

    /** One narrow photo beside two wide photos stacked on top of each other. */
    MOSAIC(3),
}

data class SlidePhoto(val photo: Photo, val aspect: Float)

/** One screenful of the slideshow. */
data class Slide(val layout: SlideLayout, val photos: List<SlidePhoto>, val vertical: Boolean = false) {
    init {
        require(photos.size == layout.capacity) { "$layout needs ${layout.capacity} photos, got ${photos.size}" }
        require(photos.map { it.photo.id }.distinct().size == photos.size) { "Slide contains duplicate photos" }
    }

    /** Stable identity for animations. */
    val key: String get() = photos.joinToString("|") { it.photo.id }

    /** The photo whose place and date are shown in the caption. */
    val primary: Photo get() = photos.first().photo
}
