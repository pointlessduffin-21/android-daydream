package com.daydream.standby.data.photos

import com.daydream.standby.data.immich.ImmichClient
import com.daydream.standby.data.immich.ImmichServer
import com.daydream.standby.data.settings.ImmichMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class ImmichPhotoSourceTest {
    private lateinit var web: MockWebServer
    private lateinit var server: ImmichServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val client = ImmichClient(OkHttpClient(), json)

    @Before fun setUp() {
        web = MockWebServer().apply { start() }
        server = ImmichServer.from(web.url("/").toString(), "k")!!
    }

    @After fun tearDown() = web.shutdown()

    private fun respond(routes: Map<String, Pair<Int, String>>) {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val (code, body) = routes[request.path] ?: (404 to "")
                return MockResponse().setResponseCode(code).setBody(body)
            }
        }
    }

    private companion object {
        const val NEW_VERSION = """{"major":2,"minor":1,"patch":0}"""
    }

    private fun source(mode: ImmichMode, albums: Set<String> = emptySet()) = ImmichPhotoSource(client, server, mode, albums)

    @Test fun `maps assets and filters non-images, trash and duplicates`() = runTest {
        respond(
            mapOf(
                "/api/search/random" to (200 to """[
                    {"id":"1","type":"IMAGE","exifInfo":{"city":"Manila","country":"Philippines","dateTimeOriginal":"2023-04-05T10:00:00.000Z"}},
                    {"id":"1","type":"IMAGE"},
                    {"id":"2","type":"VIDEO"},
                    {"id":"3","type":"IMAGE","isTrashed":true},
                    {"id":"4","type":"IMAGE","localDateTime":"2020-01-02T03:04:05.000Z"},
                    {"id":"../evil","type":"IMAGE"}
                ]"""),
            ),
        )
        val photos = source(ImmichMode.RANDOM).loadBatch()

        assertEquals(listOf("immich:1", "immich:4"), photos.map { it.id })
        val first = photos[0]
        assertEquals("Manila, Philippines", first.location)
        assertEquals(LocalDate.of(2023, 4, 5), first.takenOn)
        assertEquals(server.previewUrl("1").toString(), first.data)
        assertEquals(mapOf("x-api-key" to "k"), first.headers)
        assertNull(photos[1].location)
        assertEquals(LocalDate.of(2020, 1, 2), photos[1].takenOn)
    }

    @Test fun `favorites mode requests favorites`() = runTest {
        respond(mapOf("/api/search/random" to (200 to "[]")))
        source(ImmichMode.FAVORITES).loadBatch()
        assertTrue(web.takeRequest().body.readUtf8().contains("\"isFavorite\":true"))
    }

    @Test fun `albums mode requires a selection`() = runTest {
        try {
            source(ImmichMode.ALBUMS).loadBatch()
            fail("expected PhotoSourceException")
        } catch (e: PhotoSourceException) {
            assertTrue(e.message!!.contains("No Immich albums"))
        }
    }

    @Test fun `albums mode merges albums and tolerates a missing one`() = runTest {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/server/version") return MockResponse().setBody(NEW_VERSION)
                val body = request.body.readUtf8()
                return when {
                    "\"a\"" in body -> MockResponse().setBody("""[{"id":"p1"}]""")
                    "\"b\"" in body -> MockResponse().setBody("""[{"id":"p2"}]""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val ids = source(ImmichMode.ALBUMS, setOf("a", "gone", "b")).loadBatch().map { it.id }.toSet()
        assertEquals(setOf("immich:p1", "immich:p2"), ids)
    }

    @Test fun `albums mode splits the batch across albums`() = runTest {
        respond(mapOf("/api/server/version" to (200 to NEW_VERSION), "/api/search/random" to (200 to "[]")))
        ImmichPhotoSource(client, server, ImmichMode.ALBUMS, setOf("a", "b"), batchSize = 40).loadBatch()
        assertEquals("/api/server/version", web.takeRequest().path)
        assertTrue(web.takeRequest().body.readUtf8().contains("\"size\":20"))
    }

    @Test fun `albums mode on old servers uses album listing and caches the version`() = runTest {
        respond(
            mapOf(
                "/api/server/version" to (200 to """{"major":1,"minor":120,"patch":0}"""),
                "/api/albums/a" to (200 to """{"id":"a","albumName":"A","assetCount":1,"assets":[{"id":"old"}]}"""),
            ),
        )
        val source = source(ImmichMode.ALBUMS, setOf("a"))
        assertEquals(listOf("immich:old"), source.loadBatch().map { it.id })
        source.loadBatch()
        val paths = List(web.requestCount) { web.takeRequest().path }
        assertEquals(listOf("/api/server/version", "/api/albums/a", "/api/albums/a"), paths)
    }

    @Test fun `albums mode rethrows when every album fails`() = runTest {
        respond(mapOf("/api/server/version" to (200 to NEW_VERSION), "/api/search/random" to (404 to "")))
        try {
            source(ImmichMode.ALBUMS, setOf("gone")).loadBatch()
            fail("expected failure")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Not found"))
        }
    }

    @Test fun `parseDate handles common formats`() {
        assertEquals(LocalDate.of(2021, 3, 3), ImmichPhotoSource.parseDate("2021-03-03T10:00:00.000Z"))
        assertEquals(LocalDate.of(2021, 3, 3), ImmichPhotoSource.parseDate("2021-03-03T10:00:00+08:00"))
        assertEquals(LocalDate.of(2021, 3, 3), ImmichPhotoSource.parseDate("2021-03-03T10:00:00"))
        assertEquals(LocalDate.of(2021, 3, 3), ImmichPhotoSource.parseDate("2021-03-03"))
        assertNull(ImmichPhotoSource.parseDate(null))
        assertNull(ImmichPhotoSource.parseDate(""))
        assertNull(ImmichPhotoSource.parseDate("garbage"))
    }
}
