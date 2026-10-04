package com.maverock24.pimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PiRemoteTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    listening: Boolean,
    partialText: String,
    notice: String?,
    onToggleMic: () -> Unit,
    onOpenSettings: () -> Unit,
    onCheckUpdates: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.sessionTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (vm.busy) "working…" else vm.statusLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onCheckUpdates) { Text("Update") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
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
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.messages, key = { it.id }) { message -> MessageBubble(message) }
            }
            Composer(
                draft = vm.draft,
                onDraftChange = vm::setDraft,
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
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val container = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isTool -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            colors = CardDefaults.cardColors(containerColor = container),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.9f else 1f),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (isTool) {
                    Text(
                        text = "⚙ ${message.toolName.orEmpty()}${if (message.isError) " (failed)" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SelectionContainer {
                    Text(
                        text = message.text.ifBlank { if (message.streaming) "…" else "" },
                        fontFamily = if (isTool) FontFamily.Monospace else FontFamily.Default,
                        fontSize = if (isTool) 12.sp else 15.sp,
                    )
                }
            }
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
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
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
                placeholder = { Text("http://192.0.2.1:8787") },
            )
            Text(
                text = "The laptop address on your tailnet. Inside Termux on the laptop: " +
                    "`tailscale ip -4` prints it.",
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
            }
            if (statusLine.isNotBlank()) {
                Text(statusLine, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(versionLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
