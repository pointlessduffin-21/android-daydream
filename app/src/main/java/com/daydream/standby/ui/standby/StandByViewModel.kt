package com.daydream.standby.ui.standby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.daydream.standby.AppContainer
import com.daydream.standby.data.photos.Slideshow
import com.daydream.standby.data.photos.SlideshowState
import com.daydream.standby.data.settings.AppSettings
import com.daydream.standby.data.weather.Weather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface WeatherState {
    data object NotConfigured : WeatherState
    data object Loading : WeatherState
    data class Error(val message: String) : WeatherState
    data class Loaded(val locationName: String, val weather: Weather) : WeatherState
}

@OptIn(ExperimentalCoroutinesApi::class)
class StandByViewModel(private val container: AppContainer) : ViewModel() {

    /** Null until the first read from disk completes, so the UI doesn't flash default values. */
    val settings: StateFlow<AppSettings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val photosVisible = MutableStateFlow(false)

    /** Runs only while the photos page is on screen, so other pages don't burn data and battery. */
    val slideshow: StateFlow<SlideshowState> = container.settings.settings
        .map { it.photoConfig }
        .distinctUntilChanged()
        .combine(photosVisible) { config, visible -> config.takeIf { visible } }
        .distinctUntilChanged()
        .flatMapLatest { config ->
            if (config == null) return@flatMapLatest emptyFlow()
            val source = container.photoSource(config) ?: return@flatMapLatest flowOf<SlideshowState>(SlideshowState.NotConfigured)
            Slideshow(source, config.intervalSeconds * 1_000L, container::preparePhoto).states()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SlideshowState.Loading)

    val weather: StateFlow<WeatherState> = container.settings.settings
        .map { it.weatherLocation to it.temperatureUnit }
        .distinctUntilChanged()
        .flatMapLatest { (location, unit) ->
            if (location == null) return@flatMapLatest flowOf<WeatherState>(WeatherState.NotConfigured)
            flow<WeatherState> {
                var last: WeatherState = WeatherState.Loading
                emit(last)
                while (true) {
                    val result = try {
                        WeatherState.Loaded(location.name, container.weather.forecast(location, unit))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Keep showing stale data rather than replacing it with an error.
                        if (last is WeatherState.Loaded) last else WeatherState.Error(e.message ?: "Weather unavailable")
                    }
                    last = result
                    emit(result)
                    delay(if (result is WeatherState.Loaded) WEATHER_REFRESH_MILLIS else WEATHER_RETRY_MILLIS)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WeatherState.Loading)

    fun setPhotosVisible(visible: Boolean) {
        photosVisible.value = visible
    }

    fun onPageChanged(page: Int) = save { it.copy(lastPage = page) }

    fun onClockFaceChanged(face: Int) = save { it.copy(clockFace = face) }

    private fun save(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val WEATHER_REFRESH_MILLIS = 30 * 60_000L
        private const val WEATHER_RETRY_MILLIS = 5 * 60_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { StandByViewModel(container) }
        }
    }
}
