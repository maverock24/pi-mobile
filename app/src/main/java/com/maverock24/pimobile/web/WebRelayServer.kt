package com.maverock24.pimobile.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentType
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The HTTP half of web access: the Ktor server that serves the page and the
 * stage 2 session routes.
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
                get("/") { call.respondText(loadAsset("web/index.html"), ContentType.Text.Html) }
                get("/style.css") { call.respondText(loadAsset("web/style.css"), ContentType.Text.CSS) }
                get("/app.js") { call.respondText(loadAsset("web/app.js"), ContentType.Text.JavaScript) }

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
     * still walking to the other computer.
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
     * The one proxied route in stage 2, a read-only bridge call made with the
     * phone's token. The reply is reduced to the fields the UI uses and the
     * token is never part of it, so this cannot turn into a transparent proxy.
     */
    private suspend fun proxyState(call: ApplicationCall) {
        val session = sessionOf(call)
        if (session == null) {
            respondJson(call, HttpStatusCode.Unauthorized, errorJson("no session"))
            return
        }
        val result = try {
            upstream.state("web:${session.id.take(LABEL_ID_CHARS)}")
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
     * A state change, so it needs both a JSON body and the per-session CSRF
     * header. A cross-site form cannot set either, which is the mutation defence
     * on top of `SameSite=Strict`.
     */
    private suspend fun logout(call: ApplicationCall) {
        val session = sessionOf(call)
        if (session == null) {
            respondJson(call, HttpStatusCode.Unauthorized, errorJson("no session"))
            return
        }
        if (!call.isJson()) {
            respondJson(call, HttpStatusCode.UnsupportedMediaType, errorJson("send application/json"))
            return
        }
        if (!secretsMatch(session.csrf, call.request.headers["X-CSRF-Token"])) {
            respondJson(call, HttpStatusCode.Forbidden, errorJson("bad csrf token"))
            return
        }
        sessions.revoke(session.id)
        call.response.header("Set-Cookie", sessions.clearedCookieHeader())
        respondJson(call, HttpStatusCode.OK, jsonObject("ok" to jsonBool(true)))
    }

    // ---- checks and helpers ----------------------------------------------

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

    /** Keeps only the state fields the UI uses, dropping paths, pid and binds. */
    private fun reduceState(value: JsonValue): JsonValue {
        val source = value as? JsonValue.Obj ?: return jsonObject()
        val out = LinkedHashMap<String, JsonValue>()
        for (key in SAFE_STATE_FIELDS) source.fields[key]?.let { out[key] = it }
        source.fields["question"]?.let { question ->
            out["question"] = jsonObject("pending" to jsonBool(question !is JsonValue.Null))
        }
        return JsonValue.Obj(out)
    }

    private companion object {
        const val SESSION_COOKIE = "pi_session"
        const val LABEL_ID_CHARS = 8

        /**
         * No inline script and no placeholder for one, so a scripting bug in a
         * proxied byte cannot be turned into script beside the session (F1).
         * `default-src 'self'` covers the bundled stylesheet and script.
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
    }
}
