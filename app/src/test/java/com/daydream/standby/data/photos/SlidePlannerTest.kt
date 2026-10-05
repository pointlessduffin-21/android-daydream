package com.daydream.standby.data.photos

import com.daydream.standby.data.photos.SlideLayout.DUO
import com.daydream.standby.data.photos.SlideLayout.MOSAIC
import com.daydream.standby.data.photos.SlideLayout.SINGLE
import com.daydream.standby.data.photos.SlideLayout.SINGLE_PORTRAIT
import com.daydream.standby.data.photos.SlideLayout.TRIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlidePlannerTest {
    private val collages = SlidePlanner(collagesEnabled = true)
    private val singles = SlidePlanner(collagesEnabled = false)

    private val portrait = 0.75f
    private val square = 1.0f
    private val landscape = 1.5f
    private val phone = 20f / 9f
    private val tablet = 16f / 10f

    private fun SlidePlanner.layout(vararg aspects: Float?, viewport: Float = phone, choice: Int = 0) =
        plan(aspects.toList(), viewport, choice)

    @Test fun `landscape photo fills the screen alone`() {
        assertEquals(SlidePlanner.Plan(SINGLE, listOf(0)), collages.layout(landscape, portrait, portrait))
    }

    @Test fun `panorama stays single`() = assertEquals(SINGLE, collages.layout(4f, portrait).layout)

    @Test fun `lone portrait gets the blurred backdrop`() {
        assertEquals(SINGLE_PORTRAIT, collages.layout(portrait, landscape).layout)
        assertEquals(SINGLE_PORTRAIT, collages.layout(portrait).layout)
    }

    @Test fun `two portraits on a 16 by 10 screen make a duo`() {
        assertEquals(SlidePlanner.Plan(DUO, listOf(0, 2)), collages.layout(portrait, landscape, portrait, viewport = tablet))
    }

    @Test fun `three portraits on a wide phone make a trio`() {
        assertEquals(SlidePlanner.Plan(TRIO, listOf(0, 1, 3)), collages.layout(portrait, square, landscape, portrait))
    }

    @Test fun `trio needs a wide enough screen`() {
        val plan = collages.layout(portrait, portrait, portrait, viewport = tablet)
        assertEquals(DUO, plan.layout)
    }

    @Test fun `portrait with two landscapes can make a mosaic`() {
        assertEquals(SlidePlanner.Plan(MOSAIC, listOf(0, 1, 2)), collages.layout(portrait, landscape, landscape))
    }

    @Test fun `wide phones prefer trio because portraits fit it best`() {
        val aspects = arrayOf<Float?>(portrait, portrait, portrait, landscape, landscape)
        // Duo/mosaic tiles on 20:9 crop a third of each portrait; trio crops almost nothing.
        assertEquals(setOf(TRIO), (0 until 6).map { collages.layout(*aspects, choice = it).layout }.toSet())
    }

    @Test fun `choice varies among similarly good layouts deterministically`() {
        val aspects = arrayOf<Float?>(portrait, portrait, landscape, landscape)
        // On 16:10 both duo and mosaic fit well, so the choice alternates between them.
        val layouts = (0 until 4).map { collages.layout(*aspects, viewport = tablet, choice = it).layout }
        assertEquals(listOf(DUO, MOSAIC, DUO, MOSAIC), layouts)
        assertEquals(collages.layout(*aspects, viewport = tablet, choice = 7), collages.layout(*aspects, viewport = tablet, choice = 7))
    }

    @Test fun `fit measures the worst-cropped tile`() {
        val plan = SlidePlanner.Plan(DUO, listOf(0, 1))
        assertEquals(0.75f / (phone / 2f), collages.fit(plan, listOf(portrait, portrait), phone), 1e-4f)
        assertEquals(1f, collages.fit(SlidePlanner.Plan(TRIO, listOf(0, 1, 2)), listOf(0.74f, 0.74f, 0.74f), 2.22f), 0.01f)
    }

    @Test fun `unknown candidates are skipped`() {
        assertEquals(SlidePlanner.Plan(DUO, listOf(0, 2)), collages.layout(portrait, null, portrait, viewport = tablet))
    }

    @Test fun `very tall photos are never tiled`() = assertEquals(SINGLE_PORTRAIT, collages.layout(0.45f, portrait, portrait).layout)

    @Test fun `very tall photos are not used as partners`() = assertEquals(SINGLE_PORTRAIT, collages.layout(portrait, 0.4f).layout)

    @Test fun `square photos pair like portraits`() = assertEquals(DUO, collages.layout(square, square, viewport = tablet).layout)

    @Test fun `single setting never builds collages`() {
        assertEquals(SINGLE_PORTRAIT, singles.layout(portrait, portrait, portrait).layout)
        assertEquals(SINGLE, singles.layout(landscape, landscape).layout)
    }

    @Test fun `portrait viewport swaps axes`() {
        val vertical = 9f / 20f
        // Two landscape photos stack on a portrait screen; a portrait photo fills it alone.
        assertEquals(SlidePlanner.Plan(DUO, listOf(0, 1)), collages.layout(landscape, landscape, viewport = vertical, choice = 1))
        assertEquals(SINGLE, collages.layout(portrait, landscape, viewport = vertical).layout)
    }

    @Test fun `plans only reference provided candidates`() {
        val aspects = List(9) { portrait }
        val plan = collages.plan(aspects, phone)
        assertTrue(plan.indices.all { it in aspects.indices })
        assertEquals(plan.indices.distinct(), plan.indices)
    }

    @Test fun `sameShape follows the viewport orientation`() {
        // Landscape viewport: 0.95 and 0.8 are both "narrow"; 1.5 vs 2.5 are both wide.
        assertTrue(collages.sameShape(0.95f, 0.8f, phone))
        assertTrue(collages.sameShape(1.5f, 2.5f, phone))
        assertTrue(!collages.sameShape(0.8f, 1.5f, phone))
        // Portrait viewport inverts: 1.5 (→0.67, narrow) vs 2.5 (→0.4, tall) differ.
        assertTrue(!collages.sameShape(1.5f, 2.5f, 9f / 20f))
        assertTrue(!collages.sameShape(0.95f, 0.8f, 9f / 20f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `first aspect must be known`() {
        collages.plan(listOf(null, portrait), phone)
    }
}
