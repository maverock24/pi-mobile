package com.maverock24.pimobile.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listener state that a silent close is now recorded in, and the one line the
 * Settings screen shows for it. The store is a plain object and the summary is a
 * plain function, so both are checked without a server.
 */
class WebDiagnosticsTest {

    @Test
    fun `update records the ports, the counters and the last reason`() {
        WebDiagnostics.reset()
        WebDiagnostics.update {
            it.copy(certificateLoaded = true, listenPort = 8788, backendPort = 41_000, boundAddress = "0.0.0.0")
        }
        WebDiagnostics.update { it.copy(acceptedConnections = it.acceptedConnections + 1) }
        WebDiagnostics.update {
            it.copy(handshakeFailures = it.handshakeFailures + 1, lastCloseReason = "boom")
        }
        val state = WebDiagnostics.value
        assertEquals(true, state.certificateLoaded)
        assertEquals(8788, state.listenPort)
        assertEquals(41_000, state.backendPort)
        assertEquals(1L, state.acceptedConnections)
        assertEquals(1L, state.handshakeFailures)
        assertEquals("boom", state.lastCloseReason)
        WebDiagnostics.reset()
        assertEquals("a reset forgets the previous run", ListenerDiagnostics(), WebDiagnostics.value)
    }

    @Test
    fun `the summary names the backend and the bind address`() {
        val text = diagnosticsSummary(
            ListenerDiagnostics(
                certificateLoaded = true,
                listenPort = 8788,
                backendPort = 41_000,
                boundAddress = "0.0.0.0",
            ),
        )
        assertTrue(text, text.contains("8788"))
        assertTrue("the backend port is named", text.contains("127.0.0.1:41000"))
        assertTrue("the bind address is named", text.contains("0.0.0.0"))
    }

    @Test
    fun `the summary says when the certificate did not load`() {
        val text = diagnosticsSummary(ListenerDiagnostics())
        assertTrue(text, text.contains("did not load"))
    }

    @Test
    fun `the summary reports a failed handshake with the reason`() {
        val text = diagnosticsSummary(
            ListenerDiagnostics(
                certificateLoaded = true,
                listenPort = 8788,
                backendPort = 41_000,
                boundAddress = "0.0.0.0",
                acceptedConnections = 2,
                handshakeFailures = 1,
                lastCloseReason = "10.0.0.2: TLS handshake failed (no cipher suites in common)",
            ),
        )
        assertTrue(text, text.contains("TLS handshakes failed"))
        assertTrue("the reason is shown", text.contains("no cipher suites in common"))
    }

    @Test
    fun `the headline stays short and names the failing state`() {
        val failed = diagnosticsHeadline(
            ListenerDiagnostics(
                certificateLoaded = true,
                listenPort = 8788,
                lastCloseReason = "10.0.0.2: TLS handshake failed (no cipher suites in common)",
            ),
        )
        assertEquals("The listener is up and the last connection failed.", failed)
        assertFalse("the exception text stays folded", failed.contains("cipher"))
        assertEquals("The listener did not start: no certificate.", diagnosticsHeadline(ListenerDiagnostics()))
        assertEquals(
            "The listener is up on port 8788.",
            diagnosticsHeadline(ListenerDiagnostics(certificateLoaded = true, listenPort = 8788)),
        )
    }
}
