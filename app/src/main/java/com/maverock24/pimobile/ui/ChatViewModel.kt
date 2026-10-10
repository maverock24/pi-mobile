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
import com.maverock24.pimobile.PiRemoteApp
import com.maverock24.pimobile.data.CrashLog
import com.maverock24.pimobile.data.Pin
import com.maverock24.pimobile.data.PinsStore
import com.maverock24.pimobile.data.SettingsStore
import com.maverock24.pimobile.data.TranscriptEntry
import com.maverock24.pimobile.data.TranscriptStore
import com.maverock24.pimobile.data.mergeTranscript
import com.maverock24.pimobile.net.BridgeException
import com.maverock24.pimobile.net.Diagnostics
import com.maverock24.pimobile.net.PiRemoteClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** What the session label says before anything is attached. */
private const val NO_SESSION = "not connected"

/**
 * How many transcript entries to ask the bridge for. It is the most the bridge
 * will serve, because one turn of a busy session runs to dozens of entries: a
 * window of forty held about one answer.
 */
private const val HISTORY_LIMIT = 500

/**
 * The transcript role for a question widget the person answered. It is not a
 * prompt: the question came from pi and the choice came from the person, so it
 * belongs with the answers of the turn that was interrupted.
 */
internal const val QUESTION_ROLE = "question"

/**
 * The id prefixes the app stamps on a message it made up rather than took from
 * the bridge: an echoed prompt, an optimistic question trace and a committed
 * answer. A fetch carries such a message over only while the bridge has no
 * entry that matches it, which is what stops one being wiped by a fetch that
 * runs before the laptop has recorded the turn.
 */
private val SYNTHETIC_ID_PREFIXES = listOf("local-", "prompt-", "answer-", "question-")

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

/**
 * A first title for a pin: the first non-blank line of the prompt, short enough
 * for one list row. A pin of an answer that came before any prompt still needs
 * a name, so it gets one rather than an empty line.
 */
internal fun defaultPinTitle(prompt: String): String {
    val line = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (line.isEmpty()) return "Pinned answer"
    return if (line.length <= 60) line else line.take(57).trimEnd() + "…"
}

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
    /**
     * When the bridge wrote the entry, in epoch millis. Only an entry the bridge
     * reported has one: a message made up here carries 0, because the cache
     * never keeps one. It orders the merge of a fetched window with the cached
     * transcript, and is not read by the screen.
     */
    val timestamp: Long = 0L,
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
 * One full-session search hit. It names the entry it was found in and carries a
 * snippet with the match inside it, but the useful part is [turnId]: the id of
 * the prompt that opened the turn, so a tap can open that turn in either view.
 * [prompt] is the same turn's prompt text, shown under the snippet so a result
 * says where it came from. Both are null for an answer that arrived before any
 * prompt, which has no turn to open.
 */
data class SearchHit(
    val entryId: String,
    val role: String,
    val snippet: String,
    val prompt: String?,
    val turnId: String?,
)

/**
 * One command a prompt may dispatch: an extension command, a prompt template or
 * a skill. [source] is which of the three ("extension", "prompt" or "skill"),
 * so a skill is recognisable as a skill rather than as another extension
 * command. The bridge names a skill "skill:<name>", which is also what the
 * draft gets.
 */
data class SessionCommand(
    val name: String,
    val description: String,
    val source: String,
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
 * Which of the three message channels a [Notice] belongs to. A kind is not a
 * priority the input sorts by: the screen draws errors first, then actionables,
 * then confirmations, because a failure outranks a nudge and a nudge outranks a
 * receipt.
 */
enum class NoticeKind { Error, Actionable, Confirmation }

/**
 * One transient message and what it offers. [action] carries its own label so a
 * bar can never read "OK" while it installs an APK; a notice with no action is
 * only dismissable. [id] names the entry so a dismissal removes that one rather
 * than whatever happens to be newest.
 */
data class Notice(
    val id: Long,
    val kind: NoticeKind,
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val pinsStore = PinsStore(app)
    private val transcriptStore = TranscriptStore(app)
    private val crashLog = CrashLog(app)
    private val client = PiRemoteClient { store.baseUrl to store.token }
    private val main = Handler(Looper.getMainLooper())

    /**
     * The pinned prompts and answers, newest first. It is loaded once at start
     * and written through [pinsStore] on every change, so a pin survives a
     * restart. Pins name no session, so they outlive the one that made them.
     */
    val pins = mutableStateListOf<Pin>()

    /** The trace of the most recent crash, or null when nothing has crashed. */
    var lastCrash by mutableStateOf<String?>(null)
        private set

    /** True when a launch set data aside and it is still on disk. */
    var quarantinedData by mutableStateOf(false)
        private set

    init {
        if (!PiRemoteApp.startupSafeMode) {
            pins.addAll(runCatching { pinsStore.load() }.getOrElse { emptyList() })
            // The pinned session's transcript goes up from the cache before the
            // bridge is asked for anything, so a phone that cannot reach it still
            // shows the record it saved instead of an empty screen.
            safeLaunch { restoreCache(store.sessionId.takeIf { it.isNotBlank() }) }
        }
        lastCrash = crashLog.lastCrash()
        quarantinedData = pinsStore.hasQuarantined() || transcriptStore.hasQuarantined()
    }

    val messages = mutableStateListOf<ChatMessage>()

    /**
     * True while what is on screen came from the cache and no fetch has
     * confirmed it yet. The status line says the bridge is unreachable; this is
     * what says the transcript under it is the saved copy rather than live.
     */
    var showingSavedCopy by mutableStateOf(false)
        private set

    /** Composer text. Lives here so dictation and the UI share one source. */
    var draft by mutableStateOf("")
        private set

    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    /**
     * The answer as it streams, or null when no run is producing one. The screen
     * shows it as the last answer of the newest turn, so the words appear while
     * the run is still going instead of waiting for it to settle. It is cleared
     * when the run commits, because the committed answer takes its place.
     */
    var liveAnswer by mutableStateOf<String?>(null)
        private set

    var sessionTitle by mutableStateOf(store.sessionName.takeIf { it.isNotBlank() } ?: NO_SESSION)
        private set

    /** The folder the attached session runs in, as the bridge reports it. */
    var sessionCwd by mutableStateOf<String?>(null)
        private set

    /** The name the attached session carries on the laptop, when it has one. */
    var sessionName by mutableStateOf(store.sessionName.takeIf { it.isNotBlank() })
        private set
    var statusLine by mutableStateOf("")
        private set

    /**
     * Every transient message, drawn in read order: an error, then the
     * actionable entry, then the confirmations. One channel rather than seven
     * independent lines, so two messages cannot stack and none is
     * undismissable. A new error or actionable replaces its own kind; a
     * confirmation expires on its own.
     */
    var notices by mutableStateOf<List<Notice>>(emptyList())
        private set

    /** The id the next notice takes; a dismissal names one entry by it. */
    private var noticeSeq = 0L

    fun notifyError(text: String) {
        notices = reorder(
            notices.filterNot { it.kind == NoticeKind.Error } + Notice(++noticeSeq, NoticeKind.Error, text),
        )
    }

    fun notifyActionable(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        notices = reorder(
            notices.filterNot { it.kind == NoticeKind.Actionable } +
                Notice(++noticeSeq, NoticeKind.Actionable, text, actionLabel, action),
        )
    }

    /**
     * A receipt for something the user just did. It clears itself after two
     * seconds because it only matters while the action is fresh; three at most
     * are kept, so a run of them cannot fill the screen.
     */
    fun notifyConfirmation(text: String) {
        val id = ++noticeSeq
        val kept = notices.filter { it.kind == NoticeKind.Confirmation }.takeLast(2)
        notices = reorder(
            notices.filterNot { it.kind == NoticeKind.Confirmation } +
                kept + Notice(id, NoticeKind.Confirmation, text),
        )
        viewModelScope.launch {
            delay(2000)
            dismissNotice(id)
        }
    }

    fun dismissNotice(id: Long) {
        notices = notices.filterNot { it.id == id }
    }

    fun clearNotices() {
        notices = emptyList()
    }

    private fun reorder(list: List<Notice>) = list.sortedBy { it.kind.ordinal }

    var pendingQuestion by mutableStateOf<PendingQuestion?>(null)
        private set
    var appearance by mutableStateOf(store.appearance)
        private set

    /**
     * Which view of the transcript is on screen: the scrolling transcript or the
     * card deck. It is read and written the same way as [appearance], and both
     * live in the store, so the choice survives a restart.
     */
    var viewMode by mutableStateOf(store.viewMode)
        private set

    /**
     * Which dark palette is on screen: Midnight, Indigo, Amber or Forest. It is
     * read and written like [appearance], and it only changes the dark side.
     */
    var theme by mutableStateOf(store.theme)
        private set

    /**
     * Whether a copy lands in the composer. Read and written like [appearance],
     * so it survives a restart. When it is on the chat screen watches the
     * clipboard; when it is off nothing is watched and nothing is captured.
     */
    var clipboardCapture by mutableStateOf(store.clipboardCapture)
        private set

    /**
     * Full-session search: what the bridge matched, whether a request is in
     * flight, and whether the bridge refused. The refusal's text goes to the
     * notice channel, so this is only the panel's gate against saying "no
     * matches" for a query that failed. The results are newest first, as the
     * bridge sends them.
     */
    var searchResults by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchFailed by mutableStateOf(false)
        private set

    /**
     * A turn the search wants opened, by id. The views read it once and clear it
     * with [clearJump], so a signal is honoured once and never again.
     */
    var pendingJump by mutableStateOf<String?>(null)
        private set

    /** Set when a request the user started succeeds or fails. */
    var outcome by mutableStateOf<RequestOutcome?>(null)
        private set

    private var outcomeSeq = 0L

    /**
     * Bumped per search request so a slow answer cannot overwrite a newer one.
     * Typing restarts the request, and the last keystroke is the one that counts.
     */
    private var searchSeq = 0L

    /**
     * The commands a prompt may dispatch for the attached session, offered by
     * the composer. They are read once per session and kept in memory only;
     * nothing here is worth surviving a restart.
     */
    var commands by mutableStateOf<List<SessionCommand>>(emptyList())
        private set

    /**
     * The session [commands] was read for. A handover makes it stale, so the
     * next read replaces the list whole rather than mixing two sessions'.
     */
    private var commandsFor: String? = null
    private var commandsLoaded = false
    private var commandsInFlight = false

    /** True once this launch has done what a startup crash asks for. */
    private var startupRecoveryDone = false

    /**
     * A quiet line for the palette when the bridge has no command endpoint at
     * all, which is what an older build is. It is only ever drawn inside the
     * palette, never on the screen at rest, so nothing shouts unasked.
     */
    var commandsNote by mutableStateOf<String?>(null)
        private set

    /**
     * The session this app is pinned to. Requests carry it, so the bridge
     * refuses one that arrives after the bridge changed hands, and a restart
     * reads it back from [SettingsStore] instead of adopting whatever serves.
     */
    var attachedSessionId by mutableStateOf(store.sessionId.takeIf { it.isNotBlank() })
        private set

    /**
     * Which pi session this screen is showing: the folder the session runs in
     * plus the name it carries. The session file name is a timestamp and a uuid,
     * so it identifies nothing to a human and never appears here.
     */
    val attachedLabel: String
        get() = sessionLabel(sessionCwd, sessionName)

    /**
     * The name the app bar shows: the bridge's title for the session, then the
     * name it carries, then the folder it runs in. The title and the name are
     * what a person recognises; the folder is what is left before the bridge has
     * named the session at all.
     */
    val barTitle: String
        get() = sessionTitle.takeIf { it.isNotBlank() && it != NO_SESSION }
            ?: sessionName?.takeIf { it.isNotBlank() }
            ?: sessionCwd?.let(::shortPath)?.takeIf { it.isNotBlank() }
            ?: sessionTitle

    /** The latest thing you typed, shown at the top of the main view. */
    val lastPrompt: String?
        get() = messages.lastOrNull { it.role == "user" && it.text.isNotBlank() }?.text

    /** The transcript grouped into prompts, which is what the chat screen lists. */
    val turns: List<ChatTurn>
        get() = turnsOf(messages)

    private var streamCall: Call? = null
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null

    /**
     * Bumped every time the stream is opened or dropped. A frame or a close
     * carries the generation it was opened with, so a callback from a stream
     * that has been replaced cannot act on the one that replaced it.
     */
    private var streamGeneration = 0

    /** How many reconnects have run without a stream opening; drives the wait. */
    private var reconnectAttempt = 0

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
            showingSavedCopy = false
            forgetSession()
        }
        connect()
    }

    /**
     * Drop what named the old attachment. A new bridge or a new pairing reports
     * its own session, and until it does the screen must not keep claiming the
     * one it was talking to before.
     */
    /**
     * Leave behind whatever the last launch died on.
     *
     * The transcript cache and the pins are the only stored state the app reads
     * before it can draw, so both are moved aside rather than read again, and
     * they stay on disk: a bad launch should cost a start, not the record. A
     * second startup crash in a row says the files were not what it choked on,
     * so the bridge attachment goes as well, because pairing again costs a QR
     * code and leaving it is the only way the app can fail to open again.
     */
    private fun recoverFromStartupCrash() {
        if (!PiRemoteApp.startupSafeMode || startupRecoveryDone) return
        startupRecoveryDone = true
        val movedTranscripts = transcriptStore.quarantineAll()
        val movedPins = pinsStore.quarantine()
        val droppedAttachment = PiRemoteApp.startupCrashCount >= 2
        if (droppedAttachment) {
            store.forgetAttachment()
            forgetSession()
        }
        quarantinedData = pinsStore.hasQuarantined() || transcriptStore.hasQuarantined()
        notifyActionable(
            when {
                droppedAttachment ->
                    "The last launches crashed while starting, so the saved transcript, the pins and " +
                        "the bridge token were left behind to get the app open. Settings > " +
                        "Troubleshooting has the trace."
                movedTranscripts > 0 || movedPins ->
                    "The last launch crashed while starting, so the saved transcript and pins were set " +
                        "aside. Settings > Troubleshooting has the trace."
                else ->
                    "The last launch crashed while starting. Settings > Troubleshooting has the trace."
            },
        )
    }

    /**
     * Drop what named the old attachment. A new bridge or a new pairing reports
     * its own session, and until it does the screen must not keep claiming the
     * one it was talking to before.
     */
    private fun forgetSession() {
        attachedSessionId = null
        store.sessionId = ""
        store.sessionName = ""
        sessionTitle = NO_SESSION
        sessionName = null
        sessionCwd = null
        clearNotices()
        // The commands named a bridge that is no longer attached, so they go
        // with it rather than sitting under the next one's name.
        commands = emptyList()
        commandsFor = null
        commandsLoaded = false
        commandsNote = null
    }

    fun connect() {
        disconnect()
        recoverFromStartupCrash()
        if (!store.isConfigured) {
            statusLine = "Add the bridge token in Settings"
            return
        }
        statusLine = "connecting to ${store.baseUrl}"
        val generation = ++streamGeneration
        val call = runCatching {
            client.streamEvents(
                onOpen = {
                    main.post {
                        if (generation != streamGeneration) return@post
                        connected = true
                        statusLine = "connected"
                        reconnectAttempt = 0
                    }
                },
                onEvent = { event ->
                    main.post {
                        if (generation == streamGeneration) handleEvent(event)
                    }
                },
                onClosed = { error ->
                    main.post {
                        // A close from a stream that has already been replaced is
                        // the deliberate cancel of disconnect(), not a drop.
                        if (generation != streamGeneration) return@post
                        connected = false
                        busy = false
                        statusLine = if (error == null) {
                            "disconnected"
                        } else {
                            Diagnostics.describe(error, store.baseUrl)
                        }
                        // Find out whose session the bridge serves now, then
                        // reopen the stream. A handover closes the old server, so
                        // the poll alone would take three seconds to show it.
                        refreshState()
                        scheduleReconnect()
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
            // The pinned session's cache goes up before the bridge is asked for
            // anything, so an unreachable bridge leaves the saved transcript on
            // screen rather than a blank one. It is a no-op once a fetch has
            // given the screen its truth.
            restoreCache(attachedSessionId)
            // Identify the session before pulling any of its content. applyState
            // adopts whoever serves and loads that session's own history, so a
            // restart or a handover never mixes two transcripts.
            val state = runCatching { client.state() }.getOrNull()
            val moved = state != null && applyState(state)
            if (!moved) {
                reloadHistory()
            }
            refreshState()
            loadCommands()
            runCatching { client.question() }
                .onSuccess { applyQuestion(it.optJSONObject("pending")) }
        }
    }

    fun disconnect() {
        // Bumping the generation first means the cancel below is not read as a
        // dropped stream, so no reconnect is scheduled for it.
        streamGeneration++
        streamCall?.cancel()
        streamCall = null
        pollJob?.cancel()
        pollJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        connected = false
        busy = false
    }

    /**
     * Reopen the event stream after it dropped. The waits are short first, so a
     * handover reappears at once, and grow so a phone with no route does not
     * spin. The three second poll in [startPolling] stays the safety net.
     */
    private fun scheduleReconnect() {
        if (!store.isConfigured) return
        reconnectAttempt += 1
        val wait = when {
            reconnectAttempt <= 1 -> 250L
            reconnectAttempt == 2 -> 500L
            reconnectAttempt == 3 -> 1000L
            else -> 3000L
        }
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(wait)
            reconnectJob = null // connect() clears it, so do it here and avoid cancelling this job
            if (!connected) connect()
        }
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

    /**
     * Take the bridge's description of the session it serves. When the id is not
     * the pin, the screen moves: the pin is written down, the transcript is
     * dropped, that session's history is loaded and one line says so. Returns
     * true when it moved, which tells a caller its own history fetch is now
     * redundant.
     */
    private fun applyState(state: JSONObject): Boolean {
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
            return false
        }
        val attached = attachedSessionId
        if (attached != reported) {
            adoptSession(reported, name, cwd)
        }
        sessionTitle = title
        sessionName = name
        sessionCwd = cwd
        busy = !state.optBoolean("idle", true)
        applyQuestion(state.optJSONObject("question"))
        return attached != reported
    }

    /**
     * Move to the session the bridge serves now. This is the only place the
     * transcript is dropped, so what is listed is always one session, and the
     * pin is written down so a restart keeps it. A first connect names nothing:
     * there was no session on screen to move away from, and the line above the
     * transcript already names the one it landed on.
     */
    private fun adoptSession(sessionId: String, name: String?, cwd: String?) {
        val moved = attachedSessionId != null
        attachedSessionId = sessionId
        store.sessionId = sessionId
        store.sessionName = name.orEmpty()
        messages.clear()
        showingSavedCopy = false
        pendingQuestion = null
        // An answer that was streaming belongs to the session being left, and the
        // transcript it would be drawn over is gone with it.
        liveAnswer = null
        if (moved) {
            val label = sessionLabel(cwd, name).ifBlank { "a new session" }
            notifyActionable("The bridge moved to $label; this screen follows it")
        }
        // The moved-to session's own cache goes up before its history is fetched,
        // so the move is instant and an unreachable bridge still shows what was
        // saved. The fetch then merges over it and prunes the session left behind.
        safeLaunch {
            restoreCache(sessionId)
            reloadHistory()
        }
        // The commands belong to the session; a move drops the old set and asks
        // for the new one. The call is keyed by session, so it never refetches
        // what is already here.
        loadCommands()
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
                .onFailure { notifyError("state: ${Diagnostics.describe(it, store.baseUrl)}") }
        }
    }

    fun reloadHistory() {
        safeLaunch {
            var limit = HISTORY_LIMIT
            while (true) {
                val payload = runCatching { client.history(limit) }.getOrElse { error ->
                    notifyError("history: ${Diagnostics.describe(error, store.baseUrl)}")
                    return@safeLaunch
                }
                val array = payload.optJSONArray("messages") ?: JSONArray()
                // A bridge that cannot carry the whole window keeps the head of it
                // and marks the rest, which leaves the newest entries, the prompt
                // among them, out of the payload. Ask for what it did carry rather
                // than draw a history that stops in the past.
                val marked = array.length() > 0 && array.opt(array.length() - 1) !is JSONObject
                val carried = if (marked) array.length() - 1 else array.length()
                if (marked && carried in 1 until limit) {
                    limit = carried
                    continue
                }
                val parsed = withContext(Dispatchers.Default) {
                    val list = ArrayList<ChatMessage>()
                    for (index in 0 until carried) {
                        val entry = array.optJSONObject(index)
                        if (entry != null) {
                            for (message in toMessage(entry)) {
                                if (message.role != "assistant" || message.toolName == null) {
                                    list.add(message)
                                }
                            }
                        }
                    }
                    list
                }
                val from = payload.optString("sessionId").takeIf { it.isNotBlank() && it != "null" }
                val attached = attachedSessionId
                if (from != null && attached != null && from != attached) {
                    // The bridge changed hands during this round trip, so this
                    // transcript belongs to the other session. Re-read the state
                    // and let applyState adopt whoever serves now; this fetch is
                    // dropped rather than shown under the old pin.
                    refreshState()
                    return@safeLaunch
                }
                // What the fetch found, merged with the cache so a turn older
                // than the bridge's window survives its next fetch instead of
                // being cut back to the window every time. The cache holds only
                // what the bridge reported, so it is written before the messages
                // the app made up are put back on top of it.
                val previous = messages.toList()
                val shown = if (attached == null) {
                    parsed
                } else {
                    val fetched = parsed.map(::toEntry)
                    val merged = withContext(Dispatchers.IO) {
                        val result = mergeTranscript(transcriptStore.load(attached), fetched)
                        transcriptStore.save(attached, result)
                        result
                    }
                    merged.map(::toMessage)
                }
                // A message this app made up is not in the fetch, and a fetch
                // that runs before the laptop has recorded the turn would erase
                // it. It is carried over until the bridge reports its equivalent,
                // and dropped then, so it is never on screen twice.
                val local = carriedLocals(previous, shown)
                messages.clear()
                messages.addAll(shown)
                messages.addAll(local)
                // A fetch has now confirmed the transcript against the bridge,
                // so what is on screen is no longer only the saved copy.
                showingSavedCopy = false
                // The line naming the session the app moved to has been read by the
                // time its transcript is here, so it goes, along with anything
                // older still on screen. A notice that never leaves is noise
                // rather than information.
                clearNotices()
                return@safeLaunch
            }
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
                notifyError(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
            }
        }
    }

    /**
     * Put the session's cached transcript on screen, if the screen is still
     * empty. The file is read and parsed off the main thread, because a long
     * transcript is megabytes, and it is only drawn when the session has not
     * moved underneath it while the read was in flight.
     */
    private suspend fun restoreCache(sessionId: String?) {
        val id = sessionId ?: return
        if (messages.isNotEmpty()) return
        val entries = withContext(Dispatchers.IO) { transcriptStore.load(id) }
        if (messages.isEmpty() && attachedSessionId == id && entries.isNotEmpty()) {
            messages.addAll(entries.map(::toMessage))
            showingSavedCopy = true
        }
    }

    /** The stored form of a message the bridge reported, which is what the cache keeps. */
    private fun toEntry(message: ChatMessage): TranscriptEntry =
        TranscriptEntry(
            id = message.id,
            role = message.role,
            text = message.text,
            timestamp = message.timestamp,
            toolName = message.toolName,
            answer = message.answer,
        )

    /** A cached entry as the screen reads it. */
    private fun toMessage(entry: TranscriptEntry): ChatMessage =
        ChatMessage(
            id = entry.id,
            role = entry.role,
            text = entry.text,
            timestamp = entry.timestamp,
            toolName = entry.toolName,
            answer = entry.answer,
        )

    /**
     * The messages this app made up that the fetched set has no equivalent for,
     * in the order they were already in. An equivalent that is now in the fetch
     * leaves its local copy behind rather than showing it twice, which is how an
     * optimistic question trace or prompt echo retires once pi has recorded it.
     */
    private fun carriedLocals(before: List<ChatMessage>, fetched: List<ChatMessage>): List<ChatMessage> {
        val kept = ArrayList<ChatMessage>()
        for (message in before) {
            if (!isLocal(message)) continue
            if (fetched.any { sameEntry(it, message) }) continue
            // Two copies of the same local entry are one entry and settle the same
            // way once the fetch brings its record.
            if (kept.any { sameEntry(it, message) }) continue
            kept.add(message)
        }
        return kept
    }

    /** True for a message the app made up rather than one the bridge reported. */
    private fun isLocal(message: ChatMessage): Boolean =
        SYNTHETIC_ID_PREFIXES.any { message.id.startsWith(it) }

    /**
     * Whether a fetched entry and a local message are the same thing. A question
     * trace is compared on its question and its answer together, because that is
     * the pair the tool result repeats; everything else is compared on role and
     * text. Both sides are trimmed, since a local answer is trimmed before it is
     * shown and the bridge's own copy may not be.
     */
    private fun sameEntry(a: ChatMessage, b: ChatMessage): Boolean =
        if (a.role == QUESTION_ROLE && b.role == QUESTION_ROLE) {
            a.text.trim() == b.text.trim() && a.answer?.trim() == b.answer?.trim()
        } else {
            a.role == b.role && a.text.trim() == b.text.trim()
        }

    /**
     * When the session wrote an entry, in epoch millis. The session entry's own
     * ISO 8601 timestamp is the one every entry carries, so it is used first; the
     * message inside it carries an epoch in millis of its own and is the fallback
     * for a payload that lost the first. Neither present leaves 0, which the
     * merge fills from the entry's neighbour rather than letting a dateless one
     * jump to the front of the transcript.
     */
    private fun entryTime(entry: JSONObject): Long {
        val iso = entry.optString("timestamp").takeIf { it.isNotBlank() && it != "null" }
        if (iso != null) {
            runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()?.let { return it }
        }
        return entry.optJSONObject("message")?.optLong("timestamp") ?: 0L
    }

    fun updateAppearance(value: String) {
        store.appearance = value
        appearance = value
    }

    /** Turn clipboard capture on or off; the choice is written down. */
    fun updateClipboardCapture(value: Boolean) {
        store.clipboardCapture = value
        clipboardCapture = value
    }

    /** Switch between the transcript and the deck; the choice is written down. */
    fun updateViewMode(value: String) {
        store.viewMode = value
        viewMode = value
    }

    /** Choose one of the four dark palettes; the choice is written down. */
    fun updateTheme(value: String) {
        store.theme = value
        theme = value
    }

    /**
     * Search the whole session for [query]. A blank query clears the panel
     * rather than asking the bridge for everything, which is what an empty
     * substring would otherwise mean to a match. A newer query cancels the
     * result of an older one, so a slow response never wins the race.
     */
    fun search(query: String) {
        val text = query.trim()
        val seq = ++searchSeq
        if (text.isEmpty()) {
            searchResults = emptyList()
            searching = false
            searchFailed = false
            return
        }
        searching = true
        searchFailed = false
        safeLaunch {
            runCatching { client.search(text) }
                .onSuccess { payload ->
                    if (seq != searchSeq) return@onSuccess
                    // The bridge may have changed hands mid-request; the results
                    // then belong to another session and are dropped rather than
                    // shown under this one's pin.
                    val from = payload.optString("sessionId").takeIf { it.isNotBlank() && it != "null" }
                    val attached = attachedSessionId
                    if (from != null && attached != null && from != attached) {
                        refreshState()
                        return@onSuccess
                    }
                    val array = payload.optJSONArray("results") ?: JSONArray()
                    val hits = ArrayList<SearchHit>()
                    for (index in 0 until array.length()) {
                        val hit = array.optJSONObject(index) ?: continue
                        hits.add(
                            SearchHit(
                                entryId = hit.optString("entryId").ifBlank { "hit-$index" },
                                role = hit.optString("role"),
                                snippet = hit.optString("snippet"),
                                prompt = hit.string("prompt"),
                                turnId = hit.string("turnId"),
                            )
                        )
                    }
                    searchResults = hits
                    searching = false
                }
                .onFailure { error ->
                    if (seq != searchSeq) return@onFailure
                    searching = false
                    searchFailed = true
                    // A 404 is what an older bridge answers for a path it does not
                    // know, and the running bridge stays old until it is reloaded.
                    notifyError(
                        if (error is BridgeException && error.code == 404) {
                            "this bridge has no search yet; reload it on the laptop"
                        } else {
                            Diagnostics.describe(error, store.baseUrl)
                        },
                    )
                }
        }
    }

    /** Close the panel: drop the last query's results and any error. */
    fun clearSearch() {
        searchSeq++
        searchResults = emptyList()
        searching = false
        searchFailed = false
    }

    /**
     * Ask the bridge for the commands a prompt may dispatch. The list is
     * optional: an older bridge has no endpoint and answers 404, and then the
     * palette simply offers nothing. It is read once per session, so opening
     * the palette costs nothing, and a failure that is not a 404 is left
     * unloaded so the next reachable moment tries again.
     */
    fun loadCommands() {
        val session = attachedSessionId
        if (commandsLoaded && session == commandsFor) return
        if (commandsInFlight) return
        commandsInFlight = true
        safeLaunch {
            runCatching { client.commands() }
                .onSuccess { payload ->
                    commandsInFlight = false
                    val from = payload.optString("sessionId").takeIf { it.isNotBlank() && it != "null" }
                    val attached = attachedSessionId
                    // The bridge can change hands mid-request; those commands
                    // belong to another session and are dropped.
                    if (from != null && attached != null && from != attached) {
                        refreshState()
                        return@onSuccess
                    }
                    val array = payload.optJSONArray("commands") ?: JSONArray()
                    val list = ArrayList<SessionCommand>()
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val name = item.optString("name")
                        if (name.isBlank()) continue
                        list.add(
                            SessionCommand(
                                name = name,
                                description = item.optString("description"),
                                source = item.optString("source"),
                            )
                        )
                    }
                    commands = list
                    commandsFor = attachedSessionId
                    commandsLoaded = true
                    commandsNote = null
                }
                .onFailure { error ->
                    commandsInFlight = false
                    // A 404 is an older bridge with no such path, the same way
                    // search reads it. There is nothing to offer and nothing
                    // worth saying outside the palette, so remember only that
                    // this session has none.
                    if (error is BridgeException && error.code == 404) {
                        commandsFor = attachedSessionId
                        commandsLoaded = true
                        commandsNote = "this bridge has no command list; reload it on the laptop"
                    }
                }
        }
    }

    /** Ask either view to open the turn with this id on its next composition. */
    fun jumpToTurn(turnId: String) {
        pendingJump = turnId
    }

    /** A view has opened the turn it was asked for; forget the signal. */
    fun clearJump() {
        pendingJump = null
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
        showingSavedCopy = false
        forgetSession()
    }

    fun updateDraft(value: String) {
        draft = value
    }

    /**
     * Save one prompt and its answer as a pin. A second pin of the same two
     * texts is refused, because the record it would hold is already there and
     * two identical entries would only be noise; the caller says so. Returns
     * true when a new pin was added.
     */
    fun pin(prompt: String?, answer: String): Boolean {
        val promptText = prompt.orEmpty().trim()
        val answerText = answer.trim()
        if (answerText.isEmpty() || isPinned(promptText, answerText)) return false
        pins.add(
            0,
            Pin(
                id = UUID.randomUUID().toString(),
                title = defaultPinTitle(promptText),
                prompt = promptText,
                answer = answerText,
            ),
        )
        pinsStore.save(pins)
        return true
    }

    /** True when this prompt and answer are already pinned. */
    fun isPinned(prompt: String?, answer: String): Boolean {
        val promptText = prompt.orEmpty().trim()
        val answerText = answer.trim()
        if (answerText.isEmpty()) return false
        return pins.any { it.prompt == promptText && it.answer == answerText }
    }

    /** Change only a pin's title; the two texts are the record and stay put. */
    fun renamePin(id: String, title: String) {
        val index = pins.indexOfFirst { it.id == id }
        if (index < 0) return
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed == pins[index].title) return
        pins[index] = pins[index].copy(title = trimmed)
        pinsStore.save(pins)
    }

    fun deletePin(id: String) {
        if (pins.removeAll { it.id == id }) pinsStore.save(pins)
    }

    fun appendToDraft(text: String) {
        if (text.isBlank()) return
        draft = if (draft.isBlank()) text else "${draft.trimEnd()} $text"
    }

    /**
     * Put text captured from the clipboard into the composer. It joins an
     * existing draft on a line of its own rather than replacing it, because a
     * capture that wiped what was typed would lose work silently, which is worse
     * than the extra newline. Nothing is sent; the user still decides.
     */
    fun captureToDraft(text: String) {
        val picked = text.trim()
        if (picked.isEmpty()) return
        draft = if (draft.isBlank()) picked else "${draft.trimEnd()}\n$picked"
    }

    fun clearDraft() {
        draft = ""
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || requestsBlocked()) return
        val localId = "local-${UUID.randomUUID()}"
        messages.add(ChatMessage(id = localId, role = "user", text = trimmed))
        busy = true
        safeLaunch {
            runCatching { client.prompt(trimmed, sessionId = attachedSessionId) }
                .onSuccess { outcome = RequestOutcome(++outcomeSeq, ok = true) }
                .onFailure { error ->
                    busy = false
                    if (wrongSession(error)) {
                        // The bridge moved while this was in flight and acted on
                        // nothing. Put the prompt back in the composer rather than
                        // let it vanish with the transcript that is about to go.
                        messages.removeAll { it.id == localId }
                        if (draft.isBlank()) draft = trimmed
                        handleWrongSession()
                    } else {
                        notifyError("prompt: ${Diagnostics.describe(error, store.baseUrl)}")
                    }
                    outcome = RequestOutcome(++outcomeSeq, ok = false)
                }
        }
    }

    fun abort() {
        if (requestsBlocked()) return
        safeLaunch {
            runCatching { client.abort(sessionId = attachedSessionId) }
                .onFailure { error ->
                    if (wrongSession(error)) {
                        handleWrongSession()
                    } else {
                        error.message?.let { notifyError(it) }
                    }
                }
        }
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

    /**
     * Forget everything a bad launch could be reading: the trace, the data a
     * launch set aside, the saved transcript, the pins and the attachment. The
     * appearance and the view stay, because they are choices and not state.
     *
     * Nothing here touches the laptop. The transcript is a cache and is rebuilt
     * from the bridge, and the pins are the only copy on the phone, which is why
     * the settings screen asks before calling this.
     */
    fun clearCrashState() {
        disconnect()
        transcriptStore.clear()
        pinsStore.clear()
        crashLog.clearLastCrash()
        pins.clear()
        messages.clear()
        showingSavedCopy = false
        pendingQuestion = null
        store.forgetAttachment()
        forgetSession()
        lastCrash = null
        quarantinedData = false
        clearNotices()
        statusLine = "Add the bridge token in Settings"
    }

    /**
     * Why the composer is off, or null when a prompt can be sent.
     *
     * Only a dead stream blocks it now. A session change does not: the bridge
     * refuses a call that names another session, so the composer stays open and a
     * prompt that races a handover comes back with the draft kept.
     */
    val composerBlock: String?
        get() = if (connected) null else "Not connected"

    /** True when this app has no verified session to act on. */
    private fun requestsBlocked(): Boolean = composerBlock != null

    /**
     * The bridge refused a mutating call because it now serves another session.
     * Nothing was recorded on the laptop, so the app asks who it serves and lets
     * applyState move there and say so in one line.
     */
    private fun handleWrongSession() {
        busy = false
        refreshState()
    }

    /** True when the bridge refused a call because it had changed hands. */
    private fun wrongSession(error: Throwable): Boolean =
        error is BridgeException && error.code == 409

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
            runCatching { client.answer(questionId, value, custom, sessionId = attachedSessionId) }
                .onSuccess { outcome = RequestOutcome(++outcomeSeq, ok = true) }
                .onFailure { error ->
                    // Nothing was recorded on the laptop, so the answer must not
                    // stay on screen claiming that it was, and a retry of the
                    // same option must not look like a second answer.
                    if (trace != null) messages.remove(trace)
                    if (wrongSession(error)) {
                        handleWrongSession()
                    } else {
                        notifyError("answer: ${Diagnostics.describe(error, store.baseUrl)}")
                    }
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
            runCatching { client.answer(questionId, "", cancel = true, sessionId = attachedSessionId) }
                .onFailure { error ->
                    messages.remove(trace)
                    if (wrongSession(error)) {
                        handleWrongSession()
                    } else {
                        notifyError("cancel: ${Diagnostics.describe(error, store.baseUrl)}")
                    }
                }
        }
    }

    private fun handleEvent(event: JSONObject) {
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
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
                        liveAnswer = activeAssistant?.toString()
                    }
                    "user" -> {
                        // A new prompt starts a new answer, and the prompt itself
                        // goes on the transcript. It may have been typed at the
                        // laptop rather than here, and history alone would not show
                        // it until the next reconnect. The app echoes its own
                        // prompts, so an identical one already on screen is this
                        // same turn arriving back.
                        runCandidates.clear()
                        val prompt = textOf(message, "content").trim()
                        val echoed = messages.lastOrNull { it.role == "user" }?.text?.trim()
                        if (prompt.isNotBlank() && prompt != echoed) {
                            messages.add(
                                ChatMessage(
                                    id = "prompt-${UUID.randomUUID()}",
                                    role = "user",
                                    text = prompt,
                                )
                            )
                        }
                    }
                }
            }
            "message_update" -> {
                // Thinking and tool-call arguments stream through this same event
                // and carry a delta of their own, so the type is what separates
                // the answer from the working out that produced it.
                val update = data.optJSONObject("assistantMessageEvent")
                if (update?.optString("type") == "text_delta") {
                    val delta = update.optString("delta")
                    if (delta.isNotEmpty()) {
                        activeAssistant?.append(delta)
                        liveAnswer = activeAssistant?.toString()
                    }
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
                    liveAnswer = text
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
        // The run has settled, so the streamed text gives way to the committed
        // answer below. Clearing it here is what keeps the two from both showing.
        liveAnswer = null
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
        val time = entryTime(entry)
        // A tool result is process, and process stays off the transcript. The
        // question tools are the exception: what they return is what the person
        // chose, which is an answer and not narration. A questionnaire is the
        // one entry that becomes several, one per question it asked.
        if (role == "toolResult") return questionTraces(entry, message, text, time)
        if (text.isBlank()) return emptyList()
        return listOf(
            ChatMessage(
                id = entry.optString("id", UUID.randomUUID().toString()),
                role = role,
                text = text,
                toolName = if (role == "assistant" && hasToolCalls(message)) "toolCall" else null,
                timestamp = time,
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
    private fun questionTraces(entry: JSONObject, message: JSONObject, text: String, time: Long): List<ChatMessage> {
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
            return details?.let { questionnaireTraces(id, it, time) }.orEmpty()
        }
        if (details == null) {
            // Only the sentence came through. Keep it, rather than invent the
            // question it answered: this is a payload from before the details
            // were carried, and the choice is still worth showing.
            val sentence = text.trim()
            if (sentence.isBlank()) return emptyList()
            return listOf(
                ChatMessage(id = id, role = QUESTION_ROLE, text = "", answer = sentence, toolName = tool, timestamp = time)
            )
        }
        val question = details.string("question").orEmpty()
        // Null when nothing was chosen, which is what a dismissal records. A
        // failure records the same null and says so in the sentence instead.
        val answer = details.string("answer")
        if (answer == null && !text.contains("cancel", ignoreCase = true)) return emptyList()
        if (question.isBlank()) return emptyList()
        return listOf(
            ChatMessage(id = id, role = QUESTION_ROLE, text = question, answer = answer, toolName = tool, timestamp = time)
        )
    }

    /**
     * A questionnaire's questions as separate entries, one per question the
     * person answered, which is the shape the app puts on the transcript while
     * the widget is still open. The question's label prefixes it when there was
     * more than one, so a long pair of lists still reads straight down. A
     * questionnaire nobody answered was dismissed, and says so once.
     */
    private fun questionnaireTraces(id: String, details: JSONObject, time: Long): List<ChatMessage> {
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
                    timestamp = time,
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
                        timestamp = time,
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

    /**
     * The words a message carries, and nothing else. Thinking and tool calls are
     * part of how an answer was reached rather than part of it, so a transcript
     * built from this shows the answer on its own. An image is named, because a
     * prompt that carried one would otherwise read as empty.
     */
    private fun textOf(message: JSONObject?, key: String): String {
        val content = message?.opt(key) ?: return ""
        if (content is String) return content
        if (content !is JSONArray) return ""
        val parts = ArrayList<String>()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "text" -> parts.add(block.optString("text"))
                "image" -> parts.add("[image]")
            }
        }
        return parts.filter { it.isNotBlank() }.joinToString("\n")
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
