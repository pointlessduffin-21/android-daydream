package com.daydream.standby.data.immich

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A validated Immich server address plus API key. */
data class ImmichServer(val baseUrl: HttpUrl, val apiKey: String) {

    /** True when the key would travel unencrypted to a host that isn't obviously on the local network. */
    val isInsecureRemote: Boolean
        get() = !baseUrl.isHttps && !isLocalHost(baseUrl.host)

    fun endpoint(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments("api/$path").build()

    fun previewUrl(assetId: String): HttpUrl = baseUrl.newBuilder()
        .addPathSegments("api/assets")
        .addPathSegment(assetId)
        .addPathSegment("thumbnail")
        .addQueryParameter("size", "preview")
        .build()

    companion object {
        const val API_KEY_HEADER = "x-api-key"

        /**
         * Accepts what users typically paste — `photos.example.com`, `http://10.0.0.5:2283/`,
         * `https://host/api` — and returns a canonical base URL without the `/api` suffix,
         * or null when the input can't be parsed.
         */
        fun normalizeBaseUrl(input: String): HttpUrl? {
            var text = input.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            if (!text.contains("://")) {
                // Bare LAN addresses (192.168.1.10:2283) are almost always plain HTTP.
                val host = "https://$text".toHttpUrlOrNull()?.host ?: return null
                text = (if (isLocalHost(host)) "http://" else "https://") + text
            }
            val parsed = text.toHttpUrlOrNull() ?: return null
            val segments = parsed.pathSegments.filter { it.isNotEmpty() }.toMutableList()
            if (segments.lastOrNull().equals("api", ignoreCase = true)) segments.removeAt(segments.lastIndex)
            return parsed.newBuilder()
                .encodedPath("/")
                .apply {
                    segments.forEach { addPathSegment(it) }
                    if (segments.isNotEmpty()) addPathSegment("") // canonical trailing slash
                }
                .query(null)
                .fragment(null)
                .build()
        }

        fun from(serverUrl: String, apiKey: String): ImmichServer? {
            val key = apiKey.trim()
            if (!isValidApiKey(key)) return null
            return normalizeBaseUrl(serverUrl)?.let { ImmichServer(it, key) }
        }

        private val ID_PATTERN = Regex("^[A-Za-z0-9-]{1,64}$")

        /** Keys are printable ASCII; anything else (pasted newlines, smart quotes) can't be sent as a header. */
        fun isValidApiKey(key: String): Boolean = key.isNotEmpty() && key.all { it.code in 0x21..0x7E }

        /** Immich ids are UUIDs; reject anything that could alter a URL path (e.g. `..`). */
        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        internal fun isLocalHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
            if (':' in h) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")
            if ('.' !in h) return true // single-label names (nas, immich) only resolve via LAN DNS
            val parts = h.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }
            val (a, b) = octets
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
        }
    }
}
