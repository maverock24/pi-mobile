package com.maverock24.pimobile.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The upstream status and body the relay received from the bridge. */
internal data class UpstreamResult(val status: Int, val body: String)

/**
 * A live bridge stream the relay reads one line at a time. [readLine] blocks, so
 * a caller reads it off the request thread; [close] cancels the call so a read
 * that is waiting for the next bridge event is unblocked when the browser goes.
 */
internal interface WebUpstreamStream : AutoCloseable {
    val status: Int
    fun readLine(): String?
}

/**
 * The bridge calls the relay is allowed to make. It is an interface so the relay
 * tests can hand in a fake with no sockets, and the production implementation is
 * the only place the bridge token is put on the wire.
 *
 * The set is deliberately small and named: these are the routes the web core
 * loop needs (design section 4.4), not a pass-through. Adding a route means
 * adding a method here and a relay route, so a widening is a reviewable change.
 */
internal interface WebUpstream {
    suspend fun state(clientLabel: String): UpstreamResult
    suspend fun history(clientLabel: String, limit: Int): UpstreamResult
    suspend fun files(clientLabel: String): UpstreamResult
    suspend fun question(clientLabel: String): UpstreamResult
    suspend fun prompt(clientLabel: String, body: String): UpstreamResult
    suspend fun answer(clientLabel: String, body: String): UpstreamResult
    /** Opens the bridge event stream. Throws on connect failure. */
    suspend fun events(clientLabel: String): WebUpstreamStream
}

/**
 * The relay's own client for the bridge, fixed to the allowlisted routes.
 *
 * It is deliberately not the app's shared client. The shared client follows
 * redirects and retries, so a redirect could carry the bridge token to another
 * host and a retried request could repeat a side effect (security F10). Here
 * redirects and retries are off and the request reads have a finite timeout;
 * only the event stream keeps a long one, because it never ends. The browser's
 * own headers are never copied: only the phone's token and a label derived from
 * the session go upstream, and the token never reaches a response.
 */
internal class BridgeUpstream(
    private val baseUrl: () -> String,
    private val token: () -> String,
) : WebUpstream {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    // The event stream stays open while the run does, so it needs no read
    // timeout while every request route keeps a finite one.
    private val streamClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override suspend fun state(clientLabel: String): UpstreamResult = get(clientLabel, "/api/state")

    override suspend fun history(clientLabel: String, limit: Int): UpstreamResult =
        get(clientLabel, "/api/history?limit=$limit")

    override suspend fun files(clientLabel: String): UpstreamResult = get(clientLabel, "/api/files")

    override suspend fun question(clientLabel: String): UpstreamResult = get(clientLabel, "/api/question")

    override suspend fun prompt(clientLabel: String, body: String): UpstreamResult =
        post(clientLabel, "/api/prompt", body)

    override suspend fun answer(clientLabel: String, body: String): UpstreamResult =
        post(clientLabel, "/api/answer", body)

    override suspend fun events(clientLabel: String): WebUpstreamStream {
        val request = requestBuilder(clientLabel, "/api/events")
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val call = streamClient.newCall(request)
        val response = withContext(Dispatchers.IO) { call.execute() }
        return object : WebUpstreamStream {
            override val status: Int = response.code

            override fun readLine(): String? = response.body?.source()?.readUtf8Line()

            override fun close() {
                response.close()
                call.cancel()
            }
        }
    }

    private suspend fun get(clientLabel: String, path: String): UpstreamResult = withContext(Dispatchers.IO) {
        val request = requestBuilder(clientLabel, path)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            UpstreamResult(response.code, response.body?.string().orEmpty())
        }
    }

    private suspend fun post(clientLabel: String, path: String, body: String): UpstreamResult =
        withContext(Dispatchers.IO) {
            val request = requestBuilder(clientLabel, path)
                .header("Accept", "application/json")
                .post(body.toRequestBody(jsonMedia))
                .build()
            client.newCall(request).execute().use { response ->
                UpstreamResult(response.code, response.body?.string().orEmpty())
            }
        }

    /**
     * The fixed header set. The browser's own `Cookie` and `Authorization` are
     * never here, so a browser cannot smuggle a header upstream (security F7).
     */
    private fun requestBuilder(clientLabel: String, path: String): Request.Builder {
        val base = baseUrl().trim()
        if (base.isEmpty()) throw IOException("the bridge address is not set")
        return Request.Builder()
            .url(base.trimEnd('/') + path)
            .header("Authorization", "Bearer ${token()}")
            .header("X-Pi-Client", clientLabel)
    }
}
