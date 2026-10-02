package com.daydream.standby.ui.common

import android.content.Context
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Precision
import com.daydream.standby.data.photos.Photo

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
