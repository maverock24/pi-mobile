package com.maverock24.pimobile.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The relay's own upstream client. It has to carry the phone's token and the
 * session label, and it must not follow a redirect, because a redirect could
 * carry the token to another host (security F10). A small Ktor server stands in
 * for the bridge so the header set and the redirect behaviour are checked
 * against a real HTTP exchange.
 */
class BridgeUpstreamTest {

    private lateinit var bridge: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private var port = 0
    private val authHeader = AtomicReference<String?>()
    private val labelHeader = AtomicReference<String?>()
    private val otherHits = AtomicInteger(0)

    @Volatile
    private var redirect = false

    @Before
    fun setUp() {
        bridge = embeddedServer(CIO, port = 0) {
            routing {
                get("/api/state") {
                    // The live Headers object is backed by a recycled buffer, so copy
                    // the two values out here rather than reading it after the call.
                    authHeader.set(call.request.headers["Authorization"])
                    labelHeader.set(call.request.headers["X-Pi-Client"])
                    if (redirect) {
                        call.response.header("Location", "/elsewhere")
                        call.respond(HttpStatusCode.Found)
                    } else {
                        call.respondText("""{"sessionId":"x"}""", ContentType.Application.Json)
                    }
                }
                get("/elsewhere") {
                    otherHits.incrementAndGet()
                    call.respondText("nope")
                }
            }
        }
        bridge.start(wait = false)
        port = runBlocking { bridge.engine.resolvedConnectors().first().port }
    }

    @After
    fun tearDown() {
        bridge.stop(100, 300)
    }

    private fun upstream() =
        BridgeUpstream(baseUrl = { "http://127.0.0.1:$port" }, token = { "tok" })

    @Test
    fun `sends the token and the session label upstream`() = runBlocking {
        val result = upstream().state("web:abc")
        assertEquals(200, result.status)
        assertEquals("Bearer tok", authHeader.get())
        assertEquals("web:abc", labelHeader.get())
    }

    @Test
    fun `does not follow a redirect`() = runBlocking {
        redirect = true
        val result = upstream().state("web:abc")
        assertEquals(302, result.status)
        assertEquals(0, otherHits.get())
    }
}
