package com.daydream.standby.ui.standby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.daydream.standby.ui.standby.widgets.AlarmWidget
import com.daydream.standby.ui.standby.widgets.AnalogClockWidget
import com.daydream.standby.ui.standby.widgets.BatteryWidget
import com.daydream.standby.ui.standby.widgets.CalendarWidget
import com.daydream.standby.ui.standby.widgets.WeatherWidget
import com.daydream.standby.ui.theme.StandByColors

/** Two independently swipeable widget stacks, side by side (stacked in portrait). */
@Composable
fun WidgetsPage(weather: WeatherState, use24Hour: Boolean, showSeconds: Boolean) {
    val left: List<@Composable () -> Unit> = listOf(
        { AnalogClockWidget(showSeconds) },
        { BatteryWidget() },
        { AlarmWidget(use24Hour) },
    )
    val right: List<@Composable () -> Unit> = listOf(
        { CalendarWidget() },
        { WeatherWidget(weather) },
    )
    BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                WidgetStack(left, Modifier.weight(1f))
                WidgetStack(right, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                WidgetStack(left, Modifier.weight(1f))
                WidgetStack(right, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WidgetStack(widgets: List<@Composable () -> Unit>, modifier: Modifier) {
    val pager = rememberPagerState { widgets.size }
    Box(modifier.fillMaxSize().clip(RoundedCornerShape(36.dp)).background(StandByColors.Card)) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page -> widgets[page]() }
        if (widgets.size > 1) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(widgets.size) { i ->
                    val color = if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.25f)
                    Box(Modifier.size(5.dp).background(color, CircleShape))
                }
            }
        }
    }
}
