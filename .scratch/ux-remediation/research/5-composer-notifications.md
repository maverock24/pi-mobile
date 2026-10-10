# Research: the composer and background behaviour (questions 28 to 32 and 33 to 38)

Line numbers are against the working tree at `HEAD` (`e976063`). Every claim carries a
`file:line` and a quote.

## 28. Every control in the composer, in render order

The `Composer` composable is declared at `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt:1697`
and called at `Screens.kt:533`. Its root is `Column(modifier = Modifier.fillMaxWidth().padding(12.dp))`
(`Screens.kt:1717`). Render order top to bottom:

1. **Dictation partial text** (not an interactive control). Conditional on
   `if (listening && partialText.isNotBlank())` (`Screens.kt:1718`). Plain `Text` with
   `style = MaterialTheme.typography.bodySmall` and `color = ...onSurfaceVariant`
   (`Screens.kt:1719-1725`). No enablement, no action.

2. **`/` command affordance**, passed as the text field's `leadingIcon`. It exists only when
   `commandsEnabled` is true: `val commandAffordance: (@Composable () -> Unit)? = if (commandsEnabled)`
   (`Screens.kt:1729`), else `null` (`Screens.kt:1737-1739`). It is a bare
   `IconButton(onClick = onToggleCommands)` (`Screens.kt:1731`) wrapping
   `Text(text = "/", style = ...titleMedium, color = ...primary)` (`Screens.kt:1732-1736`).
   Always enabled (no `enabled` argument). See question 31 for semantics.

3. **`OutlinedTextField`** (`Screens.kt:1742-1748`): `value = text`, `onValueChange = onTextChange`,
   `Modifier.fillMaxWidth()`, `minLines = 1`, `maxLines = 6`, `leadingIcon = commandAffordance`,
   `placeholder = { Text(placeholder) }`. Always present, always enabled.

4. **Action `Row`** (`Screens.kt:1752-1756`), always present. Inside it, in order:
   a. **Capture toggle**, always present as one of two variants. `if (captureEnabled)`
      (`Screens.kt:1763`) composes `FilledTonalButton` with label `"Auto-paste on"`
      (`Screens.kt:1764-1770`); the `else` (`Screens.kt:1770`) composes `OutlinedButton` with label
      `"Auto-paste"` (`Screens.kt:1771-1776`). Both call `onToggleCapture`; neither sets `enabled`, so
      both are always enabled. Only the filled variant passes `haptics = true`
      (`Screens.kt:1767`).
   b. **Microphone** `FilledTonalButton` (`Screens.kt:1778-1783`), label
      `Text(if (listening) "Mic on" else "Mic", style = label)` (`Screens.kt:1782`). Always present,
      always enabled, `haptics = true` (`Screens.kt:1780`).
   c. **Primary action** `Button` (`Screens.kt:1787-1793`): `onClick = onAction`,
      `enabled = actionEnabled` (`Screens.kt:1789`), label `Text(actionLabel, ...)` (`Screens.kt:1792`).
   d. **Clear** `OutlinedButton` (`Screens.kt:1794-1800`): `onClick = onClear`,
      `enabled = text.isNotBlank()` (`Screens.kt:1796`), label `"Clear"` (`Screens.kt:1799`).
   e. **Stop** `OutlinedButton`, conditional on `if (busy)` (`Screens.kt:1801`): `onClick = onStop`,
      no `enabled` argument so it is always enabled (`Screens.kt:1802-1807`), label `"Stop"`
      (`Screens.kt:1806`).

Conditional controls: the `/` affordance (`commandsEnabled`), the capture toggle's variant
(`captureEnabled`), and Stop (`busy`). The Mic, action and Clear buttons are unconditional.

## 29. Which parameter decides each control, and where ChatScreen computes it

All arguments are computed in the `ChatScreen` call site at `Screens.kt:533-585` (composer declared
`Screens.kt:1697-1718`).

- `text`: `if (searchOpen) query else vm.draft` (`Screens.kt:534`).
- `placeholder`: search mode passes `"Search every prompt and answer…"` (`Screens.kt:537`);
  otherwise `vm.composerBlock ?: "Prompt pi…"` (`Screens.kt:539`).
- `partialText` and `listening`: parameters of `ChatScreen` (`Screens.kt:245-246`). MainActivity
  holds them as `var listening by remember { mutableStateOf(false) }` and
  `var partialText by remember { mutableStateOf("") }` (`MainActivity.kt:61-62`), fed by the
  `Dictation` callbacks `onPartial`, `onFinal`, `onListeningChanged` (`MainActivity.kt:107-113`).
- `busy = vm.busy` (`Screens.kt:542`). `vm.busy` is declared
  `var busy by mutableStateOf(false)` (`ChatViewModel.kt:275`). It is set true on send
  (`ChatViewModel.kt:1191`) and on `"prompt_accepted", "agent_start", "turn_start"`
  (`ChatViewModel.kt:1371`), and false on `"agent_settled"` (`ChatViewModel.kt:1373`), on stream close
  (`ChatViewModel.kt:564`), on `disconnect()` (`ChatViewModel.kt:617`) and when a prompt fails
  (`ChatViewModel.kt:1196`).
- `actionLabel = if (searchOpen) "Search" else "Send"` (`Screens.kt:544`).
- `actionEnabled`: `if (searchOpen) query.isNotBlank()` else
  `vm.draft.isNotBlank() && vm.composerBlock == null` (`Screens.kt:547-551`).
- `commandsEnabled = !searchOpen` (`Screens.kt:569`). Palette visibility, a separate value, is
  `val paletteVisible = !searchOpen && ((slashQuery != null && !paletteDismissed) || paletteOpen)`
  (`Screens.kt:562`).
- `captureEnabled = vm.clipboardCapture` (`Screens.kt:580`). Declared
  `var clipboardCapture by mutableStateOf(store.clipboardCapture)` (`ChatViewModel.kt:326`).

`vm.composerBlock` is a computed property: `get() = if (connected) null else "Not connected"`
(`ChatViewModel.kt:1280-1281`), documented as "Why the composer is off, or null when a prompt can be
sent" (`ChatViewModel.kt:1275-1279`). So Send is disabled in exactly two facts: in search mode when
`query` is blank, and in prompt mode when the draft is blank or the stream is not connected
(`connected == false`). `busy` does not appear in `actionEnabled`, so Send stays enabled while a run
is in flight if the draft is non-blank and connected.

## 30. What the row does when the control widths exceed the screen

The row is `Row(verticalAlignment = ..., horizontalArrangement = Arrangement.spacedBy(8.dp),
modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))` (`Screens.kt:1752-1756`).
So it is a single non-wrapping row, 8.dp between children, that scrolls horizontally rather than
compressing or wrapping. `answerStyle.buttonHeight` is `56.dp` (`ui/AnswerStyle.kt:69`) and is applied
as `Modifier.heightIn(min = AnswerStyle.buttonHeight)` (`Screens.kt:1757`), so it is a minimum height,
not a width limit. The current order is capture, Mic, action, Clear, Stop (`Screens.kt:1763`, `1778`,
`1787`, `1794`, `1801`). With five buttons and four 8.dp gaps the row can exceed a narrow viewport;
because Clear and Stop are last in order, they are the ones pushed off the initial viewport, and Stop
only exists while `busy`. The exact subset that is off-viewport is a function of the text widths and
the device width and is `needs-device`; it is not derivable from the declared `sp`/`dp` values alone.

## 31. The `/` affordance

- Condition: present iff `commandsEnabled`, i.e. `!searchOpen` (`Screens.kt:1729`).
- Label: `Text(text = "/", style = MaterialTheme.typography.titleMedium, color = ...primary)`
  (`Screens.kt:1732-1736`), inside `IconButton(onClick = onToggleCommands)` (`Screens.kt:1731`).
- Semantics / `contentDescription`: none. The `IconButton` has only the `onClick` argument; there is
  no `Modifier.semantics { contentDescription = ... }` and no `contentDescription` parameter anywhere
  on it (`Screens.kt:1731-1737`).
- `tactile()`: not called. The only modifier on the `IconButton` is the `onClick` argument
  (`Screens.kt:1731`); `tactile` appears on the row buttons (`Screens.kt:1767`, `1780`, `1790`,
  1797, 1804) but not here. The press-scale behaviour lives in `tactile(pressScale: Float = 0.97f, ...)`
  (`ui/Tactile.kt:108-115`), so this control has no press scale.

## 32. `actionLabel`, `actionEnabled`, and the disabled-Send set

Both are computed in the `Composer(...)` call (`Screens.kt:544-551`). `actionLabel` is
`if (searchOpen) "Search" else "Send"` (`Screens.kt:544`). `actionEnabled` is
`if (searchOpen) { query.isNotBlank() } else { vm.draft.isNotBlank() && vm.composerBlock == null }`
(`Screens.kt:547-551`). The search-mode placeholder is `"Search every prompt and answer…"`
(`Screens.kt:537`); prompt-mode placeholder is `vm.composerBlock ?: "Prompt pi…"` (`Screens.kt:539`).
The full set of disabled-Send conditions:

1. search mode (`searchOpen == true`) and `query.isBlank()`; or
2. prompt mode and `vm.draft.isBlank()`; or
3. prompt mode and `vm.composerBlock != null`, which happens exactly when `connected == false`
   (`ChatViewModel.kt:1281`).

No other condition enters `enabled` on the action button (`Screens.kt:1789`).

## 33. Notifications, services, workers, alarms, wake locks today

`app/src/main` contains none. The command run was:

```
grep -rnE "Notification|WorkManager|WakeLock|AlarmManager|JobScheduler|ForegroundService|startForeground|WorkRequest|JobIntentService" app/src/main/
```

It exited 1 with no output. The command

```
grep -rnE "<service|POST_NOTIFICATIONS" app/src/main/
```

also exited 1 with no output. A per-term count over `app/src/main` gives `Notification: 0`,
`Worker: 0`, `WorkManager: 0`, `WakeLock: 0`, `AlarmManager: 0`, `JobScheduler: 0`,
`ForegroundService: 0`, `startForeground: 0`; the two hits for `Service` are
`context.getSystemService(...)` calls (`net/Diagnostics.kt:22-23`, `ui/Screens.kt:293`) and one
comment (`ui/ChatViewModel.kt:835`), none of them an Android `Service`. `app/build.gradle.kts` has no
work, notification or foreground-service dependency (grep for `work|notification|lifecycle-process|foreground`
exited 1). `docs/PRODUCTION-READINESS.md` confirms the same from the audit side:
"No notification code anywhere: no `POST_NOTIFICATIONS`, no service, no `WorkManager`, no wake lock."

The manifest (`app/src/main/AndroidManifest.xml`) declares 5 permissions and 1 feature:
`INTERNET` (`:4`), `ACCESS_NETWORK_STATE` (`:5`), `RECORD_AUDIO` (`:6`),
`REQUEST_INSTALL_PACKAGES` (`:7`), `CAMERA` (`:10`), and
`<uses-feature android:name="android.hardware.camera" android:required="false" />` (`:11`). It declares
one `<application android:name=".PiRemoteApp" ...>` (`:13`), one `<activity android:name=".MainActivity" ...>`
(`:21`), and one `<provider android:name="androidx.core.content.FileProvider" ...>` (`:40`). No
`<service>`, no `POST_NOTIFICATIONS`, no `android:icon`.

## 34. What the bridge does when the SSE client disconnects

Handler: `if (req.method === "GET" && url.pathname === "/api/events")` (`bridge/extensions/pi-remote.ts:1007`).

- Response headers set `"content-type": "text/event-stream; charset=utf-8"`, `"cache-control":
  "no-store"`, `connection: "keep-alive"` (`pi-remote.ts:1008-1010`).
- Retry directive: `res.write(`retry: 3000\n\n`)` (`pi-remote.ts:1012`). No other retry logic.
- Client registration: `clients.add(res)` (`pi-remote.ts:1013`), then two immediate frames, `type:
  "state"` (`pi-remote.ts:1016-1018`) and `type: "question"` (`pi-remote.ts:1019-1021`).
- Disconnect: `req.on("close", () => { clients.delete(res); ... })` (`pi-remote.ts:1022-1026`).
- Replay buffer: none. `emit` returns before doing anything when no client is attached:
  `if (clients.size === 0) { return; }` (`pi-remote.ts:486-488`). Nothing accumulates frames for a
  later reconnect.
- `Last-Event-ID`: not read anywhere. A grep for `last-event-id|lastEventId` over the bridge matches
  nothing; the handler never inspects the request headers for it.
- Sequence: every frame carries `seq: ++seq` (`pi-remote.ts:489`, `1017`, `1020`) but that value is
  only written into the frame; it is never used to resume.
- The bridge's own header says nothing about reconnects. Lines 1 to 55 of `pi-remote.ts` describe the
  endpoints, ownership and config; a grep for `reconnect|replay|seq|resume` over that header returns
  nothing. There is no bridge-local README: `bridge/` holds only `extensions/pi-remote.ts`,
  `lib/lease.ts`, `lib/remote-question.ts` and `install.sh`. The only README in the repo is the root
  `README.md`, and it has no reconnect or replay section (grep for
  `reconnect|replay|seq|retry|resume` finds no line about SSE resumption).

## 35. Event sequence numbering, and whether the app reads it

Bridge: `let seq = 0;` (`bridge/extensions/pi-remote.ts:355`). The counter is incremented and attached
to the frame in `emit`: `const frame = `data: ${JSON.stringify({ seq: ++seq, at: nowIso(), type, data:
shrink(data) })}\n\n`;` (`pi-remote.ts:489`), and in the two frames written when a client connects
(`pi-remote.ts:1017`, `1020`). `seq` is used only to populate that field.

App: it does not read the bridge's `seq`. A grep for `seq` over
`app/src/main/java/com/maverock24/pimobile/` matches only the local `searchSeq` counter:
`val seq = ++searchSeq` (`ChatViewModel.kt:972`) with the guards `if (seq != searchSeq)` at
`ChatViewModel.kt:984` and `:1012` and `searchSeq++` at `:1027`; declared `private var searchSeq = 0L`
(`ChatViewModel.kt:358`). The separate `outcomeSeq` (`private var outcomeSeq = 0L`,
`ChatViewModel.kt:352`) is incremented only to tag `RequestOutcome` values
(`ChatViewModel.kt:1194`, `:1207`, `:1325`, `:1336`). Neither counter has any relation to the bridge's
event `seq`; nothing in the app stores a last-event cursor.

## 36. Bridge event types, which the app consumes, and background reconnect

Emitter sites (`bridge/extensions/pi-remote.ts`): `prompt_accepted` (`:1061`), `question_answered`
(`:1142`), `aborted` (`:1168`), `question` (`:1442`), `session_info_changed` (`:1632`), `agent_start`
(`:1636`), `agent_end` (`:1641`), `agent_settled` (`:1645`), `turn_start` (`:1650`), `turn_end`
(`:1654`), `message_start` (`:1658`), `message_update` (`:1666`), `message_end` (`:1670`),
`tool_execution_start` (`:1674`), `tool_execution_end` (`:1678`), `model_select` (`:1687`). The
connect-time frames add `state` and `question` (`:1016-1021`).

The app's dispatch is `when (type)` in `handleEvent` (`ChatViewModel.kt:1364-1372`). It handles:
`question` (`:1368`), `state` (`:1369`), `prompt_accepted`, `agent_start`, `turn_start` (`:1371`),
`agent_settled` (`:1372`), `agent_end` (`:1377`), `message_start` (`:1386`), `message_update`
(`:1423`), `message_end` (`:1425`), `tool_execution_start`, `tool_execution_end` (explicit no-op,
`:1439-1441`), and `model_select` (`:1442`). It does not handle `session_info_changed`, `turn_end`,
`aborted`, or `question_answered`.

- "A question needs an answer": the `question` event (`pi-remote.ts:1442`), also sent on connect
  (`pi-remote.ts:1019-1021`). The app reacts: `"question" -> applyQuestion(data.optJSONObject("pending"))`
  (`ChatViewModel.kt:1368`).
- "A run finished": `agent_settled` (`pi-remote.ts:1645`), which the app uses to clear busy and
  commit the answer (`ChatViewModel.kt:1372-1376`); `agent_end` (`pi-remote.ts:1641`) only refreshes
  state in the app (`ChatViewModel.kt:1377-1380`).
- "A run failed": there is no dedicated failure event. `agent_end` carries only
  `{ messageCount: event.messages?.length ?? 0 }` (`pi-remote.ts:1641`). `tool_execution_end` carries
  `isError` (`pi-remote.ts:1678-1683`) but the app discards both tool events (`ChatViewModel.kt:1439-1441`).
  `aborted` is emitted (`pi-remote.ts:1168`) and the app never handles it.
- Backgrounded socket: `scheduleReconnect()` is called from `onClosed`
  (`ChatViewModel.kt:574`, definition `:625-640`) with backoff 250/500/1000/3000 ms, and
  `startPolling()` runs a 3 s `client.state()` loop (`ChatViewModel.kt:646-660`). Neither is gated on
  lifecycle state. The only lifecycle hook is `ON_RESUME` calling `vm.ensureConnected()`
  (`MainActivity.kt:154-162`). Nothing in the app keeps the process or socket alive while backgrounded
  (question 33), so whether `onClosed` fires depends on the process still being scheduled.

## 37. What PRODUCTION-READINESS Tier 0.2, 0.3, 0.4 and 3.1 specify

- **0.2** (`docs/PRODUCTION-READINESS.md:43`): "Service with a persistent notification ("pi working ·
  4m"), started on `agent_start`, stopped on `agent_settled`; screen-off run keeps receiving events."
- **0.3** (`docs/PRODUCTION-READINESS.md:44`): "Notification channel plus three triggers: question
  pending, run finished, run failed. Tapping opens the answer or the question card."
- **0.4** (`docs/PRODUCTION-READINESS.md:45`): "Question notification carries up to 3 option buttons
  plus "Open"; result notification carries "Copy answer" and "Reply"; verified from the lock screen."
- **3.1** (`docs/PRODUCTION-READINESS.md:93`): "Private topic the bridge publishes to; gives real
  background notifications without FCM and without a cloud account." (referencing ntfy / UnifiedPush).

Overlap with the background-behaviour work: 0.2 is the foreground service and its persistent
notification; 0.3 the trigger set; 0.4 the notification actions.

## 38. `minSdk = 26`, `targetSdk = 35`, notification permission and channel APIs

Build config: `compileSdk = 35` (`app/build.gradle.kts:20`), `minSdk = 26` (`:24`),
`targetSdk = 35` (`:25`). The app declares no notification permission and no channel; the manifest
has no `POST_NOTIFICATIONS` line (the grep `grep -rnE "<service|POST_NOTIFICATIONS" app/src/main/`
exited 1), and no channel is created anywhere (`Notification: 0` across `app/src/main`).

Required APIs from the platform:

- Runtime permission `POST_NOTIFICATIONS`: "Android 13 (API level 33) and higher supports a runtime
  permission for sending non-exempt (including Foreground Services (FGS)) notifications from an app:
  `POST_NOTIFICATIONS`" (Android Developers, "Notification runtime permission",
  https://developer.android.com/develop/ui/compose/notifications/notification-permission). With
  `targetSdk = 35` the app targets API 33+, so on an Android 13+ device this permission must be
  declared in the manifest and requested at runtime.
- Notification channel: "Starting in Android 8.0 (API level 26), all notifications must be assigned
  to a channel." (Android Developers, "Create and manage notification channels",
  https://developer.android.com/develop/ui/compose/notifications/channels). Since `minSdk = 26` there
  is no device below the channel requirement, so a `NotificationChannel` plus
  `NotificationManager.createNotificationChannel` is required on every supported device.
