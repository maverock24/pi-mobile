package com.maverock24.pimobile.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PiRemoteTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
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
    val results = vm.messages.filter { it.role == "assistant" && it.text.isNotBlank() }
    val status = when {
        vm.busy -> "thinking…"
        !vm.connected -> vm.statusLine
        else -> ""
    }

    LaunchedEffect(results.size, results.lastOrNull()?.text?.length) {
        if (results.isNotEmpty()) {
            listState.animateScrollToItem(results.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.sessionTitle, style = MaterialTheme.typography.titleMedium)
                        if (status.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (vm.busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(12.dp).width(12.dp),
                                        strokeWidth = 1.5.dp,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (notice != null) {
                NoticeBar(text = notice, onDismiss = onDismissNotice)
            }
            vm.lastError?.let { error ->
                NoticeBar(text = error, onDismiss = vm::dismissError, isError = true)
            }

            if (results.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (vm.busy) "thinking…" else "no results yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(results, key = { it.id }) { message -> ResultBlock(message) }
                }
            }

            vm.pendingQuestion?.let { pending ->
                QuestionCard(
                    pending = pending,
                    onSelect = { questionId, value -> vm.answerQuestion(questionId, value, false) },
                    onTyped = { questionId, text -> vm.answerQuestion(questionId, text, true) },
                    onCancel = vm::cancelQuestion,
                )
            }

            Composer(
                draft = vm.draft,
                onDraftChange = vm::updateDraft,
                partialText = partialText,
                listening = listening,
                busy = vm.busy,
                onToggleMic = onToggleMic,
                onSend = {
                    vm.send(vm.draft)
                    vm.clearDraft()
                },
                onStop = vm::abort,
            )
        }
    }
}

@Composable
private fun ResultBlock(message: ChatMessage) {
    SelectionContainer {
        Text(
            text = ResultFormat.toAnnotatedString(
                answer = if (message.streaming) "${message.text}▌" else message.text,
                linkColor = MaterialTheme.colorScheme.primary,
            ),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = Modifier.fillMaxWidth(),
        )
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
            TextButton(onClick = onDismiss) { Text("OK") }
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
    onToggleMic: () -> Unit,
    onSend: () -> Unit,
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
            placeholder = { Text("Prompt pi…") },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onToggleMic) {
                Text(if (listening) "Mic on" else "Mic")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onSend, enabled = draft.isNotBlank()) { Text("Send") }
            Spacer(modifier = Modifier.width(8.dp))
            if (busy) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
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
    onSave: (String, String) -> Unit,
    onTest: () -> Unit,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(initialBaseUrl) }
    var token by rememberSaveable { mutableStateOf(initialToken) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = { TextButton(onClick = onBack) { Text("Back") } },
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
            Text("Bridge URL", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://your-laptop.your-tailnet.ts.net:8787") },
            )
            Text(
                text = "The laptop on your tailnet. The MagicDNS name is the default because " +
                    "Android evaluates its cleartext-HTTP policy per hostname; `tailscale ip -4` on " +
                    "the laptop prints the raw address if you need it.",
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onSave(baseUrl, token) }) { Text("Save") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onTest) { Text("Test") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onCheckUpdates) { Text("Update") }
            }
            if (statusLine.isNotBlank()) {
                Text(statusLine, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = versionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Answer a question widget from the phone. pi cannot continue until the widget
 * is answered, so this sits directly above the composer.
 */
@Composable
private fun QuestionCard(
    pending: PendingQuestion,
    onSelect: (String, String) -> Unit,
    onTyped: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val target = pending.firstUnanswered

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (pending.allAnswered) "sending answers…" else "pi is waiting for an answer",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = pending.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            pending.questions.forEach { question ->
                if (pending.multiple) {
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
                    question.options.forEach { option ->
                        OutlinedButton(
                            onClick = { onSelect(question.id, option.value) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                option.description?.let { description ->
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (target != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Type an answer for ${target.label}") },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onTyped(target.id, typed)
                            typed = ""
                        },
                        enabled = typed.isNotBlank(),
                    ) { Text("Send") }
                }
            }
            TextButton(onClick = onCancel) { Text("Cancel question") }
        }
    }
}
