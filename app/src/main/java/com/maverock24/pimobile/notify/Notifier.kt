package com.maverock24.pimobile.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.maverock24.pimobile.MainActivity
import com.maverock24.pimobile.PiRemoteApp
import com.maverock24.pimobile.R

/**
 * Posts the two run notifications: pi needs an answer, and pi finished.
 *
 * Both triggers fire from a live process only; there is no foreground service,
 * so a run that settles while the phone is pocketed just does not report. That
 * gap is recorded, not hidden.
 */
class Notifier(private val context: Context) {

    /** A question is waiting, so the answer cannot be given on the laptop alone. */
    fun postQuestion(title: String) {
        post(question = true, text = title)
    }

    /** The run settled, so its first answer line is worth reading from the shade. */
    fun postSettled(answer: String) {
        post(question = false, text = answer)
    }

    private fun post(question: Boolean, text: String) {
        // A notification reports something you cannot see: posting one while the
        // app is in front is noise. A user who turned notifications off has
        // already said they do not want them, so nothing is posted either way.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (PiRemoteApp.isResumed) return

        // One destination and one action: the notification has no reply path in
        // this effort, so its only job is to bring the app back to the front.
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_RUNS)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(if (question) "pi needs an answer" else "pi finished")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(0, "Open", openIntent)
            .build()

        NotificationManagerCompat.from(context).notify(if (question) QUESTION_ID else SETTLED_ID, notification)
    }

    private companion object {
        /** The channel PiRemoteApp creates on every launch. */
        const val CHANNEL_RUNS = "pi-runs"

        /** Two fixed ids so a later notification replaces its own kind, not the other's. */
        const val QUESTION_ID = 1
        const val SETTLED_ID = 2
    }
}
