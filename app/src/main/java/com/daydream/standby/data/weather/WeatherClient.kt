package com.daydream.standby.data.weather

import com.daydream.standby.data.net.fetchString
import com.daydream.standby.data.settings.TemperatureUnit
import com.daydream.standby.data.settings.WeatherLocation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class Weather(
    val temperature: Int,
    val high: Int?,
    val low: Int?,
    val condition: WeatherCondition,
    val isDay: Boolean,
    val unit: TemperatureUnit,
)

/** Keyless weather + geocoding via Open-Meteo (https://open-meteo.com). */
class WeatherClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val forecastBase: HttpUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl(),
    private val geocodeBase: HttpUrl = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl(),
) {

    suspend fun forecast(location: WeatherLocation, unit: TemperatureUnit): Weather {
        val url = forecastBase.newBuilder()
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("current", "temperature_2m,weather_code,is_day")
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "1")
            .apply { if (unit == TemperatureUnit.FAHRENHEIT) addQueryParameter("temperature_unit", "fahrenheit") }
            .build()
        val response = json.decodeFromString<ForecastResponse>(http.fetchString(Request.Builder().url(url).build()))
        return Weather(
            temperature = Math.round(response.current.temperature),
            high = response.daily?.max?.firstOrNull()?.let(Math::round),
            low = response.daily?.min?.firstOrNull()?.let(Math::round),
            condition = WeatherCondition.fromWmoCode(response.current.weatherCode),
            isDay = response.current.isDay != 0,
            unit = unit,
        )
    }

    /** Resolves a free-text place name to coordinates; returns up to [count] matches. */
    suspend fun geocode(query: String, count: Int = 5): List<WeatherLocation> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val url = geocodeBase.newBuilder()
            .addQueryParameter("name", trimmed)
            .addQueryParameter("count", count.toString())
            .addQueryParameter("format", "json")
            .build()
        val response = json.decodeFromString<GeocodeResponse>(http.fetchString(Request.Builder().url(url).build()))
        return response.results.map { r ->
            WeatherLocation(
                name = listOfNotNull(r.name, r.admin1?.takeIf { it != r.name }, r.country).joinToString(", "),
                latitude = r.latitude,
                longitude = r.longitude,
            )
        }
    }
}

@Serializable
private data class ForecastResponse(val current: Current, val daily: Daily? = null) {
    @Serializable
    data class Current(
        @SerialName("temperature_2m") val temperature: Float,
        @SerialName("weather_code") val weatherCode: Int,
        @SerialName("is_day") val isDay: Int = 1,
    )

    @Serializable
    data class Daily(
        @SerialName("temperature_2m_max") val max: List<Float> = emptyList(),
        @SerialName("temperature_2m_min") val min: List<Float> = emptyList(),
    )
}

@Serializable
private data class GeocodeResponse(val results: List<Result> = emptyList()) {
    @Serializable
    data class Result(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val country: String? = null,
        val admin1: String? = null,
    )
}
