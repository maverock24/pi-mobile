package com.maverock24.pimobile.web

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The stage 2 security surface, driven end to end against a real Ktor server on
 * loopback with a fake upstream. Nothing here needs Android or a phone: the
 * server half has no Android types, so a failure in a check is caught here rather
 * than asserted in prose.
 *
 * The refusals are the point. A wrong or missing Host, no session cookie, a
 * mutation without the CSRF token and a foreign Origin all have to fail closed.
 */
class WebRelayServerTest {

    private lateinit var gate: WebApprovalGate
    private lateinit var upstream: FakeUpstream
    private lateinit var pinStore: FakePins
    private lateinit var server: WebRelayServer
    private var port = 0

    // The source address the server sees. A test flips it to prove the session is
    // bound to the address it was approved from.
    private var peerResolver: (Int, String) -> String = { _, host -> host }

    private val client = OkHttpClient()
    private val jsonMedia = "application/json".toMediaType()

    private val base: String get() = "http://127.0.0.1:$port"
    private val authority: String get() = "127.0.0.1:$port"
    private val origin: String get() = "http://127.0.0.1:$port"

    // A second address the phone might hold. The relay must answer on every
    // candidate, not only the one this test happens to dial.
    private val hotspotAuthority: String get() = "192.168.43.1:$port"
    private val hotspotOrigin: String get() = "https://192.168.43.1:$port"

    @Before
    fun setUp() {
        // The listener state is per process, so a previous test's counts and
        // ports are cleared before this server starts and writes its own.
        WebDiagnostics.reset()
        gate = WebApprovalGate(ttlMs = 5_000)
        upstream = FakeUpstream()
        pinStore = FakePins()
        port = ServerSocket(0).use { it.localPort }
        server = WebRelayServer(
            loadAsset = { "<html></html>" },
            allowedAuthorities = setOf(authority, hotspotAuthority),
            allowedOrigins = setOf(origin, hotspotOrigin),
            upstream = upstream,
            pins = pinStore,
            gate = gate,
            peerAddress = { port, host -> peerResolver(port, host) },
        )
        runBlocking { server.start(port) }
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ---- Host --------------------------------------------------------------

    @Test
    fun `a wrong host header is refused`() {
        get("/api/session", headers = mapOf("Host" to "evil.example")).use {
            assertEquals(403, it.code)
        }
    }

    @Test
    fun `a request to any candidate address is allowed`() {
        get(
            "/api/session",
            headers = mapOf("Host" to hotspotAuthority, "Origin" to hotspotOrigin),
        ).use { assertEquals(401, it.code) }
    }

    @Test
    fun `an address that is not a candidate is refused`() {
        get("/api/session", headers = mapOf("Host" to "10.0.0.9:$port")).use {
            assertEquals(403, it.code)
        }
    }

    @Test
    fun `the allowed addresses follow the interfaces`() {
        server.updateAuthorities(setOf(authority), setOf(origin))
        get("/api/session", headers = mapOf("Host" to hotspotAuthority)).use {
            assertEquals("a hotspot address that went away is refused", 403, it.code)
        }

        server.updateAuthorities(setOf(authority, hotspotAuthority), setOf(origin, hotspotOrigin))
        get(
            "/api/session",
            headers = mapOf("Host" to hotspotAuthority, "Origin" to hotspotOrigin),
        ).use { assertEquals("a hotspot address that came up is allowed", 401, it.code) }

        // With no address left, every request is refused rather than answered for
        // a stale address.
        server.updateAuthorities(emptySet(), emptySet())
        get("/api/session", headers = mapOf("Host" to authority)).use {
            assertEquals(403, it.code)
        }
    }

    @Test
    fun `a missing host header is refused`() {
        val response = raw("GET /api/session HTTP/1.1\r\nConnection: close\r\n\r\n")
        assertRefused(response)
    }

    @Test
    fun `a duplicate host header is refused`() {
        val response = raw(
            "GET /api/session HTTP/1.1\r\nHost: $authority\r\nHost: evil.example\r\n" +
                "Connection: close\r\n\r\n",
        )
        assertRefused(response)
    }

    @Test
    fun `an absolute form target is refused`() {
        val response = raw(
            "GET $base/api/session HTTP/1.1\r\nHost: $authority\r\nConnection: close\r\n\r\n",
        )
        assertRefused(response)
    }

    @Test
    fun `an encoded separator in the target is refused`() {
        val response = raw(
            "GET /api/%2e%2e/api/pair HTTP/1.1\r\nHost: $authority\r\nConnection: close\r\n\r\n",
        )
        assertRefused(response)
    }

    // ---- session -----------------------------------------------------------

    @Test
    fun `a request with no session cookie is refused`() {
        get("/api/session").use { assertEquals(401, it.code) }
        get("/api/state").use { assertEquals(401, it.code) }
    }

    // ---- Origin and Sec-Fetch-Site -----------------------------------------

    @Test
    fun `a foreign origin is refused`() {
        get("/api/session", headers = mapOf("Origin" to "https://evil.example")).use {
            assertEquals(403, it.code)
        }
        post(
            "/api/approve/request",
            body = "{}",
            contentType = "application/json",
            headers = mapOf("Origin" to "https://evil.example"),
        ).use {
            assertEquals(403, it.code)
        }
    }

    @Test
    fun `a cross site fetch is refused`() {
        get("/api/session", headers = mapOf("Sec-Fetch-Site" to "cross-site")).use {
            assertEquals(403, it.code)
        }
    }

    // ---- approval and the session credential -------------------------------

    @Test
    fun `approval is answered on the holding connection with a strict cookie`() {
        val held = submitApproval()
        val pending = awaitPending()
        assertTrue(gate.approve(pending.id))
        assertTrue("the held request did not finish", held.latch.await(5, TimeUnit.SECONDS))
        held.response.get()!!.use { response ->
            assertEquals(200, response.code)
            val cookie = response.header("Set-Cookie")!!
            assertTrue("host-only, no Domain", !cookie.contains("Domain", ignoreCase = true))
            assertTrue(cookie.contains("HttpOnly"))
            assertTrue(cookie.contains("SameSite=Strict"))
            assertTrue(cookie.contains("Secure"))
            assertTrue(cookie.contains("Path=/"))
            assertTrue(cookie.startsWith("pi_session="))
        }
    }

    @Test
    fun `only one approval is pending at a time`() {
        val held = submitApproval()
        awaitPending()
        post(
            "/api/approve/request",
            body = "{}",
            contentType = "application/json",
            headers = mapOf("Origin" to origin),
        ).use {
            assertEquals(429, it.code)
        }
        assertTrue(gate.approve(gate.pending.value!!.id))
        assertTrue(held.latch.await(5, TimeUnit.SECONDS))
        held.response.get()!!.close()
    }

    @Test
    fun `an approval without a json content type is refused`() {
        post(
            "/api/approve/request",
            body = "x",
            contentType = "text/plain",
            headers = mapOf("Origin" to origin),
        ).use {
            assertEquals(415, it.code)
        }
    }

    @Test
    fun `a session is refused when it is used from a different address`() {
        peerResolver = { _, _ -> "10.0.0.1" }
        val session = connect()
        peerResolver = { _, _ -> "10.0.0.2" }
        get("/api/session", cookie = session.cookie).use { assertEquals(401, it.code) }
        get("/api/state", cookie = session.cookie).use { assertEquals(401, it.code) }
    }

    // ---- the mutation defence ---------------------------------------------

    @Test
    fun `a mutation without or with a wrong csrf token is refused`() {
        val session = connect()

        post("/api/logout", "{}", "application/json", mapOf("Origin" to origin), session.cookie).use {
            assertEquals(403, it.code)
        }
        post(
            "/api/logout",
            "{}",
            "application/json",
            mapOf("Origin" to origin, "X-CSRF-Token" to "not-the-token"),
            session.cookie,
        ).use {
            assertEquals(403, it.code)
        }

        post(
            "/api/logout",
            "{}",
            "application/json",
            mapOf("Origin" to origin, "X-CSRF-Token" to session.csrf),
            session.cookie,
        ).use {
            assertEquals(200, it.code)
        }
        get("/api/session", cookie = session.cookie).use { assertEquals(401, it.code) }
    }

    // ---- the one proxied route --------------------------------------------

    @Test
    fun `the proxy reduces the bridge state and never leaks the token`() {
        val session = connect()
        get("/api/state", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(200, it.code)
            val body = it.body!!.string()
            assertTrue("keeps the session id", body.contains("\"sessionId\""))
            assertTrue("keeps the model", body.contains("\"model\""))
            assertFalse("drops the session file path", body.contains("sessionFile"))
            assertFalse("drops cwd", body.contains("\"cwd\""))
            assertFalse("drops pid", body.contains("\"pid\""))
            assertFalse("drops bound address", body.contains("boundPort"))
            assertFalse("drops the host", body.contains("\"host\""))
            assertFalse("no bridge token", body.contains(FakeUpstream.TOKEN_MARKER))
        }
        assertEquals(1, upstream.calls.size)
        assertTrue("the upstream label names the web session", upstream.calls[0].startsWith("web:"))
    }

    @Test
    fun `a bridge that is not up becomes a bad gateway`() {
        val session = connect()
        upstream.status = 500
        get("/api/state", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(502, it.code)
        }
    }

    // ---- the core loop routes (stage 3) -----------------------------------

    @Test
    fun `every core loop route needs a session`() {
        get("/api/history").use { assertEquals(401, it.code) }
        get("/api/question").use { assertEquals(401, it.code) }
        get("/api/events").use { assertEquals(401, it.code) }
        post("/api/prompt", "{}", "application/json", mapOf("Origin" to origin)).use { assertEquals(401, it.code) }
        post("/api/answer", "{}", "application/json", mapOf("Origin" to origin)).use { assertEquals(401, it.code) }
    }

    @Test
    fun `the history proxy keeps text and drops paths and tool output`() {
        val session = connect()
        get("/api/history?limit=10", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(200, it.code)
            val body = it.body!!.string()
            assertTrue("keeps the prompt", body.contains("hello"))
            assertTrue("keeps the answer", body.contains("hi there"))
            assertFalse("drops the session file", body.contains("sessionFile"))
            assertFalse("drops tool arguments", body.contains("cat /secret"))
            assertFalse("drops tool results", body.contains("secret output"))
            assertFalse("no bridge token", body.contains(FakeUpstream.TOKEN_MARKER))
        }
    }

    @Test
    fun `the question proxy reduces to the fields the card draws`() {
        val session = connect()
        get("/api/question", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(200, it.code)
            val body = it.body!!.string()
            assertTrue("keeps the question id", body.contains("q-1"))
            assertTrue("keeps the options", body.contains("Green"))
            assertTrue("keeps the option value", body.contains("\"value\":\"g\""))
            assertFalse("drops the updated time", body.contains("updatedAt"))
            assertFalse("no bridge token", body.contains(FakeUpstream.TOKEN_MARKER))
        }
    }

    @Test
    fun `a prompt without or with a wrong csrf token is refused`() {
        val session = connect()
        post("/api/prompt", """{"text":"hi"}""", "application/json", mapOf("Origin" to origin), session.cookie).use {
            assertEquals(403, it.code)
        }
        post(
            "/api/prompt",
            """{"text":"hi"}""",
            "application/json",
            mapOf("Origin" to origin, "X-CSRF-Token" to "not-the-token"),
            session.cookie,
        ).use {
            assertEquals(403, it.code)
        }
        assertTrue("a refused prompt never reaches the bridge", upstream.bodies.isEmpty())
    }

    @Test
    fun `a prompt with the csrf token reaches the bridge and does not leak the token`() {
        val session = connect()
        post(
            "/api/prompt",
            """{"text":"hello bridge"}""",
            "application/json",
            mapOf("Origin" to origin, "X-CSRF-Token" to session.csrf),
            session.cookie,
        ).use {
            assertEquals(200, it.code)
            assertFalse("no bridge token", it.body!!.string().contains(FakeUpstream.TOKEN_MARKER))
        }
        assertEquals(1, upstream.bodies.size)
        assertTrue("the prompt text goes upstream", upstream.bodies[0].contains("hello bridge"))
        assertTrue("the upstream label names the web session", upstream.calls.last().startsWith("web:"))
    }

    @Test
    fun `an answer without or with a wrong csrf token is refused`() {
        val session = connect()
        post(
            "/api/answer",
            """{"questionId":"q1","value":"g"}""",
            "application/json",
            mapOf("Origin" to origin),
            session.cookie,
        ).use {
            assertEquals(403, it.code)
        }
        post(
            "/api/answer",
            """{"questionId":"q1","value":"g"}""",
            "application/json",
            mapOf("Origin" to origin, "X-CSRF-Token" to "not-the-token"),
            session.cookie,
        ).use {
            assertEquals(403, it.code)
        }
        assertTrue("a refused answer never reaches the bridge", upstream.bodies.isEmpty())
    }

    @Test
    fun `an answer with the csrf token reaches the bridge`() {
        val session = connect()
        post(
            "/api/answer",
            """{"questionId":"q1","value":"g","custom":false,"cancel":false}""",
            "application/json",
            mapOf("Origin" to origin, "X-CSRF-Token" to session.csrf),
            session.cookie,
        ).use {
            assertEquals(200, it.code)
            assertFalse("no bridge token", it.body!!.string().contains(FakeUpstream.TOKEN_MARKER))
        }
        assertTrue("the choice goes upstream", upstream.bodies[0].contains("\"value\":\"g\""))
    }

    @Test
    fun `no new route leaks the bridge token`() {
        val session = connect()
        val responses = mutableListOf<String>()
        get("/api/state", cookie = session.cookie, headers = mapOf("Origin" to origin)).use { responses.add(it.body!!.string()) }
        get("/api/history", cookie = session.cookie, headers = mapOf("Origin" to origin)).use { responses.add(it.body!!.string()) }
        get("/api/files", cookie = session.cookie, headers = mapOf("Origin" to origin)).use { responses.add(it.body!!.string()) }
        get("/api/pins", cookie = session.cookie, headers = mapOf("Origin" to origin)).use { responses.add(it.body!!.string()) }
        get("/api/question", cookie = session.cookie, headers = mapOf("Origin" to origin)).use { responses.add(it.body!!.string()) }
        get("/api/events", cookie = session.cookie, headers = mapOf("Origin" to origin)).use { responses.add(it.body!!.string()) }
        get("/api/files/t-1.mp3", cookie = session.cookie, headers = mapOf("Origin" to origin)).use { responses.add(it.body!!.string()) }
        post(
            "/api/prompt",
            """{"text":"hi"}""",
            "application/json",
            mapOf("Origin" to origin, "X-CSRF-Token" to session.csrf),
            session.cookie,
        ).use { responses.add(it.body!!.string()) }
        post(
            "/api/answer",
            """{"questionId":"q1","value":"g"}""",
            "application/json",
            mapOf("Origin" to origin, "X-CSRF-Token" to session.csrf),
            session.cookie,
        ).use { responses.add(it.body!!.string()) }
        responses.forEachIndexed { index, body ->
            assertFalse("response $index leaked the bridge token", body.contains(FakeUpstream.TOKEN_MARKER))
        }
        assertTrue("every upstream call is labelled", upstream.calls.all { it.startsWith("web:") })
    }

    @Test
    fun `the event stream forwards only the reduced message and question frames`() {
        val session = connect()
        get("/api/events", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(200, it.code)
            val body = it.body!!.string()
            assertTrue("message_start", body.contains("\"type\":\"message_start\""))
            assertTrue("assistant text", body.contains("\"text\":\"Hello\""))
            assertTrue("tool call flag", body.contains("\"toolCall\":true"))
            assertTrue("text delta", body.contains("\"delta\":\" there\""))
            assertTrue("question frame", body.contains("\"type\":\"question\""))
            assertTrue("option value", body.contains("\"value\":\"g\""))
            assertTrue("talk frame", body.contains("\"type\":\"talk\""))
            assertTrue("talk state", body.contains("\"state\":\"ready\""))
            assertTrue("talk removed frame", body.contains("\"type\":\"talk_removed\""))
            assertFalse("the talk created time is dropped", body.contains("createdAt"))
            assertFalse("state is dropped", body.contains("sessionFile"))
            assertFalse("pid is dropped", body.contains("\"pid\""))
            assertFalse("tool execution is dropped", body.contains("tool_execution"))
            assertFalse("tool arguments are dropped", body.contains("cmd"))
            assertFalse("tool results are dropped", body.contains(FakeUpstream.TOKEN_MARKER))
            assertFalse("thinking deltas are dropped", body.contains("thinking"))
        }
    }

    @Test
    fun `a bridge that is not up on the stream becomes a bad gateway`() {
        val session = connect()
        upstream.eventsStatus = 503
        get("/api/events", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(502, it.code)
        }
    }

    @Test
    fun `a live event stream holds the server open`() {
        val session = connect()
        upstream.eventsHold = CountDownLatch(1)
        val response = get("/api/events", cookie = session.cookie, headers = mapOf("Origin" to origin))
        try {
            assertEquals(200, response.code)
            val source = response.body!!.source()
            // Read until the first relayed frame, so the relay is inside the read
            // loop, then check the server still counts the stream as in flight.
            var line = source.readUtf8Line()
            while (line != null && !line.startsWith("data:")) line = source.readUtf8Line()
            assertTrue("a data frame arrived", line != null)
            assertFalse("a live stream is not idle", server.isIdle(0, System.currentTimeMillis()))
        } finally {
            upstream.eventsHold?.countDown()
            response.close()
        }
    }

    // ---- the files and pins screens (stage 4) -----------------------------

    @Test
    fun `every files and pins route needs a session`() {
        get("/api/files").use { assertEquals(401, it.code) }
        get("/api/pins").use { assertEquals(401, it.code) }
        post("/api/pins/rename", "{}", "application/json", mapOf("Origin" to origin)).use { assertEquals(401, it.code) }
        post("/api/pins/delete", "{}", "application/json", mapOf("Origin" to origin)).use { assertEquals(401, it.code) }
    }

    @Test
    fun `the files proxy keeps the row fields and drops the private ones`() {
        val session = connect()
        get("/api/files", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(200, it.code)
            val body = it.body!!.string()
            assertTrue("keeps the title", body.contains("A talk"))
            assertTrue("keeps the render state", body.contains("\"state\":\"rendering\""))
            assertTrue("keeps the failed row error", body.contains("Kokoro could not start"))
            assertTrue("keeps the duration", body.contains("\"durationSeconds\""))
            assertFalse("drops the file extension", body.contains("\"ext\""))
            assertFalse("drops the created time", body.contains("createdAt"))
            assertFalse("no bridge token", body.contains(FakeUpstream.TOKEN_MARKER))
        }
    }

    @Test
    fun `a bridge that is down on files is a bad gateway, not an empty library`() {
        val session = connect()
        upstream.status = 500
        get("/api/files", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(502, it.code)
            assertTrue("the failure is named", it.body!!.string().contains("error"))
        }
    }

    @Test
    fun `the pins list comes from the phone store`() {
        val session = connect()
        get("/api/pins", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(200, it.code)
            val body = it.body!!.string()
            assertTrue("keeps the pin title", body.contains("Old title"))
            assertTrue("keeps the prompt", body.contains("what is it"))
            assertTrue("keeps the answer", body.contains("the answer"))
            assertFalse("no bridge token", body.contains(FakeUpstream.TOKEN_MARKER))
        }
    }

    @Test
    fun `a pin rename and delete need the csrf token and change the phone store`() {
        val session = connect()
        post(
            "/api/pins/rename",
            """{"id":"p-1","title":"New title"}""",
            "application/json",
            mapOf("Origin" to origin),
            session.cookie,
        ).use { assertEquals(403, it.code) }
        assertEquals("a refused rename never reaches the store", "Old title", pinStore.title)

        post(
            "/api/pins/rename",
            """{"id":"p-1","title":"New title"}""",
            "application/json",
            mapOf("Origin" to origin, "X-CSRF-Token" to session.csrf),
            session.cookie,
        ).use { assertEquals(200, it.code) }
        assertEquals("New title", pinStore.title)

        post(
            "/api/pins/delete",
            """{"id":"p-1"}""",
            "application/json",
            mapOf("Origin" to origin, "X-CSRF-Token" to session.csrf),
            session.cookie,
        ).use { assertEquals(200, it.code) }
        assertTrue("the pin is gone from the store", pinStore.pins.isEmpty())
    }

    // ---- audio playback (stage 5) -----------------------------------------

    @Test
    fun `the audio route needs a session`() {
        get("/api/files/t-1.mp3").use { assertEquals(401, it.code) }
    }

    @Test
    fun `a range request is forwarded and answered as a 206 with content range`() {
        val session = connect()
        get(
            "/api/files/t-1.mp3",
            cookie = session.cookie,
            headers = mapOf("Origin" to origin, "Range" to "bytes=10-19"),
        ).use {
            assertEquals(206, it.code)
            assertEquals("the range comes back on the response", "bytes 10-19/100", it.header("Content-Range"))
            assertEquals("a seeker is told the file is seekable", "bytes", it.header("Accept-Ranges"))
            assertEquals("the slice length is the content length", "10", it.header("Content-Length"))
            assertEquals("always the relay's own audio type", "audio/mpeg", it.header("Content-Type"))
            val body = it.body!!.bytes()
            assertEquals(10, body.size)
            assertEquals(10.toByte(), body[0])
            assertEquals(19.toByte(), body[9])
        }
        assertEquals("the browser's range is what goes upstream", "bytes=10-19", upstream.fileRanges.single())
        assertEquals("t-1.mp3", upstream.fileNames.single())
        assertTrue("the upstream label names the web session", upstream.calls.single().startsWith("web:"))
    }

    @Test
    fun `a full file request is answered as a 200 with accept ranges`() {
        val session = connect()
        get("/api/files/t-1.mp3", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(200, it.code)
            assertEquals("bytes", it.header("Accept-Ranges"))
            assertEquals("100", it.header("Content-Length"))
            assertEquals(100, it.body!!.bytes().size)
            assertNull("a full body carries no content range", it.header("Content-Range"))
        }
        assertNull("no range is invented when the browser sent none", upstream.fileRanges.single())
    }

    @Test
    fun `the audio route serves audio only and never the bridge content type`() {
        val session = connect()
        // A non-audio name is refused before the bridge is asked, so a document
        // can never be served on the session origin (F1).
        get("/api/files/evil.html", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(404, it.code)
        }
        assertTrue("a non-audio name never reaches the bridge", upstream.fileNames.isEmpty())
        // An audio name is served with the relay's own audio type. The fake puts
        // the token in the upstream content type, so proving the response type is
        // audio/mpeg is also what proves no upstream header is copied through.
        get("/api/files/t-1.mp3", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals("audio/mpeg", it.header("Content-Type"))
            assertFalse("no upstream header leaks the bridge token", it.headers.toString().contains(FakeUpstream.TOKEN_MARKER))
        }
    }

    @Test
    fun `a bridge that will not serve the file is a bad gateway`() {
        val session = connect()
        upstream.fileStatus = 500
        get("/api/files/t-1.mp3", cookie = session.cookie, headers = mapOf("Origin" to origin)).use {
            assertEquals(502, it.code)
        }
    }

    @Test
    fun `a playing audio file holds the server open and goes idle when it ends`() {
        val session = connect()
        upstream.fileHold = CountDownLatch(1)
        val response = get("/api/files/t-1.mp3", cookie = session.cookie, headers = mapOf("Origin" to origin))
        try {
            assertEquals(200, response.code)
            // The body is still being produced, so the server counts the response
            // as in flight, the same way a live event stream does. This is what
            // keeps the server up while a talk plays rather than stopping it under
            // the player, and it is checked before the body is consumed.
            assertFalse("an open audio response is not idle", server.isIdle(0, System.currentTimeMillis()))
        } finally {
            upstream.fileHold?.countDown()
            response.close()
        }
        // With the request gone the server goes idle after the grace window, so
        // closing the browser lets it stop rather than lingering forever.
        var idle = false
        val deadline = System.currentTimeMillis() + 5_000
        while (!idle && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            idle = server.isIdle(0, System.currentTimeMillis())
        }
        assertTrue("the server goes idle once the audio request has ended", idle)
    }

    @Test
    fun `a large file streams in chunks rather than waiting for the whole body`() {
        val session = connect()
        upstream.fileHold = CountDownLatch(1)
        // The fake produces one 64 KiB chunk and then stalls, so the client can
        // only read that chunk if the relay flushes as it goes. A relay that let
        // the engine buffer the body would make this read block until the stall
        // was released, which is the difference between a seek and a re-download.
        upstream.fileStallAt = 64 * 1024
        val response = get("/api/files/big.mp3", cookie = session.cookie, headers = mapOf("Origin" to origin))
        try {
            assertEquals(200, response.code)
            // The fake stalls for 5 seconds, so a relay that did not flush would
            // make this read wait until then. A flushed chunk arrives at once; the
            // generous bound still separates the two.
            val startedAt = System.nanoTime()
            val first = response.body!!.source().readByteArray(64 * 1024)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertEquals("the first chunk arrives before the body ends", 64 * 1024, first.size)
            assertTrue("the chunk was not held to the end of the body (took ${elapsedMs}ms)", elapsedMs < 3_000)
            assertFalse("the response is still open mid-body", server.isIdle(0, System.currentTimeMillis()))
        } finally {
            upstream.fileHold?.countDown()
            response.close()
        }
    }

    // ---- headers -----------------------------------------------------------

    @Test
    fun `the diagnostics route reports the listener state without a session`() {
        get("/api/diagnostics").use {
            assertEquals(200, it.code)
            val body = it.body!!.string()
            assertTrue("the backend port is named: $body", body.contains("\"backendPort\":$port"))
            assertTrue("the certificate state is named", body.contains("\"certificateLoaded\":false"))
            assertTrue("the served address is named", body.contains(authority))
            assertTrue("the last close reason is named", body.contains("\"lastCloseReason\""))
            assertFalse("no bridge token", body.contains(FakeUpstream.TOKEN_MARKER))
        }
    }

    @Test
    fun `the diagnostics route still refuses a foreign host`() {
        get("/api/diagnostics", headers = mapOf("Host" to "evil.example")).use {
            assertEquals(403, it.code)
        }
    }

    @Test
    fun `every response carries the hardening headers and no CORS grant`() {
        get("/").use {
            assertEquals(200, it.code)
            assertEquals("nosniff", it.header("X-Content-Type-Options"))
            assertEquals("same-origin", it.header("Cross-Origin-Resource-Policy"))
            assertTrue(it.header("Content-Security-Policy")!!.contains("default-src 'self'"))
            assertNull(it.header("Access-Control-Allow-Origin"))
        }
    }

    // ---- helpers -----------------------------------------------------------

    private class Held(
        val latch: CountDownLatch,
        val response: AtomicReference<Response?>,
    )

    private data class Connected(val cookie: String, val csrf: String)

    /** Starts a held approval request in the background and returns its future. */
    private fun submitApproval(): Held {
        val latch = CountDownLatch(1)
        val response = AtomicReference<Response?>()
        val request = Request.Builder()
            .url("$base/api/approve/request")
            .header("Origin", origin)
            .post("{}".toRequestBody(jsonMedia))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = latch.countDown()
            override fun onResponse(call: Call, result: Response) {
                response.set(result)
                latch.countDown()
            }
        })
        return Held(latch, response)
    }

    private fun awaitPending(): WebApproval =
        runBlocking { withTimeout(5_000) { gate.pending.first { it != null } } }!!

    /** Holds a real approval request, allows it, and keeps the minted session. */
    private fun connect(): Connected {
        val held = submitApproval()
        assertTrue("the gate could not allow the request", gate.approve(awaitPending().id))
        assertTrue(held.latch.await(5, TimeUnit.SECONDS))
        held.response.get()!!.use { response ->
            assertEquals(200, response.code)
            val cookie = response.header("Set-Cookie")!!.substringBefore(";")
            val csrf = (parseJson(response.body!!.string()) as JsonValue.Obj).stringOrNull("csrf")!!
            return Connected(cookie, csrf)
        }
    }

    private fun get(path: String, cookie: String? = null, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(base + path).get()
        if (cookie != null) builder.header("Cookie", cookie)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.newCall(builder.build()).execute()
    }

    private fun post(
        path: String,
        body: String,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        cookie: String? = null,
    ): Response {
        val builder = Request.Builder().url(base + path).post(body.toRequestBody(contentType.toMediaType()))
        if (cookie != null) builder.header("Cookie", cookie)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.newCall(builder.build()).execute()
    }

    /** A raw request over a socket, for the cases OkHttp will not construct. */
    private fun raw(request: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readText()
        }

    private fun statusOf(response: String): String =
        response.lineSequence().firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()

    /** A refusal is 400 or 403, not a 401 the route would give an unauthenticated caller. */
    private fun assertRefused(response: String) {
        val status = statusOf(response)
        assertTrue("expected a refusal, got '$status' in: ${response.take(120)}", status == "400" || status == "403")
    }

    private class FakeUpstream : WebUpstream {
        var status = 200
        var eventsStatus = 200
        var fileStatus = 200

        /** When set, the event stream blocks after its frames until this opens. */
        var eventsHold: CountDownLatch? = null

        /** When set, the file body blocks until this opens, at [fileStallAt]. */
        var fileHold: CountDownLatch? = null

        /** The byte offset at which a held file body stalls; 0 stalls immediately. */
        var fileStallAt = 0
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val fileNames = mutableListOf<String>()
        val fileRanges = mutableListOf<String?>()

        override suspend fun state(clientLabel: String): UpstreamResult {
            calls.add(clientLabel)
            if (status != 200) return UpstreamResult(status, """{"error":"boom"}""")
            return UpstreamResult(200, STATE_BODY)
        }

        override suspend fun history(clientLabel: String, limit: Int): UpstreamResult {
            calls.add(clientLabel)
            if (status != 200) return UpstreamResult(status, """{"error":"boom"}""")
            return UpstreamResult(200, HISTORY_BODY)
        }

        override suspend fun files(clientLabel: String): UpstreamResult {
            calls.add(clientLabel)
            if (status != 200) return UpstreamResult(status, """{"error":"boom"}""")
            return UpstreamResult(200, FILES_BODY)
        }

        override suspend fun question(clientLabel: String): UpstreamResult {
            calls.add(clientLabel)
            if (status != 200) return UpstreamResult(status, """{"error":"boom"}""")
            return UpstreamResult(200, QUESTION_BODY)
        }

        override suspend fun file(clientLabel: String, name: String, range: String?): WebUpstreamFile {
            calls.add(clientLabel)
            fileNames.add(name)
            fileRanges.add(range)
            if (fileStatus != 200) return FakeFile(fileStatus, ByteArray(0), null, 0, null, fileStallAt)
            val bytes = if (name == LARGE_NAME) LARGE_FILE_BYTES else FILE_BYTES
            if (range == null) {
                return FakeFile(200, bytes, null, bytes.size.toLong(), fileHold, fileStallAt)
            }
            val spec = range.removePrefix("bytes=").split("-")
            val start = spec.getOrNull(0)?.toIntOrNull() ?: 0
            val end = spec.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: (bytes.size - 1)
            val slice = bytes.copyOfRange(start, end + 1)
            return FakeFile(206, slice, "bytes $start-$end/${bytes.size}", slice.size.toLong(), fileHold, fileStallAt)
        }

        override suspend fun prompt(clientLabel: String, body: String): UpstreamResult {
            calls.add(clientLabel)
            bodies.add(body)
            if (status != 200) return UpstreamResult(status, """{"error":"boom"}""")
            return UpstreamResult(200, """{"ok":true,"busy":false,"deliverAs":"immediate","token":"$TOKEN_MARKER"}""")
        }

        override suspend fun answer(clientLabel: String, body: String): UpstreamResult {
            calls.add(clientLabel)
            bodies.add(body)
            if (status != 200) return UpstreamResult(status, """{"error":"boom"}""")
            return UpstreamResult(200, """{"ok":true,"token":"$TOKEN_MARKER"}""")
        }

        override suspend fun events(clientLabel: String): WebUpstreamStream {
            calls.add(clientLabel)
            if (eventsStatus != 200) return FakeStream(eventsStatus, emptyList(), null)
            return FakeStream(200, EVENT_LINES, eventsHold)
        }

        private class FakeStream(
            override val status: Int,
            private val lines: List<String>,
            private val hold: CountDownLatch?,
        ) : WebUpstreamStream {
            private var index = 0

            override fun readLine(): String? {
                if (index < lines.size) return lines[index++]
                hold?.await(5, TimeUnit.SECONDS)
                return null
            }

            override fun close() {
                hold?.countDown()
            }
        }

        /**
         * A file body that streams a fixed slice and, when [hold] is set, blocks
         * at the end so a test can observe the response while it is open. The
         * content type deliberately carries the token so a test proves the relay
         * chooses its own audio type instead of copying the upstream one.
         */
        private class FakeFile(
            override val status: Int,
            private val bytes: ByteArray,
            override val contentRange: String?,
            override val contentLength: Long,
            private val hold: CountDownLatch?,
            private val stallAt: Int,
        ) : WebUpstreamFile {
            override val contentType: String = "text/html; tok=$TOKEN_MARKER"
            private var index = 0

            override fun read(buffer: ByteArray): Int {
                // A held body stalls at [stallAt]: at 0 it stalls before the first
                // byte, and at a chunk boundary it lets the first chunk through
                // first, which is what proves the relay flushes as it goes.
                if (hold != null && index >= stallAt) hold.await(5, TimeUnit.SECONDS)
                if (index >= bytes.size) return -1
                val count = minOf(buffer.size, bytes.size - index)
                System.arraycopy(bytes, index, buffer, 0, count)
                index += count
                return count
            }

            override fun close() {
                hold?.countDown()
            }
        }

        companion object {
            const val TOKEN_MARKER = "bridge-secret-token"

            /** 100 deterministic audio bytes, so a range slice is checkable. */
            val FILE_BYTES = ByteArray(100) { it.toByte() }

            /** A name the fake serves a body larger than one chunk for. */
            const val LARGE_NAME = "big.mp3"

            /** 256 KiB, several chunks, so incremental delivery is observable. */
            val LARGE_FILE_BYTES = ByteArray(256 * 1024) { it.toByte() }

            val STATE_BODY =
                """{"sessionId":"s-1","sessionName":"work","sessionFile":"/secret/session.json",""" +
                    """"cwd":"/home/pi","mode":"normal","model":{"provider":"p","id":"m"},""" +
                    """"thinkingLevel":"low","idle":true,"pendingMessages":false,"question":null,""" +
                    """"pid":4242,"host":"laptop","port":8787,"boundHost":"100.1.2.3","boundPort":8787,""" +
                    """"serverTime":"2026-01-01T00:00:00Z","token":"$TOKEN_MARKER"}"""

            val HISTORY_BODY =
                """{"sessionFile":"/secret/$TOKEN_MARKER","sessionId":"s-1","messages":[""" +
                    """{"type":"message","id":"m1","message":{"role":"user","content":"hello"}},""" +
                    """{"type":"message","id":"m2","message":{"role":"assistant","content":[""" +
                    """{"type":"text","text":"hi there"},{"type":"toolCall","name":"bash","args":{"cmd":"cat /secret"}}]}},""" +
                    """{"type":"message","id":"m3","message":{"role":"toolResult","content":[{"type":"text","text":"secret output"}]}}]}"""

            val QUESTION_BODY =
                """{"pending":{"id":"q-1","tool":"question","title":"Pick a colour","multiple":false,""" +
                    """"updatedAt":"2026-01-01T00:00:00Z","secret":"$TOKEN_MARKER","questions":[""" +
                    """{"id":"q1","label":"Colour","prompt":"Which?","allowOther":true,"options":[""" +
                    """{"value":"g","label":"Green","description":"like grass"},{"value":"r","label":"Red","description":null}],""" +
                    """"answer":null}]}}"""

            // Two rows: a talk still rendering, and a failed file with an error.
            // `ext` and `createdAt` carry the marker so the reduction that drops
            // them is what keeps the token out of the response.
            val FILES_BODY =
                """{"files":[""" +
                    """{"id":"t-1","name":"t-1.mp3","title":"A talk","kind":"audio","ext":"$TOKEN_MARKER","state":"rendering","bytes":0,"createdAt":"$TOKEN_MARKER","durationSeconds":0,"durationEstimated":false,"voice":null,"error":null},""" +
                    """{"id":"t-2","name":"t-2.mp3","title":"A failed talk","kind":"audio","ext":"$TOKEN_MARKER","state":"failed","bytes":10,"createdAt":"$TOKEN_MARKER","durationSeconds":12.5,"durationEstimated":true,"voice":"af","error":"Kokoro could not start: boom"}]}"""

            val EVENT_LINES = listOf(
                "retry: 3000",
                "",
                """data: {"seq":1,"at":"t","type":"state","data":{"sessionFile":"/secret","pid":1}}""",
                "",
                """data: {"seq":2,"at":"t","type":"tool_execution_start","data":{"toolName":"bash","args":{"cmd":"cat /secret"}}}""",
                "",
                """data: {"seq":3,"at":"t","type":"message_start","data":{"message":{"role":"assistant","content":[{"type":"text","text":"Hello"},{"type":"toolCall","name":"bash","args":{"cmd":"cat /secret"}}]}}}""",
                "",
                """data: {"seq":4,"at":"t","type":"message_update","data":{"assistantMessageEvent":{"type":"text_delta","delta":" there"}}}""",
                "",
                """data: {"seq":5,"at":"t","type":"message_update","data":{"assistantMessageEvent":{"type":"thinking","delta":"hmm"}}}""",
                "",
                """data: {"seq":6,"at":"t","type":"message_end","data":{"message":{"role":"assistant","content":[{"type":"text","text":"Hello there"}]}}}""",
                "",
                """data: {"seq":7,"at":"t","type":"question","data":{"pending":{"id":"q-1","tool":"question","title":"Pick","multiple":false,"questions":[{"id":"q1","label":"Colour","prompt":"Which?","options":[{"value":"g","label":"Green"}],"answer":null}]}}}""",
                "",
                """data: {"seq":8,"at":"t","type":"talk","data":{"id":"t-1","title":"A talk","state":"ready","createdAt":"$TOKEN_MARKER"}}""",
                "",
                """data: {"seq":9,"at":"t","type":"talk_removed","data":{"id":"t-1"}}""",
                "",
                """data: {"seq":10,"at":"t","type":"tool_execution_end","data":{"result":"$TOKEN_MARKER"}}""",
                "",
            )
        }
    }

    /** An in-memory stand-in for the phone's PinsStore, so the relay's pins
     * surface is tested without Android. */
    private class FakePins : WebPins {
        val pins = mutableListOf(WebPin(id = "p-1", title = "Old title", prompt = "what is it", answer = "the answer"))

        val title: String? get() = pins.firstOrNull()?.title

        override suspend fun list(): List<WebPin> = pins.toList()

        override suspend fun rename(id: String, title: String): Boolean {
            val index = pins.indexOfFirst { it.id == id }
            if (index < 0 || title.isBlank()) return false
            pins[index] = pins[index].copy(title = title)
            return true
        }

        override suspend fun delete(id: String): Boolean = pins.removeAll { it.id == id }
    }
}
