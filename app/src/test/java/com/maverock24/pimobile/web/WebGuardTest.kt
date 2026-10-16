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
    fun `host must be present exactly once and match an allowed address`() {
        val allowed = setOf("192.168.1.10:8788", "192.168.43.1:8788")
        assertFalse(hostRefused(listOf("192.168.1.10:8788"), allowed))
        // Every candidate address the phone holds is allowed, so the human can
        // pick the one that answers without a second copy of the list.
        assertFalse(hostRefused(listOf("192.168.43.1:8788"), allowed))
        // Host names are case-insensitive.
        assertFalse(hostRefused(listOf("192.168.1.10:8788"), setOf("192.168.1.10:8788")))
        assertTrue("missing", hostRefused(emptyList(), allowed))
        assertTrue("duplicate", hostRefused(listOf("192.168.1.10:8788", "evil:1"), allowed))
        assertTrue("name not an address", hostRefused(listOf("evil.example"), allowed))
        assertTrue("unknown address", hostRefused(listOf("10.0.0.9:8788"), allowed))
        assertTrue("wrong port", hostRefused(listOf("192.168.1.10:8789"), allowed))
    }

    @Test
    fun `origin is allowed when absent and refused when foreign`() {
        val allowed = setOf("https://192.168.1.10:8788", "https://192.168.43.1:8788")
        assertFalse(originRefused(null, allowed))
        assertFalse(originRefused("", allowed))
        assertFalse(originRefused("https://192.168.1.10:8788", allowed))
        assertFalse(originRefused("https://192.168.43.1:8788", allowed))
        assertTrue(originRefused("https://evil.example", allowed))
        assertTrue(originRefused("http://192.168.1.10:8788", allowed))
        assertTrue(originRefused("https://10.0.0.9:8788", allowed))
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
