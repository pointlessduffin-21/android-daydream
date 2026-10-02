package com.daydream.standby.ui.common

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.daydream.standby.data.settings.ClockFormat
import com.daydream.standby.data.settings.NightModeSetting
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Current local time, truncated to [unit]; recomposes readers only when the truncated value changes. */
@Composable
fun rememberNow(unit: ChronoUnit = ChronoUnit.MINUTES): State<LocalDateTime> {
    val stepMillis = unit.duration.toMillis().coerceAtLeast(1_000)
    // Re-sync immediately when the user changes the clock or time zone.
    val clockChanges by rememberBroadcastState(TIME_CHANGE_FILTER, initial = { 0 }, map = { _, _ -> System.nanoTime().toInt() })
    return produceState(LocalDateTime.now().truncatedTo(unit), unit, clockChanges) {
        while (true) {
            value = LocalDateTime.now().truncatedTo(unit)
            delay(stepMillis - System.currentTimeMillis() % stepMillis)
        }
    }
}

private val TIME_CHANGE_FILTER = IntentFilter().apply {
    addAction(Intent.ACTION_TIME_CHANGED)
    addAction(Intent.ACTION_TIMEZONE_CHANGED)
}

/** Resolves the 12/24-hour preference, tracking changes to the system setting while shown. */
@Composable
fun rememberUse24Hour(format: ClockFormat): Boolean {
    val systemIs24Hour by rememberBroadcastState(
        filter = IntentFilter(Intent.ACTION_TIME_CHANGED),
        initial = { DateFormat.is24HourFormat(it) },
        map = { ctx, _ -> DateFormat.is24HourFormat(ctx) },
    )
    return resolve24Hour(format, systemIs24Hour)
}

/** Subscribes to a broadcast for as long as the caller is composed. */
@Composable
private fun <T> rememberBroadcastState(filter: IntentFilter, initial: (Context) -> T, map: (Context, Intent?) -> T): State<T> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(initial(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent?) {
                state.value = map(ctx, intent)
            }
        }
        val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (sticky != null) state.value = map(context, sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

data class BatteryInfo(val percent: Int, val charging: Boolean)

@Composable
fun rememberBattery(): State<BatteryInfo> = rememberBroadcastState(
    filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED),
    initial = { BatteryInfo(percent = -1, charging = false) },
    map = { _, intent ->
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        BatteryInfo(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        )
    },
)

/** The next alarm set in any clock app, or null. */
@Composable
fun rememberNextAlarm(): State<LocalDateTime?> {
    val read: (Context) -> LocalDateTime? = { ctx ->
        ContextCompat.getSystemService(ctx, AlarmManager::class.java)?.nextAlarmClock?.triggerTime?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
        }
    }
    return rememberBroadcastState(
        filter = IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED),
        initial = read,
        map = { ctx, _ -> read(ctx) },
    )
}

@Composable
private fun nightByClock(): Boolean {
    val now by rememberNow(ChronoUnit.MINUTES)
    return NightModeDetector.fromTime(now.toLocalTime())
}

/** Whether the red night palette should be applied. */
@Composable
fun rememberNightMode(setting: NightModeSetting): Boolean {
    val context = LocalContext.current
    var night by remember { mutableStateOf(false) }
    val sensorManager = remember { ContextCompat.getSystemService(context, SensorManager::class.java) }
    val lightSensor = remember { sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) }

    DisposableEffect(setting, lightSensor) {
        if (setting != NightModeSetting.AUTO || sensorManager == null || lightSensor == null) {
            return@DisposableEffect onDispose { }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                night = NightModeDetector.fromLux(event.values[0], night)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    return when (setting) {
        NightModeSetting.ON -> true
        NightModeSetting.OFF -> false
        NightModeSetting.AUTO -> if (lightSensor != null) night else nightByClock()
    }
}
