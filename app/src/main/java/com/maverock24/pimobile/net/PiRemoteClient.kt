package com.maverock24.pimobile.net

import com.maverock24.pimobile.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A response the bridge refused. [code] is the HTTP status, so a caller can
 * tell the 409 of a session that changed hands from any other failure and keep
 * the user's work instead of discarding it.
 */
class BridgeException(val code: Int, message: String) : IOException(message)

/**
 * Thin client for the pi-remote bridge. Every call reads the current endpoint and
 * token from [config], so changing settings takes effect on the next request.
 */
class PiRemoteClient(private val config: () -> Pair<String, String>) {

    private val json = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // the event stream never ends
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun builder(path: String): Request.Builder {
        val (base, token) = config()
        val url = base.trim().trimEnd('/') + path
        val request = runCatching { Request.Builder().url(url) }.getOrElse {
            throw IOException("invalid bridge URL '$base' — it needs a scheme, host and port")
        }
        return request
            .header("Authorization", "Bearer $token")
            .header("X-Pi-Client", "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}")
    }

    /**
     * Blocking OkHttp call. Always runs on [Dispatchers.IO]: a synchronous
     * request from the UI thread trips Android's NetworkOnMainThreadException,
     * which carries no message and is therefore hard to diagnose.
     */
    private suspend fun execute(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw BridgeException(response.code, describeError(response.code, text))
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun describeError(code: Int, body: String): String = when (code) {
        401 -> "401 unauthorized — check the token in Settings"
        in 500..599 -> "server error $code: ${body.take(200)}"
        else -> "HTTP $code: ${body.take(200)}"
    }

    suspend fun health(): JSONObject = execute(builder("/api/health").get().build())

    suspend fun state(): JSONObject = execute(builder("/api/state").get().build())

    suspend fun history(limit: Int = 60): JSONObject = execute(builder("/api/history?limit=$limit").get().build())

    /**
     * Full-session search. The bridge matches the query against the text of
     * every prompt and answer and returns the matches newest first. An empty
     * query is refused by the caller rather than sent, but the bridge answers
     * one with no results too.
     */
    suspend fun search(query: String, limit: Int = 40): JSONObject {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return execute(builder("/api/search?q=$encoded&limit=$limit").get().build())
    }

    suspend fun prompt(text: String, deliverAs: String = "steer", sessionId: String? = null): JSONObject {
        val payload = JSONObject().apply {
            put("text", text)
            put("deliverAs", deliverAs)
            // The pin, when there is one. The bridge refuses a call that names
            // another session, so a prompt typed as the bridge changes hands is
            // not delivered to the wrong one. Omitting it, which happens before
            // the first state frame, keeps the bridge's tolerance for an app
            // that does not send the field yet.
            if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
        }.toString()
        return execute(builder("/api/prompt").post(payload.toRequestBody(json)).build())
    }

    /** The question widget pi has open, or a pending field set to null. */
    suspend fun question(): JSONObject = execute(builder("/api/question").get().build())

    /**
     * Answer an open question. [value] is normally an option's value; set
     * [custom] when it is free text, or [cancel] to dismiss the widget.
     */
    suspend fun answer(
        questionId: String?,
        value: String,
        custom: Boolean = false,
        cancel: Boolean = false,
        sessionId: String? = null,
    ): JSONObject {
        val payload = JSONObject().apply {
            put("value", value)
            put("custom", custom)
            put("cancel", cancel)
            if (!questionId.isNullOrBlank()) put("questionId", questionId)
            if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
        }.toString()
        return execute(builder("/api/answer").post(payload.toRequestBody(json)).build())
    }

    suspend fun abort(sessionId: String? = null): JSONObject {
        val payload = JSONObject().apply {
            if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
        }.toString()
        return execute(builder("/api/abort").post(payload.toRequestBody(json)).build())
    }

    /**
     * Opens the SSE stream. [onEvent] is called for every `data:` frame on an
     * OkHttp worker thread. Cancel the returned [Call] to stop streaming.
     */
    fun streamEvents(
        onOpen: () -> Unit,
        onEvent: (JSONObject) -> Unit,
        onClosed: (Throwable?) -> Unit,
    ): Call {
        val call = client.newCall(builder("/api/events").get().build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onClosed(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { open ->
                    if (!open.isSuccessful) {
                        onClosed(IOException(describeError(open.code, open.body?.string().orEmpty())))
                        return
                    }
                    onOpen()
                    val source = open.body?.source()
                    if (source == null) {
                        onClosed(IOException("empty event stream"))
                        return
                    }
                    try {
                        while (!source.exhausted() && !call.isCanceled()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data: ")) continue
                            val parsed = runCatching { JSONObject(line.removePrefix("data: ")) }.getOrNull()
                            if (parsed != null) onEvent(parsed)
                        }
                        onClosed(null)
                    } catch (e: Exception) {
                        onClosed(e)
                    }
                }
            }
        })
        return call
    }
}
