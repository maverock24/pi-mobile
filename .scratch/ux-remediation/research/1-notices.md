# Research: messages and notices (questions 1 to 10)

All line numbers are against `e976063`. Only cluster A is covered.

## 1. Every mechanism that puts a message in front of the user

The ticket names two symbols that do not exist as written and one that does:

- `pairingStatus` exists: `Screens.kt:1852` `var pairingStatus by remember { mutableStateOf("") }`.
- `SessionNotice` does not exist anywhere under `app/src/main` (grep for `SessionNotice` returns nothing). The lower-case `ChatViewModel.sessionNotice` (`ChatViewModel.kt:400`) plays that role.
- `bootNotice` exists: `ChatViewModel.kt:236`.

### A. `MainActivity.notice` (`MainActivity.kt:62`)

Local state in `setContent`, declared as `var notice by remember { mutableStateOf<String?>(null) }`.

Write sites (all to the local variable):

- `MainActivity.kt:94` `notice = "Allow installs for Pi Remote, then install again"`
- `MainActivity.kt:96` `notice = UpdateChecker.install(context, file) ?: "Installer launched"` (string composed from `UpdateChecker.install`, else the literal fallback)
- `MainActivity.kt:114` `onError = { notice = it }` inside the `Dictation(...)` construction; `it` is any of Dictation's error strings (`Dictation.kt:49`, `:84`, `:95`, `:122`, `:130`)
- `MainActivity.kt:124` `notice = "Microphone permission is required for dictation"`
- `MainActivity.kt:142` `notice = "That link is not a pairing code"`
- `MainActivity.kt:145` `notice = "Pairing…"`
- `MainActivity.kt:150` `notice = "Paired as ${paired.device}"`
- `MainActivity.kt:153` `notice = "Pairing failed: ${it.message}"`
- `MainActivity.kt:231` `notice = "Could not start dictation: ${it.javaClass.simpleName}"`

Read sites: none. Grep over `MainActivity.kt` returns the declaration, the nine writes above, and one further `notice =` token at `MainActivity.kt:217`, which is the named argument to `ChatScreen(...)` and not a read or write of this variable. Dismissable: no reachable dismiss. Cleared: only by the next write to itself; the value never reaches a composable.

### B. `ChatViewModel.lastError` (`ChatViewModel.kt:299`)

- Writes: `ChatViewModel.kt:749` `lastError = "state: ${Diagnostics.describe(it, store.baseUrl)}"`; `ChatViewModel.kt:758` `lastError = "history: ${Diagnostics.describe(error, store.baseUrl)}"`; `ChatViewModel.kt:845` `lastError = error.message?...?: error.javaClass.simpleName`; `ChatViewModel.kt:1205` `lastError = "prompt: ${Diagnostics.describe(error, store.baseUrl)}"`; `ChatViewModel.kt:1220` `lastError = error.message`; `ChatViewModel.kt:1334` `lastError = "answer: ${...}"`; `ChatViewModel.kt:1359` `lastError = "cancel: ${...}"`.
- Read/render: `Screens.kt:444-446` `vm.lastError?.let { error -> NoticeBar(text = error, onDismiss = vm::dismissError, isError = true) }`.
- Dismissable: yes, the `OK` button calls `dismissError` (`ChatViewModel.kt:1237-1239`).
- Cleared by: `dismissError` (`ChatViewModel.kt:1238`) and `clearCrashState` (`ChatViewModel.kt:1269`); otherwise replaced by the next write.
- Composition: `Diagnostics.describe` (`Diagnostics.kt:101-104`) returns `"failed · $baseUrl · $detail · ${hint(error)}"`, where `detail` is the exception message or its class name and `hint` is the paragraph at `Diagnostics.kt:77-99`. The prefix (`state:`, `history:`, `prompt:`, `answer:`, `cancel:`) is added by each caller.

### C. `ChatViewModel.bootNotice` (`ChatViewModel.kt:236`)

- Write: `ChatViewModel.kt:500-508` inside `recoverFromStartupCrash()`, a `when` with three literal strings; reached from `connect()` (`ChatViewModel.kt:535`) and only when `PiRemoteApp.startupSafeMode` is true (`ChatViewModel.kt:490`).
- Read: `Screens.kt:441-443` `if (bootNotice != null) { NoticeBar(text = bootNotice, onDismiss = onDismissBootNotice) }`.
- Dismissable: yes, `onDismissBootNotice` -> `dismissBootNotice` (`ChatViewModel.kt:1242-1244`).
- Cleared by: `dismissBootNotice` (`ChatViewModel.kt:1243`) and `clearCrashState` (`ChatViewModel.kt:1268`).
- Composition: three fixed sentences naming `Settings > Troubleshooting`; the branch picks by `droppedAttachment`, `movedTranscripts > 0 || movedPins`, else.

### D. `ChatViewModel.searchError` (`ChatViewModel.kt:338`)

Covered in question 7.

### E. `ChatViewModel.outcome` (`ChatViewModel.kt:349`)

- Writes: `ChatViewModel.kt:1194`, `:1207` (send), `:1325`, `:1336` (answer question). Each is `outcome = RequestOutcome(++outcomeSeq, ok = ...)`.
- Read: `Screens.kt:335-339` `val outcome = vm.outcome` ... `if (event.ok) Haptics.confirm(view) else Haptics.reject(view)`. There is no text render site.
- Cleared by: replacement on the next write; no dismiss, no timeout.

### F. `ChatScreen.actionNotice` (`Screens.kt:276`)

Covered in question 6.

### G. `UpdateStatus` (`Screens.kt:2203-2215`)

Covered in question 8.

### H. `ChatViewModel.statusLine` (`ChatViewModel.kt:297`)

Covered in question 10.

### I. `ChatViewModel.sessionNotice` (`ChatViewModel.kt:400`)

Covered in question 9.

### J. `ChatViewModel.commandsNote` (`ChatViewModel.kt:384`)

- Write: `ChatViewModel.kt:1085` `commandsNote = "this bridge has no command list; reload it on the laptop"`.
- Read: `Screens.kt:523` `note = vm.commandsNote` passed into `CommandPalette`, rendered at `Screens.kt:1649-1651` `text = note ?: if (commands.isEmpty()) "no commands to offer" else "no matching command"`.
- Dismissable: no; only visible while the palette is shown and its list is empty.
- Cleared by: `loadCommands` success (`ChatViewModel.kt:1074`) and `forgetSession` (`ChatViewModel.kt:531`).

### K. `ChatViewModel.lastCrash` / `quarantinedData` (`ChatViewModel.kt:240`, `:244`)

- Writes: `ChatViewModel.kt:255` `lastCrash = crashLog.lastCrash()`, `:256` `quarantinedData = pinsStore.hasQuarantined() || transcriptStore.hasQuarantined()`, `:499` same `quarantinedData` expression.
- Read: `MainActivity.kt:207-208` passed to `SettingsScreen`; rendered in the Troubleshooting section at `Screens.kt:2104-2106` (subtitle), `:2116-2121` (quarantined text), `:2129-2145` (the trace in a `StatusCard`).
- Dismissable: only via the section's "Clear the saved data and the trace" button, which calls `onClearCrashState`.
- Cleared by: `clearCrashState` (`ChatViewModel.kt:1266-1267`).

### L. `SettingsScreen.pairingStatus` (`Screens.kt:1852`)

- Writes: `Screens.kt:1856` `pairingStatus = "No QR code was read"`; `Screens.kt:1858` `pairingStatus = "Using the scanned link…"`; `Screens.kt:1868` `pairingStatus = "Camera permission is denied; paste the link below instead"`.
- Read: `Screens.kt:1891-1893` as the Pairing section subtitle, and `Screens.kt:1939-1941` `Text(pairingStatus, style = MaterialTheme.typography.bodySmall)`.
- Dismissable: no. Cleared by the next write; lost when `SettingsScreen` leaves composition.

### M. `ChatViewModel.showingSavedCopy` (`ChatViewModel.kt:266`)

- Writes: `ChatViewModel.kt:468`, `:717`, `:823`, `:1112`, `:1262` set false; `:862` `showingSavedCopy = true`.
- Read/render: `Screens.kt:427-434` `if (!vm.connected && vm.showingSavedCopy) { Text("Showing the transcript saved on this phone", ...) }` (plain `Text`).
- Dismissable: no; cleared when a fetch confirms the transcript (`ChatViewModel.kt:823`) or the session/attachment changes.

### N. `ChatViewModel.composerBlock` (`ChatViewModel.kt:1278`)

- `ChatViewModel.kt:1278` `get() = if (connected) null else "Not connected"`. The string is handed to the composer placeholder at `Screens.kt:531-535` (`placeholder = ... vm.composerBlock ?: "Prompt pi…"`) and consumed at `Screens.kt:1772` `placeholder = { Text(placeholder) }`. No dismiss; derived, recomputed with `connected`.

## 2. Writer with no reader, reader with no writer

Writer with no reader: `MainActivity.notice` (`MainActivity.kt:62`). Its nine assignments (`MainActivity.kt:94`, `:96`, `:114`, `:124`, `:142`, `:145`, `:150`, `:153`, `:231`) are never read. The tenth `notice =` occurrence, `MainActivity.kt:217`, is the `ChatScreen` named argument and does not touch the variable. No composable and no callback reads `notice`; `onDismissNotice` (`MainActivity.kt:237-242`) reads only `updateStatus`.

Reader with no writer: none found among the cluster-A states. Every state listed in question 1 that is read has at least one write: `lastError` (`ChatViewModel.kt:749` etc.), `bootNotice` (`:500`), `searchError` (`:1016`), `outcome` (`:1194` etc.), `actionNotice` (`Screens.kt:298` etc.), `statusLine` (`:538` etc.), `sessionNotice` (`:724`), `commandsNote` (`:1085`), `lastCrash`/`quarantinedData` (`:255-256`), `pairingStatus` (`Screens.kt:1856` etc.), `showingSavedCopy` (`:862`), `composerBlock` (derived from `connected`).

The `ChatScreen(notice = ...)` parameter is read at `Screens.kt:435` and receives its only value from the expression at `MainActivity.kt:217`; it has both ends.

## 3. The `ChatScreen(notice = …)` expression

`MainActivity.kt:217-219`:

```kotlin
notice = (updateStatus as? UpdateStatus.Available)?.let {
    "Update ${it.info.versionName} ready — tap to install"
},
```

The value is non-null only while `updateStatus is UpdateStatus.Available`; it is the string `"Update " + info.versionName + " ready — tap to install"`.

Because the expression ignores `MainActivity.notice`, every write to that variable is unreachable at the UI:

- install results: `MainActivity.kt:94`, `:96`
- dictation errors: `MainActivity.kt:114` (any `Dictation` error string; `Dictation.kt:49`, `:84`, `:95`, `:122`, `:130`), `MainActivity.kt:124`, `:231`
- pairing progress/results: `MainActivity.kt:142`, `:145`, `:150`, `:153`

Therefore silent failures include: "That link is not a pairing code" (`:142`), "Pairing failed: ${it.message}" (`:153`), "Microphone permission is required for dictation" (`:124`), "Could not start dictation: ${it.javaClass.simpleName}" (`:231`), and every message handed to `onError` by `Dictation` (`:114`). The pairing success line `"Paired as ${paired.device}"` (`:150`) is also invisible; that path additionally sets `showSettings = false` (`MainActivity.kt:151`), so the settings screen's `pairingStatus` text is not on screen at that moment either.

## 4. Bars that stack above the content, in render order

The `Column` at `Screens.kt:406` lays these out top to bottom, before the transcript/pins/search body:

1. Status line, `Screens.kt:414-420`: condition `!vm.connected && vm.statusLine.isNotBlank()`. Plain `Text`.
2. Saved-copy line, `Screens.kt:427-434`: condition `!vm.connected && vm.showingSavedCopy`. Plain `Text` (`"Showing the transcript saved on this phone"`).
3. Update banner, `Screens.kt:435-437`: condition `notice != null`, where `notice` is the `ChatScreen` parameter set only from `UpdateStatus.Available` (`MainActivity.kt:217`). Renders `NoticeBar`.
4. Boot/crash banner, `Screens.kt:441-443`: condition `bootNotice != null`. Renders `NoticeBar`.
5. Error banner, `Screens.kt:444-446`: condition `vm.lastError != null`. Renders `NoticeBar` with `isError = true`.
6. Action banner, `Screens.kt:447-449`: condition `actionNotice != null`. Renders `NoticeBar`.
7. Session line, `Screens.kt:453-459`: condition `vm.sessionNotice != null`. Plain `Text`.

Rendering through `NoticeBar` (`Screens.kt:1576-1609`): items 3, 4, 5, 6. Plain `Text`: items 1, 2, 7.

Simultaneity: the seven conditions are independent state reads, and no condition excludes another. Items 1 and 2 share only `!vm.connected`, and both can hold together on an unreachable bridge with a restored cache (`restoreCache` sets `showingSavedCopy = true` at `ChatViewModel.kt:862`; `connected` stays false and `statusLine` is set at `ChatViewModel.kt:565-567` or `:579`). Items 3 to 7 depend on unrelated state, so all seven can be non-null in one frame.

## 5. What clears each state

- `lastError`: user dismiss, `NoticeBar` `OK` -> `vm::dismissError` (`Screens.kt:445`; `ChatViewModel.kt:1237-1239`), plus `clearCrashState` (`:1269`). No timeout.
- `bootNotice`: user dismiss, `OK` -> `onDismissBootNotice` -> `dismissBootNotice` (`Screens.kt:442`; `ChatViewModel.kt:1242-1244`), plus `clearCrashState` (`:1268`). No timeout.
- `searchError`: no user dismiss. Cleared only by a new search: blank query (`ChatViewModel.kt:976`), non-blank search start (`:980`), `clearSearch` (`:1030`). Rendered as plain `Text` (`Screens.kt:1200-1207`).
- `actionNotice`: user dismiss (`Screens.kt:448`), auto-expiry after 2000 ms (`Screens.kt:277-281`, `delay(2000)` then `actionNotice = null`), or replacement by the next write.
- `UpdateStatus.Failed`: no user dismiss. Replaced by the next `checkForUpdates` (`MainActivity.kt:70` sets `Checking`) or the next failure (`:82`, `:100`). It is only visible in the settings App Updates section.
- `sessionNotice`: no user dismiss, no timer. Cleared by `reloadHistory` completion (`ChatViewModel.kt:827`) and by `forgetSession` (`:525`).
- `commandsNote`: no user dismiss. Cleared by `loadCommands` success (`ChatViewModel.kt:1074`) and `forgetSession` (`:531`); only rendered inside the palette.
- `statusLine`: no dismiss and never nulled; replaced by each new write (`ChatViewModel.kt:538`, `:541`, `:549`, `:565-567`, `:579`, `:745`, `:1229`, `:1232`, `:1233`, `:1270`).
- `MainActivity.notice`: not dismissable; unreachable.
- `pairingStatus`: not dismissable; replaced by the next scan/permission callback or lost on screen disposal.
- `lastCrash` / `quarantinedData`: cleared only by `clearCrashState` (`ChatViewModel.kt:1266-1267`), reached from the Troubleshooting button.

States with a user-facing dismiss today: `lastError`, `bootNotice`, the update `notice`, `actionNotice`. States without one: `searchError`, `UpdateStatus.Failed`, `sessionNotice`, `commandsNote`, `statusLine`, `pairingStatus`, `lastCrash`, `quarantinedData`.

## 6. `actionNotice`

- Type: `String?` (`Screens.kt:276` `var actionNotice by remember { mutableStateOf<String?>(null) }`).
- Expiry: `Screens.kt:277-281`, `LaunchedEffect(actionNotice)` with `delay(2000)` then `actionNotice = null`. Every new non-null value restarts the effect keyed on it.
- Write sites: `Screens.kt:298` `actionNotice = "Copied text added to prompt"` (clipboard-capture listener); `Screens.kt:478` `onConfirm = { actionNotice = it }` (PinsView); `Screens.kt:495` `onAnswerConfirmed = { actionNotice = it }` (TurnDeck); `Screens.kt:501` `onAnswerConfirmed = { actionNotice = it }` (Transcript). The strings the last three receive are produced at the call sites: `Screens.kt:1079` `"Title saved"`, `:1135` `"Prompt sent"`, `:1156` `"Pin deleted"`; `Screens.kt:1422` `"Answer copied"`, `:1440` `"Answer ready to share"`, `:1499` and `:1520` `"Prompt pinned"` / `"Already pinned"`.
- Read/render: `Screens.kt:447-448` `actionNotice?.let { line -> NoticeBar(text = line, onDismiss = { actionNotice = null }) }`.

## 7. `searchError`

- Declaration: `ChatViewModel.kt:338` `var searchError by mutableStateOf<String?>(null)`.
- Write site: `ChatViewModel.kt:1016` in the `onFailure` of `search`, `searchError = if (error is BridgeException && error.code == 404) "this bridge has no search yet; reload it on the laptop" else Diagnostics.describe(error, store.baseUrl)`.
- Clear sites: `ChatViewModel.kt:976` (blank query), `ChatViewModel.kt:980` (start of a non-blank search), `ChatViewModel.kt:1030` in `clearSearch`.
- Read/render: `Screens.kt:1200-1207`, inside `SearchPanel`, `vm.searchError?.let { error -> Text(text = error, style = bodySmall, color = colorScheme.error, ...) }`. It is a plain `Text` with no dismiss affordance. `Screens.kt:1221` also reads it to gate the "no matches" empty state (`results.isEmpty() && query.isNotBlank() && vm.searchError == null`).
- User dismiss: no. Only a new/cleared search (the three clear sites) or a later failure (the write) changes it.

## 8. `UpdateStatus`

Declaration `Screens.kt:2203-2215`:

- `UpdateStatus.Checking` (`:2205`)
- `UpdateStatus.UpToDate(versionName: String)` (`:2208`)
- `UpdateStatus.Available(info: UpdateChecker.Info)` (`:2211`)
- `UpdateStatus.Failed(message: String)` (`:2214`)

Constructed in `MainActivity` only:

- `MainActivity.kt:63` `var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Checking) }`
- `MainActivity.kt:70` `updateStatus = UpdateStatus.Checking`
- `MainActivity.kt:74` `UpdateStatus.Available(info)`
- `MainActivity.kt:78` `UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)`
- `MainActivity.kt:82` `UpdateStatus.Failed(error.message ?: error.javaClass.simpleName)`
- `MainActivity.kt:100` `UpdateStatus.Failed(error.message ?: "Update failed")`

Rendered:

- `MainActivity.kt:198` `updateStatus = updateStatus` into `SettingsScreen`.
- `MainActivity.kt:217` into the chat banner, but only after `as? UpdateStatus.Available`.
- `Screens.kt:2055-2080` the App Updates section `when (val status = updateStatus)`: `Checking` -> `StatusCard`, `Failed` -> `StatusCard(border = error)`, `UpToDate` -> `VersionCard(available = false)`, `Available` -> `VersionCard(available = true)` with an `Install update` button.
- `Screens.kt:2086-2096` the Check button label/enablement.
- `Screens.kt:2218-2222` `updateSubtitle(status)` for the section subtitle.

"Available but declined": no. There is no variant, flag, or field representing a declined or dismissed update; `Available` carries only `UpdateChecker.Info`. The settings section and the chat banner read the same `updateStatus` value declared once at `MainActivity.kt:63` and passed to both `SettingsScreen` (`:198`) and `ChatScreen` (`:217`).

## 9. `sessionNotice`

- Declaration: `ChatViewModel.kt:400` `var sessionNotice by mutableStateOf<String?>(null)`.
- Set site: `ChatViewModel.kt:724` `sessionNotice = "The bridge moved to $label; this screen follows it"`, inside `adoptSession`, only when `moved` is true (`ChatViewModel.kt:719` `val moved = attachedSessionId != null`). `label` is `sessionLabel(cwd, name).ifBlank { "a new session" }` (`:723`).
- Clear sites: `ChatViewModel.kt:525` in `forgetSession`, and `ChatViewModel.kt:827` at the end of a successful `reloadHistory` (`sessionNotice = null`).
- Lifetime: from `adoptSession` until the next history fetch completes, or `forgetSession`.
- Read/render: `Screens.kt:453-459` `vm.sessionNotice?.let { line -> Text(text = line, style = bodySmall, color = onSurfaceVariant, ...) }` (plain `Text`).
- Dismissible: no. No dismiss callback and no timeout.

## 10. `statusLine`

Declaration `ChatViewModel.kt:297` `var statusLine by mutableStateOf("")`.

Producers:

- `ChatViewModel.kt:538` `statusLine = "Add the bridge token in Settings"` (unconfigured `connect`).
- `ChatViewModel.kt:541` `statusLine = "connecting to ${store.baseUrl}"` (string template).
- `ChatViewModel.kt:549` `statusLine = "connected"` (stream `onOpen`).
- `ChatViewModel.kt:565-567` on stream `onClosed`: `statusLine = if (error == null) "disconnected" else Diagnostics.describe(error, store.baseUrl)`.
- `ChatViewModel.kt:579` stream open failure: `statusLine = Diagnostics.describe(error, store.baseUrl)`.
- `ChatViewModel.kt:745` in `refreshState`: `statusLine = listOf(if (connected) "connected" else statusLine, model).filter { it.isNotBlank() }.joinToString(" · ")`; `model` is `state.optJSONObject("model")?.optString("id").orEmpty()` (`:744`).
- `ChatViewModel.kt:1229` `statusLine = runCatching { Diagnostics.probe(context, store.baseUrl) }.getOrElse { "probe error: ${it.javaClass.simpleName}" }` (`testConnection`).
- `ChatViewModel.kt:1232` `statusLine = "$statusLine · HTTP OK"`; `ChatViewModel.kt:1233` `statusLine = "$statusLine · ${Diagnostics.describe(it, store.baseUrl)}"`.
- `ChatViewModel.kt:1270` `statusLine = "Add the bridge token in Settings"` (`clearCrashState`).

The shared string builder is `Diagnostics.describe` (`Diagnostics.kt:101-104`): `"failed · $baseUrl · $detail · ${hint(error)}"`, with `detail = raw ?: error.javaClass.simpleName` (`:102-103`) and `hint` at `Diagnostics.kt:77-99`.

Readers:

- Chat: `Screens.kt:414-420` `if (!vm.connected && vm.statusLine.isNotBlank()) { Text(text = vm.statusLine, style = bodySmall, color = onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, ...) }`. Truncation is exactly one line with an ellipsis (`Screens.kt:419-420`).
- Settings: `Screens.kt:1990-1992` `if (statusLine.isNotBlank()) { Text(statusLine, style = MaterialTheme.typography.bodyMedium) }`. No `maxLines` and no `overflow`; the text wraps and is bounded only by the column width. The value is passed from `MainActivity.kt:191` `statusLine = vm.statusLine`.
