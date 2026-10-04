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
import javax.net.ssl.SSLException

/**
 * Failure reporting for a phone-to-laptop link over Tailscale. The platform
 * throws transport exceptions with empty messages, so always name the exception
 * class and add the likely cause.
 */
object Diagnostics {

    fun networkSummary(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return "no connectivity service"
        val network = manager.activeNetwork ?: return "no active network"
        val caps = manager.getNetworkCapabilities(network) ?: return "active network, no capabilities"
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val vpn = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) " + VPN" else " (no VPN)"
        return transport + vpn
    }

    /**
     * Resolves the host and tries a plain TCP connect, which is not subject to
     * Android's cleartext HTTP policy. The result tells apart DNS problems,
     * routing problems and HTTP-policy problems.
     */
    suspend fun probe(context: Context, baseUrl: String): String = withContext(Dispatchers.IO) {
        val summary = StringBuilder("network: ").append(networkSummary(context))
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val host = uri?.host
        if (host.isNullOrBlank()) {
            return@withContext summary.append(" · invalid URL '").append(baseUrl).append("'").toString()
        }
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }
        val address = runCatching { InetAddress.getByName(host) }.getOrElse {
            return@withContext summary.append(" · DNS failed for ").append(host).toString()
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
        summary.toString()
    }

    fun hint(error: Throwable): String {
        val message = error.message.orEmpty()
        if (message.contains("CLEARTEXT", ignoreCase = true)) {
            return "Android blocked cleartext HTTP for this host. Use the MagicDNS name " +
                "(your-laptop.your-tailnet.ts.net) instead of the raw IP."
        }
        return when (error) {
            is java.net.UnknownHostException ->
                "host not found — MagicDNS may be off, or the URL has a typo"
            is java.net.SocketTimeoutException ->
                "timed out — Tailscale may be connected but blocked, or the laptop is asleep"
            is SSLException ->
                "TLS failed — drop https:// unless you set up tailscale serve"
            is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.SocketException ->
                "no TCP route to the laptop — check that Tailscale is connected on the phone " +
                    "and that the bridge is running"
            else -> error.javaClass.simpleName + if (message.isBlank()) "" else ": $message"
        }
    }

    fun describe(error: Throwable, baseUrl: String): String {
        val raw = error.message?.takeIf { it.isNotBlank() }
        val detail = raw ?: error.javaClass.simpleName
        return "failed · $baseUrl · $detail · ${hint(error)}"
    }
}
