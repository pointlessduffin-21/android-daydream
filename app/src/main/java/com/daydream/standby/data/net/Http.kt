package com.daydream.standby.data.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Thrown when a server answers with a non-2xx status. */
class HttpStatusException(val code: Int, message: String) : IOException("HTTP $code: $message")

/** Generous enough for large Immich responses, small enough to avoid OOM on a bogus URL. */
const val MAX_BODY_BYTES = 32L * 1024 * 1024
private const val MAX_ERROR_BODY = 200

/**
 * Executes [request] asynchronously and returns the response body as text. Cancelling the
 * coroutine cancels the underlying call, so callers like `flatMapLatest` switch immediately.
 *
 * @throws HttpStatusException for non-successful responses.
 * @throws IOException for transport failures or bodies larger than [maxBytes].
 */
suspend fun OkHttpClient.fetchString(request: Request, maxBytes: Long = MAX_BODY_BYTES): String =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))

            override fun onResponse(call: Call, response: Response) {
                cont.resumeWith(runCatching { response.use { readBody(it, maxBytes) } })
            }
        })
    }

private fun readBody(response: Response, maxBytes: Long): String {
    val source = response.body.source()
    // request(n) returns true once at least n bytes are buffered, i.e. the body is too large.
    if (response.body.contentLength() > maxBytes || source.request(maxBytes + 1)) {
        throw IOException("Response too large")
    }
    val body = source.readUtf8()
    if (!response.isSuccessful) {
        throw HttpStatusException(response.code, body.take(MAX_ERROR_BODY).ifBlank { response.message })
    }
    return body
}

/**
 * Network interceptor that removes [headerName] from any hop whose origin differs from the
 * original request's origin. OkHttp only strips `Authorization` on cross-host redirects, so
 * custom credential headers (like Immich's `x-api-key`) need this to avoid leaking to third parties.
 */
class StripCredentialOnRedirectInterceptor(private val headerName: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.call().request().url
        val current = chain.request()
        val sameOrigin = original.scheme == current.url.scheme &&
            original.host == current.url.host &&
            original.port == current.url.port
        if (sameOrigin || current.header(headerName) == null) return chain.proceed(current)
        return chain.proceed(current.newBuilder().removeHeader(headerName).build())
    }
}
