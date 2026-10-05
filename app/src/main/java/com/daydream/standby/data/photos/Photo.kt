package com.daydream.standby.data.photos

import java.time.LocalDate

/**
 * A single slideshow image.
 *
 * @param data anything Coil can load (a URL string or a content Uri).
 * @param headers HTTP headers required to fetch [data], e.g. Immich's API key.
 * @param aspectHint width / height as displayed (EXIF rotation applied) from metadata, if known.
 *   Only a hint for layout planning; the decoded image size is authoritative.
 */
data class Photo(
    val id: String,
    val data: Any,
    val headers: Map<String, String> = emptyMap(),
    val location: String? = null,
    val takenOn: LocalDate? = null,
    val aspectHint: Float? = null,
)

/**
 * Display aspect ratio for raw pixel dimensions plus an EXIF orientation (degrees 0/90/180/270 or
 * EXIF tag values 1–8); returns null for unusable input.
 */
fun displayAspect(width: Int?, height: Int?, rotated90: Boolean): Float? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    return if (rotated90) height.toFloat() / width else width.toFloat() / height
}

/** Supplies batches of photos; the slideshow asks for a new batch when it runs out. */
fun interface PhotoSource {
    suspend fun loadBatch(): List<Photo>
}

/** A failure message suitable for showing on screen. */
class PhotoSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
