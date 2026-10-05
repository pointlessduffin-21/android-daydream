package com.daydream.standby.data.photos

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class SlideshowTest {
    private val wide = PhotoSize(1500, 1000)
    private val tall = PhotoSize(750, 1000)

    /** Always picks the first valid option, so tests are deterministic. */
    private val firstChoice = object : Random() {
        override fun nextBits(bitCount: Int) = 0
    }

    private fun photo(id: String, hint: Float? = null) = Photo(id = id, data = "url/$id", aspectHint = hint)

    private fun slideshow(
        source: PhotoSource,
        sizes: (Photo) -> PhotoSize? = { wide },
        interval: Long = 1_000,
        collages: Boolean = true,
        viewport: Float = 16f / 10f,
        retry: Long = 30_000,
        maxFailures: Int = 5,
    ) = Slideshow(
        source = source,
        intervalMillis = interval,
        prepare = { sizes(it) },
        planner = SlidePlanner(collages),
        viewportAspect = viewport,
        random = firstChoice,
        retryDelayMillis = retry,
        maxConsecutiveFailures = maxFailures,
    )

    private fun slides(states: List<SlideshowState>) = states.filterIsInstance<SlideshowState.Showing>().map { it.slide }
    private fun shownIds(states: List<SlideshowState>) = slides(states).map { s -> s.photos.joinToString("+") { it.photo.id } }

    @Test fun `cycles single landscape photos and reloads batches`() = runTest {
        var loads = 0
        val source = PhotoSource {
            loads++
            listOf(photo("a$loads"), photo("b$loads"))
        }
        val states = slideshow(source).states().take(3).toList()
        assertEquals(listOf("a1", "b1", "a2"), shownIds(states))
        assertTrue(slides(states).all { it.layout == SlideLayout.SINGLE })
    }

    @Test fun `waits interval between slides`() = runTest {
        val emitted = mutableListOf<SlideshowState>()
        val job = launch {
            slideshow(PhotoSource { listOf(photo("1"), photo("2"), photo("3")) }, interval = 10_000).states().collect { emitted += it }
        }
        runCurrent()
        assertEquals(1, emitted.size)
        advanceTimeBy(9_999)
        assertEquals(1, emitted.size)
        advanceTimeBy(2)
        assertEquals(2, emitted.size)
        job.cancel()
    }

    @Test fun `portraits are paired using hints and removed from the queue`() = runTest {
        val source = PhotoSource { listOf(photo("p1", 0.75f), photo("l1", 1.5f), photo("p2", 0.75f), photo("l2", 1.5f)) }
        val sizes = { p: Photo -> if (p.id.startsWith("p")) tall else wide }
        val states = slideshow(source, sizes).states().take(3).toList()
        // p1 pairs with p2 (skipping l1, which keeps its place), then l1, then l2.
        assertEquals(listOf("p1+p2", "l1", "l2"), shownIds(states))
        assertEquals(SlideLayout.DUO, slides(states)[0].layout)
    }

    @Test fun `wrong hint is corrected by re-planning`() = runTest {
        // "liar" claims to be portrait but decodes landscape; p2 is the real partner.
        val source = PhotoSource { listOf(photo("p1", 0.75f), photo("liar", 0.7f), photo("p2", 0.75f)) }
        val sizes = { p: Photo -> if (p.id == "liar") wide else tall }
        val first = slides(slideshow(source, sizes).states().take(1).toList()).single()
        assertEquals(SlideLayout.DUO, first.layout)
        assertEquals(listOf("p1", "p2"), first.photos.map { it.photo.id })
        assertEquals(1.5f, PhotoSize(1500, 1000).aspect)
    }

    @Test fun `partner that fails to load is dropped and replaced`() = runTest {
        val source = PhotoSource { listOf(photo("p1", 0.75f), photo("broken", 0.75f), photo("p2", 0.75f)) }
        val sizes = { p: Photo -> if (p.id == "broken") null else tall }
        val first = slides(slideshow(source, sizes).states().take(1).toList()).single()
        assertEquals(listOf("p1", "p2"), first.photos.map { it.photo.id })
    }

    @Test fun `lone portrait uses the fitted layout`() = runTest {
        val source = PhotoSource { listOf(photo("p", 0.75f), photo("l", 1.5f)) }
        val sizes = { p: Photo -> if (p.id == "p") tall else wide }
        assertEquals(SlideLayout.SINGLE_PORTRAIT, slides(slideshow(source, sizes).states().take(1).toList()).single().layout)
    }

    @Test fun `collages disabled shows one photo per slide`() = runTest {
        val source = PhotoSource { listOf(photo("p1", 0.75f), photo("p2", 0.75f)) }
        val states = slideshow(source, { tall }, collages = false).states().take(2).toList()
        assertEquals(listOf("p1", "p2"), shownIds(states))
    }

    @Test fun `portrait viewport marks slides vertical`() = runTest {
        val slide = slides(slideshow(PhotoSource { listOf(photo("x")) }, viewport = 0.5f).states().take(1).toList()).single()
        assertTrue(slide.vertical)
    }

    @Test fun `a slide never repeats a photo`() = runTest {
        // Random sources can return the same asset twice in one batch.
        val source = PhotoSource { listOf(photo("p", 0.75f), photo("p", 0.75f), photo("q", 0.75f)) }
        val first = slides(slideshow(source, { tall }).states().take(1).toList()).single()
        assertEquals(listOf("p", "q"), first.photos.map { it.photo.id })
    }

    @Test fun `error before first slide is emitted, then retried`() = runTest {
        var calls = 0
        val source = PhotoSource { if (calls++ == 0) throw IOException("offline") else listOf(photo("x")) }
        val states = slideshow(source, retry = 5_000).states().take(2).toList()
        assertEquals(SlideshowState.Error("offline"), states[0])
        assertEquals(listOf("x"), shownIds(states))
    }

    @Test fun `error after a slide was shown keeps it on screen`() = runTest {
        var calls = 0
        val source = PhotoSource {
            when (calls++) {
                0 -> listOf(photo("first"))
                1, 2 -> throw IOException("blip")
                else -> listOf(photo("second"))
            }
        }
        val states = slideshow(source, retry = 1_000).states().take(2).toList()
        assertEquals(listOf("first", "second"), shownIds(states))
        assertTrue(states.none { it is SlideshowState.Error })
    }

    @Test fun `empty source emits Empty`() = runTest {
        assertEquals(listOf(SlideshowState.Empty), slideshow(PhotoSource { emptyList() }).states().take(1).toList())
    }

    @Test fun `photos that fail to prepare are skipped`() = runTest {
        val source = PhotoSource { listOf(photo("bad"), photo("good")) }
        val states = slideshow(source, { if (it.id == "good") wide else null }).states().take(1).toList()
        assertEquals(listOf("good"), shownIds(states))
    }

    @Test fun `prepare exceptions count as failures`() = runTest {
        val source = PhotoSource { listOf(photo("boom"), photo("ok")) }
        val states = slideshow(source, { if (it.id == "boom") error("decode") else wide }).states().take(1).toList()
        assertEquals(listOf("ok"), shownIds(states))
    }

    @Test fun `repeated prepare failures surface an error`() = runTest {
        val states = slideshow(PhotoSource { listOf(photo("x")) }, { null }, maxFailures = 3).states().take(1).toList()
        assertEquals(listOf(SlideshowState.Error("Couldn't load photos")), states)
    }

    @Test fun `does not repeat the last slide across batches`() = runTest {
        var calls = 0
        val source = PhotoSource { if (calls++ == 0) listOf(photo("a")) else listOf(photo("a"), photo("b")) }
        val states = slideshow(source).states().take(3).toList()
        assertEquals(listOf("a", "b", "a"), shownIds(states))
    }

    @Test fun `tiny library never repeats the same collage back to back`() = runTest {
        val source = PhotoSource { listOf(photo("p1", 0.75f), photo("p2", 0.75f)) }
        val states = slideshow(source, { tall }).states().take(3).toList()
        assertEquals(listOf("p1+p2", "p1", "p2"), shownIds(states))
    }

    @Test fun `photos on screen are only reused as partners as a last resort`() = runTest {
        var calls = 0
        val source = PhotoSource {
            if (calls++ == 0) listOf(photo("a", 0.75f), photo("b", 0.75f)) else listOf(photo("a", 0.75f), photo("b", 0.75f), photo("c", 0.75f), photo("d", 0.75f))
        }
        val states = slideshow(source, { tall }).states().take(2).toList()
        // Second slide starts with c and pairs it with d rather than a/b still on screen.
        assertEquals(listOf("a+b", "c+d"), shownIds(states))
    }

    @Test fun `unhinted partner is decoded then used`() = runTest {
        val source = PhotoSource { listOf(photo("p1", 0.75f), photo("mystery"), photo("p2", 0.75f)) }
        val first = slides(slideshow(source, { tall }).states().take(1).toList()).single()
        // "mystery" has no hint so it isn't planned as a partner up front; p2 is.
        assertEquals(listOf("p1", "p2"), first.photos.map { it.photo.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-positive interval`() {
        slideshow(PhotoSource { emptyList() }, interval = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `slide rejects wrong photo count`() {
        Slide(SlideLayout.DUO, listOf(SlidePhoto(photo("x"), 1f)))
    }
}
