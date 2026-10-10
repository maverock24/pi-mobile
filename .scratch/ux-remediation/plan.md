# Plan

Effort `ux-remediation`. This is the Plan artifact: 19 phases, in build order, each one a worker
session. The authority is `tree.md` (29 decisions), cut into 24 slices by `outline.md`. This file
turns the slices into work without reopening a decision.

Line numbers are against `e976063`. If a symbol has moved, find it by name; the symbols in this
file are the stable handle.

## How a worker runs a phase

1. Read the phase. Do only what it states.
2. Run the **Automated check**. It is one command and it must exit 0.
3. Hand the **Manual check** to the human; it is one observable thing on the phone. Everything
   visible is the human's call (D23).
4. Do not fix a neighbouring phase. If the check cannot pass, stop and report the phase, the
   command and the output.

Machine tools available, nothing else (D16, research Q48/Q49/Q50): `assembleDebug`, `assembleRelease`,
`:app:lintDebug`, `python3`, `grep`, and a scratch JVM harness compiled with the Kotlin compiler in
the Gradle distribution
(`/root/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar`)
against stubbed Android classes. No emulator, no test source set, no new dependency (`--offline`
would fail). Lint baseline is 0 errors, 9 warnings; a phase must not add an error.

The effort is critical (D25). Repo writes outside `.scratch/` wait for the human's answer to the
release question asked after this artifact. This document writes nothing outside `.scratch/`.

## Colour values (settled here, used by phases 4 and 15)

The method is WCAG 2.1 sRGB relative luminance, the same one the review used. It reproduces the
review table exactly (`onSurfaceVariant` on `background`: 4.38 / 4.03 / 4.66 / 5.32 / 5.51). The
scripts keep this recomputable; a claim in prose is not evidence.

### Raised roles, Midnight and Indigo only

`onSurfaceVariant` is painted on `background`, `surface` and `surfaceVariant`, so it must clear
4.5:1 on `surfaceVariant`, the lightest of the three (the harder pair). `error` is painted on
`background` and on the settings `surfaceVariant` panel (`Screens.kt:2151`), so it is measured
against both.

| scheme | role | old | new | on `background` | on `surfaceVariant` |
| --- | --- | --- | --- | --- | --- |
| Midnight | `onSurfaceVariant` | `#627A93` | `#7E94A9` (hsl 210 20% 58%) | 6.20 | 5.61 |
| Midnight | `error` | `#DD3C3C` | `#E35F5F` (hsl 0 70% 63%) | 5.62 | 5.09 |
| Indigo | `onSurfaceVariant` | `#627293` | `#7E8CA9` (hsl 221 20% 58%) | 5.75 | 5.16 |
| Indigo | `error` | `#DD3C3C` | `#E35F5F` | 5.62 | 5.04 |

Both keep the palette's hue and saturation and raise only lightness, so the muted look holds.
Amber, Forest and Light keep their current `onSurfaceVariant` and `error`; D4 scopes the raise to
Midnight and Indigo, and their remaining misses are carried in phase 4 as limitations.

### Update pill pairs, one per scheme

A pill is `background(fill.copy(alpha = 0.16f))` with an explicit text colour (`Pill`,
`Screens.kt:2484-2499`). The ratio is text against the 16% tint composited over that scheme's
`surface` (`#0B1422`, `#0E1420`, `#1E1610`, `#101E18`, `#F6F7FA`). "Available" is the amber pill,
"up to date" the green one.

| scheme | pill | fill | text | tint over surface | ratio |
| --- | --- | --- | --- | --- | --- |
| Midnight | available | `#F0A83C` | `#F0A83C` | `#302C26` | 6.85 |
| Midnight | up to date | `#46C97E` | `#46C97E` | `#143131` | 6.55 |
| Indigo | available | `#F0A83C` | `#F0A83C` | `#322C24` | 6.82 |
| Indigo | up to date | `#46C97E` | `#46C97E` | `#17312F` | 6.54 |
| Amber | available | `#F0A83C` | `#F0A83C` | `#402D17` | 6.46 |
| Amber | up to date | `#46C97E` | `#46C97E` | `#243322` | 6.31 |
| Forest | available | `#F0A83C` | `#F0A83C` | `#34341E` | 6.26 |
| Forest | up to date | `#46C97E` | `#46C97E` | `#193928` | 5.98 |
| Light | available | `#F0A83C` | `#6E4400` | `#F5EADC` | 7.10 |
| Light | up to date | `#46C97E` | `#0F6234` | `#DAF0E6` | 6.24 |

The four dark schemes share the same pair because all four already clear 4.5:1; only Light moves
from 1.71 / 1.77. The token is still carried per `AnswerTheme`, so a later theme can differ without
touch.

The `release` pill keeps `color = primary` and `textColor = primary` and is unchanged. In Light that
pair measures 4.07:1 (`#3562D6` on `#D7DFF4`), below AA; it is outside D4 and is carried as a known
limitation in phase 15.

---

# Wave 1: bugs and notifications

## Phase 1: The update banner can no longer install by accident

- **Wave**: 1. **Slices**: 1. **Closes**: D2 (action-label half), R3.
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`,
  `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`.
- **Change**:
  - `NoticeBar` (`Screens.kt:1576`) gains `actionLabel: String? = null` and `onAction: (() -> Unit)? = null`.
    The single `TextButton` labelled `"OK"` (`Screens.kt:1607`) becomes the action button, labelled
    `actionLabel` and calling `onAction`, rendered only when both are non-null. Add a second
    `TextButton` labelled `"Dismiss"` that calls `onDismiss`, always rendered.
  - `MainActivity`: `onDismissNotice` (`:237-241`) stops calling `installUpdate` and instead sets a
    new `var updateDismissed by rememberSaveable { mutableStateOf(false) }`. `checkForUpdates`
    resets `updateDismissed = false` before each check. The update banner text is derived when
    `updateStatus is UpdateStatus.Available && !updateDismissed`.
  - Add `onInstallUpdate: () -> Unit` to `ChatScreen`, wired to a `MainActivity` lambda that calls
    `installUpdate(available.info)` when `updateStatus is UpdateStatus.Available`. The banner passes
    `actionLabel = "Install"` and `onAction = onInstallUpdate`.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && ! grep -q 'Text("OK")' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && grep -q 'actionLabel' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
  The assertion: the build passes, no literal `"OK"` label remains, and `NoticeBar` carries an
  action label.
- **Manual check**: with an update available, the banner button reads `Install`; tapping `Dismiss`
  closes the banner and no package installer opens.
- **Blocked by**: none.

## Phase 2: One message channel for every message

- **Wave**: 1. **Slices**: 2. **Closes**: D2, D1, D28; R1, R7.
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/ChatViewModel.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`,
  `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`.
- **Change**:
  - In `ChatViewModel.kt` add at package level:
    `enum class NoticeKind { Error, Actionable, Confirmation }` and
    `data class Notice(val id: Long, val kind: NoticeKind, val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)`.
  - Add `var notices by mutableStateOf<List<Notice>>(emptyList()); private set` (style matches
    `lastError`). Ordering on read: errors first, then actionables, then confirmations. Keep at most
    one error (a new one replaces it), at most one actionable (a new one replaces it), and up to
    three confirmations (oldest dropped).
  - Add `fun notifyError(text: String)`, `fun notifyActionable(text: String, actionLabel: String? = null, action: (() -> Unit)? = null)`,
    `fun notifyConfirmation(text: String)`, `fun dismissNotice(id: Long)`, `fun clearNotices()`.
    Each new notice gets `id = ++noticeSeq` (add `private var noticeSeq = 0L`).
    `notifyConfirmation` also launches on `viewModelScope { delay(2000); dismissNotice(id) }`.
  - Fold-in map:
    - `lastError` (decl `:299`, writes `:749,:758,:845,:1205,:1220,:1334,:1359`) becomes `notifyError(...)`.
      Delete `dismissError()`; the error's dismiss is `dismissNotice(id)`.
    - `bootNotice` (decl `:236`, write `:500-508`) becomes `notifyActionable(text)`; delete
      `dismissBootNotice()`; `clearCrashState` (`:1266-1269`) calls `clearNotices()`.
    - `searchError` (decl `:338`) becomes `var searchFailed by mutableStateOf(false); private set`
      for the panel's empty-state gate only. `:1016` sets `searchFailed = true` and calls
      `notifyError(...)`; `:976,:980,:1030` set `searchFailed = false`.
    - `sessionNotice` (decl `:400`, set `:724`, clear `:525,:827`) becomes `notifyActionable(text)`;
      the two clears call `clearNotices()`.
  - `Screens.kt`:
    - Delete the `notice` and `bootNotice` parameters from `ChatScreen` (`:245-247`).
    - Replace the four `NoticeBar` renders (`:436`, `:442`, `:445`, `:448`) with one loop:
      `vm.notices.forEach { n -> NoticeBar(text = n.text, onDismiss = { vm.dismissNotice(n.id) }, isError = n.kind == NoticeKind.Error, actionLabel = n.actionLabel, onAction = n.action) }`.
    - Keep the status line (`:414-420`) and the saved-copy line (`:427-434`) as plain `Text` (D28).
      Delete the session-move `Text` block (`:453-459`).
    - Delete `actionNotice` and its `LaunchedEffect` (`:276-281`, `:298`). The confirmation callbacks
      `onConfirm`/`onAnswerConfirmed` (`:478`, `:495`, `:501`) call `vm.notifyConfirmation(it)`; the
      `SearchPanel` gate at `:1221` reads `!vm.searchFailed`; delete the inline error `Text`
      (`:1200-1207`).
    - `PinsView`, `TurnDeck`, `Transcript` keep their `onConfirm`/`onAnswerConfirmed` parameters; the
      `ChatScreen` wiring passes `vm::notifyConfirmation`.
  - `MainActivity.kt`: delete `var notice` (`:62`) and all nine writes, routing each:
    - `:94` `notifyActionable("Allow installs for Pi Remote, then install again", "Install", installUpdate(available.info))`
    - `:96` `notifyConfirmation(UpdateChecker.install(...) ?: "Installer launched")`
    - `:114`, `:231` `notifyError(...)`; `:124` `notifyError("Microphone permission is required for dictation")`
    - `:142` `notifyError("That link is not a pairing code")`; `:145` `notifyConfirmation("Pairing…")`;
      `:150` `notifyConfirmation("Paired as ${paired.device}")`; `:153` `notifyError("Pairing failed: ...")`
    - Add the update-ready banner as an actionable entry: when a check returns
      `UpdateStatus.Available(info)`, call
      `vm.notifyActionable("Update ${info.versionName} ready", "Install", { installUpdate(info) })`.
    - Delete the `notice =` and `bootNotice =` named arguments (`:217`, `:218`), the
      `onDismissNotice`/`onDismissBootNotice` arguments, and the `onInstallUpdate` argument added in
      phase 1 (the channel's action lambda calls `installUpdate` directly) from the `ChatScreen`
      call. Delete the `updateDismissed` state added in phase 1; the channel's `dismissNotice`
      replaces it.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline :app:lintDebug && ! grep -q 'var notice' app/src/main/java/com/maverock24/pimobile/MainActivity.kt && test "$(grep -c 'NoticeBar(text' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt)" -eq 1`
  The assertion: build and lint pass, the write-only `notice` is gone, and there is exactly one
  `NoticeBar` render site (the single channel).
- **Manual check**: deny the mic permission and tap `Mic`, the reason appears; dismiss it. Start an
  install and dismiss, no installer opens. Let a confirmation sit, it goes after about two seconds,
  while an error stays until dismissed.
- **Blocked by**: phase 1.
- **Limitation carried**: the channel now owns every transient message. Errors compete for one slot;
  an error raised during a search supersedes a connection error, and the search panel stops saying
  "no matches" when `searchFailed` is set.

## Phase 3: System back closes one thing at a time, and the tall rows get a 48 dp floor

- **Wave**: 1. **Slices**: 3, 4. **Closes**: D3, D17; R2, R6.
- **Files**: `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**:
  - Import `androidx.activity.compose.BackHandler`.
  - In `ChatScreen`, after the state declarations, register in this order:
    `BackHandler(enabled = paletteVisible) { paletteOpen = false; paletteDismissed = true }` then
    `BackHandler(enabled = searchOpen) { searchOpen = false }`. The two are mutually exclusive.
  - In `PinsView`, after `var openedId` (`:963`):
    `BackHandler(enabled = openedId != null) { openedId = null }`. It is registered while a pin is
    open and after `ChatScreen`'s handlers, so it wins there.
  - In `MainActivity`, inside the composition that renders `SettingsScreen`:
    `BackHandler(enabled = showSettings) { showSettings = false }`.
  - When no handler is enabled, do nothing: back keeps the platform default and finishes the Activity.
  - Add `Modifier.heightIn(min = 48.dp)` to the command palette row (`Screens.kt:1658-1664`, before
    `.clickable`) and to `SearchResultRow`'s Column (`:1250-1254`, before `.clickable`).
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'BackHandler' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && grep -q 'BackHandler' app/src/main/java/com/maverock24/pimobile/MainActivity.kt && test "$(grep -c 'heightIn(min = 48.dp)' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt)" -ge 3`
  The assertion: the build passes, both files register back handlers, and the two new 48 dp floors
  join the pre-existing one (`:2344`).
- **Manual check**: palette open then back closes it; search open then back closes search and the
  query is still there; a pin open then back returns to the list; in Settings back returns to the
  chat; with nothing open back leaves the app.
- **Blocked by**: phase 2 (the palette and search handlers live beside the frozen `ChatScreen` body).
- **Risk**: back changes at four depths. The nesting is the precedence: handlers registered later run
  first, and search and pins are exclusive branches.

## Phase 4: Secondary and error text meets AA, and the light pills get a readable label

- **Wave**: 1. **Slices**: 5. **Closes**: D4 (contrast half), R4 (the readable-light-pair half), R5.
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/AnswerStyle.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`, new `scripts/luminance.py`.
- **Change**:
  - `AnswerStyle.kt`, `midnight` (`darkColorScheme(...)`): `onSurfaceVariant = Color(0xFF7E94A9)`,
    `error = Color(0xFFE35F5F)`.
  - `AnswerStyle.kt`, `indigo`: `onSurfaceVariant = Color(0xFF7E8CA9)`, `error = Color(0xFFE35F5F)`.
  - `Screens.kt`, `Pill` gains `textColor: Color = color`; the `Text` uses `textColor`.
  - `Screens.kt`, `VersionCard` (`:2418-2429`): keep `amber = Color(0xFFF0A83C)` and
    `green = Color(0xFF46C97E)`; add
    `val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f` and pass
    `textColor = if (available) (if (dark) amber else Color(0xFF6E4400)) else (if (dark) green else Color(0xFF0F6234))`.
    The `release` pill call keeps the default `textColor = color`.
  - Create `scripts/luminance.py`: a table of `(scheme, label, fg, bg, alpha, required)` rows for
    the four raised roles (fg on bg and on surfaceVariant, alpha 1.0) and the ten pill pairs (fg is
    text, bg is that scheme's surface, alpha 0.16). It computes sRGB relative luminance and contrast,
    prints every row with its ratio, and exits 1 if a required row is below 4.5. The background hexes
    are the scheme values from `AnswerStyle.kt`.
- **Automated check**:
  `python3 scripts/luminance.py && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug`
  The assertion: every required pair prints at or above 4.5:1 and the script exits 0; the build still
  passes.
- **Manual check**: in light mode both update pills are readable; in Midnight and Indigo the secondary
  text and error lines are readable.
- **Blocked by**: none.
- **Limitations carried**: Amber, Forest and Light `error` on `background` remain 4.33 / 4.24 / 4.10,
  and Amber `onSurfaceVariant` on `surfaceVariant` remains 4.15; D4 scopes the raise to Midnight and
  Indigo, so those three are recorded, not fixed. The pills are readable here but still inline
  literals; phase 15 moves them onto tokens. The light `release` pill remains 4.07:1.

## Phase 5: Notifications, the question trigger and the run-finished trigger

- **Wave**: 1. **Slices**: 6, 7. **Closes**: D9, D21, D26, D27, D29; R13.
- **Files**: new `app/src/main/java/com/maverock24/pimobile/notify/Notifier.kt`,
  `app/src/main/java/com/maverock24/pimobile/PiRemoteApp.kt`,
  `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/ChatViewModel.kt`,
  `app/src/main/AndroidManifest.xml`, new
  `app/src/main/res/drawable/ic_stat_run.xml`, `docs/PRODUCTION-READINESS.md`.
- **Change**:
  - `AndroidManifest.xml`: add `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`
    beside the existing permissions.
  - New `res/drawable/ic_stat_run.xml`: a 24x24 dp monochrome vector (a filled circle with a smaller
    transparent ring, or the app's `pi` glyph). No colour, no gradient; the system tints a status icon.
  - `PiRemoteApp.kt`: in `onCreate`, register `registerActivityLifecycleCallbacks` with a counter
    incremented in `onActivityResumed` and decremented in `onActivityPaused`/`onActivityStopped`; add
    `val isResumed: Boolean get() = resumedActivities > 0` in the companion. Create the notification
    channel here: `NotificationChannel("pi-runs", "pi runs", NotificationManager.IMPORTANCE_DEFAULT)`
    through `NotificationManagerCompat.from(this)`; no-op if `Build.VERSION.SDK_INT < 26` (never, minSdk
    26, but the guard is free).
  - New `notify/Notifier.kt`: `class Notifier(private val context: Context)` with
    `fun postQuestion(title: String)`, `fun postSettled(answer: String)`, and a private `post(text)`
    that returns early unless `NotificationManagerCompat.from(context).areNotificationsEnabled()` and
    `PiRemoteApp.isResumed == false` (D26). Build with `NotificationCompat.Builder(context, "pi-runs")`,
    `setSmallIcon(R.drawable.ic_stat_run)`, `setContentTitle("pi needs an answer")` /
    `setContentTitle("pi finished")`, `setContentText(...)`, `setAutoCancel(true)`,
    `setContentIntent(openIntent)` and `addAction(0, "Open", openIntent)` (D27). `openIntent` is a
    `PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)`.
    Post on `NotificationManagerCompat.from(context).notify(if (question) 1 else 2, notification)`.
  - `ChatViewModel.kt`: add `private val notifier = Notifier(getApplication())`. In `handleEvent`,
    the `"question"` branch (`:1368`) calls `applyQuestion(...)` then
    `pendingQuestion?.let { notifier.postQuestion(it.title) }`. Change
    `private fun commitRun()` (`:1444`) to `private fun commitRun(): String?` returning the committed
    `answer` (null when blank or already on the transcript); in the `"agent_settled"` branch (`:1372`),
    call `commitRun()?.lineSequence()?.firstOrNull()?.let { notifier.postSettled(it) }` after the
    existing `busy = false`.
  - `MainActivity.kt`: request the runtime permission. Add `var notificationPermissionAsked by rememberSaveable { mutableStateOf(false) }`,
    and a `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }`. In a
    `LaunchedEffect(Unit)`, when `Build.VERSION.SDK_INT >= 33`,
    `ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED`
    and not already asked, launch the request and set the flag.
  - `docs/PRODUCTION-READINESS.md`: under Tier 0.3, add a line that this effort ships the channel and
    two triggers (question, run finished) with no foreground service, so a run that finishes while the
    phone is pocketed still produces nothing, which is what Tier 0.2 is for; record that the run-failed
    trigger is deferred because no bridge event carries it (`agent_end` carries only `messageCount`,
    `bridge/extensions/pi-remote.ts:1641`, and the app discards `tool_execution_end`'s `isError`,
    `ChatViewModel.kt:1439-1441`). Tier 0.2, 0.3 and 0.4 stay open.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'POST_NOTIFICATIONS' app/src/main/AndroidManifest.xml && grep -q 'createNotificationChannel' app/src/main/java/com/maverock24/pimobile/PiRemoteApp.kt && grep -q 'registerActivityLifecycleCallbacks' app/src/main/java/com/maverock24/pimobile/PiRemoteApp.kt && grep -q 'postQuestion\|postSettled' app/src/main/java/com/maverock24/pimobile/ui/ChatViewModel.kt`
  The assertion: the build passes, the permission and channel exist, foreground tracking is wired, and
  both triggers are called.
- **Manual check**: first launch asks for notification permission; a question raised while the app is
  in front produces no notification; background the app, raise a question on the laptop, and a
  notification arrives with a single `Open` action, which opens the app on the question card; send a
  prompt and background the app, and the notification arrives when the run settles.
- **Blocked by**: none. Phase 2's channel is independent of it.
- **Limitations carried (D21, D9, D27)**: no foreground service, so a pocketed phone gets nothing; the
  run-failed trigger does not exist and is recorded as deferred; the notification has one `Open` action
  and no question options or reply. PRD 0.4 and Tier 0.2 stay open on purpose.

## Phase 6: The per-wave phone checklist

- **Wave**: 1. **Slices**: 8. **Closes**: R21 (wave 1); D16, D23.
- **Files**: new `docs/UX-REMEDIATION-CHECKLIST.md`.
- **Change**: write the eight core flows, each with the one phone check from the slice that owns it,
  under a `## Wave 1` heading. The flows: (1) first run, not paired; (2) pair by QR or manual token;
  (3) send a prompt and read the answer, including `Steer` mid-run; (4) answer a question from the
  phone; (5) search a session and jump to a hit; (6) pins, save, rename, delete; (7) view mode and
  theme switch; (8) settings, token, updates, and the laptop command. Add a `## fontScale 1.0 and 2.0`
  section and a `## TalkBack` section, both completed in phase 19. Add a `## Known limitations`
  section carrying D21 (no service, pocketed phone gets nothing), D9 (no run-failed trigger), D27
  (`Open` only), and the out-of-scope contrast misses from phase 4.
- **Automated check**:
  `test -f docs/UX-REMEDIATION-CHECKLIST.md && test "$(grep -c 'Wave 1' docs/UX-REMEDIATION-CHECKLIST.md)" -ge 1 && grep -q 'fontScale' docs/UX-REMEDIATION-CHECKLIST.md && grep -q 'TalkBack' docs/UX-REMEDIATION-CHECKLIST.md`
  The assertion: the file exists and names wave 1, fontScale and TalkBack.
- **Manual check**: run the wave-1 section on the phone and record pass or fail on each line. A fail
  is a fix before wave 2, not a note for later.
- **Blocked by**: phases 1 to 5 (it records their checks); it is written last in wave 1.
- **Note**: this phase produces wave 1's checklist. Phase 13 appends wave 2, phase 19 appends wave 3.

---

# Wave 2: structure

## Phase 7: The composer's fixed row and the Send/Steer label

- **Wave**: 2. **Slices**: 9, 10. **Closes**: D5, D22; R9.
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**:
  - `Composer` (`:1697`): split the action `Row` (`:1752-1810`) into two.
    - Row 1, fixed, no `horizontalScroll`: `OutlinedTextField` already sits above it (`:1742-1748`);
      put the primary `Button` and, when `busy`, the `Stop` `OutlinedButton` in a
      `Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp))`.
      Move `Stop` out of the scrolling row (`:1801-1809`).
    - Row 2, `Row(... .horizontalScroll(rememberScrollState()))`: `Auto-paste` (or `Auto-paste on`),
      `Mic`, `Clear` in that order.
  - At the `Composer` call (`:544`): `actionLabel = if (searchOpen) "Search" else if (vm.busy) "Steer" else "Send"`.
    `actionEnabled` and the haptics stay exactly as they are (`:547-551`; research Q29 confirms `Send`
    is enabled mid-run when the draft is non-blank and connected).
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q '"Steer"' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && test "$(grep -c 'horizontalScroll' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt)" -eq 1`
  The assertion: the build passes, the `Steer` label exists, and `horizontalScroll` appears only on the
  second composer row.
- **Manual check**: on a narrow screen with a long draft, the primary action and, while busy, `Stop`
  are on screen without scrolling; the second row scrolls on its own. Mid-run the primary button reads
  `Steer`, idle it reads `Send`, and a second prompt still steers into the running turn.
- **Blocked by**: phase 2 (the composer's call site is frozen there).

## Phase 8: One segmented control for pick-one-of-N

- **Wave**: 2. **Slices**: 11. **Closes**: D6; R10.
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**:
  - Replace `ViewModeSwitch` (`:924`) with `SingleChoiceSegmentedButtonRow { SegmentedButton(...) }`,
    one segment per view mode (`transcript`, `deck`, `pins`).
  - Delete the hand-built `SegmentButton` (`:2333`) and the `"• "` prefix.
  - In `SettingsScreen`, replace the appearance picker (`:2011`) and the theme picker with the same
    control. Material3 1.3.1 ships it non-experimental (research Q16/Q17). `ThemeSwatch` becomes the
    segment's `icon`.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'SingleChoiceSegmentedButtonRow' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && ! grep -q '• ' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && ! grep -q 'private fun SegmentButton' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
  The assertion: the build passes, the Material3 control is used, and the bullet prefix and the
  hand-built control are gone.
- **Manual check**: the view switch and the theme picker each read as one control and the selected
  segment is visibly filled.
- **Blocked by**: none. **Prefactor**: phase 9 exposes this control's selection to TalkBack.

## Phase 9: Roles, selection, the live answer and headings

- **Wave**: 2. **Slices**: 12, 13. **Closes**: D8 (roles, selection, live region, headings); R15.
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`.
- **Change**:
  - Button role on the seven clickable rows not announced as controls: the view-mode and theme
    segments from phase 8, `PinRow` (`:997`), `TurnPrompt` (`:1286`), the deck card header (`:852`),
    `SearchResultRow` (`:1249`), the palette row (`:1660`), the settings section row (`:2247`). Use
    `Modifier.semantics { role = Role.Button }` (or the `clickable(role = Role.Button, ...)` overload).
  - `selected = true/false` and a `stateDescription` on each selection segment, so TalkBack says which
    one is chosen.
  - `liveRegion = LiveRegionMode.Polite` on the streaming answer while `busy` (`Screens.kt`, inside
    `TurnBody`'s streaming answer). Use `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`.
  - A heading semantic on `AnswerBlock.Heading`'s `Text` in `AnswerView.kt`: `Modifier.semantics { heading() }`.
    The API is present as `heading()` inside `semantics { }` (research Q16).
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline :app:lintDebug && grep -q 'Role.Button' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && grep -q 'stateDescription' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && grep -q 'liveRegion' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt && grep -q 'heading()' app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`
  The assertion: the build and lint pass and every semantic from D8 is present.
- **Manual check**: with TalkBack on, each row reads as a button, each selected segment says which one
  is chosen, a new answer is announced as it streams, and headings can be jumped to.
- **Blocked by**: phase 8.
- **Risk**: a live region can be chatty; the human judges the cadence in phase 19.

## Phase 10: The three fixed-size spots survive fontScale 2.0

- **Wave**: 2. **Slices**: 14. **Closes**: D8 (fixed-size spots); R15.
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`.
- **Change**:
  - Waiting slot `Modifier.height(24.dp)` (`Screens.kt:384`) becomes `Modifier.heightIn(min = 24.dp)`.
  - The fixed `20.dp` bullet column (`AnswerView.kt:151`) stops clipping: use
    `Modifier.widthIn(min = AnswerStyle.bulletIndent)` so the column grows with the glyph.
  - The two `maxLines = 1` app bar texts (`Screens.kt:363`, `:375`): let each grow to two lines and
    ellipsise, or raise the bar with `heightIn(min = ...)`; the subtitle keeps `maxLines = 1` only if
    the bar has room, so set `maxLines = 2` on the title and drop the subtitle's `maxLines = 1` (keep
    ellipsis). The bar is a Material3 small `TopAppBar` (64 dp); if the two lines exceed it at
    fontScale 2.0, the fix is to stop forcing `maxLines` and let the bar size itself.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'heightIn(min = 24.dp)' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && ! grep -q 'width(AnswerStyle.bulletIndent)' app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`
  The assertion: the build passes, the waiting slot is a minimum, and the bullet column no longer has
  a fixed width.
- **Manual check**: at fontScale 2.0, "waiting" is not clipped, `10.` fits its column, and the app bar
  title and subtitle stay legible.
- **Blocked by**: none.
- **Limitation carried**: the exact clip behaviour is device-only (research Q26 marks it
  `needs-device`), so the human confirms it in phase 19's fontScale pass.

## Phase 11: The status line leads with the hint, and the empty state names the situation

- **Wave**: 2. **Slices**: 15, 16. **Closes**: D18, D19; R8, R14.
- **Files**: `app/src/main/java/com/maverock24/pimobile/net/Diagnostics.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**:
  - `Diagnostics.describe` (`net/Diagnostics.kt:101-104`) reorders to
    `"${hint(error)} · failed · $baseUrl · $detail"`, so the actionable half is first and ellipsis no
    longer eats it. The settings screen keeps the one string and already wraps.
  - Chat status line (`Screens.kt:414-420`) becomes `maxLines = 2` (keep the ellipsis).
  - The empty `Box` (`:481-487`) branches on three facts:
    - `!vm.isConfigured` (or `vm.token.isBlank()`): `Text("Not paired yet")` plus a `Button` labelled
      `Open Settings` that calls `onOpenSettings`.
    - `vm.isConfigured && !vm.connected`: show `Diagnostics.describe(vm.lastError...)`-style failure
      text (reuse `vm.statusLine`) plus a `Button` labelled `Retry` calling `vm.ensureConnected()`.
    - `turns.isEmpty() && pending == null && vm.connected`: today's `"thinking…"` / `"no results yet"`
      text.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'hint(error)' app/src/main/java/com/maverock24/pimobile/net/Diagnostics.kt && grep -q 'maxLines = 2' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && grep -q 'Retry' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
  The assertion: the build passes, the hint is first in `describe`, the chat line wraps to two, and the
  empty state offers `Retry`.
- **Manual check**: with the bridge down, the chat line shows the likely fix first and wraps to two
  lines; a fresh install shows the not-paired state and its button opens Settings; a paired but down
  bridge shows `Retry`; connected and empty shows the old text.
- **Blocked by**: none.
- **Note**: `isConfigured` does not exist today; use the token check already on the view model
  (`vm.token.isBlank()`), or add `val isConfigured: Boolean get() = token.isNotBlank()` to
  `ChatViewModel`. Either is fine; the observable behaviour is what matters.

## Phase 12: State survives the trip to Settings

- **Wave**: 2. **Slices**: 17. **Closes**: D7; R11.
- **Files**: `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**:
  - In `MainActivity`, wrap the `if (showSettings)` branch (`:187` / `:212`) in
    `val holder = rememberSaveableStateHolder()` and
    `holder.SaveableStateProvider(if (showSettings) "settings" else "chat") { ... }`.
  - Make `heldTurn` (`Screens.kt:727`) and `confirmingClear` (`:1846`) `rememberSaveable` where the
    loss is user-visible; the other fifteen states in research Q39/Q40 are already saveable.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'rememberSaveableStateHolder' app/src/main/java/com/maverock24/pimobile/MainActivity.kt && grep -q 'SaveableStateProvider' app/src/main/java/com/maverock24/pimobile/MainActivity.kt`
  The assertion: the build passes and the destination keyed holder is in place.
- **Manual check**: open a pin and start renaming it, go to Settings and back, the pin is still open
  and the edit is kept; the same for an open search and a half-typed answer.
- **Blocked by**: none.

## Phase 13: Settings trims its prose and the commands copy on tap

- **Wave**: 2. **Slices**: 18. **Closes**: D24; R16.
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`,
  `docs/UX-REMEDIATION-CHECKLIST.md`.
- **Change**:
  - Section subtitles stay one line; the explanation folds behind the section (`SettingsSection`).
  - The two laptop commands (`On the laptop run /pair…` at `:1915-1918` and
    `cat ~/.config/pi-remote/token` at `:1973`) become tap-to-copy: wrap each in a clickable row that
    copies through `LocalClipboardManager` and calls `vm.notifyConfirmation("Copied")`.
  - No Help screen and no new destination; D3's chain stays palette, search, pin, settings.
  - Append the wave-2 phone checks to `docs/UX-REMEDIATION-CHECKLIST.md` under a `## Wave 2` heading.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'ClipboardManager' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && test "$(grep -c 'Wave 2' docs/UX-REMEDIATION-CHECKLIST.md)" -ge 1`
  The assertion: the build passes, the commands copy through the clipboard, and wave 2 is in the
  checklist.
- **Manual check**: a section opens to a one-line subtitle and a folded explanation; tapping a laptop
  command copies it and shows the copied confirmation.
- **Blocked by**: phase 2 (the copied confirmation goes through the channel).

---

# Wave 3: polish

## Phase 14: Token pass, spacing and sizing

- **Wave**: 3. **Slices**: 19. **Closes**: D13; R17.
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/AnswerStyle.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**:
  - Name the repeated raw dp literals used in `Screens.kt` on `AnswerStyle` (for example the 8/10/12/14/16
    used for padding and gaps) and replace the literals with the tokens.
  - Replace the `promptPadding - 2.dp` arithmetic (`Screens.kt:1296-1308`, `:852-859`) with a token
    whose name says what it is for (for example `accentBarNudge`), so the accent bar lines up without
    arithmetic at the call site.
  - The structural fixed `height(...)`/`size(...)` sites that are not animation (research Q27: the six
    fixed dp `height` and nine `size`) get a named intent where one repeats; leave one-off sizes alone.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && ! grep -q 'promptPadding - 2.dp' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
  The assertion: the build passes and the inline arithmetic is gone.
- **Manual check**: the screens look identical; nothing moved.
- **Blocked by**: none. **Prefactor**: phase 15 hangs the pill tokens on this.

## Phase 15: The update pills move onto AnswerStyle tokens

- **Wave**: 3. **Slices**: 20. **Closes**: D4 (remainder); R4, R17 (colour half).
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/AnswerStyle.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`, `scripts/luminance.py`.
- **Change**:
  - In `AnswerStyle.kt` add:
    `data class PillPair(val fill: Color, val text: Color)` and
    `data class PillColors(val available: PillPair, val upToDate: PillPair)`.
  - Add `val darkPills = PillColors(PillPair(Color(0xFFF0A83C), Color(0xFFF0A83C)), PillPair(Color(0xFF46C97E), Color(0xFF46C97E)))`
    and `val lightPills = PillColors(PillPair(Color(0xFFF0A83C), Color(0xFF6E4400)), PillPair(Color(0xFF46C97E), Color(0xFF0F6234)))`.
  - Add `val pills: PillColors` to `AnswerTheme` and set `pills = darkPills` in `midnight`, `indigo`,
    `amber` and `forest`.
  - `Screens.kt`: delete the `amber`/`green` literals from `VersionCard` (`:2421-2422`) and the
    `dark`/text-colour selection from phase 4. `VersionCard` takes `pills: PillColors` and calls
    `Pill(text=..., fill=pair.fill, textColor=pair.text)` for the chosen `PillPair`. The `release`
    pill keeps `Pill(text = "release", fill = MaterialTheme.colorScheme.primary)` (default text).
  - `SettingsScreen` resolves the pair: `val pills = if (AnswerStyle.isDarkMode(appearance)) AnswerStyle.theme(theme).pills else AnswerStyle.lightPills`,
    passing it to both `VersionCard` calls (`:2069`, `:2076`). Add
    `@Composable fun isDarkMode(mode: String): Boolean = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }`
    to `AnswerStyle.kt` and use it in `PiRemoteTheme` (`Screens.kt:120`) so the light/dark choice has
    one definition.
  - Extend `scripts/luminance.py` with the ten pill rows from the colour section (it already has them
    from phase 4; this phase confirms they still match the token values).
- **Automated check**:
  `python3 scripts/luminance.py && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && ! grep -q 'Color(0xFFF0A83C)' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && ! grep -q 'Color(0xFF46C97E)' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
  The assertion: every required pair still clears 4.5:1, the build passes, and the two literals are
  gone from `Screens.kt` (they now live only in `AnswerStyle.kt`).
- **Manual check**: all four themes, and light, still show readable update pills.
- **Blocked by**: phase 14 (tokens) and phase 4 (the light text colours).
- **Limitation carried**: the `release` pill is unchanged and still measures 4.07:1 in light mode. It
  is outside D4 and is recorded in the checklist's known-limitations section.

## Phase 16: The working sweep obeys the system animation scale

- **Wave**: 3. **Slices**: 21. **Closes**: D10; R20.
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`.
- **Change**: add a small helper that reads
  `Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)`.
  `rememberSweep` (`AnswerView.kt:343`) reads it once and, when it is zero, holds `progress` still
  instead of advancing via `withFrameNanos` (`:350`). `LocalMotionDurationScale` is absent in ui 1.7.5
  (research Q16), which is why the platform value is read.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'ANIMATOR_DURATION_SCALE' app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`
  The assertion: the build passes and the sweep reads the platform scale.
- **Manual check**: set Developer Options animation scale to 0 and start a run, the sweep holds still;
  restore the scale and it moves again.
- **Blocked by**: none.

## Phase 17: Edge-to-edge insets and an adaptive icon

- **Wave**: 3. **Slices**: 22. **Closes**: D14; R19.
- **Files**: `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`,
  `app/src/main/AndroidManifest.xml`, new
  `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, new
  `app/src/main/res/drawable/ic_launcher_foreground.xml`, new
  `app/src/main/res/values/ic_launcher_background.xml`.
- **Change**:
  - `MainActivity.onCreate`: call `enableEdgeToEdge()` before `setContent`. Keep
    `windowSoftInputMode="adjustResize"`; the insets are what handle the bars under edge-to-edge.
  - Both `Scaffold`s (`Screens.kt:342` in `ChatScreen`, `:1872` in `SettingsScreen`) gain
    `contentWindowInsets = WindowInsets.safeDrawing`.
  - Adaptive icon: `mipmap-anydpi-v26/ic_launcher.xml` with
    `<adaptive-icon><background android:drawable="@color/ic_launcher_background"/><foreground android:drawable="@drawable/ic_launcher_foreground"/></adaptive-icon>`,
    a vector foreground, and `<color name="ic_launcher_background">#070D18</color>`. Add
    `android:icon="@mipmap/ic_launcher"` to the `<application>`. The notification icon from phase 5
    stays a separate monochrome drawable.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'enableEdgeToEdge' app/src/main/java/com/maverock24/pimobile/MainActivity.kt && grep -q 'safeDrawing' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && grep -q 'android:icon' app/src/main/AndroidManifest.xml`
  The assertion: the build passes, edge-to-edge and safe drawing are wired, and the app declares an
  icon (`:app:lintDebug` should drop `MissingApplicationIcon`, 9 warnings to 8).
- **Manual check**: on Android 15 the composer sits above the gesture bar and the top bar below the
  status bar; the launcher shows the new icon.
- **Blocked by**: phase 7 (the composer must be at its final shape first).
- **Risk**: insets move every screen's padding, a visible layout change.

## Phase 18: The design record matches the code and the review

- **Wave**: 3. **Slices**: 23. **Closes**: D11, D12, D20; R18.
- **Files**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`,
  `app/src/main/java/com/maverock24/pimobile/ui/AnswerStyle.kt`,
  `docs/PRODUCTION-READINESS.md`, `docs/UX-REVIEW.md`, `GLOSSARY.md`.
- **Change**:
  - `Screens.kt`: add `Modifier.tactile()` to the three answer action `IconButton`s (copy `:1418`,
    share `:1432`, pin `:1450`) and the composer `/` `IconButton` (`:1731`).
  - Remove `Haptics.confirm(view)` from copy (`:1421`) and share (`:1439`), which the lab's `4b`
    excludes.
  - `docs/PRODUCTION-READINESS.md`: correct 0.8 (ships, via ZXing, not CameraX, and the token is
    deliberately not in the QR, `net/Pairing.kt:19`), 1.7 (ships), 1.13 (haptics ship and, after
    phase 16, reduce motion does too), 2.9 (the local crash log ships, reporting does not).
  - `AnswerStyle.kt:14`: replace the dead `pi-remote-design-lab/out/choices-latest.json` with
    `pi-remote-design-lab/out/choices-tactile-latest.json`, and reword the header so it does not claim
    that file holds the enumerated layout and typography choices (it holds the five tactile choices).
  - `docs/UX-REVIEW.md`: change the stale `AnswerStyle.kt:18-20` citation (find by
    `grep -n 'AnswerStyle.kt:18-20'`) to `AnswerStyle.kt:14`; drop "and the README" from the sentence
    "the state value, the composable and the README call it the deck" (find by
    `grep -n 'README call it the deck'`), because `grep -ic deck README.md` is 0.
  - `GLOSSARY.md`: add the UI vocabulary (transcript, cards, pins, answer, prompt, turn), stating that
    the code keeps `deck` as the internal name and the UI keeps "Cards".
  - `rubric.md` is left where it is in `.scratch/`; it is removed with the rest of `.scratch/` at
    effort close, per D12. No second doc is created.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && ! grep -rq 'choices-latest.json' app/src/main && test "$(grep -c 'Haptics.confirm' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt)" -eq 0 && ! grep -q 'README call it the deck' docs/UX-REVIEW.md`
  The assertion: the build passes, the dead lab path is gone from the app, no `Haptics.confirm` remains
  in `Screens.kt`, and the false README claim is gone.
- **Manual check**: copy, share, pin and the `/` affordance show press scale; copy and share no longer
  ring.
- **Blocked by**: phase 7 (the `/` affordance lives in the composer).
- **Risk**: removing the copy and share haptic is felt; the record now says it is deliberate.

## Phase 19: The final TalkBack and fontScale pass, and the release build

- **Wave**: 3. **Slices**: 24. **Closes**: R21 (final); D8 (final pass).
- **Files**: `docs/UX-REMEDIATION-CHECKLIST.md`.
- **Change**: append the wave-3 phone checks under a `## Wave 3` heading; fill the `## fontScale 1.0
  and 2.0` and `## TalkBack` sections with the results. File any failure as its own fix, not a note.
- **Automated check**:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleRelease && test "$(grep -c 'Wave 3' docs/UX-REMEDIATION-CHECKLIST.md)" -ge 1`
  The assertion: the release packages and the wave-3 section exists. `assembleRelease` produces
  `app/build/outputs/apk/release/app-release-unsigned.apk`; this machine cannot sign it (research Q48),
  so this is a packaging check, not a shippable artifact.
- **Manual check**: run all eight flows at fontScale 1.0 and 2.0 and one TalkBack pass; record pass or
  fail on each line.
- **Blocked by**: every other phase. It is the last thing in the effort.
- **Limitation carried**: no emulator exists here (research Q50), so this pass cannot be automated; it
  is the verification, not a formality attached to it.

---

## Waves and what ships

Every wave ends with `assembleRelease`; the signed artifact comes from CI on the push to `main`
(`.github/workflows/android-release.yml`), and the phone installs it through the app's own update
check. For a local sideload before that, `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
The human is the verifier for everything visible (D23).

### Wave 1 (phases 1 to 6): bugs and notifications

What ships: the update banner no longer installs on dismiss; one message channel replaces seven
stacked bars; system back walks palette, search, pin, settings, then exits; the palette and search
rows get a 48 dp floor; Midnight and Indigo secondary and error text meet AA and the light update
pills become readable; the app posts a notification when a question arrives and when a run settles;
`docs/UX-REMEDIATION-CHECKLIST.md` exists with the wave-1 section.

Phone checks, exactly:

1. With an update available, the banner button reads `Install`; tapping `Dismiss` closes it and no
   installer opens.
2. Deny the mic permission and tap `Mic`: the reason appears. Dismiss it. Start an install and
   dismiss: no installer opens. A confirmation goes after about two seconds; an error stays.
3. Palette open then back closes it. Search open then back closes search and the query is still
   there. A pin open then back returns to the list. In Settings back returns to the chat. With
   nothing open back leaves the app.
4. With a one-line command and a one-line search hit, a thumb lands on each row without hitting its
   neighbour.
5. In light mode both update pills are readable. In Midnight and Indigo the secondary text and the
   error lines are readable. Check all four themes.
6. First launch asks for notification permission (Android 13+). A question raised while the app is
   in front produces no notification. Backgrounded, a question produces a notification with one
   `Open` action, which opens the app on the question. Backgrounded, a prompt that settles produces
   a notification, which opens the app on the answer.

Wave-1 limitations to read as part of testing: no foreground service, so a pocketed phone gets
nothing (D21); no run-failed notification (D9); one `Open` action, no reply or option buttons (D27);
Amber, Forest and Light `error` and Amber `onSurfaceVariant` on `surfaceVariant` stay below AA (D4
scopes the raise to Midnight and Indigo); the light `release` pill stays 4.07:1.

### Wave 2 (phases 7 to 13): structure

What ships: the composer has a fixed first row (field, primary action, `Stop`) and a scrolling second
row (`Auto-paste`, `Mic`, `Clear`); the primary action reads `Send` or `Steer`; one segmented control
replaces the bullet row and the hand-built segment; TalkBack gets button roles, selection state, a
live streaming answer and headings; the three fixed-size spots survive fontScale 2.0; the status line
leads with the hint and wraps to two lines; the empty state names its three situations; search, pins
and a half-typed answer survive the trip to Settings; Settings prose folds and its two laptop
commands copy on tap.

Phone checks, exactly:

7. On a narrow screen with a long draft, the primary action and, while busy, `Stop` are on screen
   without scrolling; the second row scrolls on its own.
8. Mid-run the primary button reads `Steer`; idle it reads `Send`; a second prompt still steers into
   the running turn.
9. The view switch and the theme picker each read as one control, selected segment filled; with
   TalkBack on, each reads as a button and announces which segment is chosen.
10. TalkBack on: a streaming answer is announced; headings can be jumped to. Judge whether the live
    region is too chatty.
11. fontScale 2.0: "waiting" is not clipped, `10.` fits its column, the app bar title and subtitle
    stay legible.
12. With the bridge down, the chat status line shows the hint first and wraps to two lines.
13. A fresh install shows the not-paired state and its button opens Settings; a paired but down
    bridge shows `Retry`; connected and empty shows the old text.
14. Open a pin and start renaming it, go to Settings and back: the pin is open and the edit is kept.
    Same for an open search and a half-typed answer.
15. A settings section opens to a one-line subtitle and a folded explanation; tapping a laptop
    command copies it and shows the copied confirmation.

### Wave 3 (phases 14 to 19): polish

What ships: spacing and sizing tokens; the update pills on `AnswerStyle` tokens; the working sweep
obeys the system animation scale; edge-to-edge insets and an adaptive launcher icon; press scale on
the four controls missing it; copy and share no longer ring; the design record matches the code and
the review; the final TalkBack and fontScale pass.

Phone checks, exactly:

16. After the token pass the screens look identical; nothing moved.
17. All four themes and light still show readable update pills.
18. Developer Options animation scale 0: the sweep holds still. Restore the scale: it moves again.
19. On Android 15 the composer sits above the gesture bar and the top bar below the status bar; the
    launcher shows the new icon.
20. Copy, share, pin and the `/` affordance show press scale; copy and share no longer ring.
21. The full pass over all eight flows at fontScale 1.0 and 2.0 and one TalkBack pass; record pass or
    fail on each line and file any failure as its own fix.

## Open

None that the plan cannot answer. One gate stands outside it: the effort is critical, so repo writes
outside `.scratch/` do not begin until the human answers the release question for the whole effort
(D25). The plan is complete and executable once that answer is given.
