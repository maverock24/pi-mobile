package com.maverock24.pimobile.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maverock24.pimobile.data.SettingsStore
import com.maverock24.pimobile.net.Diagnostics
import com.maverock24.pimobile.net.PiRemoteClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val toolName: String? = null,
    val isError: Boolean = false,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val client = PiRemoteClient { store.baseUrl to store.token }
    private val main = Handler(Looper.getMainLooper())

    val messages = mutableStateListOf<ChatMessage>()

    /** Composer text. Lives here so dictation and the UI share one source. */
    var draft by mutableStateOf("")
        private set

    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var sessionTitle by mutableStateOf("not connected")
        private set
    var statusLine by mutableStateOf("")
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    private var streamCall: Call? = null
    private var streamingId: String? = null

    val baseUrl: String get() = store.baseUrl
    val token: String get() = store.token
    val isConfigured: Boolean get() = store.isConfigured

    fun saveSettings(baseUrl: String, token: String) {
        store.baseUrl = baseUrl
        store.token = token
        messages.clear()
        connect()
    }

    fun connect() {
        disconnect()
        if (!store.isConfigured) {
            statusLine = "Add the bridge token in Settings"
            return
        }
        statusLine = "connecting to ${store.baseUrl}"
        val call = runCatching {
            client.streamEvents(
                onOpen = { main.post { connected = true; statusLine = "connected" } },
                onEvent = { event -> main.post { handleEvent(event) } },
                onClosed = { error ->
                    main.post {
                        connected = false
                        busy = false
                        statusLine = if (error == null) {
                            "disconnected"
                        } else {
                            Diagnostics.describe(error, store.baseUrl)
                        }
                    }
                },
            )
        }.getOrElse { error ->
            statusLine = Diagnostics.describe(error, store.baseUrl)
            connected = false
            return
        }
        streamCall = call
        reloadHistory()
        refreshState()
    }

    fun disconnect() {
        streamCall?.cancel()
        streamCall = null
        connected = false
        busy = false
    }

    fun refreshState() {
        safeLaunch {
            runCatching { client.state() }
                .onSuccess { state ->
                    busy = !state.optBoolean("idle", true)
                    val name = state.optString("sessionName").takeIf { it.isNotBlank() && it != "null" }
                    val file = state.optString("sessionFile").substringAfterLast('/')
                    sessionTitle = name ?: file.ifBlank { "pi session" }
                    val model = state.optJSONObject("model")?.optString("id").orEmpty()
                    statusLine = listOf(if (connected) "connected" else statusLine, model)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                }
                .onFailure { lastError = "state: ${Diagnostics.describe(it, store.baseUrl)}" }
        }
    }

    fun reloadHistory() {
        safeLaunch {
            runCatching { client.history(80) }
                .onSuccess { payload ->
                    val array = payload.optJSONArray("messages") ?: JSONArray()
                    val parsed = ArrayList<ChatMessage>()
                    for (index in 0 until array.length()) {
                        val entry = array.optJSONObject(index) ?: continue
                        toMessage(entry)?.let { parsed.add(it) }
                    }
                    messages.clear()
                    messages.addAll(parsed)
                }
                .onFailure { lastError = "history: ${Diagnostics.describe(it, store.baseUrl)}" }
        }
    }

    /**
     * Runs a coroutine with a crash guard. This app talks to the network and to
     * platform services from several places, and one unexpected exception should
     * surface as an error line instead of taking the process down.
     */
    private fun safeLaunch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                lastError = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            }
        }
    }

    fun updateDraft(value: String) {
        draft = value
    }

    fun appendToDraft(text: String) {
        if (text.isBlank()) return
        draft = if (draft.isBlank()) text else "${draft.trimEnd()} $text"
    }

    fun clearDraft() {
        draft = ""
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        messages.add(ChatMessage(id = "local-${UUID.randomUUID()}", role = "user", text = trimmed))
        busy = true
        safeLaunch {
            runCatching { client.prompt(trimmed) }
                .onFailure {
                    busy = false
                    lastError = "prompt: ${Diagnostics.describe(it, store.baseUrl)}"
                }
        }
    }

    fun abort() {
        safeLaunch { runCatching { client.abort() }.onFailure { lastError = it.message } }
    }

    fun testConnection() {
        safeLaunch {
            val context = getApplication<Application>()
            statusLine = runCatching { Diagnostics.probe(context, store.baseUrl) }
                .getOrElse { "probe error: ${it.javaClass.simpleName}" }
            runCatching { client.state() }
                .onSuccess { statusLine = "$statusLine · HTTP OK" }
                .onFailure { statusLine = "$statusLine · ${Diagnostics.describe(it, store.baseUrl)}" }
        }
    }

    fun dismissError() {
        lastError = null
    }

    private fun handleEvent(event: JSONObject) {
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
        when (type) {
            "state" -> {
                busy = !data.optBoolean("idle", true)
                val file = data.optString("sessionFile").substringAfterLast('/')
                if (file.isNotBlank()) sessionTitle = data.optString("sessionName").takeIf { it.isNotBlank() && it != "null" } ?: file
            }
            "prompt_accepted", "agent_start", "turn_start" -> busy = true
            "agent_settled", "agent_end" -> {
                busy = false
                streamingId = null
                refreshState()
            }
            "message_start" -> {
                val message = data.optJSONObject("message")
                val role = message?.optString("role").orEmpty()
                if (role == "assistant") {
                    val id = "assistant-${UUID.randomUUID()}"
                    streamingId = id
                    messages.add(ChatMessage(id = id, role = "assistant", text = textOf(message, "content"), streaming = true))
                } else if (role == "user") {
                    val text = textOf(message, "content")
                    if (text.isNotBlank() && messages.none { it.role == "user" && it.text == text }) {
                        messages.add(ChatMessage(id = "user-${UUID.randomUUID()}", role = "user", text = text))
                    }
                }
            }
            "message_update" -> {
                val delta = data.optJSONObject("assistantMessageEvent")?.optString("delta").orEmpty()
                val id = streamingId ?: return
                val index = messages.indexOfFirst { it.id == id }
                if (index >= 0 && delta.isNotEmpty()) {
                    val current = messages[index]
                    messages[index] = current.copy(text = current.text + delta)
                }
            }
            "message_end" -> {
                val message = data.optJSONObject("message")
                val id = streamingId
                if (id != null && message?.optString("role") == "assistant") {
                    val index = messages.indexOfFirst { it.id == id }
                    if (index >= 0) {
                        val finalText = textOf(message, "content").ifBlank { messages[index].text }
                        messages[index] = messages[index].copy(text = finalText, streaming = false)
                    }
                }
                streamingId = null
            }
            "tool_execution_start" -> {
                val name = data.optString("toolName")
                messages.add(
                    ChatMessage(
                        id = "tool-${data.optString("toolCallId", UUID.randomUUID().toString())}",
                        role = "tool",
                        text = summarizeArgs(data.opt("args")),
                        toolName = name,
                    ),
                )
            }
            "tool_execution_end" -> {
                val callId = data.optString("toolCallId")
                val index = messages.indexOfFirst { it.id == "tool-$callId" }
                if (index >= 0 && data.optBoolean("isError", false)) {
                    messages[index] = messages[index].copy(isError = true)
                }
            }
            "model_select" -> refreshState()
        }
    }

    private fun toMessage(entry: JSONObject): ChatMessage? {
        val message = entry.optJSONObject("message") ?: return null
        val role = message.optString("role").ifBlank { return null }
        val text = textOf(message, "content")
        if (text.isBlank() && role != "toolResult") return null
        return ChatMessage(
            id = entry.optString("id", UUID.randomUUID().toString()),
            role = if (role == "toolResult") "tool" else role,
            text = text,
            toolName = message.optString("toolName").takeIf { role == "toolResult" && it.isNotBlank() },
        )
    }

    /** Flattens text, thinking and tool-call content blocks into display text. */
    private fun textOf(message: JSONObject?, key: String): String {
        val content = message?.opt(key) ?: return ""
        if (content is String) return content
        if (content !is JSONArray) return ""
        val parts = ArrayList<String>()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "text" -> parts.add(block.optString("text"))
                "thinking" -> parts.add("(thinking)")
                "toolCall" -> parts.add("↳ ${block.optString("name")}${summarizeArgs(block.opt("arguments"))}")
                "image" -> parts.add("[image]")
            }
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun summarizeArgs(args: Any?): String {
        val text = args?.toString().orEmpty()
        if (text.isBlank() || text == "null") return ""
        val compact = text.replace(Regex("\\s+"), " ")
        return if (compact.length > 140) " ${compact.take(140)}…" else " $compact"
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
