package com.daydream.standby.ui.common

import java.time.LocalTime

/**
 * Decides whether night mode should be active from ambient light, with hysteresis so the
 * display doesn't flicker when the reading hovers around a single threshold.
 */
object NightModeDetector {
    const val ENTER_LUX = 3f
    const val EXIT_LUX = 12f

    fun fromLux(lux: Float, currentlyNight: Boolean): Boolean =
        if (currentlyNight) lux < EXIT_LUX else lux < ENTER_LUX

    /** Fallback for devices without a light sensor: 22:00–06:00. */
    fun fromTime(time: LocalTime): Boolean = time.hour >= 22 || time.hour < 6
}
