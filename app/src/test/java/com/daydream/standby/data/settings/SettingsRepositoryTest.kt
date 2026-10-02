package com.daydream.standby.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.store() = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { tmp.newFile("s.preferences_pb").also { it.delete() } })

    @Test fun `defaults when empty`() = runTest {
        assertEquals(AppSettings(), SettingsRepository(store()).settings.first())
    }

    @Test fun `round-trips every field`() = runTest {
        val repo = SettingsRepository(store())
        val expected = AppSettings(
            photoSource = PhotoSourceType.IMMICH,
            immichServerUrl = "https://h/",
            immichApiKey = "k",
            immichMode = ImmichMode.ALBUMS,
            immichAlbumIds = setOf("a", "b"),
            slideIntervalSeconds = 42,
            clockFormat = ClockFormat.H24,
            showSeconds = true,
            photoClockOverlay = false,
            nightMode = NightModeSetting.ON,
            showWhenLocked = true,
            weatherLocation = WeatherLocation("Manila", 14.6, 120.9),
            temperatureUnit = TemperatureUnit.FAHRENHEIT,
            lastPage = 2,
            clockFace = 1,
        )
        repo.update { expected }
        assertEquals(expected, repo.settings.first())
    }

    @Test fun `clearing weather location removes it`() = runTest {
        val repo = SettingsRepository(store())
        repo.update { it.copy(weatherLocation = WeatherLocation("X", 1.0, 2.0)) }
        repo.update { it.copy(weatherLocation = null) }
        assertNull(repo.settings.first().weatherLocation)
    }

    @Test fun `unknown enum values and out-of-range interval fall back safely`() = runTest {
        val store = store()
        store.edit {
            it[stringPreferencesKey("photo_source")] = "SOMETHING_NEW"
            it[stringPreferencesKey("night_mode")] = ""
            it[intPreferencesKey("slide_interval")] = 1
        }
        val s = SettingsRepository(store).settings.first()
        assertEquals(PhotoSourceType.NONE, s.photoSource)
        assertEquals(NightModeSetting.AUTO, s.nightMode)
        assertEquals(AppSettings.MIN_INTERVAL_SECONDS, s.slideIntervalSeconds)

        store.edit { it[intPreferencesKey("slide_interval")] = 100_000 }
        assertEquals(AppSettings.MAX_INTERVAL_SECONDS, SettingsRepository(store).settings.first().slideIntervalSeconds)
    }

    @Test fun `photoConfig reflects photo settings only`() {
        val s = AppSettings(photoSource = PhotoSourceType.LOCAL, slideIntervalSeconds = 9)
        assertEquals(s.photoConfig, s.copy(showSeconds = true, lastPage = 0).photoConfig)
        assertEquals(9, s.photoConfig.intervalSeconds)
    }
}
