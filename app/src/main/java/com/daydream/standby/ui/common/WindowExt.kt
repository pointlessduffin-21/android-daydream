package com.daydream.standby.ui.common

import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private const val NIGHT_BRIGHTNESS = 0.02f

/** Edge-to-edge, system bars hidden (swipe to reveal), screen kept on. */
fun Window.enterStandByMode() {
    addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    WindowCompat.setDecorFitsSystemWindows(this, false)
    WindowInsetsControllerCompat(this, decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/** Drops the backlight to near-minimum while night mode is on; restores the user's setting otherwise. */
fun Window.applyNightBrightness(night: Boolean) {
    attributes = attributes.apply {
        screenBrightness = if (night) NIGHT_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}
