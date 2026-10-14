package com.maverock24.pimobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * The sizes car mode is built out of. Nothing here is under the 64 dp a driver
 * can hit without looking, and the primary action is larger than the rest.
 */
private object CarStyle {
    /** The floor for any control in car mode: a thumb at arm's length. */
    val tap = 72.dp

    /** The primary controls, comfortably over the floor. */
    val big = 96.dp

    /** The mic, the largest target in the app, because it is the main action. */
    val mic = 176.dp
}

/**
 * Car mode: the same app rebuilt for driving.
 *
 * It is a mode of [ChatViewModel], not a second app. The dictation, the
 * transcript and the media3 player are the ones the normal screen uses, so
 * turning the mode on and off changes which screen is drawn and nothing else.
 * Playback is deliberately untouched by both switches: audio keeps coming out
 * through the background service whether car mode is entering, leaving or on.
 *
 * The shape here is one job per half, chosen by two big buttons rather than a
 * tab bar. Ask is a single touch to talk, with the spoken submit phrase on
 * screen so the driver does not have to remember it. Listen is one big
 * play/pause for the current audio and a short list of the files worth playing.
 * There is no search, no delete and no slider anywhere: precision is the one
 * thing a driver does not have.
 */
@Composable
fun CarScreen(
    vm: ChatViewModel,
    listening: Boolean,
    partialText: String,
    onToggleMic: () -> Unit,
) {
    // Ask is first because talking is the point: entering the mode puts the mic
    // on screen with no navigation. It resets to Ask each time the mode is
    // entered, so the primary action is never hidden behind a previous choice.
    var panel by rememberSaveable { mutableStateOf("ask") }

    // The library is what Listen offers, so it is read on entry exactly as the
    // Files view reads it, and the player is connected so a play tap is not
    // dropped while the controller is still connecting. Neither starts audio.
    LaunchedEffect(Unit) {
        vm.refreshFiles()
        vm.player.connect()
    }

    // The same confirm or reject tick the chat plays, because a driver cannot
    // look at the screen to learn that a prompt left or failed.
    val outcome = vm.outcome
    val view = LocalView.current
    LaunchedEffect(outcome) {
        val event = outcome ?: return@LaunchedEffect
        if (event.ok) Haptics.confirm(view) else Haptics.reject(view)
    }

    Scaffold(
        // Transparent so the app's sky shows through, the same as the chat.
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AnswerStyle.screenPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Car mode",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                // The way out, always on screen. It is not destructive and it
                // does not stop playback, so it is safe to reach for while the
                // audio is still going.
                TextButton(
                    onClick = { vm.updateCarMode(false) },
                    modifier = Modifier.heightIn(min = CarStyle.tap).tactile(haptics = true),
                ) {
                    Text("Exit", style = MaterialTheme.typography.titleLarge)
                }
            }
            CarNotices(vm)
            Spacer(modifier = Modifier.height(AnswerStyle.lineGap))
            // A question blocks the run in pi until it is answered, so while one
            // is open it is the body of the screen rather than something behind a
            // panel choice: the driver answers with the same big controls the rest
            // of the mode uses, reusing the widget and its answers from the normal
            // UI. Without this the questionnaire never appeared here and the
            // session was stuck with no way to answer it.
            val pending = vm.pendingQuestion
            if (pending != null) {
                QuestionCard(
                    pending = pending,
                    onSelect = { questionId, value -> vm.answerQuestion(questionId, value, false) },
                    onTyped = { questionId, text -> vm.answerQuestion(questionId, text, true) },
                    onCancel = vm::cancelQuestion,
                    controlHeight = CarStyle.tap,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                )
            } else {
                CarPanelSwitch(panel = panel, onChange = { panel = it })
                Spacer(modifier = Modifier.height(AnswerStyle.sectionGap))
                when (panel) {
                    "listen" -> CarListenPanel(vm = vm, modifier = Modifier.weight(1f).fillMaxWidth())
                    else -> CarAskPanel(
                        vm = vm,
                        listening = listening,
                        partialText = partialText,
                        onToggleMic = onToggleMic,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** The one navigation car mode has: two big buttons, no tab bar. */
@Composable
private fun CarPanelSwitch(panel: String, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AnswerStyle.controlGap),
    ) {
        CarPanelButton("Ask", selected = panel == "ask", modifier = Modifier.weight(1f)) { onChange("ask") }
        CarPanelButton("Listen", selected = panel == "listen", modifier = Modifier.weight(1f)) { onChange("listen") }
    }
}

/** One half of the switch: the chosen half is filled, the other is outlined. */
@Composable
private fun CarPanelButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val tall = modifier.heightIn(min = CarStyle.tap).tactile(haptics = true, depth = AnswerStyle.keyDepth)
    if (selected) {
        Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp), modifier = tall) { content() }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp), modifier = tall) { content() }
    }
}

/**
 * The talking half. One big mic to start and stop listening, the spoken submit
 * phrase on screen under it, the words that came back, and a big send for when
 * the phrase was not said. Three controls at most.
 */
@Composable
private fun CarAskPanel(
    vm: ChatViewModel,
    listening: Boolean,
    partialText: String,
    onToggleMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The trigger phrase is spelled out so the hands-free submit works
        // without the driver having to remember it.
        Text(
            text = "Say \u201csend it now\u201d to send",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(AnswerStyle.blockGap))
        // One touch to talk, one more to stop; the label says which will happen.
        Surface(
            onClick = onToggleMic,
            shape = CircleShape,
            color = if (listening) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(CarStyle.mic).tactile(haptics = true),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (listening) "Listening" else "Talk",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (listening) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(modifier = Modifier.height(AnswerStyle.blockGap))
        // What has been heard: the live partial while it is still being
        // recognised, the settled draft once it is. Big enough to read at a
        // glance; it scrolls rather than clipping a long prompt.
        val heard = partialText.ifBlank { vm.draft }
        Text(
            text = heard.ifBlank { "What you say appears here" },
            style = MaterialTheme.typography.headlineSmall,
            color = if (heard.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        )
        Spacer(modifier = Modifier.height(AnswerStyle.blockGap))
        // A manual send for when the phrase was not said. It runs the same send
        // the voice phrase runs, and it is disabled while there is nothing to
        // send, so a tap can never fire an empty prompt.
        val canSend = vm.draft.isNotBlank() && vm.composerBlock == null
        Button(
            onClick = {
                vm.send(vm.draft)
                vm.clearDraft()
            },
            enabled = canSend,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CarStyle.big)
                .tactile(haptics = true, enabled = canSend, depth = AnswerStyle.keyDepth),
        ) {
            Text("Send to pi", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * The listening half: the current audio with one big play/pause, then a short
 * list of files worth playing. The list is capped at three and puts the files
 * already on the phone first, because those play on the first tap.
 */
@Composable
private fun CarListenPanel(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val playingId = vm.player.playingId
    val playing = vm.player.playing
    val currentTitle = vm.files.firstOrNull { it.name == playingId }?.title ?: playingId

    Column(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(AnswerStyle.cardPadding)) {
                Text(
                    text = "Now playing",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = currentTitle ?: "Nothing playing",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (currentTitle == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (playingId != null) {
                    Spacer(modifier = Modifier.height(AnswerStyle.lineGap))
                    CarPlaybackReadout(vm)
                }
                Spacer(modifier = Modifier.height(AnswerStyle.lineGap))
                // One control for the current audio, and it says what it does.
                // No seek bar: a slider is the control a driver cannot aim.
                Button(
                    onClick = { playingId?.let { vm.playOrPause(it) } },
                    enabled = playingId != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = CarStyle.big)
                        .tactile(haptics = true, enabled = playingId != null, depth = AnswerStyle.keyDepth),
                ) {
                    Text(
                        text = if (playing) "Pause" else "Play",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(AnswerStyle.sectionGap))
        Text(
            text = "Play something",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(AnswerStyle.controlGap))
        // Audio and video rows that are ready to play. A render that is still
        // going or has failed is left out rather than offered as a control that
        // cannot work yet.
        val playable = vm.files
            .filter { (it.kind == "audio" || it.kind == "video") && it.state != "rendering" && it.state != "failed" }
            .sortedByDescending { vm.downloadedIds.contains(it.name) }
            .take(3)
        if (playable.isEmpty()) {
            Text(
                text = "No audio to play yet. Ask for a talk and it appears here.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            playable.forEach { row ->
                CarPlayRow(vm = vm, row = row)
                Spacer(modifier = Modifier.height(AnswerStyle.controlGap))
            }
        }
    }
}

/** One file in the short list, a full-width target that names what it plays. */
@Composable
private fun CarPlayRow(vm: ChatViewModel, row: FileRow) {
    val downloaded = vm.downloadedIds.contains(row.name)
    val progress = vm.downloadProgress[row.name]
    val isThis = vm.player.playingId == row.name
    Button(
        onClick = {
            // A file already on the phone plays at once; one that is not is
            // downloaded and then played, so a tap is one action either way.
            if (downloaded) vm.playOrPause(row.name) else vm.downloadFile(row.name, autoPlay = true)
        },
        enabled = progress == null,
        contentPadding = PaddingValues(horizontal = AnswerStyle.panelPadding),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CarStyle.tap)
            .tactile(haptics = true, enabled = progress == null, depth = AnswerStyle.keyDepth),
    ) {
        Text(
            text = when {
                progress != null -> "Downloading ${(progress * 100).roundToInt()}%"
                isThis && vm.player.playing -> "Pause ${row.title}"
                else -> "Play ${row.title}"
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Where the current audio is, as a readout rather than a control. It polls the
 * player while the panel is on screen, because the player does not push its
 * position, and it is not tappable: seeking is left to the normal Files screen.
 */
@Composable
private fun CarPlaybackReadout(vm: ChatViewModel) {
    val duration = vm.player.durationMs
    var position by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            position = vm.player.positionMs()
            delay(500)
        }
    }
    val ratio = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
    Text(
        text = "${carClock(position)} / ${carClock(duration)}",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The notice channel, in car-sized type. A send that failed has to be visible
 * here or the driver never learns the prompt did not leave; a tap clears it.
 */
@Composable
private fun CarNotices(vm: ChatViewModel) {
    if (vm.notices.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        vm.notices.forEach { notice ->
            Text(
                text = notice.text,
                style = MaterialTheme.typography.titleMedium,
                color = if (notice.kind == NoticeKind.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.dismissNotice(notice.id) }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

private fun carClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
