package com.maverock24.pimobile.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The address enumeration as a plain decision. The Android read in
 * [phoneAddresses] needs a phone, but the rule the old code got wrong is here:
 * the candidates must not require a Wi-Fi client transport, because the chosen
 * transport is the phone's own hotspot, where the Wi-Fi client can be down while
 * the access point is up. A regression to the old gate fails these tests.
 */
class WebAddressesTest {

    private fun inet(ip: String) = InetAddress.getByName(ip) as Inet4Address

    @Test
    fun `a hotspot interface with no Wi-Fi client transport is still a candidate`() {
        // The bug the human hit: no TRANSPORT_WIFI network, only the hotspot and
        // the mobile radio, and the old lookup returned null so the server
        // refused. Both interfaces must survive, the hotspot one first because it
        // is unlabelled and the transport the human chose.
        val candidates = webAddresses(
            listOf(
                InterfaceAddresses("ap0", up = true, addresses = listOf(inet("192.168.43.1")), transport = null),
                InterfaceAddresses(
                    "rmnet_data0",
                    up = true,
                    addresses = listOf(inet("10.0.0.2")),
                    transport = "mobile",
                ),
            ),
        )
        assertEquals(listOf("192.168.43.1", "10.0.0.2"), candidates.map { it.address })
    }

    @Test
    fun `the hotspot address is listed first and a vpn address last`() {
        val candidates = webAddresses(
            listOf(
                InterfaceAddresses("tun0", up = true, addresses = listOf(inet("100.64.0.1")), transport = "VPN"),
                InterfaceAddresses("wlan0", up = true, addresses = listOf(inet("192.168.1.5")), transport = "Wi-Fi"),
                InterfaceAddresses("ap0", up = true, addresses = listOf(inet("192.168.43.1")), transport = null),
            ),
        )
        assertEquals(listOf("192.168.43.1", "192.168.1.5", "100.64.0.1"), candidates.map { it.address })
    }

    @Test
    fun `the interface name labels which address is which`() {
        val candidates = webAddresses(
            listOf(
                InterfaceAddresses(
                    "swlan0",
                    up = true,
                    addresses = listOf(inet("192.168.43.1")),
                    transport = null,
                ),
            ),
        )
        assertEquals("swlan0", candidates.single().interfaceName)
        assertNull("the system did not say the transport", candidates.single().transport)
    }

    @Test
    fun `loopback, link local and down interfaces are left out`() {
        val candidates = webAddresses(
            listOf(
                InterfaceAddresses("lo", up = true, addresses = listOf(inet("127.0.0.1")), transport = null),
                InterfaceAddresses("ap0", up = true, addresses = listOf(inet("169.254.10.1")), transport = null),
                InterfaceAddresses("dummy0", up = false, addresses = listOf(inet("10.0.0.9")), transport = null),
            ),
        )
        assertTrue("nothing reachable, so no candidate", candidates.isEmpty())
    }

    @Test
    fun `an address held on two interfaces is listed once`() {
        val candidates = webAddresses(
            listOf(
                InterfaceAddresses("ap0", up = true, addresses = listOf(inet("192.168.43.1")), transport = null),
                InterfaceAddresses(
                    "wlan0",
                    up = true,
                    addresses = listOf(inet("192.168.43.1")),
                    transport = "Wi-Fi",
                ),
            ),
        )
        assertEquals(1, candidates.size)
    }

    @Test
    fun `the url names the candidate address and the fixed port`() {
        val candidate = WebAddress("192.168.43.1", "ap0", null)
        assertEquals("https://192.168.43.1:8788", candidate.url(8788))
    }

    @Test
    fun `the displayed url carries the scheme and the port the server binds`() {
        // A bare address pasted into a browser would go to port 443, where
        // nothing listens, so the string the app shows must carry both the scheme
        // and the port the TLS front binds (WebAccess.PORT).
        val candidate = WebAddress("192.168.43.1", "ap0", null)
        val shown = candidate.url(WebAccess.PORT)
        assertEquals("https://192.168.43.1:${WebAccess.PORT}", shown)
        assertTrue("the scheme is https, not a bare address", shown.startsWith("https://"))
        assertTrue("the port is the one the server binds", shown.endsWith(":${WebAccess.PORT}"))
    }
}
