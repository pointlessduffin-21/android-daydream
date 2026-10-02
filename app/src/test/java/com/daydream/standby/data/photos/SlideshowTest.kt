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

@OptIn(ExperimentalCoroutinesApi::class)
class SlideshowTest {

    private fun photo(id: String) = Photo(id = id, data = "url/$id")

    private fun shownIds(states: List<SlideshowState>) =
        states.filterIsInstance<SlideshowState.Showing>().map { it.photo.id }

    @Test fun `cycles photos and reloads batches`() = runTest {
        var loads = 0
        val source = PhotoSource {
            loads++
            listOf(photo("a$loads"), photo("b$loads"))
        }
        val states = Slideshow(source, intervalMillis = 1_000, prepare = { true }).states().take(5).toList()
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), shownIds(states))
        assertEquals(3, loads)
    }

    @Test fun `waits interval between photos`() = runTest {
        val emitted = mutableListOf<SlideshowState>()
        val job = launch {
            Slideshow(PhotoSource { listOf(photo("1"), photo("2"), photo("3")) }, 10_000, { true }).states().collect { emitted += it }
        }
        runCurrent()
        assertEquals(1, emitted.size)
        advanceTimeBy(9_999)
        assertEquals(1, emitted.size)
        advanceTimeBy(2)
        assertEquals(2, emitted.size)
        job.cancel()
    }

    @Test fun `error before first photo is emitted, then retried`() = runTest {
        var calls = 0
        val source = PhotoSource {
            if (calls++ == 0) throw IOException("offline") else listOf(photo("x"))
        }
        val states = Slideshow(source, 1_000, { true }, retryDelayMillis = 5_000).states().take(2).toList()
        assertEquals(SlideshowState.Error("offline"), states[0])
        assertEquals(listOf("x"), shownIds(states))
    }

    @Test fun `error after a photo was shown keeps it on screen`() = runTest {
        var calls = 0
        val source = PhotoSource {
            when (calls++) {
                0 -> listOf(photo("first"))
                1 -> throw IOException("blip")
                else -> listOf(photo("second"))
            }
        }
        val states = Slideshow(source, 1_000, { true }, retryDelayMillis = 1_000).states().take(2).toList()
        assertEquals(listOf("first", "second"), shownIds(states))
        assertTrue(states.none { it is SlideshowState.Error })
    }

    @Test fun `empty source emits Empty`() = runTest {
        val states = Slideshow(PhotoSource { emptyList() }, 1_000, { true }).states().take(1).toList()
        assertEquals(listOf(SlideshowState.Empty), states)
    }

    @Test fun `photos that fail to prepare are skipped`() = runTest {
        val source = PhotoSource { listOf(photo("bad"), photo("good")) }
        val states = Slideshow(source, 1_000, prepare = { it.id == "good" }).states().take(1).toList()
        assertEquals(listOf("good"), shownIds(states))
    }

    @Test fun `prepare exceptions count as failures`() = runTest {
        val source = PhotoSource { listOf(photo("boom"), photo("ok")) }
        val states = Slideshow(source, 1_000, prepare = { if (it.id == "boom") error("decode") else true }).states().take(1).toList()
        assertEquals(listOf("ok"), shownIds(states))
    }

    @Test fun `repeated prepare failures surface an error`() = runTest {
        val states = Slideshow(PhotoSource { listOf(photo("x")) }, 1_000, prepare = { false }, maxConsecutiveFailures = 3)
            .states().take(1).toList()
        assertEquals(listOf(SlideshowState.Error("Couldn't load photos")), states)
    }

    @Test fun `does not repeat the last photo across batches`() = runTest {
        var calls = 0
        val source = PhotoSource {
            if (calls++ == 0) listOf(photo("a"), photo("b")) else listOf(photo("b"), photo("c"))
        }
        val states = Slideshow(source, 1_000, { true }).states().take(4).toList()
        assertEquals(listOf("a", "b", "c", "b"), shownIds(states))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non-positive interval`() {
        Slideshow(PhotoSource { emptyList() }, 0, { true })
    }
}
