package com.maverock24.pimobile.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The HTTP half of web access: the Ktor server that serves the page and the
 * relay routes.
 *
 * It binds loopback, not the WLAN interface, on purpose. Ktor's CIO engine
 * refuses an HTTPS connector of its own kind, so the phone terminates TLS in
 * [WebTlsFront] and forwards the decrypted bytes here. Binding loopback keeps
 * this plain-HTTP half off the network entirely: the only listener on the WLAN
 * is the TLS one.
 *
 * Every request passes the checks in [WebGuard] before any route runs, and every
 * response carries the same hardening headers. Nothing here is Android-specific,
 * so the whole surface is tested on a plain JVM with a fake upstream.
 *
 * The bridge routes are a literal allowlist, not a proxy: each one names a
 * [WebUpstream] call and reduces the reply to the fields the browser uses. The
 * bridge token lives in [BridgeUpstream] and never enters a response.
 */
internal class WebRelayServer(
    private val loadAsset: (String) -> String,
    private val expectedAuthority: String,
    private val expectedOrigin: String,
    private val upstream: WebUpstream,
    private val gate: WebApprovalGate = WebApprovalGate.shared,
    private val sessions: WebSessions = WebSessions(),
    private val peerAddress: (Int, String) -> String = { port, host -> WebPeers.lookup(port) ?: host },
) {

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val inFlight = AtomicInteger(0)
    private val lastActivity = AtomicLong(0)
    private val servedAnything = AtomicBoolean(false)

    /** Starts the loopback server on [port] and returns the port it bound. */
    suspend fun start(port: Int = 0): Int {
        val server = embeddedServer(
            CIO,
            configure = {
                connector {
                    host = "127.0.0.1"
                    // The production default is an ephemeral port; the TLS front
                    // learns it from the return value. A test passes a fixed one.
                    this.port = port
                }
            },
        ) {
            intercept(ApplicationCallPipeline.Setup) {
                inFlight.incrementAndGet()
                servedAnything.set(true)
                lastActivity.set(System.currentTimeMillis())
                try {
                    // `context` is the ApplicationCall for this pipeline stage.
                    harden(context)
                    if (refuseIfUnsafe(context)) return@intercept
                    proceed()
                } finally {
                    inFlight.decrementAndGet()
                    lastActivity.set(System.currentTimeMillis())
                }
            }
            routing {
                // The bundled page and its modules, a literal map so no request
                // can name a file the shell does not use.
                for ((path, asset) in ASSETS) {
                    get(path) { call.respondText(loadAsset(asset), contentTypeFor(path)) }
                }

                get("/api/session") {
                    val session = sessionOf(call)
                    if (session == null) {
                        respondJson(call, HttpStatusCode.Unauthorized, jsonObject("ok" to jsonBool(false)))
                    } else {
                        respondJson(
                            call,
                            HttpStatusCode.OK,
                            jsonObject("ok" to jsonBool(true), "csrf" to jsonString(session.csrf)),
                        )
                    }
                }

                post("/api/approve/request") { approveRequest(call) }

                get("/api/state") { proxyState(call) }
                get("/api/history") { proxyHistory(call) }
                get("/api/question") { proxyQuestion(call) }
                get("/api/events") { proxyEvents(call) }

                post("/api/prompt") { proxyPrompt(call) }
                post("/api/answer") { proxyAnswer(call) }

                post("/api/logout") { logout(call) }
            }
        }
        server.start(wait = false)
        val bound = server.engine.resolvedConnectors().first().port
        engine = server
        return bound
    }

    /**
     * True when a client has used the server and then gone quiet for [graceMs].
     * A server no client has opened yet is not idle: the human who started it is
     * still walking to the other computer. A live event stream counts as in
     * flight, so it holds the server open while the browser is attached.
     */
    fun isIdle(graceMs: Long, now: Long): Boolean =
        servedAnything.get() && inFlight.get() == 0 && now - lastActivity.get() >= graceMs

    /**
     * Closes the listening socket and drops every session, so a browser holding
     * a cookie cannot reach a server the human believes is off. Idempotent.
     */
    fun stop() {
        gate.clear()
        sessions.revokeAll()
        // Short timeouts: this can run on the way out and must not hold the
        // process open.
        engine?.stop(gracePeriodMillis = 100, timeoutMillis = 300)
        engine = null
    }

    // ---- routes -----------------------------------------------------------

    /**
     * Holds the connection until the phone answers, then writes the session
     * cookie on that same connection. Delivering the credential here, and
     * nowhere else, is what binds the approval to the requester (F4).
     */
    private suspend fun approveRequest(call: ApplicationCall) {
        if (!call.isJson()) {
            respondJson(call, HttpStatusCode.UnsupportedMediaType, errorJson("send application/json"))
            return
        }
        val peer = peerAddress(call.request.local.remotePort, call.request.local.remoteHost)
        when (gate.request(peer)) {
            ApprovalOutcome.APPROVED -> {
                val session = sessions.create(peer)
                call.response.header("Set-Cookie", sessions.cookieHeader(session))
                respondJson(
                    call,
                    HttpStatusCode.OK,
                    jsonObject("ok" to jsonBool(true), "csrf" to jsonString(session.csrf)),
                )
            }
            ApprovalOutcome.DENIED ->
                respondJson(call, HttpStatusCode.Forbidden, errorJson("the request was denied on the phone"))
            ApprovalOutcome.EXPIRED ->
                respondJson(call, HttpStatusCode.RequestTimeout, errorJson("the approval timed out"))
            ApprovalOutcome.BUSY ->
                respondJson(call, HttpStatusCode.TooManyRequests, errorJson("an approval is already waiting"))
            ApprovalOutcome.RATE_LIMITED ->
                respondJson(call, HttpStatusCode.TooManyRequests, errorJson("too many requests, try again shortly"))
        }
    }

    /**
     * A read-only bridge call made with the phone's token. The reply is reduced
     * to the fields the UI uses and the token is never part of it, so this cannot
     * turn into a transparent proxy.
     */
    private suspend fun proxyState(call: ApplicationCall) {
        val session = sessionOf(call)
        if (session == null) {
            respondJson(call, HttpStatusCode.Unauthorized, errorJson("no session"))
            return
        }
        val result = try {
            upstream.state(sessionLabel(session))
        } catch (_: Throwable) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the phone could not reach the bridge"))
            return
        }
        val reduced = if (result.status == HttpStatusCode.OK.value) {
            runCatching { reduceState(parseJson(result.body)) }.getOrNull()
        } else {
            null
        }
        if (reduced == null) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the bridge did not answer"))
            return
        }
        respondJson(call, HttpStatusCode.OK, reduced)
    }

    /**
     * The transcript catch-up: the newest messages reduced to role and text, so
     * a reload or a reconnected stream shows the run instead of a blank page
     * (UX3). Paths, tool arguments and tool results are dropped with [reduceHistory].
     */
    private suspend fun proxyHistory(call: ApplicationCall) {
        val session = sessionOf(call)
        if (session == null) {
            respondJson(call, HttpStatusCode.Unauthorized, errorJson("no session"))
            return
        }
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: HISTORY_DEFAULT)
            .coerceIn(1, HISTORY_MAX)
        val result = try {
            upstream.history(sessionLabel(session), limit)
        } catch (_: Throwable) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the phone could not reach the bridge"))
            return
        }
        if (result.status != HttpStatusCode.OK.value) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the bridge did not answer"))
            return
        }
        respondJson(call, HttpStatusCode.OK, reduceHistory(result.body))
    }

    /**
     * The question widget pi has open, reduced to the fields the card draws. The
     * phone fetches this on connect as a safety net for a missed event; the
     * stream carries it too.
     */
    private suspend fun proxyQuestion(call: ApplicationCall) {
        val session = sessionOf(call)
        if (session == null) {
            respondJson(call, HttpStatusCode.Unauthorized, errorJson("no session"))
            return
        }
        val result = try {
            upstream.question(sessionLabel(session))
        } catch (_: Throwable) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the phone could not reach the bridge"))
            return
        }
        if (result.status != HttpStatusCode.OK.value) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the bridge did not answer"))
            return
        }
        val pending = (runCatching { parseJson(result.body) }.getOrNull() as? JsonValue.Obj)
            ?.fields?.get("pending")
        respondJson(call, HttpStatusCode.OK, jsonObject("pending" to reducePending(pending)))
    }

    /**
     * The bridge event stream, filtered and forwarded. The bridge emits every
     * event type on one connection, including `state` with paths and pid, and
     * `tool_execution_*` with tool arguments and results (security F6); only the
     * frames the transcript and the question card render are re-emitted, each
     * reduced to text and choices.
     *
     * The browser carries the session cookie on the `EventSource` automatically,
     * which is why the credential is a cookie and not a header (UX1, F2).
     */
    private suspend fun proxyEvents(call: ApplicationCall) {
        val session = sessionOf(call)
        if (session == null) {
            respondJson(call, HttpStatusCode.Unauthorized, errorJson("no session"))
            return
        }
        val stream = try {
            upstream.events(sessionLabel(session))
        } catch (_: Throwable) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the phone could not reach the bridge"))
            return
        }
        if (stream.status != HttpStatusCode.OK.value) {
            stream.close()
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the bridge did not open the stream"))
            return
        }
        call.response.header("Cache-Control", "no-store")
        call.respondOutputStream(
            contentType = ContentType.Text.EventStream.withCharset(Charsets.UTF_8),
            status = HttpStatusCode.OK,
        ) {
            // Closing the upstream call when this response ends is what stops a
            // bridge stream from outliving a browser that has gone away.
            currentCoroutineContext()[Job]?.invokeOnCompletion { stream.close() }
            try {
                sendFrame("retry: 3000\n\n")
                while (true) {
                    val line = withContext(Dispatchers.IO) { stream.readLine() } ?: break
                    filterEvent(line)?.let { sendFrame(it) }
                }
            } finally {
                stream.close()
            }
        }
    }

    /**
     * A prompt from the browser. It is a mutation, so it needs the JSON body and
     * the per-session CSRF header, and the bridge token stays on this side.
     */
    private suspend fun proxyPrompt(call: ApplicationCall) {
        val session = requireMutation(call) ?: return
        val body = runCatching { parseJson(call.receiveText()) }.getOrNull() as? JsonValue.Obj
        val text = body?.stringOrNull("text")?.trim().orEmpty()
        if (text.isEmpty()) {
            respondJson(call, HttpStatusCode.BadRequest, errorJson("text is required"))
            return
        }
        val payload = LinkedHashMap<String, JsonValue>()
        payload["text"] = jsonString(text)
        body?.stringOrNull("deliverAs")?.takeIf { it == "steer" || it == "followUp" }
            ?.let { payload["deliverAs"] = jsonString(it) }
        body?.stringOrNull("sessionId")?.takeIf { it.isNotBlank() }?.let { payload["sessionId"] = jsonString(it) }
        val result = try {
            upstream.prompt(sessionLabel(session), JsonValue.Obj(payload).render())
        } catch (_: Throwable) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the phone could not reach the bridge"))
            return
        }
        if (result.status == HttpStatusCode.OK.value) {
            respondJson(call, HttpStatusCode.OK, jsonObject("ok" to jsonBool(true)))
        } else {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the bridge did not accept the prompt"))
        }
    }

    /**
     * An answer to the question widget. The bridge's own route is `POST
     * /api/answer`; this forwards the choice the same way the phone does, so a
     * run blocked on a question is not stranded (the car-mode failure the design
     * names in section 11).
     */
    private suspend fun proxyAnswer(call: ApplicationCall) {
        val session = requireMutation(call) ?: return
        val body = runCatching { parseJson(call.receiveText()) }.getOrNull() as? JsonValue.Obj
        val payload = LinkedHashMap<String, JsonValue>()
        body?.stringOrNull("questionId")?.takeIf { it.isNotBlank() }?.let { payload["questionId"] = jsonString(it) }
        body?.stringOrNull("value")?.let { payload["value"] = jsonString(it) }
        body?.boolOrNull("custom")?.let { payload["custom"] = jsonBool(it) }
        body?.boolOrNull("cancel")?.let { payload["cancel"] = jsonBool(it) }
        body?.stringOrNull("sessionId")?.takeIf { it.isNotBlank() }?.let { payload["sessionId"] = jsonString(it) }
        if (!payload.containsKey("value") && payload["cancel"] != jsonBool(true)) {
            respondJson(call, HttpStatusCode.BadRequest, errorJson("an answer needs a value or a cancel"))
            return
        }
        val result = try {
            upstream.answer(sessionLabel(session), JsonValue.Obj(payload).render())
        } catch (_: Throwable) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the phone could not reach the bridge"))
            return
        }
        if (result.status == HttpStatusCode.OK.value) {
            respondJson(call, HttpStatusCode.OK, jsonObject("ok" to jsonBool(true)))
        } else {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the bridge did not accept the answer"))
        }
    }

    /**
     * A state change, so it needs both a JSON body and the per-session CSRF
     * header. A cross-site form cannot set either, which is the mutation defence
     * on top of `SameSite=Strict`.
     */
    private suspend fun logout(call: ApplicationCall) {
        val session = requireMutation(call) ?: return
        sessions.revoke(session.id)
        call.response.header("Set-Cookie", sessions.clearedCookieHeader())
        respondJson(call, HttpStatusCode.OK, jsonObject("ok" to jsonBool(true)))
    }

    // ---- checks and helpers ----------------------------------------------

    /**
     * The checks every mutation shares: a live session, a JSON body and a
     * matching CSRF header. Returns null after writing the refusal, so a route
     * that gets a null must not write a response of its own.
     */
    private suspend fun requireMutation(call: ApplicationCall): WebSession? {
        val session = sessionOf(call)
        if (session == null) {
            respondJson(call, HttpStatusCode.Unauthorized, errorJson("no session"))
            return null
        }
        if (!call.isJson()) {
            respondJson(call, HttpStatusCode.UnsupportedMediaType, errorJson("send application/json"))
            return null
        }
        if (!secretsMatch(session.csrf, call.request.headers["X-CSRF-Token"])) {
            respondJson(call, HttpStatusCode.Forbidden, errorJson("bad csrf token"))
            return null
        }
        return session
    }

    /**
     * Resolves the cookie to a live session and checks it is still being used
     * from the address it was approved from. A cookie replayed from another host
     * fails closed, at the cost the design names: a DHCP move means approving
     * again.
     */
    private fun sessionOf(call: ApplicationCall): WebSession? {
        val session = sessions.authenticate(call.request.cookies[SESSION_COOKIE]) ?: return null
        val peer = peerAddress(call.request.local.remotePort, call.request.local.remoteHost)
        return if (session.sourceAddress == peer) session else null
    }

    /** The `X-Pi-Client` label, so one web session can be told from another. */
    private fun sessionLabel(session: WebSession): String = "web:${session.id.take(LABEL_ID_CHARS)}"

    /** The hardening headers every response carries, whatever the route. */
    private fun harden(call: ApplicationCall) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Cross-Origin-Resource-Policy", "same-origin")
        call.response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        call.response.header("Referrer-Policy", "no-referrer")
    }

    /**
     * Runs the per-request controls and answers the request itself when one
     * fails, so the route never sees an unsafe call. Returns true when it
     * refused, which also stops the pipeline.
     */
    private suspend fun refuseIfUnsafe(call: ApplicationCall): Boolean {
        val host = call.request.headers.getAll("Host") ?: emptyList()
        if (hostRefused(host, expectedAuthority)) {
            respondJson(call, HttpStatusCode.Forbidden, errorJson("host not allowed"))
            return true
        }
        if (targetRefused(call.request.uri)) {
            respondJson(call, HttpStatusCode.Forbidden, errorJson("target not allowed"))
            return true
        }
        if (originRefused(call.request.headers["Origin"], expectedOrigin)) {
            respondJson(call, HttpStatusCode.Forbidden, errorJson("origin not allowed"))
            return true
        }
        if (secFetchSiteRefused(call.request.headers["Sec-Fetch-Site"])) {
            respondJson(call, HttpStatusCode.Forbidden, errorJson("site not allowed"))
            return true
        }
        return false
    }

    private suspend fun respondJson(call: ApplicationCall, status: HttpStatusCode, body: JsonValue) {
        call.response.header("Cache-Control", "no-store")
        call.respondText(body.render(), ContentType.Application.Json, status)
    }

    private fun errorJson(message: String): JsonValue =
        jsonObject("ok" to jsonBool(false), "error" to jsonString(message))

    private fun ApplicationCall.isJson(): Boolean =
        request.contentType().match(ContentType.Application.Json)

    private fun contentTypeFor(path: String): ContentType = when {
        path.endsWith(".css") -> ContentType.Text.CSS
        path.endsWith(".js") -> ContentType.Text.JavaScript
        path.endsWith(".html") -> ContentType.Text.Html
        else -> ContentType.Application.OctetStream
    }

    /**
     * Turns one bridge event line into the frame the browser gets, or null to
     * drop it. The event type is the filter: `state`, `tool_execution_*`, `talk`
     * and everything else are not part of the core loop and are not forwarded
     * (security F6). The kept frames are re-emitted with only their `type` and
     * `data`, so no extra top-level field rides along.
     */
    private fun filterEvent(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return null
        val frame = runCatching { parseJson(payload) }.getOrNull() as? JsonValue.Obj ?: return null
        val type = frame.stringOrNull("type") ?: return null
        val data = frame.fields["data"] ?: JsonValue.Null
        val reduced: JsonValue = when (type) {
            "message_start", "message_end" -> reduceStreamMessage(data) ?: return null
            "message_update" -> reduceStreamUpdate(data) ?: return null
            "question" -> reduceStreamQuestion(data) ?: return null
            "prompt_accepted", "agent_start", "agent_end", "agent_settled" -> data
            else -> return null
        }
        return "data: " + jsonObject("type" to jsonString(type), "data" to reduced).render() + "\n\n"
    }

    /** Keeps only the role and the words of a message, plus whether it is mainly
     * a tool call. The call's arguments are dropped; the flag alone is what lets
     * the browser tell a tool-call turn from the answer, as the phone does. */
    private fun reduceStreamMessage(data: JsonValue): JsonValue? {
        val message = (data as? JsonValue.Obj)?.fields?.get("message") as? JsonValue.Obj ?: return null
        val role = message.stringOrNull("role") ?: return null
        return jsonObject(
            "message" to jsonObject(
                "role" to jsonString(role),
                "text" to jsonString(messageText(message)),
                "toolCall" to jsonBool(hasToolCalls(message)),
            ),
        )
    }

    /** Keeps a text delta and drops thinking and tool-argument deltas. */
    private fun reduceStreamUpdate(data: JsonValue): JsonValue? {
        val event = (data as? JsonValue.Obj)?.fields?.get("assistantMessageEvent") as? JsonValue.Obj ?: return null
        if (event.stringOrNull("type") != "text_delta") return null
        val delta = event.stringOrNull("delta") ?: return null
        return jsonObject(
            "assistantMessageEvent" to jsonObject(
                "type" to jsonString("text_delta"),
                "delta" to jsonString(delta),
            ),
        )
    }

    private fun reduceStreamQuestion(data: JsonValue): JsonValue =
        jsonObject("pending" to reducePending((data as? JsonValue.Obj)?.fields?.get("pending")))

    /**
     * Keeps only the state fields the UI uses, dropping paths, pid and binds.
     * The question is reduced to a pending flag: the full widget arrives on the
     * stream and from `/api/question`, not here.
     */
    private fun reduceState(value: JsonValue): JsonValue {
        val source = value as? JsonValue.Obj ?: return jsonObject()
        val out = LinkedHashMap<String, JsonValue>()
        for (key in SAFE_STATE_FIELDS) source.fields[key]?.let { out[key] = it }
        source.fields["question"]?.let { question ->
            out["question"] = jsonObject("pending" to jsonBool(question !is JsonValue.Null))
        }
        return JsonValue.Obj(out)
    }

    /** The newest messages as role and text, dropping paths and tool content. */
    private fun reduceHistory(body: String): JsonValue {
        val root = runCatching { parseJson(body) }.getOrNull() as? JsonValue.Obj
            ?: return jsonObject("messages" to JsonValue.Arr(emptyList()))
        val entries = (root.fields["messages"] as? JsonValue.Arr)?.items ?: emptyList()
        val out = ArrayList<JsonValue>()
        for (entry in entries) {
            val message = ((entry as? JsonValue.Obj)?.fields?.get("message") as? JsonValue.Obj) ?: continue
            val role = message.stringOrNull("role") ?: continue
            if (role != "user" && role != "assistant") continue
            val text = messageText(message)
            if (text.isBlank()) continue
            val fields = LinkedHashMap<String, JsonValue>()
            fields["role"] = jsonString(role)
            fields["text"] = jsonString(text)
            (entry as? JsonValue.Obj)?.stringOrNull("id")?.let { fields["id"] = jsonString(it) }
            out.add(JsonValue.Obj(fields))
        }
        return jsonObject("messages" to JsonValue.Arr(out))
    }

    /**
     * The question card's data, reduced to the fields the widget draws: the
     * options' value, label and description are what the buttons need, and no
     * other field of the pending object is forwarded.
     */
    private fun reducePending(pending: JsonValue?): JsonValue {
        val obj = pending as? JsonValue.Obj ?: return JsonValue.Null
        if (obj.stringOrNull("id").isNullOrBlank()) return JsonValue.Null
        val questions = (obj.fields["questions"] as? JsonValue.Arr)?.items ?: emptyList()
        val reduced = questions.mapNotNull { item ->
            val question = item as? JsonValue.Obj ?: return@mapNotNull null
            val options = ((question.fields["options"] as? JsonValue.Arr)?.items ?: emptyList())
                .mapNotNull { option -> (option as? JsonValue.Obj)?.let { reduceFields(it, OPTION_FIELDS) } }
            val fields = LinkedHashMap<String, JsonValue>()
            reduceFields(question, QUESTION_FIELDS).fields.forEach { (key, value) -> fields[key] = value }
            fields["options"] = JsonValue.Arr(options)
            JsonValue.Obj(fields)
        }
        if (reduced.isEmpty()) return JsonValue.Null
        val fields = LinkedHashMap<String, JsonValue>()
        reduceFields(obj, PENDING_FIELDS).fields.forEach { (key, value) -> fields[key] = value }
        fields["questions"] = JsonValue.Arr(reduced)
        return JsonValue.Obj(fields)
    }

    private fun reduceFields(source: JsonValue.Obj, keys: List<String>): JsonValue.Obj {
        val out = LinkedHashMap<String, JsonValue>()
        for (key in keys) source.fields[key]?.let { out[key] = it }
        return JsonValue.Obj(out)
    }

    /** True when a message exists mainly to call tools, as the app decides. */
    private fun hasToolCalls(message: JsonValue.Obj): Boolean {
        val content = message.fields["content"] as? JsonValue.Arr ?: return false
        return content.items.any { (it as? JsonValue.Obj)?.stringOrNull("type") == "toolCall" }
    }

    /**
     * The words a message carries, and nothing else. Thinking and tool calls are
     * how an answer was reached rather than part of it, and an image is named so
     * a prompt that carried one does not read as empty. This is the same rule the
     * app draws by.
     */
    private fun messageText(message: JsonValue.Obj): String {
        val content = message.fields["content"] ?: return ""
        if (content is JsonValue.Str) return content.value
        if (content !is JsonValue.Arr) return ""
        val parts = ArrayList<String>()
        for (item in content.items) {
            val block = item as? JsonValue.Obj ?: continue
            when (block.stringOrNull("type")) {
                "text" -> block.stringOrNull("text")?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                "image" -> parts.add("[image]")
            }
        }
        return parts.joinToString("\n")
    }

    private suspend fun OutputStream.sendFrame(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
        flush()
    }

    private companion object {
        const val SESSION_COOKIE = "pi_session"
        const val LABEL_ID_CHARS = 8
        const val HISTORY_DEFAULT = 120
        const val HISTORY_MAX = 500

        /** The bundled page and its modules. Nothing else on disk is served. */
        val ASSETS = linkedMapOf(
            "/" to "web/index.html",
            "/index.html" to "web/index.html",
            "/style.css" to "web/style.css",
            "/app.js" to "web/app.js",
            "/lib/relay.js" to "web/lib/relay.js",
            "/screens/chat.js" to "web/screens/chat.js",
        )

        /**
         * No inline script and no placeholder for one, so a scripting bug in a
         * proxied byte cannot be turned into script beside the session (F1).
         * `default-src 'self'` covers the bundled stylesheet, script and the ES
         * module imports, all same-origin.
         */
        const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; object-src 'none'; " +
                "base-uri 'none'; frame-ancestors 'none'; form-action 'none'"

        val SAFE_STATE_FIELDS = listOf(
            "sessionId",
            "sessionName",
            "mode",
            "model",
            "thinkingLevel",
            "idle",
            "pendingMessages",
            "serverTime",
        )

        val PENDING_FIELDS = listOf("id", "tool", "title", "multiple")
        val QUESTION_FIELDS = listOf("id", "label", "prompt", "answer")
        val OPTION_FIELDS = listOf("value", "label", "description")
    }
}
