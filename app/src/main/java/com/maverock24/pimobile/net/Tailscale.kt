package com.maverock24.pimobile.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri

/** Whether a VPN is up on this phone right now. */
internal enum class VpnState {
    /** No VPN transport on the active network, so no tunnel is carrying anything. */
    Off,

    /**
     * A VPN is up. Android does not tell a third-party app which app owns it, so
     * this cannot say "Tailscale is on" without guessing.
     */
    On,
}

/**
 * The honest limit of what this app can do about the tunnel, plus what it can
 * honestly report.
 *
 * It cannot start Tailscale. A VpnService belongs to the app that declares it, and
 * VpnService.prepare() grants the caller its own tunnel and revokes another app's
 * grant rather than launching it. Tailscale's service is exported to nobody and
 * bound by the system, so there is no handle to take. Tailscale does publish
 * broadcast actions for its own app (com.tailscale.ipn.CONNECT_VPN and
 * DISCONNECT_VPN, handled by its exported IPNReceiver) but they are undocumented,
 * fire-and-forget, and reported unreliable when the phone has been asleep, so this
 * app sends none: a promise it cannot check is worse than a button the human
 * presses.
 *
 * What is left is worth having: say whether a tunnel is up, and open Tailscale so
 * the human can flip the switch. Opening another app is not connecting it, and
 * nothing here says otherwise.
 */
internal object Tailscale {

    /** The Tailscale Android application ID, as Tailscale's own MDM docs state it. */
    const val PACKAGE = "com.tailscale.ipn"

    private const val PLAY_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"

    /**
     * Reads the active network's capabilities. A VPN that carries the default
     * route shows up here as TRANSPORT_VPN; a Tailscale that is signed in but
     * switched off does not.
     */
    fun state(context: Context): VpnState {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return VpnState.Off
        val network = manager.activeNetwork ?: return VpnState.Off
        val caps = manager.getNetworkCapabilities(network) ?: return VpnState.Off
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) VpnState.On else VpnState.Off
    }

    /**
     * True when the Tailscale app is on the phone. This needs the `<queries>` entry
     * in the manifest, because Android 11 and later hide other packages unless the
     * app says which ones it talks to.
     */
    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Opens the Tailscale app's own screen. False when it is not installed. */
    fun open(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** The Play Store listing, the only route left when Tailscale is not installed. */
    fun install(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}
