package com.maverock24.pimobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * True when the mini player belongs on screen: the player holds a file, and the
 * app is not in car mode. Car mode already has one big play and pause on the
 * screen in front of the driver, so a second set of the same controls there
 * would be two places to look at while driving, which is the thing car mode
 * exists to avoid.
 */
internal fun miniPlayerVisible(playingId: String?, carMode: Boolean): Boolean =
    playingId != null && !carMode

/**
 * Where the progress line sits, as a fraction of the file. Zero while the length
 * is not known yet, which draws an empty line rather than a full one: the file
 * has not started rather than finished.
 */
internal fun playbackRatio(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

/**
 * The insets a screen's Scaffold pads its content by. Left alone, a screen pads
 * by safe drawing exactly as it did before the mini player existed. The app root
 * replaces it while the mini player owns the bottom of the window, because the
 * screens then give up the navigation bar's strip to the bar.
 */
internal val LocalScreenInsets = compositionLocalOf<WindowInsets?> { null }

/**
 * The insets one of the app's screens pads its Scaffold by. Every screen reads
 * this rather than naming safe drawing, so the one place that decides what the
 * mini player has taken is the app root.
 */
@Composable
internal fun screenContentInsets(): WindowInsets = LocalScreenInsets.current ?: WindowInsets.safeDrawing

/**
 * The insets the screens pad by while the mini player is on screen: safe drawing
 * everywhere but the navigation bar's bottom, which the bar itself paints, with
 * the keyboard's inset kept, because the bar goes under the keyboard and the
 * composer must not.
 *
 * It is handed down rather than taken with Modifier.consumeWindowInsets because
 * material3's Scaffold pads by its contentWindowInsets and does not consult the
 * consumed insets, so consuming at the root would leave the composer a
 * navigation bar's height above the bar.
 */
@Composable
internal fun insetsAboveMiniPlayer(): WindowInsets =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).union(WindowInsets.ime)

/**
 * The bar that stays at the bottom of the app while audio plays.
 *
 * The app root draws it once, under whatever screen is up, so switching tab,
 * opening Settings or opening the library leaves it where it is. It is not part
 * of any screen for the reason the tabs live in one place: a bar added to each
 * screen would be a copy per screen and the copies would drift.
 *
 * It reads the view model's player and plays, pauses and stops through it, the
 * same [com.maverock24.pimobile.player.TalkPlayer] the library rows and car mode
 * drive, so the bar and the list cannot disagree about what is playing.
 */
@Composable
fun MiniPlayer(vm: ChatViewModel, name: String, onOpen: () -> Unit) {
    val row = vm.files.firstOrNull { it.name == name }
    val duration = vm.player.durationMs

    // The player does not push its position, so the bar polls it while it is on
    // screen, which is what the library's scrubber and car mode's readout do
    // with the same call. There is no second clock and no second player.
    var position by remember(name) { mutableStateOf(0L) }
    LaunchedEffect(name) {
        while (isActive) {
            position = vm.player.positionMs()
            delay(500)
        }
    }

    val details = listOfNotNull(
        // The kind is the category the library row carries, so what the bar says
        // and what the row says are the same word.
        row?.kind?.let(::fileKindLabel),
        "${formatMillis(position)} / ${formatMillis(duration)}",
    ).joinToString(" · ")

    // The surface runs to the bottom of the window so the navigation bar's strip
    // is part of the bar rather than an empty band under it, and the bar's own
    // content is padded back above the gesture bar.
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = AnswerStyle.screenPadding, vertical = AnswerStyle.controlGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AnswerStyle.controlGap),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row?.title ?: name,
                        style = MaterialTheme.typography.titleSmall,
                        // The surface's own content colour is the variant one, and
                        // the title has to read brighter than the line under it.
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LinearProgressIndicator(
                        // No seek: the library row carries the slider, and a
                        // second one on a bar this short is a thumb's width of
                        // travel for a thirty minute file.
                        progress = { playbackRatio(position, duration) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                // Words rather than icons, as in car mode: the core icon set the
                // app depends on has a play arrow but no pause, and adding a
                // dependency for one glyph is not worth it.
                TextButton(onClick = { vm.playOrPause(name) }, modifier = Modifier.tactile()) {
                    Text(
                        text = if (vm.player.playing) "Pause" else "Play",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                IconButton(onClick = { vm.stopPlayback() }, modifier = Modifier.tactile()) {
                    Icon(Icons.Filled.Close, contentDescription = "Stop and close the mini player")
                }
            }
        }
    }
}
