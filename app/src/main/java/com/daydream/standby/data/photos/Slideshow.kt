package com.daydream.standby.data.photos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.random.Random

sealed interface SlideshowState {
    data object Loading : SlideshowState
    data object NotConfigured : SlideshowState

    /** Photos are hidden because the device is locked and lock-screen display is off. */
    data object Locked : SlideshowState
    data object Empty : SlideshowState
    data class Error(val message: String) : SlideshowState
    data class Showing(val slide: Slide) : SlideshowState
}

/**
 * Drives a slideshow from a [PhotoSource]: emits a new [Slide] every [intervalMillis].
 *
 * Each slide is planned by [planner] from the next photo plus a lookahead of upcoming ones, using
 * decoded sizes where known and metadata hints otherwise. Only the photos a plan uses are
 * [prepare]d (decoded into the image cache); if a decoded shape contradicts its hint, the slide
 * is re-planned until stable. The next slide is prepared while the current one is on screen, so
 * load time doesn't stretch the interval. Transient failures keep the current slide on screen
 * and retry after [retryDelayMillis].
 */
class Slideshow(
    private val source: PhotoSource,
    private val intervalMillis: Long,
    private val prepare: suspend (Photo) -> PhotoSize?,
    private val planner: SlidePlanner,
    private val viewportAspect: Float,
    private val random: Random = Random.Default,
    private val retryDelayMillis: Long = DEFAULT_RETRY_MILLIS,
    private val maxConsecutiveFailures: Int = DEFAULT_MAX_FAILURES,
    private val lookahead: Int = SlidePlanner.LOOKAHEAD,
) {
    init {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        require(viewportAspect > 0f) { "viewportAspect must be positive" }
    }

    fun states(): Flow<SlideshowState> = flow {
        val run = Run(this)
        var slide = run.nextSlide()
        while (true) {
            run.lastShownIds = slide.photos.mapTo(HashSet()) { it.photo.id }
            run.hasShown = true
            emit(SlideshowState.Showing(slide))
            slide = coroutineScope {
                val timer = launch { delay(intervalMillis) }
                val next = run.nextSlide()
                timer.join()
                next
            }
        }
    }

    /** Mutable state for one collection of [states]. */
    private inner class Run(private val collector: FlowCollector<SlideshowState>) {
        private val queue = ArrayDeque<Photo>()

        /** Decoded aspects of photos prepared but not yet shown (lookahead candidates). */
        private val decoded = HashMap<String, Float>()
        private var failures = 0
        private var lastBatchSize = Int.MAX_VALUE
        var lastShownIds: Set<String> = emptySet()
        var hasShown = false

        suspend fun nextSlide(): Slide {
            val first = nextReady()
            if (queue.size < lookahead && lastBatchSize >= lookahead) topUp()
            val choice = random.nextInt(CHOICE_RANGE)
            repeat(MAX_REPLANS) {
                val candidates = candidatesAfter(first.photo)
                val assumed = candidates.map { decoded[it.id] ?: it.aspectHint }
                val plan = planner.plan(listOf(first.aspect) + assumed, viewportAspect, choice)
                val picks = plan.indices.drop(1).map { it - 1 }
                if (picks.isEmpty()) return slideOf(plan.layout, listOf(first))
                // Prepare each partner (a cache hit if already decoded) and check its real shape;
                // stop at the first surprise, since the plan has to be redone anyway.
                val confirmed = picks.all { i -> confirm(candidates[i], assumed[i]) }
                if (confirmed) {
                    val partners = picks.map { candidates[it] }
                    partners.forEach { queue.remove(it) }
                    val slide = slideOf(plan.layout, listOf(first) + partners.map { SlidePhoto(it, decoded.getValue(it.id)) })
                    // Tiny libraries: never show the exact same set twice in a row.
                    if (slide.photos.mapTo(HashSet()) { it.photo.id } != lastShownIds) return slide
                    partners.forEach { queue.addLast(it) }
                    return singleOf(first)
                }
            }
            return singleOf(first)
        }

        private fun singleOf(first: SlidePhoto) = slideOf(planner.plan(listOf(first.aspect), viewportAspect).layout, listOf(first))

        private fun slideOf(layout: SlideLayout, photos: List<SlidePhoto>): Slide {
            photos.forEach { decoded.remove(it.photo.id) }
            return Slide(layout, photos, vertical = viewportAspect < 1f)
        }

        /**
         * Upcoming distinct photos, excluding [first]. Photos from the slide currently on screen go
         * last, so they're only used as partners when nothing else fits.
         */
        private fun candidatesAfter(first: Photo): List<Photo> {
            val distinct = queue.asSequence().filter { it.id != first.id }.distinctBy { it.id }
            val (recent, fresh) = distinct.partition { it.id in lastShownIds }
            return (fresh + recent).take(lookahead)
        }

        /**
         * Prepares [photo] so it is in the image cache when shown. Returns false if the plan must
         * be redone: the photo failed (and was dropped) or its real shape differs from [assumed].
         */
        private suspend fun confirm(photo: Photo, assumed: Float?): Boolean {
            val size = tryPrepare(photo)
            if (size == null) {
                decoded.remove(photo.id)
                queue.removeAll { it.id == photo.id } // batches may hold duplicates of the broken photo
                return false
            }
            decoded[photo.id] = size.aspect
            return assumed != null && planner.sameShape(assumed, size.aspect, viewportAspect)
        }

        /** Returns the next photo that loads successfully, retrying through failures. */
        private suspend fun nextReady(): SlidePhoto {
            while (true) {
                if (queue.isEmpty() && !refill()) continue
                val photo = takeNext()
                // Always prepare: a cached aspect only means it *was* decoded, not that it still is.
                decoded.remove(photo.id)
                val aspect = tryPrepare(photo)?.aspect
                if (aspect != null) {
                    failures = 0
                    return SlidePhoto(photo, aspect)
                }
                if (++failures >= maxConsecutiveFailures) {
                    failures = 0
                    fail(SlideshowState.Error("Couldn't load photos"))
                }
            }
        }

        /** The next queued photo that wasn't on the previous slide (if any other is available). */
        private fun takeNext(): Photo {
            val index = queue.indexOfFirst { it.id !in lastShownIds }.coerceAtLeast(0)
            return queue.removeAt(index)
        }

        private suspend fun refill(): Boolean {
            val batch = try {
                source.loadBatch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(SlideshowState.Error(e.message ?: "Couldn't load photos"))
                return false
            }
            if (batch.isEmpty()) {
                fail(SlideshowState.Empty)
                return false
            }
            enqueue(batch)
            return true
        }

        /** Best-effort extra batch so collages have partners to choose from; never blocks on errors. */
        private suspend fun topUp() {
            val batch = try {
                source.loadBatch()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Don't retry on every slide while offline; the next regular refill re-enables it.
                lastBatchSize = 0
                return
            }
            enqueue(batch)
        }

        /** Don't show photos from the slide on screen again straight away. */
        private fun enqueue(batch: List<Photo>) {
            lastBatchSize = batch.size
            val (recent, fresh) = batch.partition { it.id in lastShownIds }
            queue.addAll(fresh + recent)
        }

        private suspend fun tryPrepare(photo: Photo): PhotoSize? = try {
            prepare(photo)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        /** Shows [state] only if nothing is on screen yet, then backs off. */
        private suspend fun fail(state: SlideshowState) {
            if (!hasShown) collector.emit(state)
            delay(retryDelayMillis)
        }
    }

    companion object {
        const val DEFAULT_RETRY_MILLIS = 30_000L
        const val DEFAULT_MAX_FAILURES = 5
        private const val MAX_REPLANS = 3
        private const val CHOICE_RANGE = 1_000
    }
}
