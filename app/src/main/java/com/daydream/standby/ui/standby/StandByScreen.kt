package com.daydream.standby.ui.standby

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daydream.standby.data.photos.SlideshowState
import com.daydream.standby.ui.common.rememberNightMode
import com.daydream.standby.ui.common.rememberUse24Hour
import com.daydream.standby.ui.theme.StandByColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop

private const val PAGE_COUNT = 3
private const val PHOTOS_PAGE = 1
private const val CONTROLS_TIMEOUT_MILLIS = 4_000L
private const val NIGHT_DIM_ALPHA = 0.35f

/**
 * The StandBy experience: widgets, photos and clock pages, swiped horizontally.
 *
 * @param onExit shown as a close button when non-null (used by the screensaver, which has no back gesture).
 * @param onNightModeChange lets the host dim the window backlight while the red palette is active.
 * @param isDeviceLocked hides photos while true unless the user allowed lock-screen display.
 */
@Composable
fun StandByScreen(
    viewModel: StandByViewModel,
    onOpenSettings: () -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    onExit: (() -> Unit)? = null,
    isDeviceLocked: Boolean = false,
) {
    val settings = viewModel.settings.collectAsStateWithLifecycle().value
    if (settings == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val slideshow by viewModel.slideshow.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val night = rememberNightMode(settings.nightMode)
    val use24Hour = rememberUse24Hour(settings.clockFormat)
    val hidePhotos = !settings.showWhenLocked && isDeviceLocked
    LaunchedEffect(night) { onNightModeChange(night) }

    val pager = rememberPagerState(initialPage = settings.lastPage.coerceIn(0, PAGE_COUNT - 1)) { PAGE_COUNT }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.drop(1).collect(viewModel::onPageChanged) }
    LaunchedEffect(pager, hidePhotos) {
        snapshotFlow { !hidePhotos && (pager.currentPage == PHOTOS_PAGE || pager.targetPage == PHOTOS_PAGE) }
            .collect(viewModel::setPhotosVisible)
    }

    var controlsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(CONTROLS_TIMEOUT_MILLIS)
            controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .nightFilter(night)
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) },
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> WidgetsPage(weather, use24Hour, settings.showSeconds)
                PHOTOS_PAGE -> PhotosPage(
                    state = if (hidePhotos) SlideshowState.Locked else slideshow,
                    intervalSeconds = settings.slideIntervalSeconds,
                    showClock = settings.photoClockOverlay,
                    use24Hour = use24Hour,
                    onOpenSettings = onOpenSettings,
                )
                else -> ClockPage(settings.clockFace, viewModel::onClockFaceChanged, use24Hour, settings.showSeconds)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible || pager.isScrollInProgress,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(PAGE_COUNT) { i ->
                    val color = if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.3f)
                    Box(Modifier.size(7.dp).background(color, CircleShape))
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(20.dp),
        ) {
            val colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = StandByColors.CardStrong.copy(alpha = 0.85f), contentColor = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick = onOpenSettings, colors = colors) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                if (onExit != null) {
                    FilledTonalIconButton(onClick = onExit, colors = colors) { Icon(Icons.Rounded.Close, contentDescription = "Exit") }
                }
            }
        }
    }
}

/** StandBy-style night mode: everything rendered in dim red to preserve night vision. */
private fun Modifier.nightFilter(enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(StandByColors.NightRed, blendMode = BlendMode.Modulate)
                drawRect(Color.Black.copy(alpha = NIGHT_DIM_ALPHA))
            }
    }
