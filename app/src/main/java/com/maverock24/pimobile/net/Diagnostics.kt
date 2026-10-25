package com.maverock24.pimobile.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * What the link is doing, for the Settings line and for [Problems]: the network
 * the phone is on, and a TCP probe that tells DNS, routing and HTTP-policy
 * failures apart.
 */
object Diagnostics {

    fun networkSummary(context: Context): String = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching "no connectivity service"
        val network = manager.activeNetwork ?: return@runCatching "no active network"
        val caps = manager.getNetworkCapabilities(network)
            ?: return@runCatching "active network, no capabilities"
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val vpn = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) " + VPN" else " (no VPN)"
        transport + vpn
    }.getOrElse { "network state unavailable (${it.javaClass.simpleName})" }

    /**
     * Resolves the host and tries a plain TCP connect, which is not subject to
     * Android's cleartext HTTP policy. The result tells apart DNS problems,
     * routing problems and HTTP-policy problems.
     *
     * Never throws: a diagnostic that crashes the app is worse than no diagnostic.
     */
    suspend fun probe(context: Context, baseUrl: String): String = withContext(Dispatchers.IO) {
        runCatching { probeUnsafe(context, baseUrl) }.getOrElse { error ->
            "probe failed (${error.javaClass.simpleName}${error.message?.let { ": $it" } ?: ""})"
        }
    }

    private fun probeUnsafe(context: Context, baseUrl: String): String {
        val summary = StringBuilder("network: ").append(networkSummary(context))
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val host = uri?.host
        if (host.isNullOrBlank()) {
            return summary.append(" · invalid URL '").append(baseUrl).append("'").toString()
        }
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }
        val address = runCatching { InetAddress.getByName(host) }.getOrElse {
            return summary.append(" · DNS failed for ").append(host).toString()
        }
        summary.append(" · ").append(host).append(" → ").append(address.hostAddress)
        runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(address, port), 5000) }
        }.onSuccess {
            summary.append(" · port ").append(port).append(" open")
        }.onFailure {
            summary.append(" · port ").append(port).append(" unreachable (")
                .append(it.javaClass.simpleName).append(")")
        }
        return summary.toString()
    }
}
