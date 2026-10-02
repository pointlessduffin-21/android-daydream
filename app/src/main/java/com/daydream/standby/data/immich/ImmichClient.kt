package com.daydream.standby.data.immich

import com.daydream.standby.data.net.HttpStatusException
import com.daydream.standby.data.net.fetchString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** User-presentable failure talking to Immich; [statusCode] is set for HTTP errors. */
class ImmichException(message: String, cause: Throwable? = null, val statusCode: Int? = null) : IOException(message, cause)

/** Minimal Immich REST client covering what the slideshow needs. */
class ImmichClient(httpClient: OkHttpClient, private val json: Json) {

    // Redirects are surfaced as errors so the API key is never replayed to an unexpected location.
    private val http = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun serverVersion(server: ImmichServer): ImmichServerVersion =
        get(server, server.endpoint("server/version"))

    /** Verifies reachability and that the key can read assets; returns the server version. */
    suspend fun testConnection(server: ImmichServer): ImmichServerVersion {
        val version = serverVersion(server)
        randomAssets(server, count = 1)
        return version
    }

    suspend fun albums(server: ImmichServer): List<ImmichAlbum> =
        get<List<ImmichAlbum>>(server, server.endpoint("albums")).sortedBy { it.albumName.lowercase() }

    /** A random sample of images, optionally limited to favorites or to a single album. */
    suspend fun randomAssets(
        server: ImmichServer,
        count: Int,
        favoritesOnly: Boolean = false,
        albumId: String? = null,
    ): List<ImmichAsset> {
        if (albumId != null && !ImmichServer.isValidId(albumId)) throw ImmichException("Invalid album id")
        val body = RandomSearchRequest(
            size = count,
            isFavorite = favoritesOnly.takeIf { it },
            albumIds = albumId?.let(::listOf),
        )
        return post(server, server.endpoint("search/random"), body)
    }

    /**
     * A random sample of [count] images from [albumId]. Servers that support album-filtered random
     * search (see [ImmichServerVersion.supportsRandomAlbumFilter]) sample server-side; older ones
     * — or a server that rejects the filter with HTTP 400 — fall back to the full album listing.
     */
    suspend fun albumSample(server: ImmichServer, albumId: String, count: Int, randomFilterSupported: Boolean): List<ImmichAsset> {
        if (!randomFilterSupported) return albumAssets(server, albumId).shuffled().take(count)
        return try {
            randomAssets(server, count, albumId = albumId)
        } catch (e: ImmichException) {
            if (e.statusCode != 400) throw e
            albumAssets(server, albumId).shuffled().take(count)
        }
    }

    /** Every image in [albumId] as embedded in the album response (legacy servers only). */
    suspend fun albumAssets(server: ImmichServer, albumId: String): List<ImmichAsset> {
        if (!ImmichServer.isValidId(albumId)) throw ImmichException("Invalid album id")
        val url = server.baseUrl.newBuilder().addPathSegments("api/albums").addPathSegment(albumId).build()
        return get<ImmichAlbum>(server, url).assets
    }

    private suspend inline fun <reified T> get(server: ImmichServer, url: HttpUrl): T =
        execute(server, Request.Builder().url(url).get())

    private suspend inline fun <reified B, reified T> post(server: ImmichServer, url: HttpUrl, body: B): T =
        execute(server, Request.Builder().url(url).post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE)))

    private suspend inline fun <reified T> execute(server: ImmichServer, builder: Request.Builder): T {
        val request = builder
            .header(ImmichServer.API_KEY_HEADER, server.apiKey)
            .header("Accept", "application/json")
            .build()
        val body = try {
            http.fetchString(request)
        } catch (e: HttpStatusException) {
            throw ImmichException(describe(e.code), e, e.code)
        } catch (e: IOException) {
            throw ImmichException("Can't reach server: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw ImmichException("Unexpected response from server — is this an Immich URL?", e)
        }
    }

    private fun describe(code: Int): String = when (code) {
        400 -> "Server rejected the request (400) — Immich may be too old"
        401 -> "Invalid API key"
        403 -> "API key is missing a permission (needs asset.read, asset.view, album.read)"
        404 -> "Not found — check the server URL"
        in 300..399 -> "Server redirected the request — use the final URL (e.g. https://…)"
        in 500..599 -> "Immich server error ($code)"
        else -> "Request failed ($code)"
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
