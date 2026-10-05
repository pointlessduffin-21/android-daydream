package com.daydream.standby.ui.standby.photos

import kotlin.random.Random

/**
 * A slow pan-and-zoom. Offsets are fractions in [-1, 1] of the overflow created by zooming, so
 * the image always covers its frame: at scale `s` the content overhangs each edge by `(s-1)/2`
 * of the frame, and translating by at most that never exposes an edge.
 */
data class KenBurnsSpec(
    val startScale: Float,
    val endScale: Float,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
) {
    data class Frame(val scale: Float, val offsetX: Float, val offsetY: Float)

    /** The transform at [progress] (0..1), with offsets already multiplied by the overflow. */
    fun at(progress: Float): Frame {
        val t = progress.coerceIn(0f, 1f)
        val scale = lerp(startScale, endScale, t)
        val overflow = (scale - 1f) / 2f
        return Frame(scale, lerp(startX, endX, t) * overflow, lerp(startY, endY, t) * overflow)
    }

    companion object {
        val STILL = KenBurnsSpec(1f, 1f, 0f, 0f, 0f, 0f)

        private const val MIN_ZOOM = 1.02f
        private const val MAX_ZOOM = 1.18f

        /**
         * A deterministic motion for [seed]. [intensity] in 0..1 scales how far it zooms
         * (collage tiles and fitted portraits move less than full-screen photos).
         */
        fun random(seed: Long, intensity: Float = 1f): KenBurnsSpec {
            val k = intensity.coerceIn(0f, 1f)
            if (k == 0f) return STILL
            val r = Random(seed)
            val low = 1f + (MIN_ZOOM - 1f) * k
            val high = 1f + (MAX_ZOOM - 1f) * k
            val zoomIn = r.nextBoolean()
            fun offset() = r.nextFloat() * 2f - 1f
            return KenBurnsSpec(
                startScale = if (zoomIn) low else high,
                endScale = if (zoomIn) high else low,
                startX = offset(),
                startY = offset(),
                endX = offset(),
                endY = offset(),
            )
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
