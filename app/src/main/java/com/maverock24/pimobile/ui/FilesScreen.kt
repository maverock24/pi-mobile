package com.maverock24.pimobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * The files library body: every file the bridge has, plus what this phone already
 * holds. A row plays an audio or video file, opens a text or image file, or says
 * plainly that the kind cannot be shown yet. A playing row carries a scrubber
 * long enough to move through a thirty minute talk.
 *
 * It is split from [FilesScreen] so the tab beside pins draws the same list
 * rather than a second copy of it.
 */
@Composable
fun FilesView(
    vm: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    // Reading the library is cheap and it is the reason the view was opened, so
    // it happens whenever the view comes on screen, and the view model keeps that
    // fact so a return to the foreground reads it again. Leaving the view clears
    // it, so a resume from anywhere else costs no fetch.
    DisposableEffect(Unit) {
        vm.filesShown()
        onDispose { vm.filesHidden() }
    }

    val preview = vm.filePreview
    if (preview != null) {
        FilePreviewView(preview = preview, onClose = { vm.closePreview() }, modifier = modifier)
        return
    }

    Column(modifier = modifier) {
        // A tap on the mini player asks for one file by name. The list scrolls to
        // that row and drops the request, so a later refresh does not drag the
        // list back to a row the human has already scrolled away from. The size
        // is part of the key because the library may still be on its way.
        val listState = rememberLazyListState()
        val focus = vm.filesFocus
        LaunchedEffect(focus, vm.files.size) {
            val wanted = focus ?: return@LaunchedEffect
            val index = vm.files.indexOfFirst { it.name == wanted }
            if (index < 0) return@LaunchedEffect
            listState.scrollToItem(index)
            vm.clearFilesFocus()
        }
        if (vm.token.isBlank()) {
            // Without a token there is nothing to ask, and saying so is more
            // useful than an empty list that looks like a failed render.
            Text(
                text = "Pair the bridge in Settings to see the file library",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(AnswerStyle.screenPadding),
            )
        }
        // An error over a library that still has rows belongs above them. When the
        // list is empty the box below carries the error instead, so the failure is
        // named once and never looks like the plain "No files yet" case.
        if (vm.files.isNotEmpty()) {
            vm.filesError?.let { problem ->
                ProblemFold(
                    problem = problem,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = AnswerStyle.screenPadding, vertical = 4.dp),
                )
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (vm.files.isEmpty()) {
                item {
                    val problem = vm.filesError
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(AnswerStyle.screenPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (vm.filesLoading || problem == null) {
                            Text(
                                text = if (vm.filesLoading) "Loading files…" else "No files yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            // The empty box carries the same fold as every other
                            // failure, so the library's reason is short and its
                            // exception is still one tap away.
                            ProblemFold(problem = problem, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            items(vm.files, key = { it.name }) { row -> FileRowCard(vm, row) }
        }
    }
}

/**
 * The library as a full screen, reached from the app bar's play icon. It wraps
 * [FilesView] so the screen and the tab stay one implementation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        // The mini player owns the bottom of the window while it is on screen;
        // see screenContentInsets.
        contentWindowInsets = screenContentInsets(),
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
                title = { Text("Files", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    if (vm.filesLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = { vm.refreshFiles() }, modifier = Modifier.tactile()) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh the library")
                    }
                },
            )
        },
    ) { padding ->
        FilesView(vm = vm, modifier = Modifier.fillMaxSize().padding(padding))
    }
}

/**
 * What a text or image row opened: text is drawn selectable, an image is drawn
 * whole, and a kind with nothing to draw yet carries the one sentence that says
 * so. The header carries the back control, since this replaces the list.
 */
@Composable
private fun FilePreviewView(
    preview: FilePreview,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.tactile()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = preview.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AnswerStyle.screenPadding),
        ) {
            when {
                preview.image != null -> Image(
                    bitmap = preview.image.asImageBitmap(),
                    contentDescription = preview.title,
                    modifier = Modifier.fillMaxWidth(),
                )
                // A script is written prose, so it is drawn with the same blocks,
                // type scale and paragraph rhythm an answer is drawn with. A plain
                // text file keeps the selectable body text it has always had.
                preview.text != null && preview.kind == "script" -> AnswerView(
                    text = preview.text,
                    modifier = Modifier.fillMaxWidth(),
                )
                preview.text != null -> SelectionContainer {
                    Text(
                        text = preview.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                else -> Text(
                    text = preview.message ?: "Nothing to show",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The scrubber for the file the player holds. It is drawn inside that file's
 * own row so the row itself is the whole control, and only while that file is
 * the one loaded, so a stopped library shows no slider at all.
 */
@Composable
private fun FileScrubber(vm: ChatViewModel, name: String) {
    val duration = vm.player.durationMs
    var position by remember(name) { mutableStateOf(0L) }
    var scrubbing by remember(name) { mutableStateOf(false) }
    var scrubValue by remember(name) { mutableStateOf(0f) }

    // The player does not push its position, so the bar polls it while it is on
    // screen. Polling pauses under the thumb, so the bar does not fight the drag.
    LaunchedEffect(name) {
        while (isActive) {
            if (!scrubbing) position = vm.player.positionMs()
            delay(500)
        }
    }

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
    Text(
        text = "${formatMillis(position)} / ${formatMillis(duration)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One library row, its controls chosen by the kind, the render state and storage. */
@Composable
private fun FileRowCard(vm: ChatViewModel, row: FileRow) {
    val downloaded = vm.downloadedIds.contains(row.name)
    val progress = vm.downloadProgress[row.name]
    val isPlayingThis = vm.player.playingId == row.name && vm.player.playing
    // A talk's row carries the id the record was written under and the mp3 the
    // id renders to, so the two differing is what says "this has a script beside
    // it". A bare file's row has its own name as its id and gets no script tap.
    val scriptName = if (row.id.isNotBlank() && row.id != row.name) "${row.id}.txt" else null
    val scriptProgress = scriptName?.let { vm.downloadProgress[it] }

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
                fileKindLabel(row.kind),
                row.state.takeIf { it != "ready" },
                fileDuration(row).takeIf { it.isNotBlank() },
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
            // The scrubber sits in the file's own row and only while that file
            // is the one the player holds. A stopped file shows no slider, so a
            // stopped library is just rows.
            if (vm.player.playingId == row.name) {
                Spacer(modifier = Modifier.height(AnswerStyle.controlGap))
                FileScrubber(vm = vm, name = row.name)
            }
            Spacer(modifier = Modifier.height(AnswerStyle.controlGap))
            when {
                row.state == "rendering" -> {
                    // An indeterminate bar is honest here: the bridge reports the
                    // state but not how far the render has got. There is no button,
                    // so a talk still being rendered cannot be tapped into playback.
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
                row.kind == "other" -> {
                    // The one honest thing to do with a kind the app cannot draw
                    // yet is to say so, rather than offering a tap that does nothing.
                    Text(
                        text = "This file cannot be shown here yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                row.kind == "audio" || row.kind == "video" -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(AnswerStyle.controlGap)) {
                        if (downloaded) {
                            Button(onClick = { vm.playOrPause(row.name) }, modifier = Modifier.tactile(haptics = true)) {
                                Text(if (isPlayingThis) "Pause" else "Play")
                            }
                            OutlinedButton(onClick = { vm.deleteFile(row.name) }, modifier = Modifier.tactile()) {
                                Text("Delete")
                            }
                        } else {
                            // A ready file that is not on the phone yet still plays on
                            // the first tap: the download runs and the playback follows
                            // it, so listening is one action rather than two.
                            Button(
                                onClick = { vm.downloadFile(row.name, autoPlay = true) },
                                modifier = Modifier.tactile(haptics = true),
                            ) {
                                Text("Play")
                            }
                        }
                    }
                    // The script sits under the play controls rather than beside
                    // them: three buttons in one row overflow at a large font scale,
                    // and reading the words is a second thing to do with an episode,
                    // not an alternative to hearing it.
                    if (scriptName != null) {
                        Spacer(modifier = Modifier.height(AnswerStyle.controlGap))
                        OutlinedButton(
                            onClick = { vm.openScript(row) },
                            enabled = scriptProgress == null,
                            modifier = Modifier.fillMaxWidth().tactile(enabled = scriptProgress == null),
                        ) {
                            Text(
                                text = when {
                                    scriptProgress != null -> "Loading script ${(scriptProgress * 100).roundToInt()}%"
                                    else -> "Read the script"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                else -> {
                    // Text and image: Open shows the file, downloading first when
                    // this phone does not hold it yet.
                    Row(horizontalArrangement = Arrangement.spacedBy(AnswerStyle.controlGap)) {
                        Button(onClick = { vm.openFile(row.name) }, modifier = Modifier.tactile(haptics = true)) {
                            Text("Open")
                        }
                        if (downloaded) {
                            OutlinedButton(onClick = { vm.deleteFile(row.name) }, modifier = Modifier.tactile()) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The word a row shows for a file's kind. Shared with the mini player, so what
 * is playing is named there exactly as it is named in the list. */
internal fun fileKindLabel(kind: String): String = when (kind) {
    "audio" -> "audio"
    "video" -> "video"
    "image" -> "image"
    "text" -> "text"
    else -> "file"
}

/** A talk's length, marked as an estimate when the bridge estimated it. */
private fun fileDuration(row: FileRow): String {
    val seconds = row.durationSeconds
    if (seconds <= 0.0) return ""
    if (row.durationEstimated) {
        val minutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        return "about $minutes min"
    }
    return formatMillis((seconds * 1000).toLong())
}

/** A length on one line. Shared by the library rows, the mini player's clock and
 * the notices, so the same position is written the same way everywhere. */
internal fun formatMillis(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** A size as one short line, shared by the library rows, Settings and the notices. */
internal fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024 * 1024 -> "%.0f kB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
