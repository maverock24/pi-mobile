package com.maverock24.pimobile.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.maverock24.pimobile.MainActivity

/**
 * Plays talks and keeps playing with the screen off.
 *
 * A 30 minute talk outlives any reasonable screen timeout, so the player cannot
 * live in the activity: when the activity goes away the audio would stop with
 * it. A `MediaSessionService` is what the platform expects for that, and it is
 * the app's first service. `mediaPlayback` in the manifest plus the
 * `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission let media3 hold the process in
 * the foreground while audio plays, and it posts the media notification the
 * human controls playback from.
 *
 * Nothing plays until a controller asks it to, and the session is released with
 * the service, so the service leaves nothing behind when playback stops.
 */
class TalkPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            // Speech, not music: this is what head units and the volume keys key
            // off, and it lets the platform duck or pause other audio for us.
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
        }
        // Tapping the media notification opens the app where the Talks list is.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Swiping the app away must not leave a dead session in the shade, but it
     * must not kill a talk that is still playing either. Stop only when nothing
     * is playing; otherwise let the foreground service keep going.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
