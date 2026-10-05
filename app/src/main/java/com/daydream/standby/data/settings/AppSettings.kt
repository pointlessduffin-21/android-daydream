package com.daydream.standby.data.settings

enum class PhotoSourceType { NONE, LOCAL, IMMICH }

enum class ImmichMode { RANDOM, FAVORITES, ALBUMS }

enum class ClockFormat { SYSTEM, H12, H24 }

enum class NightModeSetting { AUTO, ON, OFF }

enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

/** AUTO groups portraits into collages; SINGLE always shows one photo per slide. */
enum class PhotoLayout { AUTO, SINGLE }

/** Everything that determines which photos the slideshow shows and how fast. */
data class PhotoConfig(
    val source: PhotoSourceType,
    val immichServerUrl: String,
    val immichApiKey: String,
    val immichMode: ImmichMode,
    val immichAlbumIds: Set<String>,
    val localBucketIds: Set<String>,
    val intervalSeconds: Int,
    val layout: PhotoLayout,
)

data class WeatherLocation(val name: String, val latitude: Double, val longitude: Double)

data class AppSettings(
    val photoSource: PhotoSourceType = PhotoSourceType.NONE,
    val immichServerUrl: String = "",
    val immichApiKey: String = "",
    val immichMode: ImmichMode = ImmichMode.RANDOM,
    val immichAlbumIds: Set<String> = emptySet(),
    /** MediaStore bucket ids the "This device" source is limited to; empty means every folder except screenshots. */
    val localBucketIds: Set<String> = emptySet(),
    val slideIntervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
    val clockFormat: ClockFormat = ClockFormat.SYSTEM,
    val showSeconds: Boolean = false,
    val photoClockOverlay: Boolean = true,
    val photoLayout: PhotoLayout = PhotoLayout.AUTO,
    val kenBurns: Boolean = true,
    val nightMode: NightModeSetting = NightModeSetting.AUTO,
    val showWhenLocked: Boolean = false,
    val weatherLocation: WeatherLocation? = null,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val lastPage: Int = 1,
    val clockFace: Int = 0,
) {
    val photoConfig: PhotoConfig
        get() = PhotoConfig(
            source = photoSource,
            immichServerUrl = immichServerUrl,
            immichApiKey = immichApiKey,
            immichMode = immichMode,
            immichAlbumIds = immichAlbumIds,
            localBucketIds = localBucketIds,
            intervalSeconds = slideIntervalSeconds,
            layout = photoLayout,
        )

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 15
        const val MIN_INTERVAL_SECONDS = 5
        const val MAX_INTERVAL_SECONDS = 300
    }
}
