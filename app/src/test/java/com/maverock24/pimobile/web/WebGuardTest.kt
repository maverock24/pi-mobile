package com.maverock24.pimobile.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-request controls as pure decisions. These are the F1 and DNS-rebinding
 * refusals, and they are checked without a server so a regression is one small
 * failure rather than a socket test that might be flaky.
 */
class WebGuardTest {

    @Test
    fun `host must be present exactly once and match the bound authority`() {
        assertFalse(hostRefused(listOf("192.168.1.10:8788"), "192.168.1.10:8788"))
        // Host names are case-insensitive.
        assertFalse(hostRefused(listOf("192.168.1.10:8788"), "192.168.1.10:8788"))
        assertTrue("missing", hostRefused(emptyList(), "192.168.1.10:8788"))
        assertTrue("duplicate", hostRefused(listOf("192.168.1.10:8788", "evil:1"), "192.168.1.10:8788"))
        assertTrue("wrong name", hostRefused(listOf("evil.example"), "192.168.1.10:8788"))
        assertTrue("wrong port", hostRefused(listOf("192.168.1.10:8789"), "192.168.1.10:8788"))
    }

    @Test
    fun `origin is allowed when absent and refused when foreign`() {
        val own = "https://192.168.1.10:8788"
        assertFalse(originRefused(null, own))
        assertFalse(originRefused("", own))
        assertFalse(originRefused(own, own))
        assertTrue(originRefused("https://evil.example", own))
        assertTrue(originRefused("http://192.168.1.10:8788", own))
    }

    @Test
    fun `sec fetch site allows only same-origin and none`() {
        assertFalse(secFetchSiteRefused(null))
        assertFalse(secFetchSiteRefused("same-origin"))
        assertFalse(secFetchSiteRefused("none"))
        assertTrue(secFetchSiteRefused("cross-site"))
        assertTrue(secFetchSiteRefused("same-site"))
    }

    @Test
    fun `target must be origin form with no encoded separators`() {
        assertFalse(targetRefused("/api/state"))
        assertFalse(targetRefused("/api/state?limit=1"))
        assertTrue("absolute form", targetRefused("http://evil.example/api/state"))
        assertTrue("authority form", targetRefused("evil.example:8788"))
        assertTrue("encoded dot", targetRefused("/api/%2e%2e/api/pair"))
        assertTrue("encoded slash", targetRefused("/api%2fpair"))
        assertTrue("encoded backslash", targetRefused("/api%5cpair"))
        assertTrue("literal backslash", targetRefused("/api\\pair"))
        assertTrue("dot segment", targetRefused("/api/../pair"))
    }

    @Test
    fun `a secret is compared in full and empty values never match`() {
        assertTrue(secretsMatch("abc123", "abc123"))
        assertFalse(secretsMatch("abc123", "abc124"))
        assertFalse(secretsMatch("abc123", "abc12"))
        assertFalse(secretsMatch("abc123", ""))
        assertFalse(secretsMatch(null, "abc123"))
        assertFalse(secretsMatch("abc123", null))
    }

    @Test
    fun `json parses objects, arrays and escapes and round trips strings`() {
        val parsed = parseJson("""{"a":1,"b":[true,false,null],"c":"x\"y","d":{"e":"f"}}""")
        val obj = parsed as JsonValue.Obj
        assertEquals("1", (obj.fields["a"] as JsonValue.Num).raw)
        assertEquals(3, (obj.fields["b"] as JsonValue.Arr).items.size)
        assertEquals("x\"y", obj.stringOrNull("c"))
        assertEquals("f", (obj.fields["d"] as JsonValue.Obj).stringOrNull("e"))
        assertNull(obj.stringOrNull("missing"))
        assertTrue(jsonObject("k" to jsonString("v\"\\")).render().contains("v\\\"\\\\"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed json is rejected rather than partly read`() {
        parseJson("""{"a":}""")
    }
}
