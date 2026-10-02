package com.daydream.standby.ui.standby.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.daydream.standby.ui.theme.StandByColors
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AnalogClock(
    time: LocalTime,
    modifier: Modifier = Modifier,
    showSeconds: Boolean = true,
    faceColor: Color = Color.White,
    handColor: Color = Color.Black,
    secondHandColor: Color = StandByColors.Orange,
    showNumbers: Boolean = true,
) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    Canvas(modifier.aspectRatio(1f)) {
        val radius = size.minDimension / 2f
        drawCircle(faceColor, radius, center)

        for (i in 0 until 60) {
            val major = i % 5 == 0
            val outer = radius * 0.94f
            val inner = outer - radius * if (major) 0.09f else 0.04f
            drawLine(
                color = handColor.copy(alpha = if (major) 0.9f else 0.35f),
                start = polar(i * 6f, inner),
                end = polar(i * 6f, outer),
                strokeWidth = radius * if (major) 0.022f else 0.01f,
                cap = StrokeCap.Round,
            )
        }

        if (showNumbers) {
            val style = TextStyle(color = handColor, fontSize = (radius * 0.16f).toSp(), fontWeight = FontWeight.SemiBold)
            for (n in 1..12) {
                val layout = measurer.measure(n.toString(), style)
                val p = polar(n * 30f, radius * 0.70f)
                drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
            }
        }

        val hourAngle = (time.hour % 12 + time.minute / 60f) * 30f
        val minuteAngle = (time.minute + time.second / 60f) * 6f
        drawHand(hourAngle, radius * 0.48f, radius * 0.055f, handColor)
        drawHand(minuteAngle, radius * 0.78f, radius * 0.04f, handColor)
        if (showSeconds) {
            drawHand(time.second * 6f, radius * 0.86f, radius * 0.014f, secondHandColor, tail = radius * 0.15f)
            drawCircle(secondHandColor, radius * 0.045f, center)
        }
        drawCircle(if (showSeconds) faceColor else handColor, radius * 0.02f, center)
    }
}

private fun DrawScope.polar(angleDegrees: Float, distance: Float): Offset {
    val radians = Math.toRadians(angleDegrees - 90.0)
    return Offset(center.x + distance * cos(radians).toFloat(), center.y + distance * sin(radians).toFloat())
}

private fun DrawScope.drawHand(angle: Float, length: Float, width: Float, color: Color, tail: Float = 0f) {
    drawLine(color, start = polar(angle + 180f, tail), end = polar(angle, length), strokeWidth = width, cap = StrokeCap.Round)
}
