package com.maverock24.pimobile.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.maverock24.pimobile.MainActivity
import com.maverock24.pimobile.R
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
            notification(url = null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (watchdog?.isActive != true) startServer()
        return START_NOT_STICKY
    }

    private fun startServer() {
        WebAccess.update(WebAccessStatus.Starting)
        watchdog = scope.launch {
            try {
                val address = wifiIpv4(this@WebAccessService)
                    ?: error("The phone is not on a Wi-Fi network")
                val certificate = WebCertificateStore.load(this@WebAccessService, address)
                val relay = WebRelayServer(assets)
                val backendPort = relay.start()
                // Assigned before the next step so a failure below still stops it.
                server = relay
                val tls = WebTlsFront(
                    keyStore = certificate.keyStore,
                    keyAlias = certificate.alias,
                    bindAddress = address,
                    port = WebAccess.PORT,
                    backendPort = backendPort,
                )
                tls.start()
                front = tls
                val url = "https://$address:${WebAccess.PORT}"
                WebAccess.update(WebAccessStatus.On(url, certificate.fingerprint))
                notify(notification(url))
                while (isActive) {
                    delay(WATCH_INTERVAL_MS)
                    if (relay.isIdle(WebAccess.IDLE_GRACE_MS, System.currentTimeMillis())) break
                }
                teardown()
            } catch (cancelled: CancellationException) {
                // The service is going away; onDestroy closes the sockets.
                throw cancelled
            } catch (error: Throwable) {
                // Binding the fixed port can fail because something else holds
                // it; that is worth telling the human rather than retrying.
                teardown(WebAccessStatus.Failed(error.message ?: error.javaClass.simpleName))
            }
        }
    }

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

    private fun notification(url: String?, failed: String? = null): Notification {
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
        val text = failed ?: url?.let { "Serving $it" } ?: "Starting the server"
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

        /** How often the idle bookkeeping is checked. */
        private const val WATCH_INTERVAL_MS = 5_000L
    }
}
