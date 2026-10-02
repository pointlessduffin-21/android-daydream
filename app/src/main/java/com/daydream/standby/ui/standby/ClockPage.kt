package com.daydream.standby.ui.standby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daydream.standby.ui.common.formatTime
import com.daydream.standby.ui.common.localizedPattern
import com.daydream.standby.ui.common.rememberNow
import com.daydream.standby.ui.standby.widgets.AnalogClock
import com.daydream.standby.ui.theme.StandByColors
import kotlinx.coroutines.flow.drop
import java.time.temporal.ChronoUnit

const val CLOCK_FACE_COUNT = 3

/** Full-screen clock faces; swipe vertically to change style (like StandBy). */
@Composable
fun ClockPage(initialFace: Int, onFaceChanged: (Int) -> Unit, use24Hour: Boolean, showSeconds: Boolean) {
    val pager = rememberPagerState(initialPage = initialFace.coerceIn(0, CLOCK_FACE_COUNT - 1)) { CLOCK_FACE_COUNT }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.drop(1).collect(onFaceChanged) }
    VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { face ->
        when (face) {
            0 -> DigitalFace(use24Hour, showSeconds)
            1 -> AnalogFace(showSeconds)
            else -> StackedFace(use24Hour)
        }
    }
}

@Composable
private fun DigitalFace(use24Hour: Boolean, showSeconds: Boolean) {
    val now by rememberNow(if (showSeconds) ChronoUnit.SECONDS else ChronoUnit.MINUTES)
    val time = formatTime(now.toLocalTime(), use24Hour)
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { localizedPattern("EEEdMMM", locale) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        // Size digits to fill the height but never overflow the width (~0.62em per glyph).
        val glyphs = time.hoursAndMinutes.length
        val size = with(density) { minOf(maxHeight * 0.8f, maxWidth / (glyphs * 0.62f)).toSp() }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(now.format(dateFormat).uppercase(), color = StandByColors.Orange, fontSize = size * 0.12f, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(time.hoursAndMinutes, color = Color.White, fontSize = size, fontWeight = FontWeight.Bold, lineHeight = size)
                if (showSeconds) {
                    Text(time.seconds, color = StandByColors.Orange, fontSize = size * 0.22f, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 24.dp, start = 8.dp))
                } else {
                    time.period?.let { Text(it, color = StandByColors.Secondary, fontSize = size * 0.14f, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 24.dp, start = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AnalogFace(showSeconds: Boolean) {
    val now by rememberNow(if (showSeconds) ChronoUnit.SECONDS else ChronoUnit.MINUTES)
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        AnalogClock(
            now.toLocalTime(),
            Modifier.fillMaxHeight(),
            showSeconds = showSeconds,
            faceColor = Color(0xFF111114),
            handColor = Color.White,
        )
    }
}

@Composable
private fun StackedFace(use24Hour: Boolean) {
    val now by rememberNow(ChronoUnit.MINUTES)
    val time = formatTime(now.toLocalTime(), use24Hour)
    val locale = LocalConfiguration.current.locales[0]
    val weekday = remember(locale) { localizedPattern("EEEE", locale) }
    val monthDay = remember(locale) { localizedPattern("MMMMd", locale) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
        val digit = with(LocalDensity.current) { (maxHeight * 0.46f).toSp() }
        val label = with(LocalDensity.current) { (maxHeight * 0.07f).toSp() }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy((-24).dp)) {
                Text(time.hours.padStart(2, '0'), color = StandByColors.Blue, fontSize = digit, fontWeight = FontWeight.Thin, lineHeight = digit)
                Text(time.minutes, color = Color.White, fontSize = digit, fontWeight = FontWeight.Thin, lineHeight = digit)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(now.format(weekday).uppercase(), color = StandByColors.Blue, fontSize = label, fontWeight = FontWeight.Bold)
                Text(now.format(monthDay), color = Color.White, fontSize = label, fontWeight = FontWeight.Light)
                time.period?.let { Text(it, color = StandByColors.Secondary, fontSize = label, fontWeight = FontWeight.Light) }
            }
        }
    }
}
