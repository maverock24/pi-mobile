package com.maverock24.pimobile.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The upstream status and body the relay received from the bridge. */
internal data class UpstreamResult(val status: Int, val body: String)

/**
 * The one bridge call stage 2 relays. Keeping it an interface lets the relay
 * tests hand in a fake with no sockets, and the production implementation the
 * only place the bridge token is put on the wire.
 */
internal interface WebUpstream {
    suspend fun state(clientLabel: String): UpstreamResult
}

/**
 * The relay's own client for the bridge, fixed to the one read-only route in
 * stage 2.
 *
 * It is deliberately not the app's shared client. The shared client follows
 * redirects and retries, so a redirect could carry the bridge token to another
 * host and a retried request could repeat a side effect (security F10). Here
 * redirects and retries are off and the read has a finite timeout, and the
 * browser's own headers are never copied: only the phone's token and a label
 * derived from the session go upstream. The token is read fresh on every call so
 * a settings change takes effect without a restart, and it never reaches a
 * response.
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

    override suspend fun state(clientLabel: String): UpstreamResult = withContext(Dispatchers.IO) {
        val base = baseUrl().trim()
        if (base.isEmpty()) throw IOException("the bridge address is not set")
        val request = Request.Builder()
            .url(base.trimEnd('/') + "/api/state")
            .header("Authorization", "Bearer ${token()}")
            .header("X-Pi-Client", clientLabel)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            UpstreamResult(response.code, response.body?.string().orEmpty())
        }
    }
}
