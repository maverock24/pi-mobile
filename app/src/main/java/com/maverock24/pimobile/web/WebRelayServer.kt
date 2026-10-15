package com.maverock24.pimobile.web

import android.content.res.AssetManager
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The HTTP half of web access: a Ktor server that serves the one bundled page.
 *
 * It binds loopback, not the WLAN interface, on purpose. Ktor's CIO engine
 * refuses an HTTPS connector of its own kind ("CIO Engine does not currently
 * support HTTPS", in ktor-server-cio itself, in every release through 3.6.0), so
 * the phone terminates TLS in [WebTlsFront] and forwards the decrypted bytes
 * here. Binding loopback keeps this plain-HTTP half off the network entirely: the
 * only listener on the WLAN is the TLS one.
 *
 * The idle bookkeeping lives here rather than on the sockets because a browser
 * keeps a connection open between requests; what says "the client is still here"
 * is a request, not an open socket.
 */
class WebRelayServer(private val assets: AssetManager) {

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val inFlight = AtomicInteger(0)
    private val lastActivity = AtomicLong(0)
    private val servedAnything = AtomicBoolean(false)

    /** Starts the loopback server and returns the port it actually bound. */
    suspend fun start(): Int {
        val server = embeddedServer(
            CIO,
            configure = {
                connector {
                    host = "127.0.0.1"
                    // An ephemeral port avoids a clash with anything else on the
                    // phone; the TLS front learns it from the return value.
                    port = 0
                }
            },
        ) {
            // Every request marks activity, and an in-flight request (a stream in
            // a later stage) keeps the server alive for as long as it lasts.
            intercept(ApplicationCallPipeline.Setup) {
                inFlight.incrementAndGet()
                servedAnything.set(true)
                lastActivity.set(System.currentTimeMillis())
                try {
                    proceed()
                } finally {
                    inFlight.decrementAndGet()
                    lastActivity.set(System.currentTimeMillis())
                }
            }
            routing {
                get("/") {
                    call.response.header("X-Content-Type-Options", "nosniff")
                    call.respondText(indexPage(), ContentType.Text.Html)
                }
            }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        engine = server
        return port
    }

    /**
     * True when a client has used the server and then gone quiet for [graceMs].
     * A server no client has opened yet is not idle: the human who started it is
     * still walking to the other computer.
     */
    fun isIdle(graceMs: Long, now: Long): Boolean =
        servedAnything.get() && inFlight.get() == 0 && now - lastActivity.get() >= graceMs

    /** Closes the listening socket. Idempotent. */
    fun stop() {
        // Short timeouts: this can run on the way out and must not hold the
        // process open.
        engine?.stop(gracePeriodMillis = 100, timeoutMillis = 300)
        engine = null
    }

    private fun indexPage(): String =
        assets.open("web/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
}
