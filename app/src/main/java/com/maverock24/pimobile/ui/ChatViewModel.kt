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
 * The transcript role for a question widget the person answered. It is not a
 * prompt: the question came from pi and the choice came from the person, so it
 * belongs with the answers of the turn that was interrupted.
 */
internal const val QUESTION_ROLE = "question"

/** The two tools whose result is a choice the person made, and not process. */
private const val QUESTION_TOOL = "question"
private const val QUESTIONNAIRE_TOOL = "questionnaire"

/**
 * The sentence the question tools write, used only when the history came without
 * the tool name: `User selected: 2. Label`, `User wrote: ...`,
 * `User cancelled the selection`, or a questionnaire's `Label: user selected: ...`.
 */
private val questionResultShape = Regex("""(?i)^(?:\S[^:\n]{0,60}: )?user (?:selected|wrote|cancelled)\b""")

/** A string field, or null when it is missing, null or blank. */
private fun JSONObject.string(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

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
    /**
     * What the person chose for a [QUESTION_ROLE] entry: an option's label, the
     * text they typed themselves, or null when the question was dismissed
     * without an answer.
     */
    val answer: String? = null,
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
 * Tool narration never reaches [messages]. The one tool result that does, a
 * question widget the person answered ([QUESTION_ROLE]), is part of the answer
 * to the prompt it interrupted, so it sits with the answers and in order.
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
            // Kept even when the question text is missing: the choice is the
            // part that says what happened.
            QUESTION_ROLE -> answers.add(message)
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
                        for (message in toMessage(entry)) {
                            if (message.role == "assistant" && message.toolName != null) {
                                continue // narration on the way to a tool call
                            }
                            parsed.add(message)
                        }
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
        val previous = pendingQuestion
        pendingQuestion = parsePendingQuestion(json)
        // The widget closed, so pi has recorded the answer as a tool result by
        // now. Pull the transcript a moment later and let that record take the
        // place of the optimistic entry: the record is the half that survives a
        // reconnect, and fetching it here is what keeps the two from disagreeing.
        if (previous != null && pendingQuestion == null) {
            viewModelScope.launch {
                delay(500)
                reloadHistory()
            }
        }
    }

    /** Tap on an option button. */
    fun answerQuestion(questionId: String?, value: String, custom: Boolean = false) {
        if (requestsBlocked()) return
        // The tool result that records this answer arrives with the next history
        // fetch, which can be a poll or a reconnect away. Put it on the
        // transcript now, so answering reads as the one action it was.
        val trace = pendingQuestion?.let { questionEntry(it, questionId, value, custom) }
        if (trace != null) messages.add(trace)
        safeLaunch {
            runCatching { client.answer(questionId, value, custom) }
                .onSuccess { outcome = RequestOutcome(++outcomeSeq, ok = true) }
                .onFailure {
                    // Nothing was recorded on the laptop, so the answer must not
                    // stay on screen claiming that it was, and a retry of the
                    // same option must not look like a second answer.
                    if (trace != null) messages.remove(trace)
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
        // A dismissal is a choice as well, and the tool result the bridge
        // records says so. Leaving no trace here would put the question on
        // screen only after a reload, and the turn would read as if pi had
        // never asked it.
        val trace = questionEntry(current, questionId, value = null, custom = false)
        messages.add(trace)
        safeLaunch {
            runCatching { client.answer(questionId, "", cancel = true) }
                .onFailure {
                    messages.remove(trace)
                    lastError = "cancel: ${Diagnostics.describe(it, store.baseUrl)}"
                }
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

    private fun toMessage(entry: JSONObject): List<ChatMessage> {
        val message = entry.optJSONObject("message") ?: return emptyList()
        val role = message.optString("role").ifBlank { return emptyList() }
        val text = textOf(message, "content")
        // A tool result is process, and process stays off the transcript. The
        // question tools are the exception: what they return is what the person
        // chose, which is an answer and not narration. A questionnaire is the
        // one entry that becomes several, one per question it asked.
        if (role == "toolResult") return questionTraces(entry, message, text)
        if (text.isBlank()) return emptyList()
        return listOf(
            ChatMessage(
                id = entry.optString("id", UUID.randomUUID().toString()),
                role = role,
                text = text,
                toolName = if (role == "assistant" && hasToolCalls(message)) "toolCall" else null,
            )
        )
    }

    /**
     * The questions and the choices behind a tool result, or nothing for every
     * other tool.
     *
     * The history carries the tool name on the result message, the way a session
     * file does (`message.toolName`, which the bridge keeps on the way out), so
     * the name is what decides. A payload that lost the name is decided by the
     * shape of what is left instead: a questionnaire's details carry its
     * questions, a question's carry one, and neither being there leaves only the
     * sentence the tool wrote, which identifies it on its own.
     *
     * An errored tool chose nothing and is not a choice. `question` answers "UI
     * not available" with `answer: null` and no error flag, so the sentence is
     * the only thing that separates a dismissal from a failure.
     */
    private fun questionTraces(entry: JSONObject, message: JSONObject, text: String): List<ChatMessage> {
        if (message.optBoolean("isError")) return emptyList() // a tool that failed chose nothing
        val details = message.optJSONObject("details")
        val name = message.optString("toolName")
        val tool = when {
            name == QUESTION_TOOL || name == QUESTIONNAIRE_TOOL -> name
            details?.has("questions") == true -> QUESTIONNAIRE_TOOL
            details?.has("question") == true -> QUESTION_TOOL
            name.isBlank() && questionResultShape.containsMatchIn(text.trimStart()) -> QUESTION_TOOL
            else -> return emptyList()
        }
        val id = entry.optString("id", UUID.randomUUID().toString())
        if (tool == QUESTIONNAIRE_TOOL) {
            return details?.let { questionnaireTraces(id, it) }.orEmpty()
        }
        if (details == null) {
            // Only the sentence came through. Keep it, rather than invent the
            // question it answered: this is a payload from before the details
            // were carried, and the choice is still worth showing.
            val sentence = text.trim()
            if (sentence.isBlank()) return emptyList()
            return listOf(ChatMessage(id = id, role = QUESTION_ROLE, text = "", answer = sentence, toolName = tool))
        }
        val question = details.string("question").orEmpty()
        // Null when nothing was chosen, which is what a dismissal records. A
        // failure records the same null and says so in the sentence instead.
        val answer = details.string("answer")
        if (answer == null && !text.contains("cancel", ignoreCase = true)) return emptyList()
        if (question.isBlank()) return emptyList()
        return listOf(ChatMessage(id = id, role = QUESTION_ROLE, text = question, answer = answer, toolName = tool))
    }

    /**
     * A questionnaire's questions as separate entries, one per question the
     * person answered, which is the shape the app puts on the transcript while
     * the widget is still open. The question's label prefixes it when there was
     * more than one, so a long pair of lists still reads straight down. A
     * questionnaire nobody answered was dismissed, and says so once.
     */
    private fun questionnaireTraces(id: String, details: JSONObject): List<ChatMessage> {
        val questions = details.optJSONArray("questions") ?: JSONArray()
        val answers = details.optJSONArray("answers") ?: JSONArray()
        val labels = HashMap<String, String>()
        // Insertion ordered, so the entries read in the order the questions were
        // asked rather than in whatever order a map happens to hand back.
        val asked = LinkedHashMap<String, String>()
        for (index in 0 until questions.length()) {
            val question = questions.optJSONObject(index) ?: continue
            val questionId = question.string("id").orEmpty()
            val label = question.string("label") ?: questionId
            labels[questionId] = label
            asked[questionId] = question.string("prompt") ?: label
        }
        val chosen = HashMap<String, String>()
        for (index in 0 until answers.length()) {
            val answer = answers.optJSONObject(index) ?: continue
            val value = answer.string("label") ?: answer.string("value") ?: continue
            chosen[answer.string("id").orEmpty()] = value
        }
        val several = asked.size > 1
        val traces = ArrayList<ChatMessage>()
        for ((questionId, prompt) in asked) {
            val value = chosen[questionId] ?: continue
            val prefix = if (several && !labels[questionId].isNullOrBlank()) "${labels[questionId]}: " else ""
            traces.add(
                ChatMessage(
                    id = "$id-$questionId",
                    role = QUESTION_ROLE,
                    text = "$prefix$prompt",
                    answer = "$prefix$value",
                    toolName = QUESTIONNAIRE_TOOL,
                )
            )
        }
        if (traces.isEmpty()) {
            val dismissed = asked.values.joinToString("\n")
            if (dismissed.isNotBlank()) {
                traces.add(
                    ChatMessage(
                        id = id,
                        role = QUESTION_ROLE,
                        text = dismissed,
                        answer = null,
                        toolName = QUESTIONNAIRE_TOOL,
                    )
                )
            }
        }
        return traces
    }

    /**
     * What the person just did to the widget, in the shape the history gives the
     * same answer: the question as it was asked, and the choice as it was made.
     * A reload of the history replaces the whole transcript, so this entry and
     * the one derived from the tool result can never be on screen together.
     */
    private fun questionEntry(
        pending: PendingQuestion,
        questionId: String?,
        value: String?,
        custom: Boolean,
    ): ChatMessage {
        val several = pending.questions.size > 1
        val question = pending.questions.firstOrNull { it.id == questionId } ?: pending.firstUnanswered
        val label = question?.label.orEmpty()
        val text = when {
            question == null -> pending.title
            several -> "$label: ${question.prompt}"
            else -> question.prompt.ifBlank { pending.title }
        }
        val picked = when {
            value == null -> null // dismissed without an answer
            custom -> value // typed rather than chosen, so there is no label to find
            else -> question?.options?.firstOrNull { it.value == value }?.label ?: value
        }
        val answer = when {
            picked == null -> null
            several && label.isNotBlank() -> "$label: $picked"
            else -> picked
        }
        return ChatMessage(
            id = "question-${UUID.randomUUID()}",
            role = QUESTION_ROLE,
            text = text,
            answer = answer,
            toolName = pending.tool,
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
