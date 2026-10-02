package com.daydream.standby.ui.common

import android.text.format.DateFormat
import com.daydream.standby.data.settings.ClockFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class FormattedTime(val hours: String, val minutes: String, val seconds: String, val period: String?) {
    val hoursAndMinutes: String get() = "$hours:$minutes"
}

fun resolve24Hour(format: ClockFormat, systemIs24Hour: Boolean): Boolean = when (format) {
    ClockFormat.SYSTEM -> systemIs24Hour
    ClockFormat.H12 -> false
    ClockFormat.H24 -> true
}

fun formatTime(time: LocalTime, use24Hour: Boolean): FormattedTime {
    val minutes = time.minute.toString().padStart(2, '0')
    val seconds = time.second.toString().padStart(2, '0')
    if (use24Hour) return FormattedTime(time.hour.toString().padStart(2, '0'), minutes, seconds, null)
    val hour12 = (time.hour % 12).let { if (it == 0) 12 else it }
    return FormattedTime(hour12.toString(), minutes, seconds, if (time.hour < 12) "AM" else "PM")
}

/** A formatter for [skeleton] (e.g. "EEEEMMMMd") in the user's locale's field order. */
fun localizedPattern(skeleton: String, locale: Locale = Locale.getDefault()): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
