package com.maverock24.pimobile.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The relay's own upstream client. It has to carry the phone's token and the
 * session label and none of the browser's own headers, and it must not follow a
 * redirect, because a redirect could carry the token to another host (security
 * F10). A small Ktor server stands in for the bridge so the header set and the
 * redirect behaviour are checked against a real HTTP exchange.
 */
class BridgeUpstreamTest {

    private lateinit var bridge: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private var port = 0
    private val authHeader = AtomicReference<String?>()
    private val labelHeader = AtomicReference<String?>()
    private val cookieHeader = AtomicReference<String?>()
    private val requestBody = AtomicReference<String?>()
    private val filePath = AtomicReference<String?>()
    private val rangeHeader = AtomicReference<String?>()
    private val otherHits = AtomicInteger(0)

    @Volatile
    private var redirect = false

    @Before
    fun setUp() {
        bridge = embeddedServer(CIO, port = 0) {
            routing {
                get("/api/state") {
                    capture(call)
                    if (redirect) {
                        call.response.header("Location", "/elsewhere")
                        call.respond(HttpStatusCode.Found)
                    } else {
                        call.respondText("""{"sessionId":"x"}""", ContentType.Application.Json)
                    }
                }
                get("/api/history") { capture(call); call.respondText("""{"messages":[]}""", ContentType.Application.Json) }
                get("/api/files") { capture(call); call.respondText("""{"files":[]}""", ContentType.Application.Json) }
                get("/api/files/{name}") {
                    capture(call)
                    filePath.set(call.request.uri)
                    rangeHeader.set(call.request.headers["Range"])
                    if (rangeHeader.get() != null) {
                        call.response.header("Content-Range", "bytes 0-3/10")
                        call.respondBytes(byteArrayOf(1, 2, 3, 4), ContentType.Audio.MPEG, HttpStatusCode.PartialContent)
                    } else {
                        call.respondBytes(ByteArray(10), ContentType.Audio.MPEG)
                    }
                }
                get("/api/question") { capture(call); call.respondText("""{"pending":null}""", ContentType.Application.Json) }
                get("/api/events") {
                    capture(call)
                    call.respondText("data: {\"type\":\"message_end\"}\n\n", ContentType.Text.EventStream)
                }
                post("/api/prompt") {
                    capture(call)
                    requestBody.set(call.receiveText())
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
                post("/api/answer") {
                    capture(call)
                    requestBody.set(call.receiveText())
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
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

    /**
     * The live Headers object is backed by a recycled buffer, so the values are
     * copied out here rather than read after the call.
     */
    private fun capture(call: ApplicationCall) {
        authHeader.set(call.request.headers["Authorization"])
        labelHeader.set(call.request.headers["X-Pi-Client"])
        cookieHeader.set(call.request.headers["Cookie"])
    }

    @Test
    fun `sends the token and the session label upstream`() = runBlocking {
        val result = upstream().state("web:abc")
        assertEquals(200, result.status)
        assertEquals("Bearer tok", authHeader.get())
        assertEquals("web:abc", labelHeader.get())
    }

    @Test
    fun `every request route sends the token and never the browser cookie`() = runBlocking {
        upstream().history("web:abc", 20)
        assertEquals("Bearer tok", authHeader.get())
        assertEquals("web:abc", labelHeader.get())

        upstream().question("web:abc")
        assertEquals("Bearer tok", authHeader.get())

        upstream().files("web:abc")
        assertEquals("Bearer tok", authHeader.get())
        assertNull("the browser's cookie is never forwarded", cookieHeader.get())

        upstream().prompt("web:abc", """{"text":"hi"}""")
        assertEquals("Bearer tok", authHeader.get())
        assertTrue(requestBody.get()!!.contains("hi"))

        upstream().answer("web:abc", """{"value":"g"}""")
        assertEquals("Bearer tok", authHeader.get())
        assertNull("the browser's cookie is never forwarded", cookieHeader.get())
    }

    @Test
    fun `the event stream is read line by line and carries the token`() = runBlocking {
        val stream = upstream().events("web:abc")
        assertEquals(200, stream.status)
        val lines = generateSequence { stream.readLine() }.toList()
        stream.close()
        assertEquals("Bearer tok", authHeader.get())
        assertEquals("web:abc", labelHeader.get())
        assertTrue(lines.any { it.startsWith("data: ") })
    }

    @Test
    fun `the file route sends the token and the range, never the browser cookie`() = runBlocking {
        val file = upstream().file("web:abc", "t-1.mp3", "bytes=0-3")
        assertEquals(206, file.status)
        assertEquals("Bearer tok", authHeader.get())
        assertEquals("web:abc", labelHeader.get())
        assertEquals("the browser's range goes upstream", "bytes=0-3", rangeHeader.get())
        assertEquals("/api/files/t-1.mp3", filePath.get())
        assertFalse("the request path carries no token", filePath.get()!!.contains("tok"))
        assertNull("the browser's cookie is never forwarded", cookieHeader.get())
        assertEquals("bytes 0-3/10", file.contentRange)
        assertEquals(4L, file.contentLength)
        val buffer = ByteArray(8)
        assertEquals(4, file.read(buffer))
        assertEquals(1.toByte(), buffer[0])
        assertEquals("the body ends after its slice", -1, file.read(buffer))
        file.close()
    }

    @Test
    fun `does not follow a redirect`() = runBlocking {
        redirect = true
        val result = upstream().state("web:abc")
        assertEquals(302, result.status)
        assertEquals(0, otherHits.get())
    }
}
