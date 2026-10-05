package com.daydream.standby.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.daydream.standby.AppContainer
import com.daydream.standby.data.immich.ImmichAlbum
import com.daydream.standby.data.immich.ImmichServer
import com.daydream.standby.data.photos.LocalFolder
import com.daydream.standby.data.photos.PhotoSourceException
import com.daydream.standby.data.settings.AppSettings
import com.daydream.standby.data.settings.PhotoSourceType
import com.daydream.standby.data.settings.WeatherLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Testing : ConnectionState

    /** The key would be sent unencrypted over the internet; the user must confirm first. */
    data class ConfirmInsecure(val host: String) : ConnectionState
    data class Connected(val version: String, val insecure: Boolean) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

sealed interface AlbumsState {
    data object Idle : AlbumsState
    data object Loading : AlbumsState
    data class Loaded(val albums: List<ImmichAlbum>) : AlbumsState
    data class Failed(val message: String) : AlbumsState
}

sealed interface FoldersState {
    data object Idle : FoldersState
    data object Loading : FoldersState
    data object NoPermission : FoldersState
    data class Loaded(val folders: List<LocalFolder>) : FoldersState
    data class Failed(val message: String) : FoldersState
}

data class LocationSearch(
    val searching: Boolean = false,
    val results: List<WeatherLocation> = emptyList(),
    val error: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection = _connection.asStateFlow()

    private val _albums = MutableStateFlow<AlbumsState>(AlbumsState.Idle)
    val albums = _albums.asStateFlow()

    private val _folders = MutableStateFlow<FoldersState>(FoldersState.Idle)
    val folders = _folders.asStateFlow()

    private val _locationSearch = MutableStateFlow(LocationSearch())
    val locationSearch = _locationSearch.asStateFlow()

    private var connectJob: Job? = null
    private var searchJob: Job? = null
    private var foldersJob: Job? = null

    init {
        // Re-validate a previously saved server so the album list is ready when the screen opens.
        viewModelScope.launch {
            val saved = container.settings.settings.first()
            if (saved.immichServerUrl.isNotBlank() && saved.immichApiKey.isNotBlank()) {
                // Already saved, so the user accepted this URL (including any HTTP warning) before.
                connectImmich(
                    saved.immichServerUrl,
                    saved.immichApiKey,
                    selectAsSource = false,
                    allowInsecureHost = ImmichServer.normalizeBaseUrl(saved.immichServerUrl)?.host,
                )
            }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    /**
     * Validates and saves the server. [allowInsecureHost] skips the plain-HTTP warning only for
     * that exact host, so editing the URL after confirming re-triggers the warning.
     */
    fun connectImmich(url: String, apiKey: String, selectAsSource: Boolean = true, allowInsecureHost: String? = null) {
        val server = ImmichServer.from(url, apiKey)
        if (server == null) {
            _connection.value = ConnectionState.Failed(
                when {
                    apiKey.isBlank() -> "Enter an API key (Immich › Account Settings › API Keys)"
                    !ImmichServer.isValidApiKey(apiKey.trim()) -> "The API key contains invalid characters — paste it again"
                    else -> "Enter a valid server URL"
                },
            )
            return
        }
        if (server.isInsecureRemote && server.baseUrl.host != allowInsecureHost) {
            _connection.value = ConnectionState.ConfirmInsecure(server.baseUrl.host)
            return
        }
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _connection.value = ConnectionState.Testing
            try {
                val version = container.immich.testConnection(server)
                container.settings.update {
                    it.copy(
                        immichServerUrl = server.baseUrl.toString(),
                        immichApiKey = server.apiKey,
                        photoSource = if (selectAsSource) PhotoSourceType.IMMICH else it.photoSource,
                    )
                }
                _connection.value = ConnectionState.Connected(version.toString(), server.isInsecureRemote)
                loadAlbums(server)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _connection.value = ConnectionState.Failed(e.message ?: "Connection failed")
            }
        }
    }

    private suspend fun loadAlbums(server: ImmichServer) {
        _albums.value = AlbumsState.Loading
        _albums.value = try {
            AlbumsState.Loaded(container.immich.albums(server))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AlbumsState.Failed(e.message ?: "Couldn't load albums")
        }
    }

    fun toggleAlbum(albumId: String) = update { s ->
        val ids = if (albumId in s.immichAlbumIds) s.immichAlbumIds - albumId else s.immichAlbumIds + albumId
        s.copy(immichAlbumIds = ids)
    }

    /** Loads the folder list unless it is already loaded or loading. */
    fun ensureFoldersLoaded() {
        if (_folders.value is FoldersState.Loaded || _folders.value == FoldersState.Loading) return
        loadFolders()
    }

    /** Lists device folders for the "This device" picker; [FoldersState.NoPermission] if access isn't granted. */
    fun loadFolders() {
        foldersJob?.cancel()
        foldersJob = viewModelScope.launch {
            _folders.value = FoldersState.Loading
            _folders.value = try {
                FoldersState.Loaded(container.localFolders())
            } catch (e: CancellationException) {
                throw e
            } catch (_: PhotoSourceException) {
                FoldersState.NoPermission
            } catch (e: Exception) {
                FoldersState.Failed(e.message ?: "Couldn't load folders")
            }
        }
    }

    fun toggleFolder(bucketId: String) = update { s ->
        val ids = if (bucketId in s.localBucketIds) s.localBucketIds - bucketId else s.localBucketIds + bucketId
        s.copy(localBucketIds = ids)
    }

    /** Back to "all folders" (screenshots excluded). */
    fun clearFolders() = update { it.copy(localBucketIds = emptySet()) }

    /** Drops [ids] (folders that no longer exist) from the selection. */
    fun removeFolders(ids: Set<String>) = update { it.copy(localBucketIds = it.localBucketIds - ids) }

    fun disconnectImmich() {
        connectJob?.cancel()
        viewModelScope.launch { container.clearImageCaches() }
        _connection.value = ConnectionState.Idle
        _albums.value = AlbumsState.Idle
        update {
            it.copy(
                immichServerUrl = "",
                immichApiKey = "",
                immichAlbumIds = emptySet(),
                photoSource = if (it.photoSource == PhotoSourceType.IMMICH) PhotoSourceType.NONE else it.photoSource,
            )
        }
    }

    fun searchLocation(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _locationSearch.value = LocationSearch(searching = true)
            _locationSearch.value = try {
                val results = container.weather.geocode(query)
                LocationSearch(results = results, error = if (results.isEmpty()) "No places found" else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LocationSearch(error = e.message ?: "Search failed")
            }
        }
    }

    fun chooseLocation(location: WeatherLocation?) {
        _locationSearch.update { LocationSearch() }
        update { it.copy(weatherLocation = location) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
