package com.daydream.standby.ui.common

import android.content.Context
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import com.daydream.standby.data.photos.Photo

/**
 * The request used both to prepare a slideshow photo and to display it, so the on-screen image is
 * always a memory-cache hit (same key, size and scale). Decodes to fit a square of the screen's
 * long side, so typical photos can be cropped to fill the screen or a tile at full sharpness while
 * each bitmap stays bounded (Coil never upscales past the source with INEXACT precision). Photos
 * far more elongated than the screen are slightly upscaled when cropped; an accepted trade-off.
 * Metrics come from the application context so prepare and display always agree.
 */
fun Photo.toSlideRequest(context: Context): ImageRequest {
    val metrics = context.applicationContext.resources.displayMetrics
    val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
    return toImageRequest(context).newBuilder()
        .size(longSide, longSide)
        .scale(Scale.FIT)
        .build()
}

/** Builds the Coil request for [photo], attaching any auth headers it needs. */
fun Photo.toImageRequest(context: Context): ImageRequest =
    ImageRequest.Builder(context)
        .data(data)
        .memoryCacheKey(id)
        .precision(Precision.INEXACT)
        .apply {
            if (headers.isNotEmpty()) {
                httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
            }
        }
        .build()
