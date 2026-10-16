package com.maverock24.pimobile.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * One IPv4 address the other computer might reach the phone on, with where it
 * came from.
 *
 * The transport is the phone's own hotspot (design section 15), where the phone
 * is the access point. Its Wi-Fi *client* interface can be down while the hotspot
 * is up, so reachability is read from the phone's interfaces rather than from one
 * connectivity state.
 */
internal data class WebAddress(
    val address: String,
    val interfaceName: String,
    /**
     * The transport the system attributes the address to, or null when it does
     * not say. A hotspot's own interface is usually in the null case, because
     * ConnectivityManager does not report the access-point interface as a
     * Network.
     */
    val transport: String?,
) {
    /** The URL the human types on the other computer. */
    fun url(port: Int): String = "https://$address:$port"
}

/**
 * The per-interface facts the enumeration collects, kept separate from the
 * Android calls so the candidate decision is a plain function the build machine
 * can test without a phone.
 */
internal data class InterfaceAddresses(
    val name: String,
    val up: Boolean,
    val addresses: List<InetAddress>,
    val transport: String?,
)

/**
 * The candidate addresses from [interfaces], hotspot first.
 *
 * There is deliberately no Wi-Fi transport requirement: on the phone's own
 * hotspot the Wi-Fi client may be down and the access-point interface is not a
 * Wi-Fi network ConnectivityManager reports, so requiring `TRANSPORT_WIFI` is
 * exactly the wrong gate. Every non-loopback IPv4 address is a candidate so the
 * human can pick the one that answers, which is also why no single interface is
 * guessed: the tethering interface name (ap0, wlan1, swlan0, softap0 ...) differs
 * by manufacturer. The name is carried through only to label the address.
 *
 * The order is a display hint, not a claim about which address is the hotspot: an
 * unlabelled interface (usually the access point) first, a named transport next,
 * and a VPN last, because a tunnel address is the least likely to be the one the
 * other computer should type.
 */
internal fun webAddresses(interfaces: List<InterfaceAddresses>): List<WebAddress> =
    interfaces
        .asSequence()
        .filter { it.up }
        .flatMap { iface ->
            iface.addresses.asSequence()
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { WebAddress(it.hostAddress.orEmpty(), iface.name, iface.transport) }
        }
        .filter { it.address.isNotBlank() }
        .distinctBy { it.address }
        .sortedWith(compareBy({ rankOf(it.transport) }, { it.interfaceName }, { it.address }))
        .toList()

private const val VPN = "VPN"

private fun rankOf(transport: String?): Int = when (transport) {
    null -> 0
    VPN -> 2
    else -> 1
}

/**
 * Every non-loopback IPv4 address the phone owns, the hotspot included.
 *
 * ConnectivityManager is used only to label an address the interface list already
 * found, never as a gate. Reading `NetworkInterface` is what sees the hotspot: its
 * address is usually on an interface ConnectivityManager does not report as a
 * Network at all, so the interface list is the thing that sees the address
 * whichever transport the phone is serving on.
 */
internal fun phoneAddresses(context: Context): List<WebAddress> {
    val labels = transportLabels(context)
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
    val raw = mutableListOf<InterfaceAddresses>()
    for (iface in interfaces) {
        val name = iface.name ?: continue
        raw += InterfaceAddresses(
            name = name,
            up = runCatching { iface.isUp }.getOrDefault(false),
            addresses = runCatching { iface.inetAddresses.toList() }.getOrDefault(emptyList()),
            transport = labels[name],
        )
    }
    return webAddresses(raw)
}

/**
 * The transport the system attributes to each interface, by name, so an address
 * can be labelled. Empty when ConnectivityManager is unavailable, which only
 * costs the labels and not the candidates.
 *
 * `allNetworks` is deprecated on API 31 in favour of a `NetworkCallback`, but the
 * callback form is asynchronous and this is a one-shot read each time the shown
 * addresses are refreshed.
 */
@Suppress("DEPRECATION")
private fun transportLabels(context: Context): Map<String, String> {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyMap()
    val labels = mutableMapOf<String, String>()
    for (network in manager.allNetworks) {
        val name = manager.getLinkProperties(network)?.interfaceName ?: continue
        val capabilities = manager.getNetworkCapabilities(network) ?: continue
        labels[name] = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> VPN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "network"
        }
    }
    return labels
}
