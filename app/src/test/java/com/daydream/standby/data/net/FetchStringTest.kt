package com.daydream.standby.data.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class FetchStringTest {
    private lateinit var web: MockWebServer
    private val client = OkHttpClient()

    @Before fun setUp() {
        web = MockWebServer().apply { start() }
    }

    @After fun tearDown() = web.shutdown()

    private fun request() = Request.Builder().url(web.url("/")).build()

    @Test fun `returns body on success`() = runTest {
        web.enqueue(MockResponse().setBody("hello"))
        assertEquals("hello", client.fetchString(request()))
    }

    @Test fun `body exactly at limit is accepted`() = runTest {
        web.enqueue(MockResponse().setBody("x".repeat(10)))
        assertEquals(10, client.fetchString(request(), maxBytes = 10).length)
    }

    @Test fun `body over limit is rejected`() = runTest {
        web.enqueue(MockResponse().setBody("x".repeat(11)))
        try {
            client.fetchString(request(), maxBytes = 10)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Response too large", e.message)
        }
    }

    @Test fun `chunked body over limit is rejected`() = runTest {
        web.enqueue(MockResponse().setChunkedBody("y".repeat(50), 8))
        try {
            client.fetchString(request(), maxBytes = 20)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Response too large", e.message)
        }
    }

    @Test fun `error status carries code and truncated body`() = runTest {
        web.enqueue(MockResponse().setResponseCode(418).setBody("z".repeat(500)))
        try {
            client.fetchString(request())
            fail("expected HttpStatusException")
        } catch (e: HttpStatusException) {
            assertEquals(418, e.code)
            assertTrue(e.message!!.length < 250)
        }
    }
}
