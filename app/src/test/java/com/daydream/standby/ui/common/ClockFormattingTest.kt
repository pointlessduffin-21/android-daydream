package com.daydream.standby.ui.common

import com.daydream.standby.data.settings.ClockFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ClockFormattingTest {
    @Test fun `12-hour midnight and noon`() {
        assertEquals(FormattedTime("12", "05", "09", "AM"), formatTime(LocalTime.of(0, 5, 9), use24Hour = false))
        assertEquals(FormattedTime("12", "00", "00", "PM"), formatTime(LocalTime.NOON, use24Hour = false))
    }

    @Test fun `12-hour afternoon has no leading zero`() {
        val t = formatTime(LocalTime.of(13, 7), use24Hour = false)
        assertEquals("1:07", t.hoursAndMinutes)
        assertEquals("PM", t.period)
    }

    @Test fun `24-hour pads and omits period`() {
        val t = formatTime(LocalTime.of(7, 3), use24Hour = true)
        assertEquals("07:03", t.hoursAndMinutes)
        assertNull(t.period)
        assertEquals("23:59", formatTime(LocalTime.of(23, 59), true).hoursAndMinutes)
    }

    @Test fun `resolve24Hour honours explicit choice over system`() {
        assertTrue(resolve24Hour(ClockFormat.SYSTEM, systemIs24Hour = true))
        assertFalse(resolve24Hour(ClockFormat.SYSTEM, systemIs24Hour = false))
        assertFalse(resolve24Hour(ClockFormat.H12, systemIs24Hour = true))
        assertTrue(resolve24Hour(ClockFormat.H24, systemIs24Hour = false))
    }
}
