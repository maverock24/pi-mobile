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

/** What the session label says before anything is attached. */
private const val NO_SESSION = "not connected"

/**
 * Names a folder by its tail. The bridge reports an absolute path, and the last
 * two segments are what tell one project from another on a phone-width line.
 */
internal fun shortPath(path: String): String {
    val trimmed = path.trim().trimEnd('/')
    val parts = trimmed.split('/').filter { it.isNotBlank() }
    return when {
        parts.size <= 2 -> trimmed
        else -> "…/" + parts.takeLast(2).joinToString("/")
    }
}

/**
 * Names a session on screen: the folder it runs in first, then the name it
 * carries. Either can be missing, so the result can come back empty and each
 * caller says what it means by that.
 */
internal fun sessionLabel(cwd: String?, name: String?): String =
    listOfNotNull(
        cwd?.let(::shortPath)?.takeIf { it.isNotBlank() },
        name?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

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

/**
 * A session other than the one this app attached to is serving the bridge.
 * [name] is what that session calls itself and [cwd] is the folder it runs in.
 * Either can be missing: a bridge that changed hands may have been read before
 * it could say much.
 */
data class SessionNotice(val sessionId: String, val name: String?, val cwd: String?)

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
    var sessionTitle by mutableStateOf(NO_SESSION)
        private set

    /** The folder the attached session runs in, as the bridge reports it. */
    var sessionCwd by mutableStateOf<String?>(null)
        private set

    /** The name the attached session carries on the laptop, when it has one. */
    var sessionName by mutableStateOf<String?>(null)
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

    /**
     * The session this app attached to. Requests act on that one and nothing is
     * taken from another, so two sessions never share this screen.
     */
    var attachedSessionId by mutableStateOf<String?>(null)
        private set

    /** Set while the bridge serves someone else; cleared by moving to it. */
    var otherSession by mutableStateOf<SessionNotice?>(null)
        private set

    /**
     * Which pi session this screen is showing: the folder the session runs in
     * plus the name it carries. The session file name is a timestamp and a uuid,
     * so it identifies nothing to a human and never appears here.
     */
    val attachedLabel: String
        get() = sessionLabel(sessionCwd, sessionName)

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
        val attachedElsewhere = baseUrl != store.baseUrl || token != store.token
        store.baseUrl = baseUrl
        store.token = token
        if (attachedElsewhere) {
            // A different bridge is a different attachment, so the transcript and
            // the pin go with it. Saving the same values again must not clear
            // either: attaching is the only other place that drops the transcript.
            messages.clear()
            forgetSession()
        }
        connect()
    }

    /**
     * Drop what named the old attachment. A new bridge or a new pairing reports
     * its own session, and until it does the screen must not keep claiming the
     * one it was talking to before.
     */
    private fun forgetSession() {
        attachedSessionId = null
        otherSession = null
        sessionTitle = NO_SESSION
        sessionName = null
        sessionCwd = null
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
                        // Find out whose session the bridge serves now. The poll
                        // would take three seconds, and this is the moment another
                        // session may have taken the bridge over.
                        refreshState()
                    }
                },
            )
        }.getOrElse { error ->
            statusLine = Diagnostics.describe(error, store.baseUrl)
            connected = false
            return
        }
        streamCall = call
        startPolling()
        safeLaunch {
            // Identify the session before pulling any of its content. Loading
            // history first is what used to put a second session's transcript on
            // a screen that was still showing the first one.
            val state = runCatching { client.state() }.getOrNull()
            if (state != null) {
                applyState(state)
            }
            if (otherSession != null) {
                statusLine = "another pi session is serving this bridge"
                return@safeLaunch
            }
            reloadHistory()
            refreshState()
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
        val reported = state.optString("sessionId").takeIf { it.isNotBlank() && it != "null" }
        val name = state.optString("sessionName").takeIf { it.isNotBlank() && it != "null" }
        val cwd = state.optString("cwd").takeIf { it.isNotBlank() && it != "null" }
        // The session file name is a timestamp and a uuid, so it names nothing to
        // a person. The name says which session this is when it carries one, and
        // the folder is what is left when it does not.
        val title = name ?: cwd?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "pi session"
        if (reported == null) {
            // A bridge that cannot identify itself: take what it can still say,
            // and leave the pin alone. None of it may name the screen while a
            // session is attached, since this may be the other one talking.
            if (attachedSessionId == null) {
                sessionTitle = title
                sessionName = name
                sessionCwd = cwd
            }
            busy = !state.optBoolean("idle", true)
            applyQuestion(state.optJSONObject("question"))
            return
        }
        val attached = attachedSessionId
        if (attached == null) {
            attachedSessionId = reported
        } else if (attached != reported) {
            // Another pi session owns the bridge now. Take nothing from it: not
            // its title, not its busy state, not its question widget, and send
            // nothing to it. What is on screen stays the session you were
            // reading until you choose to move.
            otherSession = SessionNotice(reported, name, cwd)
            busy = false
            // Stop following it. connect() opens the stream before this check can
            // run, and its frames would otherwise be applied to the session that
            // is still on screen.
            streamCall?.cancel()
            streamCall = null
            connected = false
            return
        }
        otherSession = null
        sessionTitle = title
        sessionName = name
        sessionCwd = cwd
        busy = !state.optBoolean("idle", true)
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
        if (otherSession != null) {
            return // another session is serving: its history is not ours to show
        }
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
                    val from = payload.optString("sessionId").takeIf { it.isNotBlank() && it != "null" }
                    val attached = attachedSessionId
                    if (from != null && attached != null && from != attached) {
                        // The bridge changed hands during this round trip, so this
                        // transcript belongs to the other session. Ask the bridge
                        // who it serves now instead of reading the answer off the
                        // file name: the state payload carries the folder and the
                        // name, and those are what identify a session to a person.
                        val current = runCatching { client.state() }.getOrNull()
                        if (current != null) {
                            applyState(current)
                        } else {
                            // The bridge did not answer. Keep whatever the poll
                            // already found, and at worst show the bar without a
                            // name rather than one built from a uuid.
                            otherSession = otherSession ?: SessionNotice(from, null, null)
                        }
                        return@onSuccess
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
        // A fresh pairing is a fresh attachment: nothing from the old one may
        // survive a history fetch that fails.
        messages.clear()
        forgetSession()
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
        if (trimmed.isEmpty() || requestsBlocked()) return
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
        if (requestsBlocked()) return
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

    /**
     * Why the composer is off, or null when a prompt can be sent.
     *
     * The live stream is the proof of identity: a takeover makes the serving
     * process close its server, which drops this stream, so while the stream is
     * up the session checked when it opened is still the one on the other end. A
     * dead stream therefore counts as not ours until it is reopened, and the poll
     * keeps the state readable in the meantime.
     */
    val composerBlock: String?
        get() = when {
            otherSession != null -> "Another pi session is serving the bridge"
            !connected -> "Not connected"
            else -> null
        }

    /** True when this app has no verified session to act on. */
    private fun requestsBlocked(): Boolean = composerBlock != null

    /**
     * Move to the session the bridge is actually serving. The transcript is
     * dropped here and only here, so what is listed is always one session.
     */
    fun attachToReportedSession() {
        val notice = otherSession ?: return
        attachedSessionId = notice.sessionId
        otherSession = null
        messages.clear()
        connect()
    }

    private fun applyQuestion(json: JSONObject?) {
        pendingQuestion = parsePendingQuestion(json)
    }

    /** Tap on an option button. */
    fun answerQuestion(questionId: String?, value: String, custom: Boolean = false) {
        if (requestsBlocked()) return
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
        if (requestsBlocked()) return
        val questionId = current.firstUnanswered?.id ?: current.questions.first().id
        safeLaunch {
            runCatching { client.answer(questionId, "", cancel = true) }
                .onFailure { lastError = "cancel: ${Diagnostics.describe(it, store.baseUrl)}" }
        }
    }

    private fun handleEvent(event: JSONObject) {
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
        // Frames queued before a takeover was noticed still arrive here, and none
        // of them belong to the session on screen.
        if (otherSession != null) return
        when (type) {
            "question" -> applyQuestion(data.optJSONObject("pending"))
            "state" -> applyState(data)
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
