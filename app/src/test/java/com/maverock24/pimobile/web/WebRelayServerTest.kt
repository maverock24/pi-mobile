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

    @Before
    fun setUp() {
        gate = WebApprovalGate(ttlMs = 5_000)
        upstream = FakeUpstream()
        port = ServerSocket(0).use { it.localPort }
        server = WebRelayServer(
            loadAsset = { "<html></html>" },
            expectedAuthority = authority,
            expectedOrigin = origin,
            upstream = upstream,
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

    // ---- headers -----------------------------------------------------------

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
        val calls = mutableListOf<String>()
        override suspend fun state(clientLabel: String): UpstreamResult {
            calls.add(clientLabel)
            if (status != 200) return UpstreamResult(status, """{"error":"boom"}""")
            return UpstreamResult(
                200,
                """{"sessionId":"s-1","sessionName":"work","sessionFile":"/secret/session.json",""" +
                    """"cwd":"/home/pi","mode":"normal","model":{"provider":"p","id":"m"},""" +
                    """"thinkingLevel":"low","idle":true,"pendingMessages":false,"question":null,""" +
                    """"pid":4242,"host":"laptop","port":8787,"boundHost":"100.1.2.3","boundPort":8787,""" +
                    """"serverTime":"2026-01-01T00:00:00Z","token":"$TOKEN_MARKER"}""",
            )
        }

        companion object {
            const val TOKEN_MARKER = "bridge-secret-token"
        }
    }
}
