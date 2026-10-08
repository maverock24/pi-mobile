package com.maverock24.pimobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun PiRemoteTheme(mode: String = "dark", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) AnswerStyle.darkScheme else AnswerStyle.lightScheme, content = content)
}

/**
 * The bar's working indicator: an accent line sweeping along its top edge, shown
 * only while pi is busy. It is drawn over the bar rather than laid out above or
 * beside it, so the bar keeps its height and nothing under it moves. The sweep
 * runs on the same 1300ms rhythm and the same two colours as the working
 * shimmer, so the two read as one signal.
 */
@Composable
private fun Modifier.workingEdge(visible: Boolean): Modifier {
    if (!visible) return this
    val progress = rememberSweep(SWEEP_PERIOD_MS)
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    return this.drawWithContent {
        drawContent()
        val thickness = 2.dp.toPx()
        // A dim line so the bar has an edge at all, with the accent travelling
        // along it: a bar of the same colour as the transcript needs both.
        drawRect(color = track, size = Size(size.width, thickness))
        val travel = size.width * 0.4f
        val start = progress.value * (size.width + travel) - travel
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, accent, Color.Transparent),
                startX = start,
                endX = start + travel,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, thickness),
        )
    }
}

/**
 * Results-only view.
 *
 * Answers are what matter on a phone, so the transcript shows finished (and
 * in-flight) assistant answers and nothing else: no prompts, no tool calls, no
 * model or cost bookkeeping. The only status shown is that pi is thinking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    listening: Boolean,
    partialText: String,
    notice: String?,
    onToggleMic: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val listState = rememberLazyListState()
    val turns = vm.turns
    val pending = vm.pendingQuestion
    val liveAnswer = vm.liveAnswer

    // Accordion: exactly one answer is open. Until you pick a prompt that is the
    // newest turn, and a new prompt or a fresh answer puts the choice back
    // there, so the answer you are waiting for is never the one left folded.
    var explicitTurn by rememberSaveable { mutableStateOf<String?>(null) }
    var followNewest by rememberSaveable { mutableStateOf(true) }
    val newestTurn = turns.lastOrNull()?.id
    val expandedTurn = if (followNewest) newestTurn else explicitTurn

    LaunchedEffect(newestTurn, turns.lastOrNull()?.answers?.size) { followNewest = true }

    LaunchedEffect(pending?.id, turns.size, turns.lastOrNull()?.answers?.size) {
        when {
            // pi is blocked until the widget is answered, so it stays on screen.
            pending != null -> listState.animateScrollToItem(0)
            turns.isNotEmpty() -> listState.animateScrollToItem(turns.lastIndex)
        }
    }

    // The outcome of a request the user started: confirm when the bridge took
    // it, reject when it failed. Only these two paths and never a background
    // poll, so a reject always means something the user just did.
    val outcome = vm.outcome
    val view = LocalView.current
    LaunchedEffect(outcome) {
        val event = outcome ?: return@LaunchedEffect
        if (event.ok) Haptics.confirm(view) else Haptics.reject(view)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // The working accent line rides on the bar's own top edge, so it
                // costs the bar no height and shifts nothing below it.
                modifier = Modifier.workingEdge(vm.busy),
                title = {
                    Column {
                        Text(
                            text = vm.lastPrompt?.takeIf { it.isNotBlank() } ?: vm.sessionTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        when {
                            vm.pendingQuestion != null -> Text(
                                text = "waiting for your answer",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            vm.busy -> WorkingShimmer(modifier = Modifier.padding(top = 4.dp))
                            !vm.connected && vm.statusLine.isNotBlank() -> Text(
                                text = vm.statusLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = { TextButton(onClick = onOpenSettings, modifier = Modifier.tactile()) { Text("Settings") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Which session this screen is showing. Once there is a prompt the
            // title bar carries that instead, so the identity of the session
            // gets a line of its own here, above the transcript, and it stays put
            // while the answers scroll. When the bridge changes hands the screen
            // follows it, and the line below says which session it moved to.
            val attachedTo = vm.attachedLabel
            if (attachedTo.isNotBlank()) {
                Text(
                    text = attachedTo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (notice != null) {
                NoticeBar(text = notice, onDismiss = onDismissNotice)
            }
            vm.lastError?.let { error ->
                NoticeBar(text = error, onDismiss = vm::dismissError, isError = true)
            }

            // A handover is named here, in one line, rather than a bar with an
            // attach button: the move has already happened, and this says so.
            vm.sessionNotice?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (turns.isEmpty() && pending == null) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (vm.busy) "thinking…" else "no results yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // One scrolling surface for the question widget and the
                // transcript. pi is blocked until the widget is answered, so
                // the card is the first item, and its options keep their full
                // height instead of living inside a scroll box of their own.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(AnswerStyle.answerGap),
                ) {
                    if (pending != null) {
                        item(key = "pending-question") {
                            QuestionCard(
                                pending = pending,
                                onSelect = { questionId, value -> vm.answerQuestion(questionId, value, false) },
                                onTyped = { questionId, text -> vm.answerQuestion(questionId, text, true) },
                                onCancel = vm::cancelQuestion,
                            )
                        }
                    }
                    items(turns, key = { it.id }) { turn ->
                        TurnView(
                            turn = turn,
                            expanded = turn.id == expandedTurn,
                            liveText = if (turn.id == newestTurn) liveAnswer else null,
                            working = turn.id == newestTurn && vm.busy,
                            onToggle = {
                                followNewest = false
                                explicitTurn = if (expandedTurn == turn.id) null else turn.id
                            },
                        )
                    }
                }
            }

            Composer(
                draft = vm.draft,
                onDraftChange = vm::updateDraft,
                partialText = partialText,
                listening = listening,
                busy = vm.busy,
                blockReason = vm.composerBlock,
                onToggleMic = onToggleMic,
                onSend = {
                    vm.send(vm.draft)
                    vm.clearDraft()
                },
                onClear = vm::clearDraft,
                onStop = vm::abort,
            )
        }
    }
}

/**
 * One prompt and the answer it produced. The prompt is always there in full,
 * so scrolling back through a session reads as a list of what you asked for;
 * tapping it opens the answer, and tapping it again folds the answer away.
 * Nothing else of the run is shown, which is why an older answer is reachable
 * from its prompt alone.
 */
@Composable
private fun TurnView(
    turn: ChatTurn,
    expanded: Boolean,
    liveText: String?,
    working: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val prompt = turn.prompt
    val openable = turn.answers.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(AnswerStyle.paragraphGap)) {
        if (prompt != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = openable, onClick = onToggle)
                    .padding(vertical = 2.dp)
                    .tactile(enabled = openable),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp, end = AnswerStyle.accentBarGap)
                        .width(AnswerStyle.accentBar)
                        .heightIn(min = 18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.primary),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = prompt,
                        fontSize = AnswerStyle.bodySize,
                        lineHeight = AnswerStyle.bodyLineHeight,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onBackground,
                    )
                    if (openable) {
                        Text(
                            text = if (expanded) "Hide answer" else "Show answer",
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = AnswerStyle.accentBar + AnswerStyle.accentBarGap),
                verticalArrangement = Arrangement.spacedBy(AnswerStyle.answerGap),
            ) {
                turn.answers.forEach { answer ->
                    if (answer.role == QUESTION_ROLE) {
                        AnsweredQuestion(
                            question = answer.text,
                            answer = answer.answer,
                            modifier = Modifier.widthIn(max = AnswerStyle.measure),
                        )
                    } else {
                        AnswerView(
                            text = answer.text,
                            modifier = Modifier.widthIn(max = AnswerStyle.measure),
                        )
                    }
                }
                // The answer of the run that is still going. It is drawn like a
                // finished answer and replaced by the committed one when the run
                // settles, which clears it in the same step that adds the answer.
                if (!liveText.isNullOrBlank()) {
                    AnswerView(
                        text = liveText,
                        modifier = Modifier.widthIn(max = AnswerStyle.measure),
                    )
                } else if (working) {
                    // Nothing has streamed yet, so the run has nothing to show but
                    // itself: this is the step between the prompt above and the
                    // answer that will replace it.
                    WorkingShimmer()
                }
            }
        }
    }
}

/**
 * A question widget the person answered, kept where it happened and drawn the
 * way the widget itself was, in the same surface and border as [QuestionCard].
 * The transcript then shows both halves of the exchange: what pi asked and what
 * came back. A question dismissed without an answer says so rather than naming a
 * choice that was never made, which is also how the tool records it.
 */
@Composable
private fun AnsweredQuestion(question: String, answer: String?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (question.isNotBlank()) {
                Text(
                    text = question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = answer?.lines()?.joinToString("\n") { "✓ $it" } ?: "cancelled, no answer",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (answer == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun NoticeBar(text: String, onDismiss: () -> Unit, isError: Boolean = false) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss, modifier = Modifier.tactile()) { Text("OK") }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    partialText: String,
    listening: Boolean,
    busy: Boolean,
    blockReason: String?,
    onToggleMic: () -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    onStop: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        if (listening && partialText.isNotBlank()) {
            Text(
                text = partialText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 6,
            placeholder = { Text(blockReason ?: "Prompt pi…") },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            val buttonModifier = Modifier.heightIn(min = AnswerStyle.buttonHeight)
            val label = MaterialTheme.typography.bodyLarge
            val canSend = draft.isNotBlank() && blockReason == null
            FilledTonalButton(
                onClick = onToggleMic,
                modifier = buttonModifier.tactile(haptics = true, depth = AnswerStyle.keyDepth),
            ) {
                Text(if (listening) "Mic on" else "Mic", style = label)
            }
            Button(
                onClick = onSend,
                enabled = canSend,
                modifier = buttonModifier.tactile(haptics = true, enabled = canSend, depth = AnswerStyle.keyDepth),
            ) {
                Text("Send", style = label)
            }
            OutlinedButton(
                onClick = onClear,
                enabled = draft.isNotBlank(),
                modifier = buttonModifier.tactile(enabled = draft.isNotBlank(), depth = AnswerStyle.keyDepth),
            ) {
                Text("Clear", style = label)
            }
            if (busy) {
                OutlinedButton(
                    onClick = onStop,
                    modifier = buttonModifier.tactile(haptics = true, depth = AnswerStyle.keyDepth),
                ) {
                    Text("Stop", style = label)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialBaseUrl: String,
    initialToken: String,
    statusLine: String,
    versionLabel: String,
    sessionLabel: String,
    appearance: String,
    onAppearanceChange: (String) -> Unit,
    onPairLink: (String) -> Unit,
    onSave: (String, String) -> Unit,
    onTest: () -> Unit,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(initialBaseUrl) }
    var token by rememberSaveable { mutableStateOf(initialToken) }

    // Pairing lives here rather than in the chat screen because it is setup work: the
    // scanner reads the QR /pair drew, and both routes end in the same link parser.
    val context = LocalContext.current
    var pastedLink by rememberSaveable { mutableStateOf("") }
    var pairingStatus by remember { mutableStateOf("") }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned.isNullOrBlank()) {
            pairingStatus = "No QR code was read"
        } else {
            pairingStatus = "Using the scanned link…"
            onPairLink(scanned)
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(pairingScanOptions())
        } else {
            pairingStatus = "Camera permission is denied; paste the link below instead"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = { TextButton(onClick = onBack, modifier = Modifier.tactile()) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pairing", style = MaterialTheme.typography.labelLarge)
            val pairButton = Modifier.heightIn(min = AnswerStyle.buttonHeight)
            val pairLabel = MaterialTheme.typography.bodyLarge
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        scanLauncher.launch(pairingScanOptions())
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = pairButton.tactile(haptics = true, depth = AnswerStyle.keyDepth),
            ) {
                Text("Scan the pairing QR", style = pairLabel)
            }
            Text(
                text = "On the laptop run /pair in the pi session that serves the bridge; a window " +
                    "with the QR opens. Scanning it fills in the address and the token below and " +
                    "pairs straight away. The camera is used to read that one code.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = pastedLink,
                onValueChange = { pastedLink = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("pi-remote://pair?v=1&u=…&c=…") },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        onPairLink(pastedLink)
                        pastedLink = ""
                    },
                    enabled = pastedLink.isNotBlank(),
                    modifier = pairButton.tactile(haptics = true, enabled = pastedLink.isNotBlank(), depth = AnswerStyle.keyDepth),
                ) {
                    Text("Pair with this link", style = pairLabel)
                }
            }
            if (pairingStatus.isNotBlank()) {
                Text(pairingStatus, style = MaterialTheme.typography.bodySmall)
            }
            Text("Bridge URL", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://your-laptop.your-tailnet.ts.net:8787") },
            )
            Text(
                text = "The laptop on your tailnet. A pairing QR fills this in for you; the " +
                    "address itself never ships with the app. Use the MagicDNS name, not a raw " +
                    "IP: Android checks its cleartext-HTTP policy per hostname.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Token", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("paste the token") },
            )
            Text(
                text = "On the laptop: cat ~/.config/pi-remote/token",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Appearance", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("dark" to "Dark", "light" to "Light", "system" to "System").forEach { (value, label) ->
                    OutlinedButton(
                        onClick = { onAppearanceChange(value) },
                        modifier = Modifier.padding(end = 8.dp)
                            .heightIn(min = AnswerStyle.buttonHeight)
                            .tactile(depth = AnswerStyle.keyDepth),
                    ) {
                        Text(
                            text = if (appearance == value) "• $label" else label,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = MaterialTheme.typography.bodyLarge
                val size = Modifier.heightIn(min = AnswerStyle.buttonHeight)
                Button(
                    onClick = { onSave(baseUrl, token) },
                    modifier = size.tactile(haptics = true, depth = AnswerStyle.keyDepth),
                ) { Text("Save", style = label) }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onTest,
                    modifier = size.tactile(depth = AnswerStyle.keyDepth),
                ) { Text("Test", style = label) }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onCheckUpdates,
                    modifier = size.tactile(haptics = true, depth = AnswerStyle.keyDepth),
                ) { Text("Update", style = label) }
            }
            if (statusLine.isNotBlank()) {
                Text(statusLine, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "session: $sessionLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = versionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** ZXing runs the scan in its own activity and hands back the raw QR text. */
private fun pairingScanOptions(): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt("Scan the pairing QR from the laptop")
    .setBeepEnabled(false)
    .setOrientationLocked(false)
    .setBarcodeImageEnabled(false)

/**
 * Answer a question widget from the phone. pi cannot continue until the widget
 * is answered, so this is the first thing on screen. It is laid out at full
 * height, every question and every option in one column: the transcript is the
 * only scroller, so no option hides behind a scroll box of its own.
 */
@Composable
private fun QuestionCard(
    pending: PendingQuestion,
    onSelect: (String, String) -> Unit,
    onTyped: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val targetId = pending.firstUnanswered?.id
    val several = pending.questions.size > 1
    val answered = pending.questions.count { it.answer != null }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    pending.allAnswered -> "sending answers…"
                    several -> "$answered of ${pending.questions.size} answered"
                    else -> "pi is waiting for an answer"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = pending.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            pending.questions.forEach { question ->
                if (several) {
                    Text(
                        text = "${question.label}: ${question.prompt}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val answer = question.answer
                if (answer != null) {
                    Text(
                        text = "✓ $answer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The option's own shape goes to the tactile modifier as
                        // well: the depth it draws is cut from the same outline
                        // as the face, so the two edges line up.
                        val optionShape = RoundedCornerShape(12.dp)
                        question.options.forEachIndexed { index, option ->
                            Button(
                                onClick = { onSelect(question.id, option.value) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = AnswerStyle.buttonHeight)
                                    .tactile(
                                        haptics = true,
                                        depth = AnswerStyle.keyDepth,
                                        shape = optionShape,
                                    ),
                                shape = optionShape,
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Text(
                                        text = "${index + 1}. ${option.label}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    option.description?.let { description ->
                                        Text(
                                            text = description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                                        )
                                    }
                                }
                            }
                        }
                        // The free-text field belongs to the question it answers,
                        // which is the first one still open.
                        if (question.id == targetId) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = typed,
                                    onValueChange = { typed = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    placeholder = { Text("Or type your own answer") },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        onTyped(question.id, typed)
                                        typed = ""
                                    },
                                    enabled = typed.isNotBlank(),
                                    modifier = Modifier
                                        .heightIn(min = AnswerStyle.buttonHeight)
                                        .tactile(
                                            haptics = true,
                                            enabled = typed.isNotBlank(),
                                            depth = AnswerStyle.keyDepth,
                                        ),
                                ) { Text("Send") }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onCancel, modifier = Modifier.tactile(haptics = true)) {
                Text("Cancel question")
            }
        }
    }
}
