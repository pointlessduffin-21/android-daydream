package com.daydream.standby.ui.standby.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daydream.standby.data.weather.WeatherCondition
import com.daydream.standby.ui.common.formatTime
import com.daydream.standby.ui.common.rememberBattery
import com.daydream.standby.ui.common.rememberNextAlarm
import com.daydream.standby.ui.common.rememberNow
import com.daydream.standby.ui.standby.WeatherState
import com.daydream.standby.ui.theme.StandByColors
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
private fun Dp.sp(): TextUnit = with(LocalDensity.current) { this@sp.toSp() }

@Composable
fun AnalogClockWidget(showSeconds: Boolean) {
    val now by rememberNow(if (showSeconds) ChronoUnit.SECONDS else ChronoUnit.MINUTES)
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        AnalogClock(now.toLocalTime(), Modifier.fillMaxHeight(), showSeconds = showSeconds)
    }
}

@Composable
fun CalendarWidget() {
    val now by rememberNow(ChronoUnit.MINUTES)
    val today = now.toLocalDate()
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
        val h = maxHeight
        val big = (h * 0.45f).sp()
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(0.9f)) {
                Text(
                    today.dayOfWeek.getDisplayName(DateTextStyle.FULL, currentLocale()).uppercase(),
                    color = StandByColors.Accent,
                    fontSize = (h * 0.09f).sp(),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(today.dayOfMonth.toString(), color = Color.White, fontSize = big, fontWeight = FontWeight.Medium, lineHeight = big)
            }
            MonthGrid(today, Modifier.weight(1.1f), cell = (h * 0.075f).sp())
        }
    }
}

@Composable
private fun MonthGrid(today: LocalDate, modifier: Modifier, cell: TextUnit) {
    val locale = currentLocale()
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val first = today.withDayOfMonth(1)
    val leading = (first.dayOfWeek.value - firstDay.value + 7) % 7
    val days: List<Int?> = List(leading) { null } + (1..today.lengthOfMonth()).toList()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            today.month.getDisplayName(DateTextStyle.FULL, locale).uppercase(),
            color = StandByColors.Accent, fontSize = cell, fontWeight = FontWeight.Bold,
        )
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { i ->
                val dow = firstDay.plus(i.toLong())
                Text(
                    dow.getDisplayName(DateTextStyle.NARROW, locale),
                    Modifier.weight(1f), color = StandByColors.Secondary, fontSize = cell,
                    textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val day = week.getOrNull(i)
                    val isToday = day == today.dayOfMonth
                    Box(Modifier.weight(1f).aspectRatio(1.3f), contentAlignment = Alignment.Center) {
                        if (isToday) Box(Modifier.fillMaxHeight().aspectRatio(1f).background(StandByColors.Accent, CircleShape))
                        Text(day?.toString().orEmpty(), color = Color.White, fontSize = cell, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
fun BatteryWidget() {
    val battery by rememberBattery()
    val percent = battery.percent
    val color = when {
        battery.charging -> StandByColors.Green
        percent in 0..20 -> StandByColors.Accent
        else -> Color.White
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        val h = maxHeight
        val ring = minOf(maxWidth, h * 0.8f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = size.minDimension * 0.1f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val topLeft = Offset(stroke / 2, stroke / 2)
                    drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                    if (percent >= 0) {
                        drawArc(color, -90f, 360f * percent / 100f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = if (battery.charging) color else StandByColors.Secondary, modifier = Modifier.size(ring * 0.4f))
            }
            Spacer(Modifier.height(8.dp))
            Text(if (percent >= 0) "$percent%" else "—", color = Color.White, fontSize = (h * 0.12f).sp(), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun AlarmWidget(use24Hour: Boolean) {
    val alarm by rememberNextAlarm()
    val now by rememberNow(ChronoUnit.MINUTES)
    BoxWithConstraints(Modifier.fillMaxSize().padding(28.dp)) {
        val h = maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Alarm, contentDescription = null, tint = StandByColors.Orange, modifier = Modifier.size(h * 0.16f))
                Spacer(Modifier.width(8.dp))
                Text("ALARM", color = StandByColors.Orange, fontSize = (h * 0.09f).sp(), fontWeight = FontWeight.Bold)
            }
            val next = alarm
            if (next == null) {
                Text("No alarm set", color = StandByColors.Secondary, fontSize = (h * 0.12f).sp(), fontWeight = FontWeight.Medium)
            } else {
                val t = formatTime(next.toLocalTime(), use24Hour)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(t.hoursAndMinutes, color = Color.White, fontSize = (h * 0.3f).sp(), fontWeight = FontWeight.SemiBold)
                    t.period?.let { Text(" $it", color = Color.White, fontSize = (h * 0.1f).sp(), modifier = Modifier.padding(bottom = 12.dp)) }
                }
                val days = ChronoUnit.DAYS.between(now.toLocalDate(), next.toLocalDate())
                val label = when (days) {
                    0L -> "Today"
                    1L -> "Tomorrow"
                    else -> next.dayOfWeek.getDisplayName(DateTextStyle.FULL, currentLocale())
                }
                Text(label, color = StandByColors.Secondary, fontSize = (h * 0.1f).sp())
            }
        }
    }
}

@Composable
fun WeatherWidget(state: WeatherState) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(28.dp)) {
        val h = maxHeight
        val small = (h * 0.09f).sp()
        when (state) {
            WeatherState.NotConfigured -> CenteredMessage("Weather", "Set a location in Settings", small)
            WeatherState.Loading -> CenteredMessage("Weather", "Loading…", small)
            is WeatherState.Error -> CenteredMessage("Weather", state.message, small)
            is WeatherState.Loaded -> {
                val w = state.weather
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(state.locationName.substringBefore(","), color = Color.White, fontSize = small, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${w.temperature}°", color = Color.White, fontSize = (h * 0.36f).sp(), fontWeight = FontWeight.Light)
                        Spacer(Modifier.weight(1f))
                        val (icon, tint) = weatherIcon(w.condition, w.isDay)
                        Icon(icon, contentDescription = w.condition.label, tint = tint, modifier = Modifier.size(h * 0.3f))
                    }
                    Column {
                        Text(w.condition.label, color = Color.White, fontSize = small, fontWeight = FontWeight.Medium)
                        if (w.high != null && w.low != null) {
                            Text("H:${w.high}°  L:${w.low}°", color = StandByColors.Secondary, fontSize = small)
                        }
                    }
                }
            }
        }
    }
}

private fun weatherIcon(condition: WeatherCondition, isDay: Boolean): Pair<ImageVector, Color> = when (condition) {
    WeatherCondition.CLEAR -> if (isDay) Icons.Rounded.WbSunny to StandByColors.Yellow else Icons.Rounded.NightsStay to StandByColors.Blue
    WeatherCondition.PARTLY_CLOUDY -> Icons.Rounded.WbCloudy to Color.White
    WeatherCondition.CLOUDY -> Icons.Rounded.Cloud to StandByColors.Secondary
    WeatherCondition.FOG -> Icons.Rounded.Dehaze to StandByColors.Secondary
    WeatherCondition.DRIZZLE -> Icons.Rounded.Grain to StandByColors.Blue
    WeatherCondition.RAIN -> Icons.Rounded.WaterDrop to StandByColors.Blue
    WeatherCondition.SNOW -> Icons.Rounded.AcUnit to Color.White
    WeatherCondition.THUNDERSTORM -> Icons.Rounded.Thunderstorm to StandByColors.Yellow
    WeatherCondition.UNKNOWN -> Icons.Rounded.QuestionMark to StandByColors.Secondary
}

@Composable
private fun CenteredMessage(title: String, message: String, size: TextUnit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(title.uppercase(), color = StandByColors.Blue, fontSize = size, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = StandByColors.Secondary, fontSize = size)
    }
}
