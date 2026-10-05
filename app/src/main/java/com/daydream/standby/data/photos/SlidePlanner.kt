package com.daydream.standby.data.photos

/**
 * Chooses a [SlideLayout] for the next photo plus a lookahead of upcoming candidates.
 *
 * Pure and deterministic: the caller supplies the [choice] used to pick between equally valid
 * layouts, so re-planning the same slide with better information gives a stable answer.
 */
class SlidePlanner(private val collagesEnabled: Boolean) {

    /** The chosen layout and the candidate indices it uses; index 0 (the first photo) is always included. */
    data class Plan(val layout: SlideLayout, val indices: List<Int>)

    /**
     * @param aspects width/height per candidate; index 0 must be known, null entries are skipped.
     * @param viewportAspect width/height of the area the slide fills.
     * @param choice any non-negative number; picks among valid collage layouts.
     */
    fun plan(aspects: List<Float?>, viewportAspect: Float, choice: Int = 0): Plan {
        val first = requireNotNull(aspects.firstOrNull()) { "The first photo's aspect must be known" }
        require(viewportAspect > 0f) { "viewportAspect must be positive" }
        // Plan in landscape terms; a portrait viewport is the same problem with axes swapped.
        val portraitViewport = viewportAspect < 1f
        val viewport = if (portraitViewport) 1f / viewportAspect else viewportAspect
        val norm = aspects.map { a -> a?.takeIf { it > 0f }?.let { if (portraitViewport) 1f / it else it } }
        val firstShape = shapeOf(norm[0] ?: first)

        if (firstShape == Shape.WIDE) return Plan(SlideLayout.SINGLE, listOf(0))
        val single = Plan(SlideLayout.SINGLE_PORTRAIT, listOf(0))
        if (!collagesEnabled || firstShape == Shape.TALL) return single

        val narrow = norm.indices.drop(1).filter { norm[it]?.let(::shapeOf) == Shape.NARROW }
        val wide = norm.indices.drop(1).filter { norm[it]?.let(::shapeOf) == Shape.WIDE }

        val options = buildList {
            if (viewport >= TRIO_MIN_VIEWPORT && narrow.size >= 2) add(Plan(SlideLayout.TRIO, listOf(0) + narrow.take(2)))
            if (narrow.isNotEmpty()) add(Plan(SlideLayout.DUO, listOf(0, narrow.first())))
            if (wide.size >= 2) add(Plan(SlideLayout.MOSAIC, listOf(0) + wide.take(2)))
        }
        if (options.isEmpty()) return single
        // Vary layouts, but only among those that crop about as little as the best one.
        val fits = options.map { fit(it, norm, viewport) }
        val best = fits.max()
        val good = options.filterIndexed { i, _ -> fits[i] >= best - FIT_TOLERANCE }
        return good[choice.mod(good.size)]
    }

    /**
     * How much of each photo stays visible when cropped into its tile (1 = nothing cropped),
     * taking the worst tile. Tile aspects follow the layouts in SlideView (gaps ignored).
     */
    internal fun fit(plan: Plan, norm: List<Float?>, viewport: Float): Float {
        val tiles = when (plan.layout) {
            SlideLayout.SINGLE, SlideLayout.SINGLE_PORTRAIT -> listOf(viewport)
            SlideLayout.DUO -> List(2) { viewport / 2f }
            SlideLayout.TRIO -> List(3) { viewport / 3f }
            // Half-width narrow tile, plus two half-width × half-height wide tiles.
            SlideLayout.MOSAIC -> listOf(viewport / 2f, viewport, viewport)
        }
        return plan.indices.zip(tiles).minOf { (index, tile) ->
            val a = requireNotNull(norm[index])
            minOf(a / tile, tile / a)
        }
    }

    private enum class Shape { TALL, NARROW, WIDE }

    /** Whether two aspects would be treated the same way when planning for [viewportAspect]. */
    fun sameShape(a: Float, b: Float, viewportAspect: Float): Boolean {
        val portraitViewport = viewportAspect < 1f
        fun norm(x: Float) = if (portraitViewport) 1f / x else x
        return shapeOf(norm(a)) == shapeOf(norm(b))
    }

    private fun shapeOf(aspect: Float): Shape = when {
        aspect < TALL_MAX -> Shape.TALL
        aspect < WIDE_MIN -> Shape.NARROW
        else -> Shape.WIDE
    }

    companion object {
        /** Narrower than this (e.g. phone screenshots) is never squeezed into a tile. */
        const val TALL_MAX = 0.5f

        /** At or above this a photo fills a landscape screen well on its own. */
        const val WIDE_MIN = 1.1f

        /** Three tiles only on screens at least this wide (20:9 phones, not 16:10 tablets). */
        const val TRIO_MIN_VIEWPORT = 1.9f

        /** Layouts whose [fit] is within this of the best one are picked between at random. */
        const val FIT_TOLERANCE = 0.12f

        /** How many upcoming photos are considered as collage partners. */
        const val LOOKAHEAD = 8
    }
}
