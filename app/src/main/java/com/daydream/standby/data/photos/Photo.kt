package com.daydream.standby.data.photos

import java.time.LocalDate

/**
 * A single slideshow image.
 *
 * @param data anything Coil can load (a URL string or a content Uri).
 * @param headers HTTP headers required to fetch [data], e.g. Immich's API key.
 */
data class Photo(
    val id: String,
    val data: Any,
    val headers: Map<String, String> = emptyMap(),
    val location: String? = null,
    val takenOn: LocalDate? = null,
)

/** Supplies batches of photos; the slideshow asks for a new batch when it runs out. */
fun interface PhotoSource {
    suspend fun loadBatch(): List<Photo>
}

/** A failure message suitable for showing on screen. */
class PhotoSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
