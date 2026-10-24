package com.maverock24.pimobile.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File

/** One requested playback, held until the controller is ready to run it. */
private data class QueuedPlay(val file: File, val id: String, val title: String)

/**
 * The app's handle on [TalkPlaybackService].
 *
 * The audio plays in the service so it survives the screen going off; this is
 * the thin controller the UI uses to start it, pause it and read where it is.
 * A controller connects asynchronously, so [play] remembers a request made
 * before the connection is ready and runs it the moment the controller arrives,
 * rather than dropping the tap.
 */
class TalkPlayer(private val context: Context) {

    /** True while audio is actually coming out, which drives the play/pause face. */
    var playing by mutableStateOf(false)
        private set

    /** The talk the player is on, by id, or null when it holds nothing. */
    var playingId by mutableStateOf<String?>(null)
        private set

    /** The length of the current talk in millis, or 0 until it is known. */
    var durationMs by mutableStateOf(0L)
        private set

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** A play asked for before the controller was ready, replayed on connect. */
    private var queued: QueuedPlay? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            playingId = mediaItem?.mediaId
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Duration only becomes meaningful once the file has been prepared.
            durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L
        }
    }

    /** Open the connection to the service. Safe to call repeatedly. */
    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, TalkPlaybackService::class.java))
        val created = MediaController.Builder(context, token).buildAsync()
        future = created
        created.addListener({
            val ready = runCatching { created.get() }.getOrNull() ?: return@addListener
            controller = ready
            ready.addListener(listener)
            queued?.let { request ->
                queued = null
                start(request)
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * Play [file] as talk [id], titled [title]. The title is what the media
     * notification shows, so it is the human's name for the talk rather than the
     * id the file is named after.
     */
    fun play(file: File, id: String, title: String) {
        val request = QueuedPlay(file, id, title)
        val ready = controller
        if (ready == null) {
            queued = request
            connect()
            return
        }
        start(request)
    }

    private fun start(request: QueuedPlay) {
        val ready = controller ?: return
        ready.setMediaItem(
            MediaItem.Builder()
                .setMediaId(request.id)
                .setUri(Uri.fromFile(request.file))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(request.title)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build(),
                )
                .build(),
        )
        ready.prepare()
        ready.play()
    }

    /** Pause if playing, resume if paused. */
    fun toggle() {
        val ready = controller ?: return
        if (ready.isPlaying) ready.pause() else ready.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** Where playback is now. The player does not push this, so the UI polls it. */
    fun positionMs(): Long = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L

    /** Forget the current talk and stop the service from lingering. */
    fun stop() {
        queued = null
        // The controller answers on its own thread, so waiting for its cleared
        // item leaves the app claiming a talk it no longer holds for a frame or
        // two. Clearing here is what makes the mini player go on the tap that
        // asked for the stop rather than after it.
        playingId = null
        playing = false
        durationMs = 0L
        controller?.run {
            stop()
            clearMediaItems()
        }
    }

    /** Release the connection. Called when the view model that owns this goes away. */
    fun release() {
        controller?.removeListener(listener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }
}
