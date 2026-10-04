package com.maverock24.pimobile.net

import android.util.Base64
import com.maverock24.pimobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * QR pairing: the phone scans a link carrying a single-use code and trades it for a
 * device token. The token is deliberately kept out of the QR, so a screenshot of the
 * code is worthless once it has been spent, and no secret ever reaches the camera.
 *
 * The payload is `pi-remote://pair?v=1&u=<base64url of the bridge URL>&c=<code>`.
 */
object Pairing {

    private const val SCHEME = "pi-remote"
    private const val HOST = "pair"
    private const val VERSION = "1"

    data class Invite(val baseUrl: String, val code: String)
    data class Paired(val baseUrl: String, val token: String, val device: String)

    private val json = "application/json; charset=utf-8".toMediaType()

    /** Pairing talks to an address the user just scanned, so it gets its own client. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Returns null for anything that is not a well-formed invite of ours. */
    fun parse(link: String): Invite? {
        val trimmed = link.trim()
        if (!trimmed.startsWith("$SCHEME://", ignoreCase = true)) return null
        val afterScheme = trimmed.substringAfter("://")
        if (!afterScheme.substringBefore('?').equals(HOST, ignoreCase = true)) return null
        val params = parseQuery(afterScheme.substringAfter('?', ""))
        if (params["v"] != VERSION) return null
        val code = params["c"].orEmpty()
        val baseUrl = decodeBaseUrl(params["u"].orEmpty())
        if (code.isBlank() || baseUrl == null) return null
        return Invite(baseUrl, code)
    }

    /**
     * Spends [invite] and returns the device token the bridge minted for it.
     * Throws [IOException] with a message worth showing when the bridge refuses.
     */
    suspend fun exchange(invite: Invite, deviceLabel: String): Paired = withContext(Dispatchers.IO) {
        val url = invite.baseUrl.trim().trimEnd('/') + "/api/pair"
        val payload = JSONObject().put("code", invite.code).put("label", deviceLabel)
        val request = runCatching { Request.Builder().url(url) }.getOrElse {
            throw IOException("the pairing link does not name a usable address")
        }
            .header("X-Pi-Client", "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}")
            .post(payload.toString().toRequestBody(json))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(describe(response.code, text))
            val body = runCatching { JSONObject(text) }.getOrElse {
                throw IOException("the bridge sent an unreadable reply")
            }
            val token = body.optString("token")
            if (token.isBlank()) throw IOException("the bridge did not return a token")
            Paired(invite.baseUrl.trim().trimEnd('/'), token, body.optString("device", deviceLabel))
        }
    }

    private fun describe(code: Int, body: String): String {
        val reason = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
        return when {
            reason.isNotBlank() -> reason
            code == 429 -> "too many attempts; wait a minute and run /pair again"
            else -> "pairing failed (HTTP $code)"
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotBlank() }.associate { part ->
            val raw = part.substringAfter('=', "")
            part.substringBefore('=') to runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }

    /**
     * The invite carries the bridge URL base64url-encoded, and it is only accepted if
     * it is http(s) with a host: a QR must not be able to point the app at a file path
     * or a content provider.
     */
    private fun decodeBaseUrl(encoded: String): String? {
        if (encoded.isBlank()) return null
        val base64 = encoded.replace('-', '+').replace('_', '/')
        val padded = base64 + "=".repeat((4 - base64.length % 4) % 4)
        val decoded = runCatching { String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrNull()
            ?.trim()
            ?: return null
        if (!decoded.startsWith("http://", true) && !decoded.startsWith("https://", true)) return null
        val parsed = runCatching { decoded.toHttpUrlOrNull() }.getOrNull() ?: return null
        return if (parsed.host.isNotBlank()) decoded.trimEnd('/') else null
    }
}
