package com.daydream.standby

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import com.daydream.standby.data.immich.ImmichClient
import com.daydream.standby.data.immich.ImmichServer
import com.daydream.standby.data.net.StripCredentialOnRedirectInterceptor
import com.daydream.standby.data.photos.ImmichPhotoSource
import com.daydream.standby.data.photos.LocalFolder
import com.daydream.standby.data.photos.LocalPhotoSource
import com.daydream.standby.data.photos.Photo
import com.daydream.standby.data.photos.PhotoSize
import com.daydream.standby.data.photos.PhotoSource
import com.daydream.standby.data.settings.PhotoConfig
import com.daydream.standby.data.settings.PhotoSourceType
import com.daydream.standby.data.settings.SettingsRepository
import com.daydream.standby.data.weather.WeatherClient
import com.daydream.standby.ui.common.toSlideRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** Manual dependency container, created once by [DaydreamApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(StripCredentialOnRedirectInterceptor(ImmichServer.API_KEY_HEADER))
        .build()

    val settings = SettingsRepository(appContext.settingsStore)
    val immich = ImmichClient(httpClient, json)
    val weather = WeatherClient(httpClient, json)

    private val imageLoader: ImageLoader get() = SingletonImageLoader.get(appContext)

    /** Returns the photo source for [config], or null if it isn't usable yet. */
    fun photoSource(config: PhotoConfig): PhotoSource? = when (config.source) {
        PhotoSourceType.NONE -> null
        PhotoSourceType.LOCAL -> LocalPhotoSource(appContext, config.localBucketIds)
        PhotoSourceType.IMMICH -> ImmichServer.from(config.immichServerUrl, config.immichApiKey)?.let { server ->
            ImmichPhotoSource(immich, server, config.immichMode, config.immichAlbumIds)
        }
    }

    /** Device photo folders for the Settings picker; throws if photo access isn't granted. */
    suspend fun localFolders(): List<LocalFolder> = LocalPhotoSource.listFolders(appContext)

    /** Drops cached photos, e.g. after disconnecting a server so its images don't linger. */
    suspend fun clearImageCaches() {
        imageLoader.memoryCache?.clear()
        withContext(Dispatchers.IO) { imageLoader.diskCache?.clear() }
    }

    /** Decodes [photo] into Coil's memory cache; returns its displayed size, or null if it can't be loaded. */
    suspend fun preparePhoto(photo: Photo): PhotoSize? {
        val result = imageLoader.execute(photo.toSlideRequest(appContext)) as? SuccessResult ?: return null
        val image = result.image
        return if (image.width > 0 && image.height > 0) PhotoSize(image.width, image.height) else null
    }
}
