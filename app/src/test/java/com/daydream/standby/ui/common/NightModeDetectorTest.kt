package com.daydream.standby.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class NightModeDetectorTest {
    @Test fun `enters below enter threshold only`() {
        assertTrue(NightModeDetector.fromLux(0f, currentlyNight = false))
        assertFalse(NightModeDetector.fromLux(NightModeDetector.ENTER_LUX, currentlyNight = false))
        assertFalse(NightModeDetector.fromLux(8f, currentlyNight = false))
    }

    @Test fun `stays in night mode until exit threshold`() {
        assertTrue(NightModeDetector.fromLux(8f, currentlyNight = true))
        assertFalse(NightModeDetector.fromLux(NightModeDetector.EXIT_LUX, currentlyNight = true))
        assertFalse(NightModeDetector.fromLux(500f, currentlyNight = true))
    }

    @Test fun `time fallback covers 22 to 6`() {
        assertTrue(NightModeDetector.fromTime(LocalTime.of(22, 0)))
        assertTrue(NightModeDetector.fromTime(LocalTime.of(3, 30)))
        assertTrue(NightModeDetector.fromTime(LocalTime.of(5, 59)))
        assertFalse(NightModeDetector.fromTime(LocalTime.of(6, 0)))
        assertFalse(NightModeDetector.fromTime(LocalTime.of(21, 59)))
    }
}
