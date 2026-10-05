package com.daydream.standby.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Persists [AppSettings] in a Preferences DataStore. */
class SettingsRepository(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::fromPreferences)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> writeTo(prefs, transform(fromPreferences(prefs))) }
    }

    private object Keys {
        val PHOTO_SOURCE = stringPreferencesKey("photo_source")
        val IMMICH_URL = stringPreferencesKey("immich_url")
        val IMMICH_KEY = stringPreferencesKey("immich_api_key")
        val IMMICH_MODE = stringPreferencesKey("immich_mode")
        val IMMICH_ALBUMS = stringSetPreferencesKey("immich_albums")
        val LOCAL_BUCKETS = stringSetPreferencesKey("local_buckets")
        val INTERVAL = intPreferencesKey("slide_interval")
        val CLOCK_FORMAT = stringPreferencesKey("clock_format")
        val SHOW_SECONDS = booleanPreferencesKey("show_seconds")
        val PHOTO_CLOCK = booleanPreferencesKey("photo_clock_overlay")
        val PHOTO_LAYOUT = stringPreferencesKey("photo_layout")
        val KEN_BURNS = booleanPreferencesKey("ken_burns")
        val NIGHT_MODE = stringPreferencesKey("night_mode")
        val SHOW_WHEN_LOCKED = booleanPreferencesKey("show_when_locked")
        val WEATHER_NAME = stringPreferencesKey("weather_name")
        val WEATHER_LAT = doublePreferencesKey("weather_lat")
        val WEATHER_LON = doublePreferencesKey("weather_lon")
        val TEMP_UNIT = stringPreferencesKey("temperature_unit")
        val LAST_PAGE = intPreferencesKey("last_page")
        val CLOCK_FACE = intPreferencesKey("clock_face")
    }

    private fun fromPreferences(p: Preferences): AppSettings {
        val defaults = AppSettings()
        val name = p[Keys.WEATHER_NAME]
        val lat = p[Keys.WEATHER_LAT]
        val lon = p[Keys.WEATHER_LON]
        return AppSettings(
            photoSource = enumOrDefault(p[Keys.PHOTO_SOURCE], defaults.photoSource),
            immichServerUrl = p[Keys.IMMICH_URL] ?: defaults.immichServerUrl,
            immichApiKey = p[Keys.IMMICH_KEY] ?: defaults.immichApiKey,
            immichMode = enumOrDefault(p[Keys.IMMICH_MODE], defaults.immichMode),
            immichAlbumIds = p[Keys.IMMICH_ALBUMS] ?: defaults.immichAlbumIds,
            localBucketIds = p[Keys.LOCAL_BUCKETS] ?: defaults.localBucketIds,
            slideIntervalSeconds = (p[Keys.INTERVAL] ?: defaults.slideIntervalSeconds)
                .coerceIn(AppSettings.MIN_INTERVAL_SECONDS, AppSettings.MAX_INTERVAL_SECONDS),
            clockFormat = enumOrDefault(p[Keys.CLOCK_FORMAT], defaults.clockFormat),
            showSeconds = p[Keys.SHOW_SECONDS] ?: defaults.showSeconds,
            photoClockOverlay = p[Keys.PHOTO_CLOCK] ?: defaults.photoClockOverlay,
            photoLayout = enumOrDefault(p[Keys.PHOTO_LAYOUT], defaults.photoLayout),
            kenBurns = p[Keys.KEN_BURNS] ?: defaults.kenBurns,
            nightMode = enumOrDefault(p[Keys.NIGHT_MODE], defaults.nightMode),
            showWhenLocked = p[Keys.SHOW_WHEN_LOCKED] ?: defaults.showWhenLocked,
            weatherLocation = if (name != null && lat != null && lon != null) WeatherLocation(name, lat, lon) else null,
            temperatureUnit = enumOrDefault(p[Keys.TEMP_UNIT], defaults.temperatureUnit),
            lastPage = p[Keys.LAST_PAGE] ?: defaults.lastPage,
            clockFace = p[Keys.CLOCK_FACE] ?: defaults.clockFace,
        )
    }

    private fun writeTo(p: MutablePreferences, s: AppSettings) {
        p[Keys.PHOTO_SOURCE] = s.photoSource.name
        p[Keys.IMMICH_URL] = s.immichServerUrl
        p[Keys.IMMICH_KEY] = s.immichApiKey
        p[Keys.IMMICH_MODE] = s.immichMode.name
        p[Keys.IMMICH_ALBUMS] = s.immichAlbumIds
        p[Keys.LOCAL_BUCKETS] = s.localBucketIds
        p[Keys.INTERVAL] = s.slideIntervalSeconds
        p[Keys.CLOCK_FORMAT] = s.clockFormat.name
        p[Keys.SHOW_SECONDS] = s.showSeconds
        p[Keys.PHOTO_CLOCK] = s.photoClockOverlay
        p[Keys.PHOTO_LAYOUT] = s.photoLayout.name
        p[Keys.KEN_BURNS] = s.kenBurns
        p[Keys.NIGHT_MODE] = s.nightMode.name
        p[Keys.SHOW_WHEN_LOCKED] = s.showWhenLocked
        val loc = s.weatherLocation
        if (loc != null) {
            p[Keys.WEATHER_NAME] = loc.name
            p[Keys.WEATHER_LAT] = loc.latitude
            p[Keys.WEATHER_LON] = loc.longitude
        } else {
            p.remove(Keys.WEATHER_NAME)
            p.remove(Keys.WEATHER_LAT)
            p.remove(Keys.WEATHER_LON)
        }
        p[Keys.TEMP_UNIT] = s.temperatureUnit.name
        p[Keys.LAST_PAGE] = s.lastPage
        p[Keys.CLOCK_FACE] = s.clockFace
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(raw: String?, default: E): E =
        raw?.let { value -> enumValues<E>().firstOrNull { it.name == value } } ?: default
}
