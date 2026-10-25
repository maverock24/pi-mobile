package com.maverock24.pimobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * The split between the line a human reads on a phone and the detail a debugging
 * round needs. Both halves are plain strings, so they are checked without a
 * device: the summary has to stay short and free of exception class names, and
 * the detail has to keep everything the old inline string used to carry.
 */
class ProblemsTest {

    private val url = "http://laptop.tailnet.ts.net:8787"

    @Test
    fun `the summary is one short line with no exception class in it`() {
        for (error in everyKindOfFailure()) {
            val summary = Problems.summary(error)
            assertTrue("too long for a phone: $summary", summary.length <= 80)
            assertFalse("names a class: $summary", summary.contains("Exception"))
            assertFalse("names a class: $summary", summary.contains("java."))
        }
    }

    @Test
    fun `a missing name says Tailscale may be off and offers the tunnel action`() {
        val problem = Problems.of(UnknownHostException("Unable to resolve host \"laptop\": No address associated"), url)
        assertEquals("Cannot find the laptop. Tailscale may be off.", problem.summary)
        assertTrue("the tailnet action is offered", problem.tailnet)
    }

    @Test
    fun `a refused connection blames the bridge rather than the tunnel`() {
        val problem = Problems.of(ConnectException("Failed to connect to laptop/100.1.2.3:8787"), url)
        assertTrue(problem.summary, problem.summary.contains("not answering"))
        assertFalse("a refused port means the link works", problem.tailnet)
    }

    @Test
    fun `a handshake message stays whole in the detail and out of the summary`() {
        val raw = "Trust anchor for certification path not found: " +
            "java.security.cert.CertPathValidatorException: trust anchor for certification path not found."
        val problem = Problems.of(SSLHandshakeException(raw), url, subject = "web access")
        assertEquals("The secure connection failed. Check the address and the certificate.", problem.summary)
        assertFalse(problem.summary, problem.summary.contains("CertPathValidator"))
        assertTrue("the raw message is kept", problem.detail.contains(raw))
        assertTrue("the class is kept", problem.detail.contains("SSLHandshakeException"))
        assertTrue("the address is kept", problem.detail.contains(url))
        assertTrue("the subject is kept", problem.detail.startsWith("web access:"))
    }

    @Test
    fun `a bridge refusal names the status without pasting the body`() {
        val unauthorized = Problems.of(BridgeException(401, "401 unauthorized"), url)
        assertTrue(unauthorized.summary, unauthorized.summary.contains("token"))
        val broken = Problems.of(BridgeException(500, "<html>" + "x".repeat(400) + "</html>"), url)
        assertEquals("The bridge hit an error. The details say what.", broken.summary)
        assertTrue("the body is still reachable", broken.detail.contains("x".repeat(400)))
    }

    @Test
    fun `cleartext is a local policy failure, not a tunnel one`() {
        val error = IOException("CLEARTEXT communication to laptop.tailnet.ts.net not permitted by network security policy")
        val problem = Problems.of(error, url)
        assertTrue(problem.summary, problem.summary.contains("Android blocked"))
        assertFalse(problem.tailnet)
        assertTrue(problem.detail, problem.detail.contains("Pair again"))
    }

    @Test
    fun `a plain note has no detail to unfold`() {
        val note = Problems.note("This bridge has no search yet. Reload it on the laptop.")
        assertEquals("", note.detail)
        assertFalse(note.tailnet)
    }

    /** Every shape of failure the app draws, so the length and wording rules hold for all of them. */
    private fun everyKindOfFailure(): List<Throwable> = listOf(
        UnknownHostException("Unable to resolve host \"laptop\": No address associated with hostname"),
        NoRouteToHostException("Network is unreachable"),
        ConnectException("Failed to connect to /100.101.102.103:8787"),
        SocketTimeoutException("failed to connect to /100.101.102.103 (port 8787) after 10000ms"),
        SocketException("Socket closed"),
        SSLHandshakeException("java.security.cert.CertPathValidatorException: trust anchor not found"),
        BridgeException(401, "401 unauthorized"),
        BridgeException(500, "server error"),
        IOException("CLEARTEXT communication not permitted"),
        IOException("unexpected end of stream"),
        IllegalStateException("something odd"),
    )
}
