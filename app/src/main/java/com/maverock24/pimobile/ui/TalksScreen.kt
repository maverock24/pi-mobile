package com.maverock24.pimobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * The talks library: what the bridge has rendered, plus what this phone already
 * holds. A heading row plays back the downloaded audio, with a scrubber long
 * enough to move through a thirty minute talk, and each row offers download for
 * a ready talk or the delete that reclaims its space.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalksScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
) {
    // Reading the library is cheap and it is the reason the screen was opened, so
    // it happens on entry rather than on a manual refresh.
    LaunchedEffect(Unit) { vm.refreshTalks() }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.tactile()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Talks", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    if (vm.talksLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = { vm.refreshTalks() }, modifier = Modifier.tactile()) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh the library")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (vm.token.isBlank()) {
                // Without a token there is nothing to ask, and saying so is more
                // useful than an empty list that looks like a failed render.
                Text(
                    text = "Pair the bridge in Settings to see rendered talks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(AnswerStyle.screenPadding),
                )
            }
            vm.talksError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AnswerStyle.screenPadding, vertical = 4.dp),
                )
            }
            NowPlaying(vm)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (vm.talks.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(AnswerStyle.screenPadding),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (vm.talksLoading) "Loading talks…" else "No talks yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(vm.talks, key = { it.id }) { row -> TalkRowCard(vm, row) }
            }
        }
    }
}

/**
 * The player strip, drawn only once a talk has been loaded into the player. It
 * is the one place with a scrubber, since a thirty minute MP3 is unusable
 * without one.
 */
@Composable
private fun NowPlaying(vm: ChatViewModel) {
    val playingId = vm.player.playingId ?: return
    val title = vm.talks.firstOrNull { it.id == playingId }?.title ?: "Talk"
    val duration = vm.player.durationMs
    var position by remember(playingId) { mutableStateOf(0L) }
    var scrubbing by remember(playingId) { mutableStateOf(false) }
    var scrubValue by remember(playingId) { mutableStateOf(0f) }

    // The player does not push its position, so the bar polls it while it is on
    // screen. Polling pauses under the thumb, so the bar does not fight the drag.
    LaunchedEffect(playingId) {
        while (isActive) {
            if (!scrubbing) position = vm.player.positionMs()
            delay(500)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = AnswerStyle.screenPadding, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = (if (scrubbing) scrubValue else position.toFloat()).coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
            onValueChange = {
                scrubbing = true
                scrubValue = it
            },
            onValueChangeFinished = {
                vm.seekPlayback(scrubValue.toLong())
                position = scrubValue.toLong()
                scrubbing = false
            },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AnswerStyle.controlGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(onClick = vm::togglePlayback, modifier = Modifier.tactile(haptics = true)) {
                Text(if (vm.player.playing) "Pause" else "Play")
            }
            Text(
                text = "${formatMillis(position)} / ${formatMillis(duration)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
}

/** One library row, its controls chosen by the render state and what is stored. */
@Composable
private fun TalkRowCard(vm: ChatViewModel, row: TalkRow) {
    val downloaded = vm.downloadedIds.contains(row.id)
    val progress = vm.downloadProgress[row.id]
    val isPlayingThis = vm.player.playingId == row.id && vm.player.playing

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AnswerStyle.screenPadding, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(AnswerStyle.panelPadding)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                talkStateLabel(row.state),
                talkDuration(row).takeIf { it.isNotBlank() },
                formatSize(row.bytes).takeIf { it.isNotBlank() },
                row.voice,
            ).joinToString(" · ")
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.state == "failed" && !row.error.isNullOrBlank()) {
                Text(
                    text = row.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(AnswerStyle.controlGap))
            when {
                row.state == "rendering" -> {
                    // An indeterminate bar is honest here: the bridge reports the
                    // state but not how far the render has got.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                row.state == "failed" -> Unit
                progress != null -> {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Downloading ${(progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                downloaded -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(AnswerStyle.controlGap)) {
                        Button(onClick = { vm.playOrPause(row.id) }, modifier = Modifier.tactile(haptics = true)) {
                            Text(if (isPlayingThis) "Pause" else "Play")
                        }
                        OutlinedButton(onClick = { vm.deleteTalk(row.id) }, modifier = Modifier.tactile()) {
                            Text("Delete")
                        }
                    }
                }
                else -> {
                    Button(
                        onClick = { vm.downloadTalk(row.id) },
                        modifier = Modifier.tactile(haptics = true),
                    ) {
                        Text("Download")
                    }
                }
            }
        }
    }
}

private fun talkStateLabel(state: String): String = when (state) {
    "rendering" -> "rendering"
    "ready" -> "ready"
    "failed" -> "failed"
    else -> state
}

/** A talk's length, marked as an estimate when the bridge estimated it. */
private fun talkDuration(row: TalkRow): String {
    val seconds = row.durationSeconds
    if (seconds <= 0.0) return ""
    if (row.durationEstimated) {
        val minutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        return "about $minutes min"
    }
    return formatMillis((seconds * 1000).toLong())
}

private fun formatMillis(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024 * 1024 -> "%.0f kB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
