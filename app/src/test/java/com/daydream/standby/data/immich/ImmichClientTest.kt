package com.daydream.standby.data.immich

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ImmichClientTest {
    private lateinit var web: MockWebServer
    private lateinit var server: ImmichServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val client = ImmichClient(OkHttpClient(), json)

    @Before fun setUp() {
        web = MockWebServer().apply { start() }
        server = ImmichServer.from(web.url("/").toString(), "secret-key")!!
    }

    @After fun tearDown() = web.shutdown()

    private fun enqueue(body: String, code: Int = 200) = web.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private suspend fun expectError(expected: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ImmichException")
        } catch (e: ImmichException) {
            assertTrue("'${e.message}' should contain '$expected'", e.message!!.contains(expected))
        }
    }

    @Test fun `random assets posts search body with api key`() = runTest {
        enqueue("""[{"id":"a1","type":"IMAGE","extra":"ignored"}]""")
        val assets = client.randomAssets(server, 25, favoritesOnly = true)

        assertEquals(listOf("a1"), assets.map { it.id })
        val request = web.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/search/random", request.path)
        assertEquals("secret-key", request.getHeader("x-api-key"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(25, body["size"]!!.jsonPrimitive.int)
        assertEquals("IMAGE", body["type"]!!.jsonPrimitive.content)
        assertTrue(body["isFavorite"]!!.jsonPrimitive.boolean)
    }

    @Test fun `random without favorites omits isFavorite`() = runTest {
        enqueue("[]")
        client.randomAssets(server, 5)
        val body = json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject
        assertFalse(body.containsKey("isFavorite"))
    }

    @Test fun `test connection checks version then asset access`() = runTest {
        enqueue("""{"major":1,"minor":135,"patch":3}""")
        enqueue("[]")
        assertEquals("v1.135.3", client.testConnection(server).toString())
        assertEquals("/api/server/version", web.takeRequest().path)
        assertEquals("/api/search/random", web.takeRequest().path)
    }

    @Test fun `albums are sorted case-insensitively`() = runTest {
        enqueue("""[{"id":"2","albumName":"zoo"},{"id":"1","albumName":"Beach","assetCount":3}]""")
        assertEquals(listOf("Beach", "zoo"), client.albums(server).map { it.albumName })
    }

    @Test fun `album sample uses random search filtered by album`() = runTest {
        enqueue("""[{"id":"x"},{"id":"y"}]""")
        assertEquals(listOf("x", "y"), client.albumSample(server, "al", 30, randomFilterSupported = true).map { it.id })
        val request = web.takeRequest()
        assertEquals("/api/search/random", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"albumIds\":[\"al\"]"))
        assertTrue(body.contains("\"size\":30"))
    }

    @Test fun `album sample falls back to album listing on old servers`() = runTest {
        enqueue("{}", 400)
        enqueue("""{"id":"al","albumName":"A","assetCount":3,"assets":[{"id":"1"},{"id":"2"},{"id":"3"}]}""")
        val sample = client.albumSample(server, "al", 2, randomFilterSupported = true)
        assertEquals(2, sample.size)
        assertTrue(sample.all { it.id in setOf("1", "2", "3") })
        web.takeRequest()
        assertEquals("/api/albums/al", web.takeRequest().path)
    }

    @Test fun `album sample uses album listing when filter unsupported`() = runTest {
        enqueue("""{"id":"al","albumName":"A","assetCount":1,"assets":[{"id":"only"}]}""")
        assertEquals(listOf("only"), client.albumSample(server, "al", 5, randomFilterSupported = false).map { it.id })
        assertEquals("/api/albums/al", web.takeRequest().path)
        assertEquals(1, web.requestCount)
    }

    @Test fun `version comparison gates album filter`() {
        assertFalse(ImmichServerVersion(1, 137, 9).supportsRandomAlbumFilter)
        assertTrue(ImmichServerVersion(1, 138, 0).supportsRandomAlbumFilter)
        assertTrue(ImmichServerVersion(2, 0, 0).supportsRandomAlbumFilter)
        assertTrue(ImmichServerVersion(1, 2, 3) < ImmichServerVersion(1, 10, 0))
    }

    @Test fun `album sample propagates non-400 errors`() = runTest {
        enqueue("", 401)
        expectError("Invalid API key") { client.albumSample(server, "al", 5, randomFilterSupported = true) }
        assertEquals(1, web.requestCount)
    }

    @Test fun `http errors carry status code`() = runTest {
        enqueue("", 403)
        try {
            client.albums(server)
            fail()
        } catch (e: ImmichException) {
            assertEquals(403, e.statusCode)
        }
    }

    @Test fun `invalid album id is rejected without a request`() = runTest {
        expectError("Invalid album id") { client.albumAssets(server, "../users") }
        expectError("Invalid album id") { client.randomAssets(server, 5, albumId = "a/b") }
        assertEquals(0, web.requestCount)
    }

    @Test fun `401 maps to invalid key`() = runTest {
        enqueue("""{"message":"Invalid API key"}""", 401)
        expectError("Invalid API key") { client.albums(server) }
    }

    @Test fun `403 maps to missing permission`() = runTest {
        enqueue("{}", 403)
        expectError("permission") { client.albums(server) }
    }

    @Test fun `404 and 5xx are described`() = runTest {
        enqueue("", 404)
        expectError("Not found") { client.albums(server) }
        enqueue("", 502)
        expectError("server error (502)") { client.albums(server) }
    }

    @Test fun `redirects are not followed`() = runTest {
        web.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example/"))
        expectError("redirected") { client.albums(server) }
        assertEquals(1, web.requestCount)
    }

    @Test fun `non-json response is reported`() = runTest {
        enqueue("<html>hello</html>")
        expectError("is this an Immich URL") { client.albums(server) }
    }

    @Test fun `wrong json shape is reported`() = runTest {
        enqueue("""{"unexpected":true}""")
        expectError("is this an Immich URL") { client.albums(server) }
    }

    @Test fun `network failure is reported`() = runTest {
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        expectError("Can't reach server") { client.albums(server) }
    }
}
