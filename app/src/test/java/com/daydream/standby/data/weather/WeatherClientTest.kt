package com.daydream.standby.data.weather

import com.daydream.standby.data.net.HttpStatusException
import com.daydream.standby.data.settings.TemperatureUnit
import com.daydream.standby.data.settings.WeatherLocation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WeatherClientTest {
    private lateinit var web: MockWebServer
    private lateinit var client: WeatherClient
    private val manila = WeatherLocation("Manila", 14.6, 120.98)

    @Before fun setUp() {
        web = MockWebServer().apply { start() }
        client = WeatherClient(
            OkHttpClient(),
            Json { ignoreUnknownKeys = true },
            forecastBase = web.url("/v1/forecast"),
            geocodeBase = web.url("/v1/search"),
        )
    }

    @After fun tearDown() = web.shutdown()

    @Test fun `parses forecast and rounds temperatures`() = runTest {
        web.enqueue(MockResponse().setBody("""{"current":{"temperature_2m":28.6,"weather_code":2,"is_day":0},"daily":{"temperature_2m_max":[31.4],"temperature_2m_min":[24.5]}}"""))
        val w = client.forecast(manila, TemperatureUnit.CELSIUS)

        assertEquals(29, w.temperature)
        assertEquals(31, w.high)
        assertEquals(25, w.low)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, w.condition)
        assertEquals(false, w.isDay)
        val url = web.takeRequest().requestUrl!!
        assertEquals("14.6", url.queryParameter("latitude"))
        assertNull(url.queryParameter("temperature_unit"))
    }

    @Test fun `fahrenheit adds unit and missing daily is tolerated`() = runTest {
        web.enqueue(MockResponse().setBody("""{"current":{"temperature_2m":80,"weather_code":0}}"""))
        val w = client.forecast(manila, TemperatureUnit.FAHRENHEIT)

        assertEquals(80, w.temperature)
        assertNull(w.high)
        assertEquals(true, w.isDay)
        assertEquals("fahrenheit", web.takeRequest().requestUrl!!.queryParameter("temperature_unit"))
    }

    @Test(expected = HttpStatusException::class)
    fun `http errors propagate`() = runTest {
        web.enqueue(MockResponse().setResponseCode(500))
        client.forecast(manila, TemperatureUnit.CELSIUS)
    }

    @Test fun `geocode formats names and skips duplicate region`() = runTest {
        web.enqueue(MockResponse().setBody("""{"results":[
            {"name":"Manila","latitude":14.6,"longitude":120.9,"country":"Philippines","admin1":"Metro Manila"},
            {"name":"Singapore","latitude":1.3,"longitude":103.8,"country":"Singapore","admin1":"Singapore"}
        ]}"""))
        val results = client.geocode("  man ")

        assertEquals(listOf("Manila, Metro Manila, Philippines", "Singapore, Singapore"), results.map { it.name })
        assertEquals("man", web.takeRequest().requestUrl!!.queryParameter("name"))
    }

    @Test fun `geocode with no results field returns empty`() = runTest {
        web.enqueue(MockResponse().setBody("""{"generationtime_ms":0.5}"""))
        assertTrue(client.geocode("zzzz").isEmpty())
    }

    @Test fun `too-short query makes no request`() = runTest {
        assertTrue(client.geocode(" a ").isEmpty())
        assertEquals(0, web.requestCount)
    }
}
