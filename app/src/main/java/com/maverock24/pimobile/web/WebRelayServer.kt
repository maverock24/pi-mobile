package com.maverock24.pimobile.web

import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
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
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.URLDecoder
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
    // Read on every request and replaced by the watchdog, so volatile.
    @Volatile private var allowedAuthorities: Set<String>,
    @Volatile private var allowedOrigins: Set<String>,
    private val upstream: WebUpstream,
    private val pins: WebPins = WebPins.Empty,
    private val gate: WebApprovalGate = WebApprovalGate.shared,
    private val sessions: WebSessions = WebSessions(),
    private val peerAddress: (Int, String) -> String = { port, host -> WebPeers.lookup(port) ?: host },
) {

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val inFlight = AtomicInteger(0)
    private val lastActivity = AtomicLong(0)
    private val servedAnything = AtomicBoolean(false)

    /**
     * Replaces the addresses the relay answers for when the phone's interfaces
     * come up or go down. It is called with an empty set when no address is left,
     * which refuses every request rather than falling back to a stale address.
     * The listening socket itself does not move: the TLS front is bound to every
     * interface, so only the allowed authorities, the origins and the shown
     * addresses change here.
     */
    fun updateAuthorities(authorities: Set<String>, origins: Set<String>) {
        allowedAuthorities = authorities
        allowedOrigins = origins
        WebDiagnostics.update { it.copy(allowedAddresses = authorities.sorted()) }
    }

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

                // The listener's own state, readable from the browser before a
                // session exists. This is the route the human opens when an
                // address does not answer, so it carries no secret: the backend
                // port, whether the certificate loaded, and why a connection last
                // closed. It still passes the Host and target checks like every
                // other route, so a rebound name cannot read it either.
                get("/api/diagnostics") { diagnosticsRoute(call) }

                post("/api/approve/request") { approveRequest(call) }

                get("/api/state") { proxyState(call) }
                get("/api/history") { proxyHistory(call) }
                get("/api/files") { proxyFiles(call) }
                // One audio file, streamed with the browser's byte range. The
                // route is a single path segment, so a name cannot walk out of
                // the library, and the extension allowlist below is what keeps
                // non-audio bytes off the session origin (F1).
                get("/api/files/{name}") { proxyFile(call) }
                get("/api/question") { proxyQuestion(call) }
                get("/api/events") { proxyEvents(call) }

                post("/api/prompt") { proxyPrompt(call) }
                post("/api/answer") { proxyAnswer(call) }

                // The pins are the phone's own data, so these are relay-local like
                // the session routes, not proxied bridge routes. Rename and delete
                // are mutations, so they carry the same CSRF and JSON checks.
                get("/api/pins") { listPins(call) }
                post("/api/pins/rename") { renamePin(call) }
                post("/api/pins/delete") { deletePin(call) }

                post("/api/logout") { logout(call) }
            }
        }
        server.start(wait = false)
        val bound = server.engine.resolvedConnectors().first().port
        engine = server
        // The port the TLS front forwards to, and the addresses this server
        // answers for. Both are surfaced on /api/diagnostics so a front that
        // cannot reach the backend, which closes connections with no response,
        // is visible rather than silent. The addresses are updated again whenever
        // the phone's interfaces change.
        WebDiagnostics.update {
            it.copy(backendPort = bound, allowedAddresses = allowedAuthorities.sorted())
        }
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
     * The listener's real state: the ports, whether the certificate loaded, the
     * addresses it answers for, and the last reason a connection closed. It needs
     * no session because it is what the human reads when the session flow is the
     * thing that is not working, and it exposes no token, cookie or session data.
     */
    private suspend fun diagnosticsRoute(call: ApplicationCall) {
        val state = WebDiagnostics.value
        respondJson(
            call,
            HttpStatusCode.OK,
            jsonObject(
                "ok" to jsonBool(true),
                "certificateLoaded" to jsonBool(state.certificateLoaded),
                "fingerprint" to jsonString(state.fingerprint),
                "listenPort" to jsonNum(state.listenPort.toLong()),
                "backendPort" to jsonNum(state.backendPort.toLong()),
                "boundAddress" to jsonString(state.boundAddress),
                "allowedAddresses" to JsonValue.Arr(state.allowedAddresses.map { jsonString(it) }),
                "acceptedConnections" to jsonNum(state.acceptedConnections),
                "handshakeFailures" to jsonNum(state.handshakeFailures),
                "backendFailures" to jsonNum(state.backendFailures),
                "lastCloseReason" to jsonString(state.lastCloseReason),
            ),
        )
    }

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
     * The library the Files screen draws, reduced to the fields the phone's rows
     * show. A bridge that is down or that answers with a non-200 is a bad gateway,
     * which is a different thing from an empty library and must stay different.
     */
    private suspend fun proxyFiles(call: ApplicationCall) {
        val session = sessionOf(call)
        if (session == null) {
            respondJson(call, HttpStatusCode.Unauthorized, errorJson("no session"))
            return
        }
        val result = try {
            upstream.files(sessionLabel(session))
        } catch (_: Throwable) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the phone could not reach the bridge"))
            return
        }
        if (result.status != HttpStatusCode.OK.value) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the bridge did not answer"))
            return
        }
        respondJson(call, HttpStatusCode.OK, reduceFiles(result.body))
    }

    /**
     * One library file, streamed through the relay so the bytes reach the browser
     * from the phone rather than from a second download (see implementation-stage-5).
     *
     * Three rules make this safe on the session origin. The name must match the
     * bridge's own file-name shape and an audio extension, so the route serves
     * audio and nothing else (F1). The content type is chosen here from the
     * extension, never copied from the bridge, so a bridge that answered
     * `text/html` for an `.mp3` name could not put a document on this origin. And
     * the `Range` header is the only browser header forwarded; the cookie and the
     * bridge token never cross in either direction.
     *
     * A 206 keeps the upstream `Content-Range` and `Accept-Ranges`, which is what
     * makes seeking in a long talk work; dropping either is the silent breakage
     * the design warned about.
     */
    private suspend fun proxyFile(call: ApplicationCall) {
        val session = sessionOf(call)
        if (session == null) {
            respondJson(call, HttpStatusCode.Unauthorized, errorJson("no session"))
            return
        }
        val name = runCatching { URLDecoder.decode(call.parameters["name"].orEmpty(), "UTF-8") }
            .getOrNull()
            .orEmpty()
        val type = audioContentType(name)
        if (type == null) {
            // Non-audio kinds are refused here rather than served as themselves:
            // the design refuses any non-audio bytes on the session origin (F1),
            // and the Files list already says a non-audio row cannot be shown.
            respondJson(call, HttpStatusCode.NotFound, errorJson("this file cannot be played in the browser"))
            return
        }
        val range = call.request.headers["Range"]?.takeIf { it.isNotBlank() }
        val file = try {
            upstream.file(sessionLabel(session), name, range)
        } catch (_: Throwable) {
            respondJson(call, HttpStatusCode.BadGateway, errorJson("the phone could not reach the bridge"))
            return
        }
        when (file.status) {
            200, 206 -> respondFile(call, file, type)
            416 -> {
                // The bridge answers a range it cannot satisfy with the size it
                // knows, and a player uses that to re-ask. Pass the status and
                // the header through with an empty body.
                val contentRange = file.contentRange
                file.close()
                call.response.header("Accept-Ranges", "bytes")
                contentRange?.let { call.response.header("Content-Range", it) }
                respondJson(call, HttpStatusCode.RequestedRangeNotSatisfiable, errorJson("range not satisfiable"))
            }
            else -> {
                file.close()
                respondJson(call, HttpStatusCode.BadGateway, errorJson("the bridge did not serve the file"))
            }
        }
    }

    /**
     * Streams an upstream file to the browser with the headers a range player
     * needs, then closes the upstream call. The body is written in chunks so a
     * long talk is not held in memory, and closing on any path is what stops an
     * upstream read from outliving the browser request.
     */
    private suspend fun respondFile(call: ApplicationCall, file: WebUpstreamFile, type: ContentType) {
        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val status: HttpStatusCode = HttpStatusCode.fromValue(file.status)
            override val contentType: ContentType = type
            override val contentLength: Long? = file.contentLength.takeIf { it >= 0 }
            override val headers: Headers = Headers.build {
                append("Accept-Ranges", "bytes")
                file.contentRange?.let { append("Content-Range", it) }
                append("Content-Disposition", "inline")
            }

            override suspend fun writeTo(channel: ByteWriteChannel) {
                try {
                    val buffer = ByteArray(FILE_CHUNK_BYTES)
                    while (true) {
                        val read = withContext(Dispatchers.IO) { file.read(buffer) }
                        if (read < 0) break
                        if (read == 0) continue
                        channel.writeFully(buffer, 0, read)
                        // Flush each chunk, as the event stream flushes each frame.
                        // Without it the engine holds the body until the writer
                        // finishes, which would make a long talk arrive all at once
                        // and turn a seek into a re-download of everything before
                        // the range.
                        channel.flush()
                    }
                } finally {
                    file.close()
                }
            }
        })
    }

    /**
     * The phone's pins, relay-local. A session is required because a pin carries
     * the prompt and the answer, which are the session's own record.
     */
    private suspend fun listPins(call: ApplicationCall) {
        val session = sessionOf(call)
        if (session == null) {
            respondJson(call, HttpStatusCode.Unauthorized, errorJson("no session"))
            return
        }
        val list = runCatching { pins.list() }.getOrDefault(emptyList()).map { pin ->
            jsonObject(
                "id" to jsonString(pin.id),
                "title" to jsonString(pin.title),
                "prompt" to jsonString(pin.prompt),
                "answer" to jsonString(pin.answer),
            )
        }
        respondJson(call, HttpStatusCode.OK, jsonObject("pins" to JsonValue.Arr(list)))
    }

    /** Changes a pin's title. A mutation, so it needs the session, JSON and CSRF. */
    private suspend fun renamePin(call: ApplicationCall) {
        requireMutation(call) ?: return
        val body = runCatching { parseJson(call.receiveText()) }.getOrNull() as? JsonValue.Obj
        val id = body?.stringOrNull("id")?.takeIf { it.isNotBlank() }
        val title = body?.stringOrNull("title")?.trim()
        if (id == null || title.isNullOrBlank()) {
            respondJson(call, HttpStatusCode.BadRequest, errorJson("a pin id and a title are required"))
            return
        }
        val changed = runCatching { pins.rename(id, title) }.getOrDefault(false)
        if (changed) {
            respondJson(call, HttpStatusCode.OK, jsonObject("ok" to jsonBool(true)))
        } else {
            respondJson(call, HttpStatusCode.NotFound, errorJson("no such pin"))
        }
    }

    /** Removes a pin. A mutation, so it needs the session, JSON and CSRF. */
    private suspend fun deletePin(call: ApplicationCall) {
        requireMutation(call) ?: return
        val body = runCatching { parseJson(call.receiveText()) }.getOrNull() as? JsonValue.Obj
        val id = body?.stringOrNull("id")?.takeIf { it.isNotBlank() }
        if (id == null) {
            respondJson(call, HttpStatusCode.BadRequest, errorJson("a pin id is required"))
            return
        }
        val removed = runCatching { pins.delete(id) }.getOrDefault(false)
        if (removed) {
            respondJson(call, HttpStatusCode.OK, jsonObject("ok" to jsonBool(true)))
        } else {
            respondJson(call, HttpStatusCode.NotFound, errorJson("no such pin"))
        }
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
        if (hostRefused(host, allowedAuthorities)) {
            respondJson(call, HttpStatusCode.Forbidden, errorJson("host not allowed"))
            return true
        }
        if (targetRefused(call.request.uri)) {
            respondJson(call, HttpStatusCode.Forbidden, errorJson("target not allowed"))
            return true
        }
        if (originRefused(call.request.headers["Origin"], allowedOrigins)) {
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
     * The relay's own content type for an audio name, from the extension alone.
     * A name that is not the bridge's file-name shape, or whose extension is not
     * one of the audio kinds, returns null and the route refuses it. The bridge's
     * own header is never used, so an audio route can only ever be an audio
     * document on the session origin (F1).
     */
    private fun audioContentType(name: String): ContentType? {
        if (!SAFE_FILE_NAME.matches(name)) return null
        return when (extensionOf(name)) {
            "mp3" -> ContentType.Audio.MPEG
            "m4a" -> ContentType.Audio.MP4
            "aac" -> ContentType("audio", "aac")
            "wav" -> ContentType("audio", "wav")
            "ogg", "oga", "opus" -> ContentType.Audio.OGG
            "flac" -> ContentType("audio", "flac")
            "weba" -> ContentType("audio", "webm")
            else -> null
        }
    }

    /** The lowercased extension without its dot, or "" when there is none. */
    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
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
            // The Files screen refreshes on a talk render advancing or a talk
            // being removed, so these two summaries are forwarded reduced and
            // nothing else is. They are not `state` and carry no paths or pid.
            "talk" -> reduceTalk(data) ?: return null
            "talk_removed" -> reduceTalkRemoved(data) ?: return null
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
     * The library rows, reduced to the fields the phone's rows show. `ext` and
     * `createdAt` are dropped because no row draws them; the rest, including the
     * render `state` and the `error`, are what the phone shows for a file that is
     * rendering or has failed.
     */
    private fun reduceFiles(body: String): JsonValue {
        val root = runCatching { parseJson(body) }.getOrNull() as? JsonValue.Obj
        val rows = (root?.fields?.get("files") as? JsonValue.Arr)?.items ?: emptyList()
        val out = rows.mapNotNull { row -> (row as? JsonValue.Obj)?.let { reduceFields(it, FILE_FIELDS) } }
        return jsonObject("files" to JsonValue.Arr(out))
    }

    /** One talk row for the Files refresh, the same fields `/api/files` exposes. */
    private fun reduceTalk(data: JsonValue): JsonValue? {
        val obj = data as? JsonValue.Obj ?: return null
        if (obj.stringOrNull("id").isNullOrBlank()) return null
        return reduceFields(obj, FILE_FIELDS)
    }

    /** A removed talk, reduced to the id the Files row keys on. */
    private fun reduceTalkRemoved(data: JsonValue): JsonValue? {
        val id = (data as? JsonValue.Obj)?.stringOrNull("id") ?: return null
        if (id.isBlank()) return null
        return jsonObject("id" to jsonString(id))
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
        const val FILE_CHUNK_BYTES = 64 * 1024

        /**
         * The bridge's own file-name shape (its `SAFE_NAME_RE`). A name that is
         * not this could not be listed by `/api/files`, so it is not served here
         * either; this is the second lock beside the single-segment route pattern.
         */
        val SAFE_FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9 ._()-]{0,127}$")

        /** The bundled page and its modules. Nothing else on disk is served. */
        val ASSETS = linkedMapOf(
            "/" to "web/index.html",
            "/index.html" to "web/index.html",
            "/style.css" to "web/style.css",
            "/app.js" to "web/app.js",
            "/lib/relay.js" to "web/lib/relay.js",
            "/lib/events.js" to "web/lib/events.js",
            "/lib/theme.js" to "web/lib/theme.js",
            "/screens/chat.js" to "web/screens/chat.js",
            "/screens/files.js" to "web/screens/files.js",
            "/screens/pins.js" to "web/screens/pins.js",
            "/screens/settings.js" to "web/screens/settings.js",
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

        /**
         * The file row fields the Files screen draws. `state` is the bridge's own
         * word for a render and the `error` is the reason a failed one carries, so
         * both are kept: a rendering file and a failed file have to read as
         * themselves, not as an empty list.
         */
        val FILE_FIELDS = listOf(
            "id",
            "name",
            "title",
            "kind",
            "state",
            "bytes",
            "durationSeconds",
            "durationEstimated",
            "voice",
            "error",
        )
        val QUESTION_FIELDS = listOf("id", "label", "prompt", "answer")
        val OPTION_FIELDS = listOf("value", "label", "description")
    }
}
