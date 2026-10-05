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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

/**
 * One prompt and the answers it produced. The chat lists prompts and opens the
 * answer behind the one you tap, so a turn keeps the two together.
 */
data class ChatTurn(
    val id: String,
    val prompt: String?,
    val answers: List<ChatMessage>,
)

/**
 * Groups the transcript by prompt. An assistant message carries no link to the
 * prompt that caused it, but the order does: every answer that follows a prompt
 * belongs to it. Answers that arrive before any prompt, which happens when the
 * history is fetched mid-run, become a turn of their own with a null prompt.
 * Tool narration never reaches [messages], so nothing sits between the two.
 */
fun turnsOf(messages: List<ChatMessage>): List<ChatTurn> {
    val turns = ArrayList<ChatTurn>()
    var prompt: ChatMessage? = null
    val answers = ArrayList<ChatMessage>()
    for (message in messages) {
        when (message.role) {
            "user" -> {
                if (prompt != null || answers.isNotEmpty()) {
                    turns.add(ChatTurn(prompt?.id ?: "lead-${turns.size}", prompt?.text, ArrayList(answers)))
                    answers.clear()
                }
                prompt = message
            }
            "assistant" -> if (message.text.isNotBlank()) answers.add(message)
        }
    }
    if (prompt != null || answers.isNotEmpty()) {
        turns.add(ChatTurn(prompt?.id ?: "lead-${turns.size}", prompt?.text, ArrayList(answers)))
    }
    return turns
}

/**
 * The result of a request the user started, which the chat screen plays back as a
 * confirm or reject tick. [id] changes per event, so the same outcome twice in a
 * row still fires the feedback.
 */
data class RequestOutcome(val id: Long, val ok: Boolean)

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
    var pendingQuestion by mutableStateOf<PendingQuestion?>(null)
        private set
    var appearance by mutableStateOf(store.appearance)
        private set

    /** Set when a request the user started succeeds or fails. */
    var outcome by mutableStateOf<RequestOutcome?>(null)
        private set

    private var outcomeSeq = 0L

    /** The latest thing you typed, shown at the top of the main view. */
    val lastPrompt: String?
        get() = messages.lastOrNull { it.role == "user" && it.text.isNotBlank() }?.text

    /** The transcript grouped into prompts, which is what the chat screen lists. */
    val turns: List<ChatTurn>
        get() = turnsOf(messages)

    private var streamCall: Call? = null
    private var pollJob: Job? = null

    /**
     * Answers are committed only when the run settles, and only if the message
     * carried no tool calls. Intermediate narration ("let me check the file")
     * therefore never reaches the screen: the phone shows the result, not the
     * process.
     */
    private val runCandidates = ArrayList<Pair<String, Boolean>>()
    private var activeAssistant: StringBuilder? = null
    private var activeHasToolCalls = false

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
        startPolling()
        safeLaunch {
            runCatching { client.question() }
                .onSuccess { applyQuestion(it.optJSONObject("pending")) }
        }
    }

    fun disconnect() {
        streamCall?.cancel()
        streamCall = null
        pollJob?.cancel()
        pollJob = null
        connected = false
        busy = false
    }

    /**
     * Someone has to notice when pi opens a question widget or goes idle. The
     * event stream is the fast path, but it dies whenever the phone sleeps or
     * switches networks, and a missed "question" event used to leave the app
     * with no way to answer at all. A slow poll is the safety net.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(3000)
                if (!store.isConfigured) continue
                runCatching { client.state() }.onSuccess { applyState(it) }
            }
        }
    }

    /** Called when the app comes back to the foreground. */
    fun ensureConnected() {
        if (!connected) connect() else refreshState()
    }

    private fun applyState(state: JSONObject) {
        busy = !state.optBoolean("idle", true)
        val name = state.optString("sessionName").takeIf { it.isNotBlank() && it != "null" }
        val file = state.optString("sessionFile").substringAfterLast('/')
        sessionTitle = name ?: file.ifBlank { "pi session" }
        applyQuestion(state.optJSONObject("question"))
    }

    fun refreshState() {
        safeLaunch {
            runCatching { client.state() }
                .onSuccess { state ->
                    applyState(state)
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
                        val message = toMessage(entry) ?: continue
                        if (message.role == "assistant" && message.toolName != null) {
                            continue // narration on the way to a tool call
                        }
                        parsed.add(message)
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

    fun updateAppearance(value: String) {
        store.appearance = value
        appearance = value
    }

    /**
     * QR pairing wrote a fresh device token on the laptop: adopt it and reconnect.
     * Nothing is stored on the phone beyond this endpoint and token, which the app
     * needs anyway.
     */
    fun applyPairing(baseUrl: String, token: String) {
        store.baseUrl = baseUrl
        store.token = token
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
                .onSuccess { outcome = RequestOutcome(++outcomeSeq, ok = true) }
                .onFailure {
                    busy = false
                    lastError = "prompt: ${Diagnostics.describe(it, store.baseUrl)}"
                    outcome = RequestOutcome(++outcomeSeq, ok = false)
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

    private fun applyQuestion(json: JSONObject?) {
        pendingQuestion = parsePendingQuestion(json)
    }

    /** Tap on an option button. */
    fun answerQuestion(questionId: String?, value: String, custom: Boolean = false) {
        safeLaunch {
            runCatching { client.answer(questionId, value, custom) }
                .onSuccess { outcome = RequestOutcome(++outcomeSeq, ok = true) }
                .onFailure {
                    lastError = "answer: ${Diagnostics.describe(it, store.baseUrl)}"
                    outcome = RequestOutcome(++outcomeSeq, ok = false)
                }
        }
    }

    /** Dismiss the widget; the tool records it as cancelled. */
    fun cancelQuestion() {
        val current = pendingQuestion ?: return
        val questionId = current.firstUnanswered?.id ?: current.questions.first().id
        safeLaunch {
            runCatching { client.answer(questionId, "", cancel = true) }
                .onFailure { lastError = "cancel: ${Diagnostics.describe(it, store.baseUrl)}" }
        }
    }

    private fun handleEvent(event: JSONObject) {
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
        when (type) {
            "question" -> applyQuestion(data.optJSONObject("pending"))
            "state" -> {
                busy = !data.optBoolean("idle", true)
                val file = data.optString("sessionFile").substringAfterLast('/')
                if (file.isNotBlank()) sessionTitle = data.optString("sessionName").takeIf { it.isNotBlank() && it != "null" } ?: file
            }
            "prompt_accepted", "agent_start", "turn_start" -> busy = true
            "agent_settled" -> {
                busy = false
                commitRun()
                refreshState()
            }
            "agent_end" -> {
                // The run may still retry or pick up a queued follow-up, so keep
                // the busy state and wait for agent_settled before showing text.
                refreshState()
            }
            "message_start" -> {
                val message = data.optJSONObject("message")
                when (message?.optString("role").orEmpty()) {
                    "assistant" -> {
                        activeAssistant = StringBuilder(textOf(message, "content"))
                        activeHasToolCalls = hasToolCalls(message)
                    }
                    "user" -> runCandidates.clear() // a new prompt starts a new answer
                }
            }
            "message_update" -> {
                val delta = data.optJSONObject("assistantMessageEvent")?.optString("delta").orEmpty()
                if (delta.isNotEmpty()) {
                    activeAssistant?.append(delta)
                }
            }
            "message_end" -> {
                val message = data.optJSONObject("message")
                if (message?.optString("role") == "assistant") {
                    val text = activeAssistant?.toString()?.ifBlank { textOf(message, "content") }
                        ?: textOf(message, "content")
                    runCandidates.add(text to (activeHasToolCalls || hasToolCalls(message)))
                    activeAssistant = null
                    activeHasToolCalls = false
                }
            }
            "tool_execution_start", "tool_execution_end" -> {
                // Tool activity stays off the screen by design.
            }
            "model_select" -> refreshState()
        }
    }

    /** Shows the last answer of the finished run, if it was not tool narration. */
    private fun commitRun() {
        val answer = runCandidates.lastOrNull { !it.second && it.first.isNotBlank() }?.first?.trim()
        runCandidates.clear()
        activeAssistant = null
        if (answer.isNullOrBlank()) {
            return
        }
        // Connecting mid-run can load the same answer with the history fetch.
        val lastCommitted = messages.lastOrNull { it.role == "assistant" }?.text?.trim()
        if (answer == lastCommitted) {
            return
        }
        messages.add(ChatMessage(id = "answer-${UUID.randomUUID()}", role = "assistant", text = answer))
    }

    /** True when the message exists mainly to call tools. */
    private fun hasToolCalls(message: JSONObject?): Boolean {
        val content = message?.opt("content") ?: return false
        if (content !is JSONArray) return false
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            if (block.optString("type") == "toolCall") return true
        }
        return false
    }

    private fun toMessage(entry: JSONObject): ChatMessage? {
        val message = entry.optJSONObject("message") ?: return null
        val role = message.optString("role").ifBlank { return null }
        val text = textOf(message, "content")
        if (text.isBlank() && role != "toolResult") return null
        val marker = when {
            role == "toolResult" -> message.optString("toolName").takeIf { it.isNotBlank() }
            role == "assistant" && hasToolCalls(message) -> "toolCall"
            else -> null
        }
        return ChatMessage(
            id = entry.optString("id", UUID.randomUUID().toString()),
            role = if (role == "toolResult") "tool" else role,
            text = text,
            toolName = marker,
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
