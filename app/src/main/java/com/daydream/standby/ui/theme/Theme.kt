package com.daydream.standby.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object StandByColors {
    val Background = Color.Black
    val Card = Color(0xFF1C1C1E)
    val CardStrong = Color(0xFF2C2C2E)
    val Primary = Color.White
    val Secondary = Color(0xFF8E8E93)
    val Accent = Color(0xFFFF453A)
    val Orange = Color(0xFFFF9F0A)
    val Green = Color(0xFF30D158)
    val Blue = Color(0xFF64D2FF)
    val Yellow = Color(0xFFFFD60A)
    val NightRed = Color(0xFFFF2A1A)
}

private val scheme = darkColorScheme(
    primary = StandByColors.Orange,
    onPrimary = Color.Black,
    secondary = StandByColors.Blue,
    background = StandByColors.Background,
    surface = StandByColors.Background,
    surfaceContainer = StandByColors.Card,
    surfaceContainerHigh = StandByColors.CardStrong,
    error = StandByColors.Accent,
)

@Composable
fun DaydreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
