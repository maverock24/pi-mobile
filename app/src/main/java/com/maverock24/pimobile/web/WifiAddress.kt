package com.maverock24.pimobile.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * The phone's IPv4 address on the WLAN, or null when it is not on one.
 *
 * The app asks per network rather than for the "active" one, because with the
 * Tailscale VPN up the active network is the tunnel and the address the other
 * computer must reach is still the Wi-Fi one. Only IPv4 is returned: the URL the
 * human types is a v4 literal, and the phone's v6 address is out of scope for the
 * first version.
 *
 * `allNetworks` is deprecated on API 31 in favour of a `NetworkCallback`, but the
 * callback form is asynchronous; the address-change watcher that needs it belongs
 * with the lifecycle work of a later stage. For a one-shot lookup at the moment
 * the server starts, the deprecated enumeration is the only synchronous route.
 */
@Suppress("DEPRECATION")
internal fun wifiIpv4(context: Context): String? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    for (network in manager.allNetworks) {
        val capabilities = manager.getNetworkCapabilities(network) ?: continue
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
        val link = manager.getLinkProperties(network) ?: continue
        val address = link.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        if (address != null) return address.hostAddress
    }
    return null
}
