package com.daydream.standby.ui.standby

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.daydream.standby.data.photos.Photo
import com.daydream.standby.data.photos.SlideshowState
import com.daydream.standby.ui.common.formatTime
import com.daydream.standby.ui.common.localizedPattern
import com.daydream.standby.ui.common.rememberNow
import com.daydream.standby.ui.common.toImageRequest
import com.daydream.standby.ui.theme.StandByColors
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

private val overlayShadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 16f)

@Composable
fun PhotosPage(
    state: SlideshowState,
    intervalSeconds: Int,
    showClock: Boolean,
    use24Hour: Boolean,
    onOpenSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (state) {
            is SlideshowState.Showing -> Crossfade(targetState = state.photo, animationSpec = tween(CROSSFADE_MILLIS), label = "photo") { photo ->
                KenBurnsPhoto(photo, durationMillis = intervalSeconds * 1_000 + CROSSFADE_MILLIS)
            }
            SlideshowState.Loading -> Unit
            SlideshowState.NotConfigured -> Placeholder("Add your photos", "Connect Immich or use photos on this device.", onOpenSettings)
            SlideshowState.Locked -> Placeholder("Photos hidden", "Unlock your phone, or allow “Show over lock screen” in Settings.", onOpenSettings)
            SlideshowState.Empty -> Placeholder("No photos found", "The selected source has no images yet.", onOpenSettings)
            is SlideshowState.Error -> Placeholder("Photos unavailable", state.message, onOpenSettings)
        }

        if (showClock) ClockOverlay(use24Hour, Modifier.align(Alignment.TopStart))
        (state as? SlideshowState.Showing)?.photo?.let { PhotoCaption(it, Modifier.align(Alignment.BottomStart)) }
    }
}

@Composable
private fun KenBurnsPhoto(photo: Photo, durationMillis: Int) {
    val context = LocalContext.current
    val request = remember(photo.id) { photo.toImageRequest(context) }
    val scale = remember(photo.id) { Animatable(1f) }
    LaunchedEffect(photo.id) { scale.animateTo(KEN_BURNS_SCALE, tween(durationMillis, easing = LinearEasing)) }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}

@Composable
private fun ClockOverlay(use24Hour: Boolean, modifier: Modifier) {
    val now by rememberNow(ChronoUnit.MINUTES)
    val time = formatTime(now.toLocalTime(), use24Hour)
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { localizedPattern("EEEEMMMMd", locale) }
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Column {
            Text(
                now.format(dateFormat),
                style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, shadow = overlayShadow),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    time.hoursAndMinutes,
                    style = TextStyle(color = Color.White, fontSize = 112.sp, fontWeight = FontWeight.Bold, shadow = overlayShadow, lineHeight = 112.sp),
                )
                time.period?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(start = 8.dp, bottom = 18.dp),
                        style = TextStyle(color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, shadow = overlayShadow),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoCaption(photo: Photo, modifier: Modifier) {
    val parts = listOfNotNull(photo.location, photo.takenOn?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)))
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  ·  "),
        modifier = modifier.padding(horizontal = 40.dp, vertical = 24.dp),
        style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, fontWeight = FontWeight.Medium, shadow = overlayShadow),
    )
}

@Composable
private fun Placeholder(title: String, message: String, onOpenSettings: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF1B1F3B), Color(0xFF3A1C47), Color(0xFF0B0B0F))),
        ),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            Modifier.padding(horizontal = 40.dp, vertical = 32.dp).widthIn(max = 420.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(message, color = StandByColors.Secondary, fontSize = 16.sp, textAlign = TextAlign.End)
            TextButton(onClick = onOpenSettings) { Text("Open Settings", color = StandByColors.Orange, fontSize = 16.sp) }
        }
    }
}

private const val CROSSFADE_MILLIS = 1_200
private const val KEN_BURNS_SCALE = 1.08f
