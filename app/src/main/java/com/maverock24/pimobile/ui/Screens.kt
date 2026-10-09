package com.maverock24.pimobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val turns = vm.turns
    val pending = vm.pendingQuestion

    // Search is a mode, not a field that always takes space. It is opened from
    // the row above the composer and, while it is open, the results take the
    // transcript's place so the composer and the switch stay where they are.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // A keystroke is not a request: the typing settles before the bridge is
    // asked, and a newer keystroke cancels the request the older one would send.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            vm.clearSearch()
            return@LaunchedEffect
        }
        delay(350)
        vm.search(query)
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

            if (searchOpen) {
                SearchPanel(
                    vm = vm,
                    query = query,
                    onQueryChange = { query = it },
                    onOpenResult = { hit ->
                        hit.turnId?.let(vm::jumpToTurn)
                        searchOpen = false
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (turns.isEmpty() && pending == null) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (vm.busy) "thinking…" else "no results yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (vm.viewMode == "deck") {
                // The same transcript as cards. The deck and the transcript read
                // one view model, so a turn and its answer are one thing shown
                // two ways rather than two lists kept in step.
                TurnDeck(vm = vm, modifier = Modifier.weight(1f).fillMaxWidth())
            } else {
                Transcript(vm = vm, modifier = Modifier.weight(1f).fillMaxWidth())
            }

            // The switch sits directly above the composer, in the same thumb's
            // reach as the box you type in, and above it rather than over it so
            // the composer keeps its full width and height. Search shares the
            // row, so it is one tap away and never lands on the composer or on a
            // waiting question below the transcript.
            ViewModeSwitch(
                mode = vm.viewMode,
                onChange = vm::updateViewMode,
                searchOpen = searchOpen,
                onToggleSearch = { searchOpen = !searchOpen },
            )

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
 * The transcript: a list of turns whose prompt is a sticky header and whose
 * answers open one at a time. It is the view the deck substitutes for, kept
 * whole so the two share nothing but the view model they both read.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Transcript(vm: ChatViewModel, modifier: Modifier = Modifier) {
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

    // How many items the transcript makes: a prompt for every turn, a body for
    // the one the accordion has open, a card when a question is waiting, and the
    // end marker. The scroll below aims at the last of those.
    val bottomItem = turns.size +
        (if (turns.any { it.id == expandedTurn }) 1 else 0) +
        (if (pending != null) 1 else 0)

    LaunchedEffect(pending?.id, turns.size, turns.lastOrNull()?.answers?.size) {
        // The end of the list, so the newest entry is on screen. Scrolling an
        // item to the top of the viewport is not the same thing: a long answer
        // would show its first lines, and the question widget below it would come
        // to rest above the bottom edge rather than at it.
        listState.animateScrollToItem(bottomItem)
    }

    // A search hit names the turn to open. This view opens that turn's answer and
    // scrolls its prompt to the top, then clears the signal so it fires once. A
    // hit from outside the held window names no turn here and only clears it.
    val jump = vm.pendingJump
    LaunchedEffect(jump) {
        val target = jump ?: return@LaunchedEffect
        val index = turns.indexOfFirst { it.id == target }
        if (index >= 0) {
            followNewest = false
            explicitTurn = target
            listState.animateScrollToItem(index)
        }
        vm.clearJump()
    }

    // One scrolling surface for the transcript and the question widget. The
    // widget is the newest thing there is and pi cannot go on without it, so it
    // is the last item, below the turn that is waiting on it, and its options
    // keep their full height instead of living inside a scroll box of their own.
    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(AnswerStyle.answerGap),
    ) {
        turns.forEach { turn ->
            // The prompt is a sticky header, so it stays at the top of the
            // screen while its answer is scrolled, and the answer can be folded
            // away without going back up to find it.
            stickyHeader(key = "prompt-${turn.id}") {
                TurnPrompt(
                    turn = turn,
                    expanded = turn.id == expandedTurn,
                    onToggle = {
                        followNewest = false
                        explicitTurn = if (expandedTurn == turn.id) null else turn.id
                    },
                )
            }
            if (turn.id == expandedTurn) {
                item(key = "body-${turn.id}") {
                    TurnBody(
                        turn = turn,
                        liveText = if (turn.id == newestTurn) liveAnswer else null,
                        working = turn.id == newestTurn && vm.busy,
                        // Fades in and out as the accordion opens and closes, so
                        // folding an answer reads as a change rather than as a
                        // jump.
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
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
        // The end of the list, so a scroll to the last item comes to rest at the
        // bottom edge with the newest entry above it, rather than putting that
        // entry at the top of the screen.
        item(key = "bottom") { Spacer(modifier = Modifier.height(1.dp)) }
    }
}

/**
 * The same transcript as a deck: one prompt per card, newest first, swiped
 * sideways instead of scrolled. A turn and its answer are the same objects the
 * transcript draws, so there is one transcript and two ways to read it rather
 * than two records to keep in step.
 */
@Composable
private fun TurnDeck(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val turns = vm.turns
    val pending = vm.pendingQuestion
    val liveAnswer = vm.liveAnswer
    val newestTurn = turns.lastOrNull()?.id

    // Newest first. A waiting question leads, because it is the newest thing in
    // the session and pi is blocked on it.
    val ordered = turns.asReversed()
    val pageCount = ordered.size + if (pending != null) 1 else 0
    val pagerState = rememberPagerState(pageCount = { pageCount })

    // The card the answer is open on, one at a time as in the transcript.
    var openTurn by rememberSaveable { mutableStateOf<String?>(null) }
    // The older card the thumb is on, held by turn id: a turn arriving above it
    // must not swap the card underneath, which is what a page number would do.
    var heldTurn by remember { mutableStateOf<String?>(null) }

    val keys = remember(ordered.map { it.id }, pending?.id) {
        buildList {
            if (pending != null) add("pending-question")
            ordered.forEach { add("turn-${it.id}") }
        }
    }
    val currentKeys by rememberUpdatedState(keys)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            // Page zero is the newest card and follows whatever arrives there.
            // Any other page is a turn being read, so it keeps its place.
            heldTurn = if (page <= 0) null else currentKeys.getOrNull(page)?.removePrefix("turn-")
        }
    }

    LaunchedEffect(keys, pagerState) {
        // A waiting question is first and pi is blocked on it, so the deck opens
        // there. Otherwise hold the card the user is on, or the newest when they
        // are already at the front.
        val held = heldTurn?.let { keys.indexOf("turn-$it") } ?: -1
        val target = if (pending != null) 0 else if (held >= 0) held else 0
        if (target != pagerState.currentPage) pagerState.scrollToPage(target)
    }

    // A search hit names the turn to open. The deck moves its pager to that card
    // and holds it there, then clears the signal so it fires once. A hit from
    // outside the held window names no card here and only clears it.
    val jump = vm.pendingJump
    LaunchedEffect(jump) {
        val target = jump ?: return@LaunchedEffect
        val index = ordered.indexOfFirst { it.id == target }
        if (index >= 0) {
            heldTurn = target
            pagerState.scrollToPage(index + if (pending != null) 1 else 0)
        }
        vm.clearJump()
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        key = { index ->
            if (pending != null && index == 0) {
                "pending-question"
            } else {
                "turn-" + ordered[if (pending != null) index - 1 else index].id
            }
        },
    ) { page ->
        val index = page - if (pending != null) 1 else 0
        if (pending != null && page == 0) {
            // The question is the whole page and scrolls on its own, so a long
            // widget is answered without leaving the deck.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                QuestionCard(
                    pending = pending,
                    onSelect = { questionId, value -> vm.answerQuestion(questionId, value, false) },
                    onTyped = { questionId, text -> vm.answerQuestion(questionId, text, true) },
                    onCancel = vm::cancelQuestion,
                )
            }
        } else if (index in ordered.indices) {
            val turn = ordered[index]
            DeckCard(
                turn = turn,
                expanded = turn.id == openTurn,
                liveText = if (turn.id == newestTurn) liveAnswer else null,
                working = turn.id == newestTurn && vm.busy,
                onToggle = { openTurn = if (openTurn == turn.id) null else turn.id },
            )
        }
    }
}

/**
 * One turn as a card: the prompt in full as its heading, the answer folded
 * behind a tap on that heading. The card scrolls on its own, so an answer taller
 * than the screen is read within the page rather than by leaving the deck.
 */
@Composable
private fun DeckCard(
    turn: ChatTurn,
    expanded: Boolean,
    liveText: String?,
    working: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val prompt = turn.prompt
    // A turn with no prompt is the answers that arrived before any prompt, which
    // is what a history fetch mid-run gives back. There is nothing to fold them
    // behind, so they are always shown.
    val openable = prompt != null && turn.answers.isNotEmpty()
    val showBody = prompt == null || expanded
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "deckChevron")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = AnswerStyle.promptGap),
    ) {
        Surface(
            color = scheme.surfaceVariant,
            border = BorderStroke(1.dp, scheme.outline),
            shape = RoundedCornerShape(AnswerStyle.promptRadius),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                if (prompt != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .clickable(enabled = openable, onClick = onToggle)
                            .tactile(enabled = openable)
                            .padding(
                                start = AnswerStyle.promptPadding,
                                end = AnswerStyle.promptPadding,
                                top = AnswerStyle.promptPadding - 2.dp,
                                bottom = AnswerStyle.promptPadding - 2.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(AnswerStyle.accentBar)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(scheme.primary),
                        )
                        Spacer(modifier = Modifier.width(AnswerStyle.accentBarGap))
                        Text(
                            text = prompt,
                            fontSize = AnswerStyle.promptSize,
                            lineHeight = AnswerStyle.promptLineHeight,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                        if (openable) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (expanded) "Hide answer" else "Show answer",
                                tint = scheme.primary,
                                modifier = Modifier.size(22.dp).rotate(chevron),
                            )
                        }
                    }
                }
                when {
                    showBody -> TurnBody(
                        turn = turn,
                        liveText = liveText,
                        working = working,
                        modifier = Modifier.padding(
                            start = AnswerStyle.promptPadding,
                            end = AnswerStyle.promptPadding,
                            bottom = AnswerStyle.promptPadding,
                        ),
                    )
                    // The working indicator belongs on the newest card while pi
                    // is busy, so it shows even folded: the deck never leaves the
                    // person without the one signal that work is happening.
                    working -> WorkingShimmer(
                        modifier = Modifier.padding(
                            start = AnswerStyle.promptPadding,
                            bottom = AnswerStyle.promptPadding,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The switch between the transcript and the deck: two buttons that name the
 * views, the one in use bearing the mark the appearance buttons use. It sits in
 * a row of its own so it never lands on the composer.
 */
@Composable
private fun ViewModeSwitch(
    mode: String,
    onChange: (String) -> Unit,
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("transcript" to "Transcript", "deck" to "Cards").forEach { (value, label) ->
            TextButton(onClick = { onChange(value) }, modifier = Modifier.tactile()) {
                Text(
                    text = if (mode == value) "• $label" else label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (mode == value) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onToggleSearch, modifier = Modifier.tactile()) {
            Text(
                text = if (searchOpen) "Close" else "Search",
                style = MaterialTheme.typography.bodyMedium,
                color = if (searchOpen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Full-session search: a field and the matches under it. It takes the
 * transcript's place while it is open, so the composer and the view switch keep
 * their positions and neither is covered. A tap on a hit hands its turn to the
 * view that is showing, which opens and scrolls to it.
 */
@Composable
private fun SearchPanel(
    vm: ChatViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenResult: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text("Search every prompt and answer…") },
        )
        vm.searchError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val results = vm.searchResults
        // Search reads the whole session; the screen only holds a window of it.
        // A hit whose turn is outside that window has nothing here to open, so
        // its row is inert rather than tapping into nothing.
        val loadedTurnIds = vm.turns.mapTo(HashSet()) { it.id }
        when {
            vm.searching -> Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "searching…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            results.isEmpty() && query.isNotBlank() && vm.searchError == null ->
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "no matches",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(results, key = { it.entryId }) { hit ->
                    SearchResultRow(
                        hit = hit,
                        openable = hit.turnId != null && hit.turnId in loadedTurnIds,
                        onOpen = { onOpenResult(hit) },
                    )
                }
            }
        }
    }
}

/**
 * One search hit: the snippet with the match inside it, and the prompt of the
 * turn it belongs to, so a result says where it was said and not just what.
 * [openable] is false for a hit whose turn is not in the held window, since
 * there is nothing on screen to open.
 */
@Composable
private fun SearchResultRow(hit: SearchHit, openable: Boolean, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = openable, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = hit.snippet,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (hit.prompt != null) {
            Text(
                text = hit.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * One prompt, as the list's sticky header: a card that stays at the top of the
 * screen while the answer under it is scrolled, so an answer can be folded away
 * without scrolling back up to the prompt that opened it. Tapping the card
 * toggles that answer, and the chevron turns to say which way it went.
 *
 * It is deliberately unlike an answer: larger, heavier, on its own surface with
 * a border and its own indent, so a column of prompts reads as a list of what was
 * asked rather than blending into the prose. It paints the screen colour behind
 * itself because the answer passes underneath.
 */
@Composable
private fun TurnPrompt(turn: ChatTurn, expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val prompt = turn.prompt ?: return
    val openable = turn.answers.isNotEmpty()
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "promptChevron")
    val shape = RoundedCornerShape(AnswerStyle.promptRadius)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(top = AnswerStyle.promptGap)
            .background(scheme.background)
            .clip(shape)
            .background(scheme.surfaceVariant)
            .border(1.dp, scheme.outline, shape)
            .clickable(enabled = openable, onClick = onToggle)
            .tactile(enabled = openable)
            .padding(
                start = AnswerStyle.promptPadding,
                end = AnswerStyle.promptPadding,
                top = AnswerStyle.promptPadding - 2.dp,
                bottom = AnswerStyle.promptPadding - 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(AnswerStyle.accentBar)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.primary),
        )
        Spacer(modifier = Modifier.width(AnswerStyle.accentBarGap))
        Text(
            text = prompt,
            fontSize = AnswerStyle.promptSize,
            lineHeight = AnswerStyle.promptLineHeight,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (openable) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide answer" else "Show answer",
                tint = scheme.primary,
                modifier = Modifier.size(22.dp).rotate(chevron),
            )
        }
    }
}

/**
 * The answer of one prompt, drawn only for the turn the accordion has open.
 * Nothing else of the run is shown, which is why an older answer is reachable
 * from its prompt alone.
 */
@Composable
private fun TurnBody(turn: ChatTurn, liveText: String?, working: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(start = AnswerStyle.accentBar + AnswerStyle.accentBarGap),
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
        // The answer of the run that is still going. It is drawn like a finished
        // answer and replaced by the committed one when the run settles, which
        // clears it in the same step that adds the answer.
        if (!liveText.isNullOrBlank()) {
            AnswerView(
                text = liveText,
                modifier = Modifier.widthIn(max = AnswerStyle.measure),
            )
        } else if (working && turn.answers.isEmpty()) {
            // Nothing has streamed yet and nothing has been committed, so the run
            // has nothing to show but itself: this is the step between the prompt
            // above and the answer that replaces it. It never sits beside an
            // answer that has already arrived.
            WorkingShimmer()
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
