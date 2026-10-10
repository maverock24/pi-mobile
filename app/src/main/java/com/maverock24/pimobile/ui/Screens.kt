package com.maverock24.pimobile.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.maverock24.pimobile.data.Pin
import com.maverock24.pimobile.update.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

@Composable
fun PiRemoteTheme(
    mode: String = "dark",
    theme: String = AnswerStyle.defaultTheme,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    // Light mode is not a theme: it always takes the light scheme and the flat
    // background, whatever dark palette is chosen. The palette colours the dark
    // scheme and the sky drawn behind everything.
    val palette = AnswerStyle.theme(theme)
    val scheme = if (dark) palette.scheme else AnswerStyle.lightScheme
    MaterialTheme(colorScheme = scheme) {
        // Material3 leaves LocalContentColor at black, so any Text outside a
        // Surface or a Button would draw black. In a dark theme that is
        // unreadable, so the page's content colour is set here to the scheme's
        // own onBackground. Do not remove this again.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            // One ground for every screen, drawn here once and left alone by the
            // screens above it, so the transcript, the deck and the settings all sit
            // on the same sky rather than each painting its own.
            Box(modifier = Modifier.fillMaxSize().skyBackground(scheme, dark, palette)) { content() }
        }
    }
}

/**
 * The page the app sits on: the scheme's own background colour, with the media
 * app's night sky drawn over it for the dark scheme.
 *
 * The flat colour is painted either way, because the screens above are
 * transparent so that this is what shows through. Leaving it unpainted for the
 * light scheme put that scheme's dark-on-light text on whatever the window
 * happened to have behind it.
 */
private fun Modifier.skyBackground(scheme: ColorScheme, dark: Boolean, palette: AnswerTheme): Modifier =
    this.drawWithContent {
        drawRect(color = scheme.background)
        if (dark) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(palette.skyTop, palette.skyMid, palette.skyBottom),
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(palette.skyHorizonGlow, Color.Transparent),
                    center = Offset(size.width / 2f, 0f),
                    radius = size.width * 0.9f,
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(palette.skyMiddleGlow, Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.62f),
                    radius = size.width,
                )
            )
        }
        drawContent()
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
 * A hairline along the bar's bottom edge. The bar paints its own surface so it
 * reads as a bar against the page, and in the light scheme that surface is the
 * page's own colour, so this line is what keeps the two apart there. It is
 * drawn inside the bar's bounds, so it costs the bar no height.
 */
@Composable
private fun Modifier.barHairline(): Modifier {
    val color = MaterialTheme.colorScheme.outline
    return drawWithContent {
        drawContent()
        val thickness = 1.dp.toPx()
        drawRect(
            color = color,
            topLeft = Offset(0f, size.height - thickness),
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
    onToggleMic: () -> Unit,
    onOpenSettings: () -> Unit,
    // A notice's action is a value, because the notice outlives the composition
    // that raised it; this composition maps the value to the behaviour it has
    // now, so the action still runs after the screen is recreated.
    onNoticeAction: (NoticeAction) -> Unit,
) {
    val turns = vm.turns
    val pending = vm.pendingQuestion

    // Search is a mode, not a field of its own: while it is open the composer
    // becomes the field, so there is one input on screen and the action button
    // under it runs the search instead of sending. The prompt draft lives in the
    // view model and this text lives here, so opening search does not touch what
    // was typed for a prompt and closing it hands that draft straight back.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // The command palette opens on its own when the draft starts with a slash,
    // and by the affordance beside the field otherwise. Tapping a command only
    // writes it into the draft, so the palette never sends; the user still adds
    // arguments and presses Send.
    var paletteOpen by rememberSaveable { mutableStateOf(false) }
    // A command was just picked, so the palette stays closed while the user adds
    // arguments even though the draft still starts with a slash. It reopens once
    // the slash is gone, or if the affordance is tapped again.
    var paletteDismissed by rememberSaveable { mutableStateOf(false) }
    // Compose does not hand the app the text a selection picked, so the only way
    // to move selected text into the composer is the clipboard. The user selects
    // with the normal handles and taps Copy in the system toolbar; that copy is
    // the signal. The listener is registered only while the toggle is on and is
    // torn down when it goes off or the screen leaves composition, so nothing is
    // captured while the feature is off and nothing leaks.
    val captureClipboard = vm.clipboardCapture
    val context = LocalContext.current
    DisposableEffect(captureClipboard) {
        if (!captureClipboard) return@DisposableEffect onDispose {}
        val manager = context.getSystemService(ClipboardManager::class.java)
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val picked = readClipText(manager)
            if (!picked.isNullOrBlank()) {
                vm.captureToDraft(picked)
                vm.notifyConfirmation("Copied text added to prompt")
            }
        }
        manager?.addPrimaryClipChangedListener(listener)
        onDispose { manager?.removePrimaryClipChangedListener(listener) }
    }
    // A keystroke is not a request: the typing settles before the bridge is
    // asked, and a newer keystroke cancels the request the older one would send.
    // The button runs the same search at once when it is pressed.
    LaunchedEffect(query, searchOpen) {
        if (!searchOpen) return@LaunchedEffect
        if (query.isBlank()) {
            vm.clearSearch()
            return@LaunchedEffect
        }
        delay(350)
        vm.search(query)
    }

    // Commands are offered for a prompt and never for a search, and only when
    // the user is reaching for one: a leading slash, or the affordance. What
    // follows the slash is the filter, so /ski narrows to the skills. A pick
    // hides the palette until the slash goes, so the arguments can be typed.
    val slashQuery = if (!searchOpen && vm.draft.startsWith("/")) vm.draft.drop(1) else null
    val paletteVisible = !searchOpen && ((slashQuery != null && !paletteDismissed) || paletteOpen)
    LaunchedEffect(slashQuery) {
        if (slashQuery == null) paletteDismissed = false
    }
    // Opening the palette fetches the list if nothing has yet. The call is
    // keyed by session, so this is a no-op once the list is here.
    LaunchedEffect(paletteVisible) {
        if (paletteVisible) vm.loadCommands()
    }

    // System back closes one layer at a time. The palette and search are
    // mutually exclusive modes of the composer, so at most one of these is ever
    // enabled; with neither, back keeps the platform default and leaves the app.
    BackHandler(enabled = paletteVisible) {
        paletteOpen = false
        paletteDismissed = true
    }
    BackHandler(enabled = searchOpen) { searchOpen = false }

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
        // Transparent so the sky behind it is what shows, rather than the theme's
        // background colour painted over it.
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                // The working accent line rides on the bar's own top edge and the
                // hairline closes the bar off from the page. Both are drawn inside
                // the bar's bounds, so the bar keeps one height and the content
                // below it never moves when either appears or goes.
                modifier = Modifier.workingEdge(vm.busy).barHairline(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Column {
                        // The title names the session this screen is attached to,
                        // not the last thing typed. It stays a title. Two lines is
                        // the cap so a long name at a large font scale is read
                        // rather than cut mid-glyph.
                        Text(
                            text = vm.barTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The last prompt is a subtitle that tells you which turn
                        // you are in. It wraps and ellipsises rather than being
                        // pinned to one line: at fontScale 2.0 a single forced line
                        // is taller than the bar and the bottom of the glyphs is
                        // clipped.
                        Text(
                            text = vm.lastPrompt?.takeIf { it.isNotBlank() } ?: vm.attachedLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    // One slot for the working signal, floored at the height of the
                    // icon buttons beside it so showing or clearing it cannot move
                    // the bar. A floor rather than a fixed size because the waiting
                    // label is taller than 24 dp at a large font scale, and a fixed
                    // box would clip it.
                    Box(modifier = Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
                        when {
                            vm.pendingQuestion != null -> Text(
                                text = "waiting",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            vm.busy -> WorkingShimmer()
                        }
                    }
                    IconButton(onClick = { searchOpen = !searchOpen }, modifier = Modifier.tactile()) {
                        Icon(
                            imageVector = if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searchOpen) "Close search" else "Search",
                        )
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.tactile()) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The bar's title and subtitle now carry the session identity, so the
            // folder and name are not repeated under it. What still needs a line
            // of its own is a lost connection: the status says which bridge it is
            // trying and why it stopped, and it stays visible however many turns
            // the transcript holds.
            if (!vm.connected && vm.statusLine.isNotBlank()) {
                Text(
                    text = vm.statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // The transcript under that line can be the copy saved on this phone
            // rather than live, and saying so is the difference between a record
            // and a claim that the bridge just said it.
            if (!vm.connected && vm.showingSavedCopy) {
                Text(
                    text = "Showing the transcript saved on this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // The view model's notice channel draws here, in the order it keeps
            // its entries: the error, the actionable entry, then the
            // confirmations. One render site is the whole of it, so nothing can
            // stack a second message on top of this one.
            vm.notices.forEach { n ->
                NoticeBar(text = n.text, onDismiss = { vm.dismissNotice(n.id) }, isError = n.kind == NoticeKind.Error, actionLabel = n.actionLabel, onAction = n.action?.let { action -> { onNoticeAction(action) } })
            }

            if (searchOpen) {
                SearchPanel(
                    vm = vm,
                    query = query,
                    onOpenResult = { hit ->
                        hit.turnId?.let(vm::jumpToTurn)
                        searchOpen = false
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (vm.viewMode == "pins") {
                // Pins is its own view of the record, so it is shown even when
                // the transcript is empty: a phone that has not connected yet can
                // still read and send what it saved before.
                PinsView(
                    vm = vm,
                    paletteVisible = paletteVisible,
                    onConfirm = vm::notifyConfirmation,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (turns.isEmpty() && pending == null) {
                // With nothing on the transcript the screen should say which of
                // the three situations it is in, because the fix differs: pair,
                // retry, or wait. Showing the same "no results yet" for all three
                // leaves a first-run user with no way forward.
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when {
                            vm.token.isBlank() -> {
                                Text(
                                    text = "Not paired yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = onOpenSettings) { Text("Open Settings") }
                            }
                            !vm.connected -> {
                                Text(
                                    text = vm.statusLine.ifBlank { "Not connected" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = vm::ensureConnected) { Text("Retry") }
                            }
                            else -> Text(
                                text = if (vm.busy) "thinking…" else "no results yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (vm.viewMode == "deck") {
                // The same transcript as cards. The deck and the transcript read
                // one view model, so a turn and its answer are one thing shown
                // two ways rather than two lists kept in step.
                TurnDeck(
                    vm = vm,
                    onAnswerConfirmed = vm::notifyConfirmation,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                Transcript(
                    vm = vm,
                    onAnswerConfirmed = vm::notifyConfirmation,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }

            // The switch between the transcript and the deck stays its own row
            // here, directly above the composer and in the same thumb's reach as
            // the box you type in. It is one tap away and never lands on the
            // composer or on a waiting question. Search moved to the app bar, so
            // it is no longer sharing this row.
            ViewModeSwitch(
                mode = vm.viewMode,
                onChange = vm::updateViewMode,
            )

            // The commands sit directly above the composer, in the same thumb's
            // reach as the field they fill. It is only present while the user is
            // reaching for a command, so the transcript keeps the room otherwise.
            if (paletteVisible) {
                CommandPalette(
                    commands = vm.commands,
                    query = slashQuery,
                    note = vm.commandsNote,
                    onPick = { command ->
                        vm.updateDraft("/${command.name} ")
                        paletteOpen = false
                        paletteDismissed = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Composer(
                text = if (searchOpen) query else vm.draft,
                onTextChange = { if (searchOpen) query = it else vm.updateDraft(it) },
                placeholder = if (searchOpen) {
                    "Search every prompt and answer…"
                } else {
                    vm.composerBlock ?: "Prompt pi…"
                },
                partialText = partialText,
                listening = listening,
                busy = vm.busy,
                actionLabel = if (searchOpen) "Search" else if (vm.busy) "Steer" else "Send",
                // Search runs on whatever is typed, connected or not, while a
                // prompt still needs a live bridge.
                actionEnabled = if (searchOpen) {
                    query.isNotBlank()
                } else {
                    vm.draft.isNotBlank() && vm.composerBlock == null
                },
                onAction = {
                    if (searchOpen) {
                        vm.search(query)
                    } else {
                        vm.send(vm.draft)
                        vm.clearDraft()
                    }
                },
                onToggleCommands = {
                    if (paletteVisible) {
                        paletteOpen = false
                        paletteDismissed = true
                    } else {
                        paletteOpen = true
                        paletteDismissed = false
                    }
                },
                commandsEnabled = !searchOpen,
                onToggleMic = onToggleMic,
                onClear = {
                    if (searchOpen) {
                        query = ""
                        vm.clearSearch()
                    } else {
                        vm.clearDraft()
                    }
                },
                onStop = vm::abort,
                captureEnabled = vm.clipboardCapture,
                onToggleCapture = { vm.updateClipboardCapture(!vm.clipboardCapture) },
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
private fun Transcript(
    vm: ChatViewModel,
    onAnswerConfirmed: (String) -> Unit,
    modifier: Modifier = Modifier,
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
                        vm = vm,
                        turn = turn,
                        liveText = if (turn.id == newestTurn) liveAnswer else null,
                        working = turn.id == newestTurn && vm.busy,
                        onConfirm = onAnswerConfirmed,
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
private fun TurnDeck(
    vm: ChatViewModel,
    onAnswerConfirmed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
    var heldTurn by rememberSaveable { mutableStateOf<String?>(null) }

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
                vm = vm,
                turn = turn,
                expanded = turn.id == openTurn,
                liveText = if (turn.id == newestTurn) liveAnswer else null,
                working = turn.id == newestTurn && vm.busy,
                onConfirm = onAnswerConfirmed,
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
    vm: ChatViewModel,
    turn: ChatTurn,
    expanded: Boolean,
    liveText: String?,
    working: Boolean,
    onConfirm: (String) -> Unit,
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
                            .clickable(enabled = openable, role = Role.Button, onClick = onToggle)
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
                        vm = vm,
                        turn = turn,
                        liveText = liveText,
                        working = working,
                        onConfirm = onConfirm,
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
 * The switch between the transcript, the deck and the pins: one segmented row,
 * the view in use drawn as the chosen segment. It sits in a row of its own so it
 * never lands on the composer.
 */
@Composable
private fun ViewModeSwitch(
    mode: String,
    onChange: (String) -> Unit,
) {
    val modes = listOf("transcript" to "Transcript", "deck" to "Cards", "pins" to "Pins")
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        modes.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.tactile().semantics {
                    stateDescription = if (mode == value) "Selected" else "Not selected"
                },
            )
        }
    }
}

/**
 * The pins, newest first: each row is a title with enough of the prompt under it
 * to recognise the pin by. Tapping a row opens it whole. The row carries no
 * actions of its own because they all belong to one pin and need the pin on
 * screen, so they live in the opened view instead of on a list that can only
 * show a snippet.
 */
@Composable
private fun PinsView(
    vm: ChatViewModel,
    paletteVisible: Boolean,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pins = vm.pins
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    // An open pin is a layer over the list, so back returns to the list. It is
    // registered after the chat's handlers, which normally puts it ahead of
    // them; while the command palette is up, though, D3 puts the palette first,
    // so the pin stands down until the palette is gone.
    BackHandler(enabled = openedId != null && !paletteVisible) { openedId = null }
    val opened = pins.firstOrNull { it.id == openedId }

    if (opened != null) {
        PinDetail(
            pin = opened,
            vm = vm,
            onConfirm = onConfirm,
            onBack = { openedId = null },
            modifier = modifier,
        )
    } else if (pins.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No pins yet. Pin an answer to keep its prompt and its reply here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(AnswerStyle.answerGap),
        ) {
            items(pins, key = { it.id }) { pin ->
                PinRow(pin = pin, onClick = { openedId = pin.id })
            }
        }
    }
}

/** One pin in the list: its title, then the prompt, or the answer when there is none. */
@Composable
private fun PinRow(pin: Pin, onClick: () -> Unit) {
    val snippet = pin.prompt.ifBlank { pin.answer }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(AnswerStyle.promptRadius),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).tactile(),
    ) {
        Column(modifier = Modifier.padding(AnswerStyle.promptPadding)) {
            Text(
                text = pin.title,
                fontSize = AnswerStyle.promptSize,
                lineHeight = AnswerStyle.promptLineHeight,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = snippet,
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
 * One pin, opened. The prompt and the answer are shown in full, the answer drawn
 * the way answers are drawn everywhere else. Editing the title, deleting the pin
 * and sending the prompt again all live here, on the one pin they act on.
 *
 * Deleting takes two taps: the first asks, the second does it, so a thumb that
 * lands on Delete by accident cannot lose a pin.
 */
@Composable
private fun PinDetail(
    pin: Pin,
    vm: ChatViewModel,
    onConfirm: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable(pin.id) { mutableStateOf(false) }
    var titleDraft by rememberSaveable(pin.id) { mutableStateOf(pin.title) }
    var confirmingDelete by rememberSaveable(pin.id) { mutableStateOf(false) }
    val canSend = pin.prompt.isNotBlank() && vm.composerBlock == null

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.tactile()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to pins")
            }
            Text(
                text = "Pinned",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(AnswerStyle.answerGap),
        ) {
            if (editing) {
                OutlinedTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Title") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.renamePin(pin.id, titleDraft)
                            editing = false
                            onConfirm("Title saved")
                        },
                        enabled = titleDraft.isNotBlank(),
                        modifier = Modifier
                            .heightIn(min = AnswerStyle.buttonHeight)
                            .tactile(haptics = true, enabled = titleDraft.isNotBlank(), depth = AnswerStyle.keyDepth),
                    ) {
                        Text("Save", style = MaterialTheme.typography.bodyLarge)
                    }
                    TextButton(
                        onClick = { titleDraft = pin.title; editing = false },
                        modifier = Modifier.tactile(),
                    ) { Text("Cancel") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pin.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { titleDraft = pin.title; editing = true },
                        modifier = Modifier.tactile(),
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit title")
                    }
                }
            }

            if (pin.prompt.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(AnswerStyle.promptRadius),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = pin.prompt,
                        fontSize = AnswerStyle.promptSize,
                        lineHeight = AnswerStyle.promptLineHeight,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(AnswerStyle.promptPadding),
                    )
                }
            }

            AnswerView(
                text = pin.answer,
                modifier = Modifier.widthIn(max = AnswerStyle.measure),
            )

            Button(
                onClick = {
                    vm.send(pin.prompt)
                    onConfirm("Prompt sent")
                },
                enabled = canSend,
                modifier = Modifier
                    .heightIn(min = AnswerStyle.buttonHeight)
                    .tactile(haptics = true, enabled = canSend, depth = AnswerStyle.keyDepth),
            ) {
                Text("Send prompt", style = MaterialTheme.typography.bodyLarge)
            }

            if (confirmingDelete) {
                Text(
                    text = "Delete this pin? This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.deletePin(pin.id)
                            onBack()
                            onConfirm("Pin deleted")
                        },
                        modifier = Modifier
                            .heightIn(min = AnswerStyle.buttonHeight)
                            .tactile(haptics = true, depth = AnswerStyle.keyDepth),
                    ) {
                        Text("Delete", style = MaterialTheme.typography.bodyLarge)
                    }
                    TextButton(onClick = { confirmingDelete = false }, modifier = Modifier.tactile()) {
                        Text("Cancel")
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { confirmingDelete = true },
                    modifier = Modifier
                        .heightIn(min = AnswerStyle.buttonHeight)
                        .tactile(depth = AnswerStyle.keyDepth),
                ) {
                    Text("Delete pin", style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
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
    onOpenResult: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // The field is the composer now, so the panel is only the outcome: the
        // wait, the empty answer, and the matches themselves. A refusal is a
        // message, and the notice channel owns those, so there is no error line
        // here to duplicate it.
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
            results.isEmpty() && query.isNotBlank() && !vm.searchFailed ->
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
            .heightIn(min = 48.dp)
            .clickable(enabled = openable, role = Role.Button, onClick = onOpen)
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
            .clickable(enabled = openable, role = Role.Button, onClick = onToggle)
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
 * The copy glyph, built here rather than pulled from material-icons-extended,
 * which is a large dependency for one icon. material-icons-core has Share but no
 * copy, so this is two rounded rectangles, a back page behind a front page, the
 * two shapes the standard copy mark is made from.
 */
private val CopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Copy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                // The top and left of the back page, left open where the front
                // page covers it, then the front page as an outlined rectangle.
                "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1z" +
                    "M19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5z" +
                    "M19,21H8V7h11V21z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

/**
 * The pin glyph, built here for the same reason as the copy glyph: the icon set
 * on the classpath has no pin and the extended set is too large a dependency. It
 * is a thumbtack, a head over a narrowed neck and a point.
 */
private val PinIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Pin",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M16,9V4l1,0c0.55,0 1,-0.45 1,-1v0c0,-0.55 -0.45,-1 -1,-1H7" +
                    "C6.45,2 6,2.45 6,3v0c0,0.55 0.45,1 1,1l1,0v5c0,1.66 -1.34,3 -3,3v2h5.97v7" +
                    "l1,1l1,-1v-7H19v-2c-1.66,0 -3,-1.34 -3,-3z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

/**
 * Copy, share and pin, under one answer. Copy puts the answer's text on the
 * clipboard; share hands it to the system chooser as plain text; pin saves the
 * prompt and the answer together, with a title the user can change in the Pins
 * view. All three ring the confirm haptic and raise the chat screen's notice, so
 * a tap always says it did something. The row belongs to a plain answer only,
 * never to a question trace or to the working indicator.
 *
 * The pin drawn filled means this prompt and answer are already saved, and a
 * second tap says so rather than making a duplicate; removing a pin is done in
 * the Pins view, where the whole pin is on screen.
 */
@Composable
private fun AnswerActions(
    text: String,
    pinned: Boolean,
    onPin: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val view = LocalView.current
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                Haptics.confirm(view)
                onConfirm("Answer copied")
            },
        ) {
            Icon(
                imageVector = CopyIcon,
                contentDescription = "Copy answer",
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, "Share answer"))
                Haptics.confirm(view)
                onConfirm("Answer ready to share")
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = "Share answer",
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onPin) {
            Icon(
                imageVector = PinIcon,
                contentDescription = if (pinned) "Pinned" else "Pin answer",
                tint = if (pinned) MaterialTheme.colorScheme.primary else tint,
                modifier = Modifier.size(18.dp),
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
private fun TurnBody(
    vm: ChatViewModel,
    turn: ChatTurn,
    liveText: String?,
    working: Boolean,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                // The actions sit just under their own answer, closer to it than
                // the gap between two answers, so the pair reads as one block.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AnswerView(
                        text = answer.text,
                        modifier = Modifier.widthIn(max = AnswerStyle.measure),
                    )
                    AnswerActions(
                        text = answer.text,
                        pinned = vm.isPinned(turn.prompt, answer.text),
                        onPin = {
                            val added = vm.pin(turn.prompt, answer.text)
                            onConfirm(if (added) "Prompt pinned" else "Already pinned")
                        },
                        onConfirm = onConfirm,
                    )
                }
            }
        }
        // The answer of the run that is still going. It is drawn like a finished
        // answer and replaced by the committed one when the run settles, which
        // clears it in the same step that adds the answer.
        if (!liveText.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AnswerView(
                    text = liveText,
                    // The answer is still arriving, so it is marked a live region
                    // while the run goes: TalkBack reads each update instead of
                    // waiting for the person to move focus onto it.
                    modifier = Modifier
                        .widthIn(max = AnswerStyle.measure)
                        .then(
                            if (working) {
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            } else {
                                Modifier
                            },
                        ),
                )
                AnswerActions(
                    text = liveText,
                    pinned = vm.isPinned(turn.prompt, liveText),
                    onPin = {
                        val added = vm.pin(turn.prompt, liveText)
                        onConfirm(if (added) "Prompt pinned" else "Already pinned")
                    },
                    onConfirm = onConfirm,
                )
            }
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
private fun NoticeBar(
    text: String,
    onDismiss: () -> Unit,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    // The text colour is passed with the fill rather than left to the container,
    // so it can never fall back to a default that does not match this theme.
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = container,
        contentColor = onContainer,
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
                color = onContainer,
            )
            // The action and the dismiss are two controls, not one: a dismiss
            // must never fire the action. Sharing the button is how the update
            // banner used to start an install on a tap meant to close it.
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.tactile()) { Text(actionLabel) }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.tactile()) { Text("Dismiss") }
        }
    }
}

/**
 * The primary clip as plain text, or null. The clip may be absent, may hold a
 * non-text item, or may hold styled text, so the first item's text is taken and
 * stringified and a clip without text is dropped. primaryClip is only readable
 * while the app is in front, which is exactly when the user sees the Copy action
 * and taps it.
 */
private fun readClipText(manager: ClipboardManager?): String? {
    val clip = manager?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).text?.toString()
}

/**
 * The commands a prompt may dispatch, above the composer. It is shown filtered
 * by whatever follows a leading slash, and whole when the affordance opened it.
 * Each row names the command, says what it does and marks where it comes from,
 * so a skill reads as a skill rather than as another extension. A tap only
 * writes the command into the draft; the row never sends on its own.
 */
@Composable
private fun CommandPalette(
    commands: List<SessionCommand>,
    query: String?,
    note: String?,
    onPick: (SessionCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val needle = query?.trim()?.lowercase()
    val shown = if (needle.isNullOrEmpty()) commands else commands.filter { it.name.lowercase().contains(needle) }
    Column(
        modifier = modifier
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        if (shown.isEmpty()) {
            // The note covers an older bridge that has no command list at all;
            // an empty list that is merely not loaded yet stays quiet.
            Text(
                text = note ?: if (commands.isEmpty()) "no commands to offer" else "no matching command",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            )
            return@Column
        }
        for (command in shown) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(AnswerStyle.promptRadius))
                    .clickable(role = Role.Button) { onPick(command) }
                    .tactile()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "/${command.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (command.description.isNotBlank()) {
                        Text(
                            text = command.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (command.source.isNotBlank()) {
                    Text(
                        text = command.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    // What the field is for right now. The caller resolves it, so a search says
    // it is a search and a prompt says what a prompt says.
    placeholder: String,
    partialText: String,
    listening: Boolean,
    busy: Boolean,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    onToggleCommands: () -> Unit,
    commandsEnabled: Boolean,
    onToggleMic: () -> Unit,
    onClear: () -> Unit,
    onStop: () -> Unit,
    captureEnabled: Boolean,
    onToggleCapture: () -> Unit,
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
        // The command affordance, small and at the field's leading edge. It
        // opens the palette for a prompt; in search mode it is absent, since
        // there is no prompt to complete.
        val commandAffordance: (@Composable () -> Unit)? = if (commandsEnabled) {
            {
                IconButton(onClick = onToggleCommands) {
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else {
            null
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 6,
            leadingIcon = commandAffordance,
            placeholder = { Text(placeholder) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        val buttonModifier = Modifier.heightIn(min = AnswerStyle.buttonHeight)
        val label = MaterialTheme.typography.bodyLarge
        // Two rows, and the split is the point: the action and the escape are the
        // two controls a run needs within reach, so neither of them sits in a row
        // that scrolls. The action leads, so Stop appearing beside it cannot move
        // it sideways.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // One button, two jobs: it reads what it will do. Sending is the same
            // as before, while searching runs the search on this same text, so a
            // search can never leave as a prompt.
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = buttonModifier.tactile(haptics = true, enabled = actionEnabled, depth = AnswerStyle.keyDepth),
            ) {
                Text(actionLabel, style = label)
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
        Spacer(modifier = Modifier.height(8.dp))
        // The rest of the composer's controls, in a row that scrolls so a narrow
        // screen can still reach them without crowding the action above.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            // The clipboard capture toggle. It shows its own state: filled and
            // reading "Auto-paste on" when a copy will be collected, outlined and
            // reading "Auto-paste" when it will not. It sits in the composer row
            // because that is where the captured text arrives.
            if (captureEnabled) {
                FilledTonalButton(
                    onClick = onToggleCapture,
                    modifier = buttonModifier.tactile(haptics = true, depth = AnswerStyle.keyDepth),
                ) {
                    Text("Auto-paste on", style = label)
                }
            } else {
                OutlinedButton(
                    onClick = onToggleCapture,
                    modifier = buttonModifier.tactile(depth = AnswerStyle.keyDepth),
                ) {
                    Text("Auto-paste", style = label)
                }
            }
            FilledTonalButton(
                onClick = onToggleMic,
                modifier = buttonModifier.tactile(haptics = true, depth = AnswerStyle.keyDepth),
            ) {
                Text(if (listening) "Mic on" else "Mic", style = label)
            }
            OutlinedButton(
                onClick = onClear,
                enabled = text.isNotBlank(),
                modifier = buttonModifier.tactile(enabled = text.isNotBlank(), depth = AnswerStyle.keyDepth),
            ) {
                Text("Clear", style = label)
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
    theme: String,
    onThemeChange: (String) -> Unit,
    updateStatus: UpdateStatus,
    onCheckUpdates: () -> Unit,
    onInstallUpdate: (UpdateChecker.Info) -> Unit,
    onPairLink: (String) -> Unit,
    onSave: (String, String) -> Unit,
    onTest: () -> Unit,
    lastCrash: String?,
    quarantined: Boolean,
    onClearCrashState: () -> Unit,
    onBack: () -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(initialBaseUrl) }
    var token by rememberSaveable { mutableStateOf(initialToken) }

    // One section is open at a time, as in the media app, so the list stays a set
    // of statements about the current setup rather than a wall of controls.
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    val toggle: (String) -> Unit = { name -> openSection = if (openSection == name) null else name }

    // Clearing the stored data takes the token with it, so it asks twice rather
    // than doing it under a thumb that was reaching for something else.
    var confirmingClear by rememberSaveable { mutableStateOf(false) }

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
        // Transparent for the same reason as the chat screen: the sky is drawn
        // once, behind everything, and this must not paint over it.
        containerColor = Color.Transparent,
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
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(
                title = "Pairing",
                subtitle = pairingStatus.ifBlank {
                    if (initialBaseUrl.isNotBlank()) "Paired to a bridge" else "Not paired yet"
                },
                icon = Icons.Filled.AccountBox,
                expanded = openSection == "pairing",
                onToggle = { toggle("pairing") },
            ) {
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
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = AnswerStyle.buttonHeight)
                        .tactile(haptics = true, depth = AnswerStyle.keyDepth),
                ) {
                    Text("Scan the pairing QR", style = MaterialTheme.typography.bodyLarge)
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
                Button(
                    onClick = {
                        onPairLink(pastedLink)
                        pastedLink = ""
                    },
                    enabled = pastedLink.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = AnswerStyle.buttonHeight)
                        .tactile(haptics = true, enabled = pastedLink.isNotBlank(), depth = AnswerStyle.keyDepth),
                ) {
                    Text("Pair with this link", style = MaterialTheme.typography.bodyLarge)
                }
                if (pairingStatus.isNotBlank()) {
                    Text(pairingStatus, style = MaterialTheme.typography.bodySmall)
                }
            }

            SettingsSection(
                title = "Connection",
                subtitle = baseUrl.ifBlank { "No bridge address yet" },
                icon = Icons.Filled.Lock,
                expanded = openSection == "connection",
                onToggle = { toggle("connection") },
            ) {
                SectionLabel("Bridge URL")
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
                SectionLabel("Token")
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
                }
                if (statusLine.isNotBlank()) {
                    Text(statusLine, style = MaterialTheme.typography.bodyMedium)
                }
                if (sessionLabel.isNotBlank()) {
                    Text(
                        text = "session: $sessionLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsSection(
                title = "Appearance",
                subtitle = "${appearanceLabel(appearance)} · ${AnswerStyle.theme(theme).label}",
                icon = Icons.Filled.Star,
                expanded = openSection == "appearance",
                onToggle = { toggle("appearance") },
            ) {
                SectionLabel("Appearance")
                val appearances = listOf("dark" to "Dark", "light" to "Light", "system" to "System")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    appearances.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = appearance == value,
                            onClick = { onAppearanceChange(value) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = appearances.size),
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.semantics {
                                stateDescription = if (appearance == value) "Selected" else "Not selected"
                            },
                        )
                    }
                }
                SectionLabel("Theme")
                Text(
                    text = "The theme repaints the dark side of the app. Light mode always keeps " +
                        "its own palette.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val themes = AnswerStyle.themes.entries.toList()
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    themes.forEachIndexed { index, (name, palette) ->
                        SegmentedButton(
                            selected = theme == name,
                            onClick = { onThemeChange(name) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = themes.size),
                            icon = { ThemeSwatch(palette) },
                            label = { Text(palette.label, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.semantics {
                                stateDescription = if (theme == name) "Selected" else "Not selected"
                            },
                        )
                    }
                }
            }

            SettingsSection(
                title = "App Updates",
                subtitle = updateSubtitle(updateStatus),
                icon = Icons.Filled.Refresh,
                expanded = openSection == "updates",
                onToggle = { toggle("updates") },
            ) {
                Text(
                    text = "Install the newest Android build from this app's own releases. The APK " +
                        "is checked against the manifest hash and this app's signing key before the " +
                        "installer is offered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val status = updateStatus) {
                    UpdateStatus.Checking -> StatusCard {
                        Text(
                            text = "Checking the latest Android build…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateStatus.Failed -> StatusCard(border = MaterialTheme.colorScheme.error) {
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is UpdateStatus.UpToDate -> VersionCard(
                        versionName = status.versionName,
                        versionCode = null,
                        sizeBytes = null,
                        available = false,
                        onInstall = null,
                    )
                    is UpdateStatus.Available -> VersionCard(
                        versionName = status.info.versionName,
                        versionCode = status.info.versionCode,
                        sizeBytes = status.info.sizeBytes,
                        available = true,
                        onInstall = { onInstallUpdate(status.info) },
                    )
                }
                OutlinedButton(
                    onClick = onCheckUpdates,
                    enabled = updateStatus != UpdateStatus.Checking,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = AnswerStyle.buttonHeight)
                        .tactile(
                            haptics = true,
                            enabled = updateStatus != UpdateStatus.Checking,
                            depth = AnswerStyle.keyDepth,
                        ),
                ) {
                    Text(
                        text = if (updateStatus == UpdateStatus.Checking) "Checking…" else "Check for updates",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            SettingsSection(
                title = "Troubleshooting",
                subtitle = when {
                    lastCrash != null -> "The last launch crashed"
                    quarantined -> "Data was set aside to get the app open"
                    else -> "No crashes recorded"
                },
                icon = Icons.Filled.Build,
                expanded = openSection == "troubleshooting",
                onToggle = { toggle("troubleshooting") },
            ) {
                Text(
                    text = "The app records the trace of a crash and whether the launch that " +
                        "died had come up yet. A launch that died while starting leaves the saved " +
                        "transcript and the pins unread and moves them aside, so state left behind " +
                        "by one bad start cannot stop the app opening again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (quarantined) {
                    Text(
                        text = "A transcript or a pin list is still set aside. Clearing below " +
                            "deletes it, and the pins with it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                lastCrash?.let { trace ->
                    SectionLabel("Last crash")
                    StatusCard {
                        Text(
                            text = trace,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = "The text selects, so it can be copied out of here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (confirmingClear) {
                    Text(
                        text = "This forgets the bridge token, the session it was attached to, " +
                            "the pins and the saved transcript, and deletes the trace and " +
                            "anything set aside. The laptop is not touched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val label = MaterialTheme.typography.bodyLarge
                        val size = Modifier.heightIn(min = AnswerStyle.buttonHeight)
                        Button(
                            onClick = {
                                // The fields above hold their own copy of what is
                                // being forgotten, so they are emptied with it.
                                baseUrl = ""
                                token = ""
                                confirmingClear = false
                                onClearCrashState()
                            },
                            modifier = size.tactile(haptics = true, depth = AnswerStyle.keyDepth),
                        ) { Text("Clear it", style = label) }
                        OutlinedButton(
                            onClick = { confirmingClear = false },
                            modifier = size.tactile(depth = AnswerStyle.keyDepth),
                        ) { Text("Cancel", style = label) }
                    }
                } else {
                    OutlinedButton(
                        onClick = { confirmingClear = true },
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = AnswerStyle.buttonHeight)
                            .tactile(haptics = true, depth = AnswerStyle.keyDepth),
                    ) {
                        Text(
                            text = "Clear the saved data and the trace",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = versionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * What the App Updates section states. One check feeds it, so the chat banner
 * and the settings section never disagree about whether a newer build exists.
 */
sealed interface UpdateStatus {
    /** A check is in flight. */
    data object Checking : UpdateStatus

    /** The installed build is the newest one; it carries the name to show. */
    data class UpToDate(val versionName: String) : UpdateStatus

    /** A newer, verified build is ready to install. */
    data class Available(val info: UpdateChecker.Info) : UpdateStatus

    /** The check or the install failed; the message is shown unchanged. */
    data class Failed(val message: String) : UpdateStatus
}

/** The App Updates subtitle: it always states the state, as the media app does. */
private fun updateSubtitle(status: UpdateStatus): String = when (status) {
    UpdateStatus.Checking -> "Checking the latest build"
    is UpdateStatus.UpToDate -> "Up to date · ${status.versionName}"
    is UpdateStatus.Available -> "Update available · ${status.info.versionName}"
    is UpdateStatus.Failed -> status.message
}

/** The stored appearance value as the word the Appearance subtitle shows. */
private fun appearanceLabel(value: String): String = when (value) {
    "dark" -> "Dark"
    "light" -> "Light"
    else -> "System"
}

/** A field's name above its control. */
@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

/**
 * One collapsible section: a trigger row that always states the current value,
 * and a bordered panel that holds the controls. It mirrors the media app's
 * divide-y list, so the two apps read the same way.
 */
@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .tactile()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The icon sits in a rounded square tinted with the accent, as in the
            // media app, so a section is recognisable before its title is read.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A down chevron that swings to point right when the section is shut,
            // the same signal as the media app's rotating chevron.
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 0f else -90f,
                label = "settingsChevron",
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation),
            )
        }
        if (expanded) {
            // The panel is the media app's settings-panel-body: a bordered rounded
            // box a step off the sky, so it reads as a card rather than a page.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A theme's own colours in miniature: its accent over its own background. */
@Composable
private fun ThemeSwatch(palette: AnswerTheme) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(palette.scheme.background)
            .border(1.dp, palette.scheme.outline, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(palette.scheme.primary),
        )
    }
}

/** The bordered card a status line sits in when there is no version to show. */
@Composable
private fun StatusCard(
    border: Color = MaterialTheme.colorScheme.outline,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) { content() }
    }
}

/**
 * The version card in the expanded updates section: the build's name, a
 * build-type pill and either an amber "Update available" or green "Up to date"
 * pill. The install action appears only when there is something to install.
 */
@Composable
private fun VersionCard(
    versionName: String,
    versionCode: Int?,
    sizeBytes: Long?,
    available: Boolean,
    onInstall: (() -> Unit)?,
) {
    // The amber and green are deliberately not theme colours: they mean the same
    // thing under every palette, and the media app uses the same pair. On the
    // 16% tint the fill already reads in the dark schemes, but the light tint
    // needs a darker shade of the same hue for the label to clear AA.
    val amber = Color(0xFFF0A83C)
    val green = Color(0xFF46C97E)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Android build $versionName",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (versionCode != null) {
                        Text(
                            text = "Version code $versionCode",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (sizeBytes != null && sizeBytes > 0) {
                        Text(
                            text = "${sizeBytes / (1024 * 1024)} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The publish is always a release build, so the pill names that
                    // rather than inventing a type the manifest does not carry.
                    Pill(text = "release", color = MaterialTheme.colorScheme.primary)
                    Pill(
                        text = if (available) "Update available" else "Up to date",
                        color = if (available) amber else green,
                        textColor = if (available) {
                            if (dark) amber else Color(0xFF6E4400)
                        } else {
                            if (dark) green else Color(0xFF0F6234)
                        },
                    )
                }
            }
            if (available && onInstall != null) {
                Button(
                    onClick = onInstall,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = AnswerStyle.buttonHeight)
                        .tactile(haptics = true, depth = AnswerStyle.keyDepth),
                ) {
                    Text("Install update", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** A rounded status pill, e.g. "Update available". */
@Composable
private fun Pill(text: String, color: Color, textColor: Color = color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
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
