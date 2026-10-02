package com.daydream.standby.data.photos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

sealed interface SlideshowState {
    data object Loading : SlideshowState
    data object NotConfigured : SlideshowState

    /** Photos are hidden because the device is locked and lock-screen display is off. */
    data object Locked : SlideshowState
    data object Empty : SlideshowState
    data class Error(val message: String) : SlideshowState
    data class Showing(val photo: Photo) : SlideshowState
}

/**
 * Drives a slideshow from a [PhotoSource]: emits a new photo every [intervalMillis], only after
 * [prepare] has successfully loaded it (so the UI never crossfades to a blank image). The next
 * photo is prepared while the current one is on screen, so load time doesn't stretch the interval.
 * Transient failures keep the current photo on screen and retry after [retryDelayMillis].
 */
class Slideshow(
    private val source: PhotoSource,
    private val intervalMillis: Long,
    private val prepare: suspend (Photo) -> Boolean,
    private val retryDelayMillis: Long = DEFAULT_RETRY_MILLIS,
    private val maxConsecutiveFailures: Int = DEFAULT_MAX_FAILURES,
) {
    init {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
    }

    fun states(): Flow<SlideshowState> = flow {
        val run = Run(this)
        var photo = run.nextReady()
        while (true) {
            run.lastShownId = photo.id
            run.hasShown = true
            emit(SlideshowState.Showing(photo))
            photo = coroutineScope {
                val timer = launch { delay(intervalMillis) }
                val next = run.nextReady()
                timer.join()
                next
            }
        }
    }

    /** Mutable state for one collection of [states]. */
    private inner class Run(private val collector: FlowCollector<SlideshowState>) {
        private val queue = ArrayDeque<Photo>()
        private var failures = 0
        var lastShownId: String? = null
        var hasShown = false

        /** Returns the next photo that loads successfully, retrying through failures. */
        suspend fun nextReady(): Photo {
            while (true) {
                if (queue.isEmpty() && !refill()) continue
                val photo = queue.removeFirst()
                if (tryPrepare(photo)) {
                    failures = 0
                    return photo
                }
                if (++failures >= maxConsecutiveFailures) {
                    failures = 0
                    fail(SlideshowState.Error("Couldn't load photos"))
                }
            }
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
            // Don't show the same photo twice in a row across batch boundaries.
            queue.addAll(if (batch.size > 1 && batch.first().id == lastShownId) batch.drop(1) + batch.first() else batch)
            return true
        }

        private suspend fun tryPrepare(photo: Photo): Boolean = try {
            prepare(photo)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
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
    }
}
