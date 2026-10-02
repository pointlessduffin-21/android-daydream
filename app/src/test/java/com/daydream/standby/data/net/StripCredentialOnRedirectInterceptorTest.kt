package com.daydream.standby.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class StripCredentialOnRedirectInterceptorTest {
    private lateinit var origin: MockWebServer
    private lateinit var other: MockWebServer
    private val client = OkHttpClient.Builder()
        .addNetworkInterceptor(StripCredentialOnRedirectInterceptor("x-api-key"))
        .build()

    @Before fun setUp() {
        origin = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        origin.shutdown()
        other.shutdown()
    }

    private fun get(path: String) = client.newCall(
        Request.Builder().url(origin.url(path)).header("x-api-key", "secret").build(),
    ).execute().close()

    @Test fun `header removed on cross-origin redirect`() {
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/stolen")))
        other.enqueue(MockResponse().setBody("ok"))
        get("/start")

        assertEquals("secret", origin.takeRequest().getHeader("x-api-key"))
        assertNull(other.takeRequest().getHeader("x-api-key"))
    }

    @Test fun `header kept on same-origin redirect`() {
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
        origin.enqueue(MockResponse().setBody("ok"))
        get("/start")

        origin.takeRequest()
        assertEquals("secret", origin.takeRequest().getHeader("x-api-key"))
    }

    @Test fun `requests without header pass through`() {
        origin.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(origin.url("/")).build()).execute().close()
        assertNull(origin.takeRequest().getHeader("x-api-key"))
    }
}
