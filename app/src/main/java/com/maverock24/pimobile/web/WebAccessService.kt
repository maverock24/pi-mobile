package com.maverock24.pimobile.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.maverock24.pimobile.MainActivity
import com.maverock24.pimobile.R
import com.maverock24.pimobile.data.PinsStore
import com.maverock24.pimobile.data.SettingsStore
import com.maverock24.pimobile.net.Problems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the web server while it is on.
 *
 * The server has to keep serving with the screen off, so it lives in a foreground
 * service: Android kills a background process and the listening socket with it.
 * The notification is not decoration, it is the only place the human can see that
 * a listener exists and stop it without opening the app, so it always states the
 * URL and carries a Stop action.
 *
 * The service is started from the visible Settings screen, which is what Android
 * 12 and later require for a foreground service started from the app. It stops
 * itself once the client has been gone for the idle grace period, and the
 * listening socket is closed on that path.
 */
class WebAccessService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: WebRelayServer? = null
    private var front: WebTlsFront? = null
    private var watchdog: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The channel has to exist before anything posts to it, and it is a
        // user-facing setting, so it is created on every start rather than once.
        val channel = NotificationChannel(CHANNEL_ID, "Web access", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Cancel the watchdog from outside so it cannot report the stop as an
            // idle exit, then tear down on this path.
            scope.launch {
                watchdog?.cancel()
                watchdog = null
                teardown()
            }
            return START_NOT_STICKY
        }
        // Promote to the foreground before the five second deadline. The URL is
        // not known yet, so the first notification says it is starting and the
        // real one replaces it as soon as the socket is bound.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting the server"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (watchdog?.isActive != true) startServer()
        return START_NOT_STICKY
    }

    private fun startServer() {
        WebAccess.update(WebAccessStatus.Starting)
        // Start with a clean sheet so a previous run's handshake failures do not
        // read as this run's, and so a fresh listener that has served nobody says
        // exactly that.
        WebDiagnostics.reset()
        watchdog = scope.launch {
            try {
                var addresses = phoneAddresses(this@WebAccessService)
                // The gate is an address, not a connectivity state: the hotspot is
                // up even when the Wi-Fi client is down, and any non-loopback IPv4
                // the phone owns is somewhere the other computer might reach it.
                if (addresses.isEmpty()) error("The phone has no reachable IPv4 address")
                val certificate = WebCertificateStore.load(this@WebAccessService, addresses.map { it.address })
                val store = SettingsStore(this@WebAccessService)
                val relay = WebRelayServer(
                    loadAsset = { name ->
                        assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    },
                    // Every candidate address is an allowed authority and origin,
                    // because the human picks the one that answers. A name is never
                    // in the set, so the Host check still refuses a rebound name.
                    allowedAuthorities = authoritiesOf(addresses),
                    allowedOrigins = originsOf(addresses),
                    upstream = BridgeUpstream(baseUrl = { store.baseUrl }, token = { store.token }),
                    // The pins are the phone's own data, so the relay reads and
                    // writes them through the same store the app's Pins screen uses.
                    pins = StoredPins(PinsStore(this@WebAccessService)),
                )
                val backendPort = relay.start()
                // Assigned before the next step so a failure below still stops it.
                server = relay
                val tls = WebTlsFront(
                    keyStore = certificate.keyStore,
                    keyAlias = certificate.alias,
                    certificate = certificate.certificate,
                    port = WebAccess.PORT,
                    backendPort = backendPort,
                    log = { message -> Log.i(LOG_TAG, message) },
                )
                tls.start()
                front = tls
                WebAccess.update(WebAccessStatus.On(addresses, certificate.fingerprint))
                notify(notification(servingText(addresses)))
                while (isActive) {
                    delay(WATCH_INTERVAL_MS)
                    addresses = refreshAddresses(addresses, relay, certificate.fingerprint)
                    if (relay.isIdle(WebAccess.IDLE_GRACE_MS, System.currentTimeMillis())) break
                }
                teardown()
            } catch (cancelled: CancellationException) {
                // The service is going away; onDestroy closes the sockets.
                throw cancelled
            } catch (error: Throwable) {
                // Binding the fixed port can fail because something else holds
                // it; that is worth telling the human rather than retrying.
                teardown(WebAccessStatus.Failed(Problems.of(error, subject = "web access")))
            }
        }
    }

    /**
     * Re-reads the phone's interfaces while the server runs. A hotspot coming up
     * or going down changes the set, and the Settings screen must not keep showing
     * an address that no longer answers (design section 14: a wrong address is
     * worse than none). The listening socket does not move, because the TLS front
     * is bound to every interface, so only the accepted addresses, the shown list
     * and the notification change.
     */
    private fun refreshAddresses(
        previous: List<WebAddress>,
        relay: WebRelayServer,
        fingerprint: String,
    ): List<WebAddress> {
        val current = phoneAddresses(this)
        if (current == previous) return previous
        relay.updateAuthorities(authoritiesOf(current), originsOf(current))
        WebAccess.update(WebAccessStatus.On(current, fingerprint))
        notify(notification(servingText(current)))
        return current
    }

    private fun authoritiesOf(addresses: List<WebAddress>): Set<String> =
        addresses.map { "${it.address}:${WebAccess.PORT}" }.toSet()

    private fun originsOf(addresses: List<WebAddress>): Set<String> =
        addresses.map { it.url(WebAccess.PORT) }.toSet()

    private fun servingText(addresses: List<WebAddress>): String =
        addresses.firstOrNull()?.let { "Serving ${it.url(WebAccess.PORT)}" }
            ?: "No reachable address right now"

    /**
     * Stops the listeners and the service. [status] is what the Settings screen
     * keeps showing afterwards, so a failure stays visible after the notification
     * is gone.
     */
    private suspend fun teardown(status: WebAccessStatus = WebAccessStatus.Off) {
        front?.stop()
        front = null
        server?.stop()
        server = null
        WebAccess.update(status)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        watchdog?.cancel()
        watchdog = null
        front?.stop()
        front = null
        server?.stop()
        server = null
        // A stop the service did not run itself, such as a `stopService` call or
        // a system kill, would otherwise leave the UI saying it is on. A failure
        // set by teardown is left alone so it stays visible.
        val status = WebAccess.status.value
        if (status is WebAccessStatus.On || status is WebAccessStatus.Starting) {
            WebAccess.update(WebAccessStatus.Off)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WebAccessService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle("Web access is on")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        /**
         * Sent by the notification's Stop action. The same action stops the
         * server through the service rather than `stopService`, so the listening
         * socket is closed before the process is allowed to end.
         */
        const val ACTION_STOP = "com.maverock24.pimobile.web.STOP"

        private const val CHANNEL_ID = "web-access"
        private const val NOTIFICATION_ID = 42

        /** Logcat tag for the listener's own account of what it did. */
        private const val LOG_TAG = "WebAccess"

        /** How often the idle bookkeeping is checked. */
        private const val WATCH_INTERVAL_MS = 5_000L
    }
}
