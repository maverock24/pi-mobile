# Work tree

Effort `ux-remediation`. The Plan's 19 phases cut into 40 tasks, one commit each, trailer
`QRSPI: ux-remediation`. This is the Work Tree artifact: the orchestrator reads `Touches` to decide
what may run together, `Blocked by` to order the work, and `Check` to accept a task.

Every `Check` is copied from the plan's automated check for that phase. Where a phase splits, the
check is the phase check's own clause for the file that task changes; the phase's full check is the
conjunction of its tasks' checks and passes once the phase's last task lands. No emulator and no test
source set exist (D16, D23, Q49, Q50), so a check is `assembleDebug` / `assembleRelease`,
`:app:lintDebug`, `python3 scripts/luminance.py`, or a `grep` assertion. Everything visible is the
human's phone check (D23), listed in the phase; the per-wave checklist records it (T16, T28, T40).

`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` and `./gradlew --offline` are assumed in every
command below; the full string is written out once per task for exactness.

Line numbers cite `e976063`. Symbols are the stable handle if a line has moved.

---

## Wave 1 - bugs and notifications (T1-T16)

### T1. The notice bar gets an action label and its own dismiss

- **Wave**: 1. **Phase**: 1.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: `NoticeBar` (`:1576`) gains `actionLabel: String? = null` and
  `onAction: (() -> Unit)? = null`. The `TextButton` labelled `"OK"` (`:1607`) becomes the action
  button, labelled `actionLabel`, calling `onAction`, rendered only when both are non-null. Add a
  second `TextButton` labelled `"Dismiss"` calling `onDismiss`, always rendered.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && ! grep -q 'Text("OK")' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && grep -q 'actionLabel' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
- **Blocked by**: none.
- **Status**: done (e8165cc)
- **Commit subject**: Give the notice bar an action label and its own dismiss

### T2. Dismiss stops starting the install

- **Wave**: 1. **Phase**: 1.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`.
- **Change**: `onDismissNotice` (`:237-241`) stops calling `installUpdate` and sets a new
  `var updateDismissed by rememberSaveable { mutableStateOf(false) }`; `checkForUpdates` resets
  `updateDismissed = false` before each check. Add `onInstallUpdate: () -> Unit` to `ChatScreen`,
  wired to a lambda calling `installUpdate(available.info)`; the banner passes
  `actionLabel = "Install"` and `onAction = onInstallUpdate`.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && ! grep -q 'Text("OK")' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && grep -q 'actionLabel' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
- **Blocked by**: T1.
- **Status**: done (182ae51)
- **Commit subject**: Stop dismissing the update banner from starting the install

### T3. One notice channel in the view model

- **Wave**: 1. **Phase**: 2.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/ChatViewModel.kt`.
- **Change**: add package-level `enum class NoticeKind { Error, Actionable, Confirmation }` and
  `data class Notice(val id: Long, val kind: NoticeKind, val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)`. Add
  `var notices by mutableStateOf<List<Notice>>(emptyList()); private set` (ordering on read: errors,
  actionables, confirmations; at most one error, one actionable, three confirmations) and
  `notifyError`, `notifyActionable`, `notifyConfirmation`, `dismissNotice(id)`, `clearNotices`,
  `private var noticeSeq = 0L`; `notifyConfirmation` also launches
  `viewModelScope { delay(2000); dismissNotice(id) }`. Fold in `lastError` (`:299` and its seven
  writes), `bootNotice` (`:236`, `:500-508`), `searchError` (`:338`) to
  `var searchFailed by mutableStateOf(false); private set` plus `notifyError`, and `sessionNotice`
  (`:400`) to `notifyActionable`; delete `dismissError`, `dismissBootNotice`; `clearCrashState`
  (`:1266-1269`) calls `clearNotices()`.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug`
- **Blocked by**: T2.
- **Status**: done (51d2433)
- **Commit subject**: Give the view model one notice channel

### T4. Every notice renders through one bar

- **Wave**: 1. **Phase**: 2.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: delete the `notice` and `bootNotice` parameters from `ChatScreen` (`:245-247`). Replace
  the four `NoticeBar` renders (`:436`, `:442`, `:445`, `:448`) with one loop over `vm.notices`
  passing `onDismiss = { vm.dismissNotice(n.id) }`, `isError = n.kind == NoticeKind.Error`,
  `actionLabel = n.actionLabel`, `onAction = n.action`. Keep the status line (`:414-420`) and the
  saved-copy line (`:427-434`) as plain `Text` (D28); delete the session-move `Text` (`:453-459`).
  Delete `actionNotice` and its `LaunchedEffect` (`:276-281`, `:298`); `onConfirm`/`onAnswerConfirmed`
  call `vm.notifyConfirmation(it)`; the `SearchPanel` gate (`:1221`) reads `!vm.searchFailed` and the
  inline error `Text` (`:1200-1207`) goes.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && test "$(grep -c 'NoticeBar(text' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt)" -eq 1`
- **Blocked by**: T3.
- **Status**: done (b8f727c)
- **Commit subject**: Render every notice through one bar

### T5. MainActivity's messages go through the channel

- **Wave**: 1. **Phase**: 2.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`.
- **Change**: delete `var notice` (`:62`) and its nine writes, routing each: `:94`
  `notifyActionable("Allow installs for Pi Remote, then install again", "Install", installUpdate(available.info))`;
  `:96` `notifyConfirmation(UpdateChecker.install(...) ?: "Installer launched")`; `:114`, `:231`
  `notifyError(...)`; `:124` `notifyError("Microphone permission is required for dictation")`;
  `:142` `notifyError("That link is not a pairing code")`; `:145` `notifyConfirmation("Pairing…")`;
  `:150` `notifyConfirmation("Paired as ${paired.device}")`; `:153` `notifyError("Pairing failed: ...")`.
  Add the update-ready entry: on `UpdateStatus.Available(info)` call
  `vm.notifyActionable("Update ${info.versionName} ready", "Install", { installUpdate(info) })`.
  Delete the `notice =`, `bootNotice =`, `onDismissNotice`, `onDismissBootNotice` and `onInstallUpdate`
  arguments from the `ChatScreen` call and delete the `updateDismissed` state from T2.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline :app:lintDebug && ! grep -q 'var notice' app/src/main/java/com/maverock24/pimobile/MainActivity.kt`
- **Blocked by**: T3.
- **Status**: done (fc35370)
- **Limitation carried**: the channel owns every transient message. Errors compete for one slot; a
  search error supersedes a connection error and the search panel stops saying "no matches" while
  `searchFailed` is set.
- **Commit subject**: Route MainActivity's messages through the notice channel

### T6. Back walks palette, search and pin, and the short rows get a floor

- **Wave**: 1. **Phase**: 3.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: import `androidx.activity.compose.BackHandler`. In `ChatScreen` register
  `BackHandler(enabled = paletteVisible) { paletteOpen = false; paletteDismissed = true }` then
  `BackHandler(enabled = searchOpen) { searchOpen = false }`. In `PinsView`, after `var openedId`
  (`:963`), `BackHandler(enabled = openedId != null) { openedId = null }` (registered later, so it
  wins while a pin is open). Add `Modifier.heightIn(min = 48.dp)` to the palette row
  (`:1658-1664`, before `.clickable`) and to `SearchResultRow`'s Column (`:1250-1254`, before
  `.clickable`).
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'BackHandler' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && test "$(grep -c 'heightIn(min = 48.dp)' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt)" -ge 3`
- **Blocked by**: T4.
- **Status**: done (aad773f)
- **Commit subject**: Walk system back through palette, search and pin, and floor the short rows

### T7. System back leaves settings

- **Wave**: 1. **Phase**: 3.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`.
- **Change**: in the composition that renders `SettingsScreen`, add
  `BackHandler(enabled = showSettings) { showSettings = false }`. With no handler enabled, back keeps
  the platform default and finishes the Activity.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'BackHandler' app/src/main/java/com/maverock24/pimobile/MainActivity.kt`
- **Blocked by**: T5.
- **Status**: done (07f1ca2)
- **Commit subject**: Let system back leave the settings screen

### T8. Midnight and Indigo text reaches AA

- **Wave**: 1. **Phase**: 4.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/AnswerStyle.kt`.
- **Change**: in `midnight` (`darkColorScheme(...)`) set `onSurfaceVariant = Color(0xFF7E94A9)` and
  `error = Color(0xFFE35F5F)`; in `indigo` set `onSurfaceVariant = Color(0xFF7E8CA9)` and
  `error = Color(0xFFE35F5F)`. Both keep hue and saturation, raise lightness only.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug`
- **Blocked by**: none.
- **Status**: done (67bc713)
- **Limitation carried**: Amber, Forest and Light `error` on `background` stay 4.33 / 4.24 / 4.10 and
  Amber `onSurfaceVariant` on `surfaceVariant` stays 4.15; D4 scopes the raise to Midnight and Indigo.
- **Commit subject**: Raise Midnight and Indigo secondary and error text to AA

### T9. The pills take an explicit text colour

- **Wave**: 1. **Phase**: 4.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: `Pill` (`:2484-2499`) gains `textColor: Color = color` and its `Text` uses it. In
  `VersionCard` (`:2418-2429`) keep `amber = Color(0xFFF0A83C)` and `green = Color(0xFF46C97E)`, add
  `val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f`, and pass
  `textColor = if (available) (if (dark) amber else Color(0xFF6E4400)) else (if (dark) green else Color(0xFF0F6234))`.
  The `release` pill keeps the default `textColor = color`.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug`
- **Blocked by**: none.
- **Status**: done (5521af0)
- **Limitation carried**: the light `release` pill stays 4.07:1; the pills are readable here but
  inline literals until T31/T32.
- **Commit subject**: Give the light update pills a readable label

### T10. The luminance script is the contrast check

- **Wave**: 1. **Phase**: 4.
- **Touches**: `scripts/luminance.py` (new).
- **Change**: create a table of `(scheme, label, fg, bg, alpha, required)` rows for the four raised
  roles (fg on `background` and on `surfaceVariant`, alpha 1.0) and the ten pill pairs (fg is text,
  bg is that scheme's `surface`, alpha 0.16); compute WCAG sRGB relative luminance and contrast, print
  every row with its ratio, exit 1 if a required row is below 4.5. Background hexes are the scheme
  values in `AnswerStyle.kt`.
- **Check**: `python3 scripts/luminance.py && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug`
- **Blocked by**: T8, T9.
- **Status**: done (93ecd5f)
- **Commit subject**: Check every colour pair with a luminance script

### T11. The notification permission and its icon

- **Wave**: 1. **Phase**: 5.
- **Touches**: `app/src/main/AndroidManifest.xml`, `app/src/main/res/drawable/ic_stat_run.xml` (new).
- **Change**: add `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` beside
  the existing permissions. Add a 24x24 dp monochrome vector: a filled circle with a smaller
  transparent ring, or the app `pi` glyph; no colour, no gradient, because the system tints a status
  icon.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'POST_NOTIFICATIONS' app/src/main/AndroidManifest.xml`
- **Blocked by**: none.
- **Status**: done (bd8c037)
- **Commit subject**: Declare the notification permission and add its icon

### T12. The channel and the foreground counter

- **Wave**: 1. **Phase**: 5.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/PiRemoteApp.kt`.
- **Change**: in `onCreate` register `registerActivityLifecycleCallbacks` with a counter incremented
  in `onActivityResumed` and decremented in `onActivityPaused`/`onActivityStopped`; add
  `val isResumed: Boolean get() = resumedActivities > 0` in the companion. Create the channel
  `NotificationChannel("pi-runs", "pi runs", NotificationManager.IMPORTANCE_DEFAULT)` through
  `NotificationManagerCompat.from(this)`; guard `Build.VERSION.SDK_INT < 26` even though minSdk is 26.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'createNotificationChannel' app/src/main/java/com/maverock24/pimobile/PiRemoteApp.kt && grep -q 'registerActivityLifecycleCallbacks' app/src/main/java/com/maverock24/pimobile/PiRemoteApp.kt`
- **Blocked by**: none.
- **Status**: done (725c0b6)
- **Commit subject**: Create the pi-runs channel and track the foreground

### T12a. Drop the obsolete SDK guard the channel setup arrived with

- **Wave**: 1. **Phase**: 5.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/PiRemoteApp.kt`.
- **Change**: `createRunChannel` opens with `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return`. `minSdk` is 26 and `VERSION_CODES.O` is 26, so the branch can never be taken and lint reports `ObsoleteSdkInt`. Remove the guard: the channel API is always present at this app's minimum.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline :app:lintDebug` reports no `ObsoleteSdkInt` in `PiRemoteApp.kt`.
- **Blocked by**: T12.
- **Status**: done (b3ad6d9)
- **Commit subject**: `Drop an obsolete SDK guard in the channel setup`

### T13. The notifier, with one Open action

- **Wave**: 1. **Phase**: 5.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/notify/Notifier.kt` (new).
- **Change**: `class Notifier(private val context: Context)` with `fun postQuestion(title: String)`,
  `fun postSettled(answer: String)` and a private `post(text)` returning early unless
  `NotificationManagerCompat.from(context).areNotificationsEnabled()` and `PiRemoteApp.isResumed == false`.
  Build `NotificationCompat.Builder(context, "pi-runs")`, `setSmallIcon(R.drawable.ic_stat_run)`,
  `setContentTitle("pi needs an answer")` / `setContentTitle("pi finished")`, `setContentText(...)`,
  `setAutoCancel(true)`, `setContentIntent(openIntent)` and `addAction(0, "Open", openIntent)`.
  `openIntent` is `PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)`.
  Post on `notify(if (question) 1 else 2, notification)`.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug`
- **Blocked by**: T11, T12.
- **Status**: done (7359911)
- **Limitation carried**: an `Open` action only, no question options and no reply (D27).
- **Commit subject**: Add the notifier with one Open action

### T14. The two triggers, and what is still missing

- **Wave**: 1. **Phase**: 5.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/ChatViewModel.kt`,
  `docs/PRODUCTION-READINESS.md`.
- **Change**: add `private val notifier = Notifier(getApplication())`. In `handleEvent`, the
  `"question"` branch (`:1368`) calls `applyQuestion(...)` then
  `pendingQuestion?.let { notifier.postQuestion(it.title) }`. Change `private fun commitRun()`
  (`:1444`) to `private fun commitRun(): String?` returning the committed answer (null when blank or
  already on the transcript); in the `"agent_settled"` branch (`:1372`) after `busy = false` call
  `commitRun()?.lineSequence()?.firstOrNull()?.let { notifier.postSettled(it) }`. In
  `docs/PRODUCTION-READINESS.md` under Tier 0.3 add: the effort ships the channel and two triggers
  (question, run finished), with no foreground service, so a run that finishes while the phone is
  pocketed still produces nothing, which is what Tier 0.2 is for; the run-failed trigger is deferred
  because no bridge event carries it (`agent_end` carries only `messageCount`,
  `bridge/extensions/pi-remote.ts:1641`; the app discards `tool_execution_end`'s `isError`,
  `ChatViewModel.kt:1439-1441`). Tier 0.2, 0.3 and 0.4 stay open.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'postQuestion\|postSettled' app/src/main/java/com/maverock24/pimobile/ui/ChatViewModel.kt`
- **Blocked by**: T13, T3.
- **Status**: done (98b000e)
- **Limitation carried**: no foreground service, so a pocketed phone gets nothing (D21); there is no
  run-failed trigger (D9); a notification carries only `Open` (D27). PRD 0.4 and Tier 0.2 stay open on
  purpose. This is the first of the two documents the effort corrects.
- **Commit subject**: Notify on a question and a settled run, and record what is still missing

### T15. Ask for the notification permission

- **Wave**: 1. **Phase**: 5.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`.
- **Change**: add `var notificationPermissionAsked by rememberSaveable { mutableStateOf(false) }` and
  a `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }`. In a
  `LaunchedEffect(Unit)`, when `Build.VERSION.SDK_INT >= 33`,
  `ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED`
  and not already asked, launch the request and set the flag.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug`
- **Blocked by**: T2, T11.
- **Status**: done (df5113d)
- **Commit subject**: Ask for notification permission on first launch

### T16. The wave 1 phone checklist

- **Wave**: 1. **Phase**: 6.
- **Touches**: `docs/UX-REMEDIATION-CHECKLIST.md` (new).
- **Change**: write the eight core flows each with the one phone check from the slice that owns it,
  under `## Wave 1`: (1) first run, not paired; (2) pair by QR or manual token; (3) send a prompt and
  read the answer, including `Steer` mid-run; (4) answer a question from the phone; (5) search a
  session and jump to a hit; (6) pins, save, rename, delete; (7) view mode and theme switch;
  (8) settings, token, updates and the laptop command. Add a `## fontScale 1.0 and 2.0` section and a
  `## TalkBack` section (filled in T40), and a `## Known limitations` section carrying D21 (no
  service, pocketed phone gets nothing), D9 (no run-failed trigger), D27 (`Open` only), and the
  out-of-scope contrast misses from phase 4.
- **Check**: `test -f docs/UX-REMEDIATION-CHECKLIST.md && test "$(grep -c 'Wave 1' docs/UX-REMEDIATION-CHECKLIST.md)" -ge 1 && grep -q 'fontScale' docs/UX-REMEDIATION-CHECKLIST.md && grep -q 'TalkBack' docs/UX-REMEDIATION-CHECKLIST.md`
- **Blocked by**: T2, T4, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15 (every wave-1 code task).
- **Status**: done (52a591b)
- **Commit subject**: Write the wave 1 phone checklist

---

## Wave 2 - structure (T17-T28)

### T17. The composer's first row, and Send or Steer

- **Wave**: 2. **Phase**: 7.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: in `Composer` (`:1697`) split the action `Row` (`:1752-1810`). Row 1, fixed, no
  `horizontalScroll`: the primary `Button` and, when `busy`, the `Stop` `OutlinedButton` in a
  `Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp))`;
  `Stop` moves out of the scrolling row (`:1801-1809`). Row 2,
  `Row(... .horizontalScroll(rememberScrollState()))`, holds `Auto-paste` (or `Auto-paste on`), `Mic`,
  `Clear` in that order. At the `Composer` call (`:544`) set
  `actionLabel = if (searchOpen) "Search" else if (vm.busy) "Steer" else "Send"`; leave
  `actionEnabled` and the haptics unchanged (`:547-551`).
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q '"Steer"' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && test "$(grep -c 'horizontalScroll' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt)" -eq 1`
- **Blocked by**: T4.
- **Status**: done (2da5ddb)
- **Commit subject**: Fix the composer's first row and label its action Send or Steer

### T18. One segmented control for pick-one-of-N

- **Wave**: 2. **Phase**: 8.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: replace `ViewModeSwitch` (`:924`) with
  `SingleChoiceSegmentedButtonRow { SegmentedButton(...) }`, one segment per view mode (`transcript`,
  `deck`, `pins`). Delete the hand-built `SegmentButton` (`:2333`) and the `"• "` prefix. In
  `SettingsScreen` replace the appearance picker (`:2011`) and the theme picker with the same
  control; `ThemeSwatch` becomes the segment's `icon`.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'SingleChoiceSegmentedButtonRow' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && ! grep -q '• ' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && ! grep -q 'private fun SegmentButton' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
- **Blocked by**: none.
- **Status**: done (29fc86e)
- **Commit subject**: Replace the view and theme pickers with one segmented control

### T19. Roles, selection and a live streaming answer

- **Wave**: 2. **Phase**: 9.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: button role on the seven clickable rows not announced as controls: the view-mode and
  theme segments from T18, `PinRow` (`:997`), `TurnPrompt` (`:1286`), the deck card header (`:852`),
  `SearchResultRow` (`:1249`), the palette row (`:1660`), the settings section row (`:2247`), via
  `Modifier.semantics { role = Role.Button }` or the `clickable(role = Role.Button, ...)` overload.
  Add `selected = true/false` and a `stateDescription` on each selection segment. Add
  `liveRegion = LiveRegionMode.Polite` on the streaming answer in `TurnBody` while `busy`
  (`Modifier.semantics { liveRegion = LiveRegionMode.Polite }`).
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline :app:lintDebug && grep -q 'Role.Button' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && grep -q 'stateDescription' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && grep -q 'liveRegion' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
- **Blocked by**: T18.
- **Status**: done (0d6cf8f)
- **Risk**: a live region can be chatty; the human judges the cadence in T40.
- **Commit subject**: Give the clickable rows roles, selection and a live answer

### T20. Headings are marked for TalkBack

- **Wave**: 2. **Phase**: 9.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`.
- **Change**: add `Modifier.semantics { heading() }` to the `Text` in `AnswerBlock.Heading`; the API
  is present as `heading()` inside `semantics { }` in ui 1.7.5.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'liveRegion' app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt && grep -q 'heading()' app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`
- **Blocked by**: none.
- **Status**: done (4116a1c)
- **Commit subject**: Mark answer headings for TalkBack

### T21. The waiting slot and the app bar survive fontScale 2.0

- **Wave**: 2. **Phase**: 10.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: waiting slot `Modifier.height(24.dp)` (`:384`) becomes `Modifier.heightIn(min = 24.dp)`.
  The two `maxLines = 1` app bar texts (`:363`, `:375`): set `maxLines = 2` on the title and drop the
  subtitle's `maxLines = 1` (keep ellipsis); if two lines exceed the Material3 small `TopAppBar`
  (64 dp) at fontScale 2.0, stop forcing `maxLines` and let the bar size itself.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'heightIn(min = 24.dp)' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
- **Blocked by**: none.
- **Status**: done (75c649a)
- **Limitation carried**: the exact clip behaviour is device-only (Q26), confirmed by the human in T40.
- **Commit subject**: Let the waiting slot and the app bar survive fontScale 2.0

### T22. The bullet column grows with its glyph

- **Wave**: 2. **Phase**: 10.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`.
- **Change**: the fixed `20.dp` bullet column (`:151`) becomes
  `Modifier.widthIn(min = AnswerStyle.bulletIndent)` so the column grows with the glyph instead of
  clipping.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && ! grep -q 'width(AnswerStyle.bulletIndent)' app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`
- **Blocked by**: T20.
- **Status**: done (0a98f12)
- **Commit subject**: Let the bullet column grow with its glyph

### T23. The hint leads the connection string

- **Wave**: 2. **Phase**: 11.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/net/Diagnostics.kt`.
- **Change**: reorder `Diagnostics.describe` (`:101-104`) to
  `"${hint(error)} · failed · $baseUrl · $detail"`, so the actionable half is first and ellipsis no
  longer eats it. The settings screen keeps the one string and already wraps.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'hint(error)' app/src/main/java/com/maverock24/pimobile/net/Diagnostics.kt`
- **Blocked by**: none.
- **Status**: done (a807157)
- **Commit subject**: Put the fix first in the connection hint

### T24. The empty state names its situation

- **Wave**: 2. **Phase**: 11.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: chat status line (`:414-420`) becomes `maxLines = 2` (keep ellipsis). The empty `Box`
  (`:481-487`) branches on three facts: not paired (`vm.token.isBlank()`, or add
  `val isConfigured: Boolean get() = token.isNotBlank()` to `ChatViewModel`) shows `Text("Not paired yet")`
  and a `Button` labelled `Open Settings` calling `onOpenSettings`; paired but not connected shows the
  failure text (reuse `vm.statusLine`) and a `Button` labelled `Retry` calling `vm.ensureConnected()`;
  `turns.isEmpty() && pending == null && vm.connected` keeps today's `"thinking…"` / `"no results yet"`.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'maxLines = 2' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && grep -q 'Retry' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
- **Blocked by**: T23.
- **Status**: done (02d0730)
- **Commit subject**: Name the empty state's three situations

### T25. The keyed state holder

- **Wave**: 2. **Phase**: 12.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`.
- **Change**: wrap the `if (showSettings)` branch (`:187` / `:212`) in
  `val holder = rememberSaveableStateHolder()` and
  `holder.SaveableStateProvider(if (showSettings) "settings" else "chat") { ... }`.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'rememberSaveableStateHolder' app/src/main/java/com/maverock24/pimobile/MainActivity.kt && grep -q 'SaveableStateProvider' app/src/main/java/com/maverock24/pimobile/MainActivity.kt`
- **Blocked by**: none.
- **Status**: done (8ea7f65)
- **Commit subject**: Keep the chat's state across the trip to settings

### T26. Two states become saveable

- **Wave**: 2. **Phase**: 12.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: make `heldTurn` (`:727`) and `confirmingClear` (`:1846`) `rememberSaveable`; the other
  fifteen states in Q39/Q40 are already saveable.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug`
- **Blocked by**: none.
- **Status**: done (41c1423)
- **Commit subject**: Save the held turn and the clear confirmation

### T27. Settings prose folds, commands copy on tap

- **Wave**: 2. **Phase**: 13.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: section subtitles stay one line and the explanation folds behind the section
  (`SettingsSection`). The two laptop commands (`On the laptop run /pair…` at `:1915-1918` and
  `cat ~/.config/pi-remote/token` at `:1973`) become tap-to-copy: wrap each in a clickable row that
  copies through `LocalClipboardManager` and calls `vm.notifyConfirmation("Copied")`. No Help screen,
  so D3's chain stays palette, search, pin, settings.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'ClipboardManager' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
- **Blocked by**: T3.
- **Status**: done (e12f7e9)
- **Commit subject**: Fold settings prose and copy the laptop commands on tap

### T28. The wave 2 phone checklist

- **Wave**: 2. **Phase**: 13.
- **Touches**: `docs/UX-REMEDIATION-CHECKLIST.md`.
- **Change**: append the wave-2 phone checks to `docs/UX-REMEDIATION-CHECKLIST.md` under a
  `## Wave 2` heading: the composer's fixed row and `Steer`; the segmented control and its TalkBack
  reading; the live answer and heading jumps; fontScale 2.0; the status hint and the three empty
  states; the pin, the search and the half-typed answer surviving Settings; the folded prose and the
  copied command.
- **Check**: `test "$(grep -c 'Wave 2' docs/UX-REMEDIATION-CHECKLIST.md)" -ge 1`
- **Blocked by**: T16, T27.
- **Status**: done (f16496b)
- **Commit subject**: Write the wave 2 phone checklist

---

## Wave 3 - polish (T29-T40)

### T29. Name the spacing and sizing tokens

- **Wave**: 3. **Phase**: 14.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/AnswerStyle.kt`.
- **Change**: name the repeated raw dp literals used in `Screens.kt` on `AnswerStyle` (the
  8/10/12/14/16 used for padding and gaps). Add a token whose name says what it is for, for example
  `accentBarNudge`, to replace the `promptPadding - 2.dp` arithmetic. Name the structural fixed
  `height(...)` / `size(...)` sites that repeat; leave one-off sizes alone.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug`
- **Blocked by**: T8.
- **Commit subject**: Name the repeated spacing and sizing tokens

### T30. The screens use the tokens

- **Wave**: 3. **Phase**: 14.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: replace the raw dp literals with the T29 tokens, and replace the
  `promptPadding - 2.dp` arithmetic (`:1296-1308`, `:852-859`) with `accentBarNudge` so the accent bar
  lines up without arithmetic at the call site.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && ! grep -q 'promptPadding - 2.dp' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
- **Blocked by**: T29.
- **Commit subject**: Use the spacing and sizing tokens in the screens

### T31. The pill pairs move onto AnswerStyle

- **Wave**: 3. **Phase**: 15.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/AnswerStyle.kt`.
- **Change**: add `data class PillPair(val fill: Color, val text: Color)` and
  `data class PillColors(val available: PillPair, val upToDate: PillPair)`. Add
  `val darkPills = PillColors(PillPair(Color(0xFFF0A83C), Color(0xFFF0A83C)), PillPair(Color(0xFF46C97E), Color(0xFF46C97E)))`
  and `val lightPills = PillColors(PillPair(Color(0xFFF0A83C), Color(0xFF6E4400)), PillPair(Color(0xFF46C97E), Color(0xFF0F6234)))`.
  Add `val pills: PillColors` to `AnswerTheme`, set `pills = darkPills` in `midnight`, `indigo`,
  `amber` and `forest`, and add
  `@Composable fun isDarkMode(mode: String): Boolean = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }`;
  use it in `PiRemoteTheme` (`Screens.kt:120`) in T32 so the light/dark choice has one definition.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug`
- **Blocked by**: T29.
- **Commit subject**: Move the update pill colours onto AnswerStyle

### T32. The version pills paint from the tokens

- **Wave**: 3. **Phase**: 15.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: delete the `amber`/`green` literals from `VersionCard` (`:2421-2422`) and the
  `dark`/text-colour selection from T9. `VersionCard` takes `pills: PillColors` and calls
  `Pill(text = ..., fill = pair.fill, textColor = pair.text)` for the chosen `PillPair`; the `release`
  pill keeps `Pill(text = "release", fill = MaterialTheme.colorScheme.primary)` (default text).
  `SettingsScreen` resolves
  `val pills = if (AnswerStyle.isDarkMode(appearance)) AnswerStyle.theme(theme).pills else AnswerStyle.lightPills`
  and passes it to both `VersionCard` calls (`:2069`, `:2076`). Use `AnswerStyle.isDarkMode` in
  `PiRemoteTheme` (`:120`) so the light/dark choice has one definition. `scripts/luminance.py` is unchanged;
  its ten pill rows were written in T10 and re-run here to confirm they match the tokens.
- **Check**: `python3 scripts/luminance.py && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && ! grep -q 'Color(0xFFF0A83C)' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt && ! grep -q 'Color(0xFF46C97E)' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
- **Blocked by**: T31, T9.
- **Limitation carried**: the `release` pill is unchanged and still measures 4.07:1 in light mode, is
  outside D4, and is recorded in the checklist's known-limitations section.
- **Commit subject**: Paint the version pills from the theme tokens

### T33. The sweep obeys the animation scale

- **Wave**: 3. **Phase**: 16.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`.
- **Change**: add a small helper reading
  `Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)`.
  `rememberSweep` (`:343`) reads it once and, when it is zero, holds `progress` still instead of
  advancing via `withFrameNanos` (`:350`). `LocalMotionDurationScale` is absent in ui 1.7.5 (Q16).
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'ANIMATOR_DURATION_SCALE' app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt`
- **Blocked by**: T22.
- **Commit subject**: Hold the working sweep when animations are off

### T34. Edge to edge

- **Wave**: 3. **Phase**: 17.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`.
- **Change**: in `onCreate` call `enableEdgeToEdge()` before `setContent`. Keep
  `windowSoftInputMode="adjustResize"`; the insets handle the bars.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'enableEdgeToEdge' app/src/main/java/com/maverock24/pimobile/MainActivity.kt`
- **Blocked by**: T17, T25.
- **Commit subject**: Go edge to edge

### T35. Both scaffolds pad for the safe drawing insets

- **Wave**: 3. **Phase**: 17.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: both `Scaffold`s (`:342` in `ChatScreen`, `:1872` in `SettingsScreen`) gain
  `contentWindowInsets = WindowInsets.safeDrawing`.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'safeDrawing' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`
- **Blocked by**: T17.
- **Commit subject**: Pad both scaffolds with the safe drawing insets

### T36. The launcher gets an adaptive icon

- **Wave**: 3. **Phase**: 17.
- **Touches**: `app/src/main/AndroidManifest.xml`,
  `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (new),
  `app/src/main/res/drawable/ic_launcher_foreground.xml` (new),
  `app/src/main/res/values/ic_launcher_background.xml` (new).
- **Change**: `mipmap-anydpi-v26/ic_launcher.xml` with
  `<adaptive-icon><background android:drawable="@color/ic_launcher_background"/><foreground android:drawable="@drawable/ic_launcher_foreground"/></adaptive-icon>`,
  a vector foreground, and `<color name="ic_launcher_background">#070D18</color>`. Add
  `android:icon="@mipmap/ic_launcher"` to the `<application>`. The notification icon from T11 stays a
  separate monochrome drawable.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && grep -q 'android:icon' app/src/main/AndroidManifest.xml`
- **Blocked by**: T11.
- **Commit subject**: Give the launcher an adaptive icon

### T37. Press scale on the four controls that lack it

- **Wave**: 3. **Phase**: 18.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: add `Modifier.tactile()` to the three answer action `IconButton`s (copy `:1418`, share
  `:1432`, pin `:1450`) and the composer `/` `IconButton` (`:1731`). Remove `Haptics.confirm(view)`
  from copy (`:1421`) and share (`:1439`), which the lab's `4b` excludes.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && test "$(grep -c 'Haptics.confirm' app/src/main/java/com/maverock24/pimobile/ui/Screens.kt)" -eq 0`
- **Blocked by**: T17.
- **Risk**: removing the copy and share haptic is felt; the record now says it is deliberate.
- **Commit subject**: Add press scale to the four controls that lack it

### T38. AnswerStyle points at the lab file that exists

- **Wave**: 3. **Phase**: 18.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/AnswerStyle.kt`.
- **Change**: at `AnswerStyle.kt:14` replace the dead `pi-remote-design-lab/out/choices-latest.json`
  with `pi-remote-design-lab/out/choices-tactile-latest.json`, and reword the header so it does not
  claim that file holds the enumerated layout and typography choices (it holds the five tactile
  choices).
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && ! grep -rq 'choices-latest.json' app/src/main`
- **Blocked by**: T31.
- **Commit subject**: Point AnswerStyle at the lab file that exists

### T39. The production record and the review are corrected

- **Wave**: 3. **Phase**: 18.
- **Touches**: `docs/PRODUCTION-READINESS.md`, `docs/UX-REVIEW.md`, `GLOSSARY.md`.
- **Change**: in `docs/PRODUCTION-READINESS.md` correct 0.8 (ships, via ZXing not CameraX, and the
  token is deliberately not in the QR, `net/Pairing.kt:19`), 1.7 (ships), 1.13 (haptics ship and,
  after T33, reduce motion does too), 2.9 (the local crash log ships, reporting does not). In
  `docs/UX-REVIEW.md` change the stale `AnswerStyle.kt:18-20` citation (find by
  `grep -n 'AnswerStyle.kt:18-20'`) to `AnswerStyle.kt:14`, and drop "and the README" from the
  sentence "the state value, the composable and the README call it the deck" (find by
  `grep -n 'README call it the deck'`), because `grep -ic deck README.md` is 0. Add the UI vocabulary
  (transcript, cards, pins, answer, prompt, turn) to `GLOSSARY.md`, stating the code keeps `deck` as
  the internal name and the UI keeps "Cards". `rubric.md` stays in `.scratch/` and is not a second doc.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug && ! grep -q 'README call it the deck' docs/UX-REVIEW.md`
- **Blocked by**: T14.
- **Limitation carried**: this is the second document the effort corrects; `docs/PRODUCTION-READINESS.md`
  was first touched in T14 and the notification limitations recorded there stay open. No second doc is
  created for `rubric.md` (D12).
- **Commit subject**: Correct the production record and the review, and add the UI words

### T40. The final pass and the release build

- **Wave**: 3. **Phase**: 19.
- **Touches**: `docs/UX-REMEDIATION-CHECKLIST.md`.
- **Change**: append the wave-3 phone checks under a `## Wave 3` heading and fill the
  `## fontScale 1.0 and 2.0` and `## TalkBack` sections with the results of the eight flows at both
  scales and one TalkBack pass. File any failure as its own fix, not a note.
- **Check**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleRelease && test "$(grep -c 'Wave 3' docs/UX-REMEDIATION-CHECKLIST.md)" -ge 1`
- **Blocked by**: every earlier task (T1-T39).
- **Limitation carried**: no emulator exists here (Q50), so this pass cannot be automated; it is the
  verification. `assembleRelease` produces `app/build/outputs/apk/release/app-release-unsigned.apk`;
  this machine cannot sign it (Q48), so this is a packaging check, not a shippable artifact.
- **Commit subject**: Run the final fontScale and TalkBack pass and package the release

---

## Parallel batches

A batch runs as a unit: within it every `Touches` set is disjoint, so no two workers share a file.
Batches are dispatched in order. Every task in a batch has its `Blocked by` satisfied by an earlier
batch (or by a completed wave). The bottleneck named for each wave is the file whose serial edits
force the number of batches.

### Wave 1 (16 tasks, 7 batches)

- **W1-a**: T1 (Screens.kt), T8 (AnswerStyle.kt), T11 (AndroidManifest.xml,
  res/drawable/ic_stat_run.xml), T12 (PiRemoteApp.kt).
- **W1-b**: T2 (MainActivity.kt), T9 (Screens.kt), T13 (notify/Notifier.kt).
- **W1-c**: T3 (ChatViewModel.kt), T10 (scripts/luminance.py).
- **W1-d**: T4 (Screens.kt), T5 (MainActivity.kt), T14 (ChatViewModel.kt, docs/PRODUCTION-READINESS.md).
- **W1-e**: T6 (Screens.kt), T7 (MainActivity.kt).
- **W1-f**: T15 (MainActivity.kt).
- **W1-g**: T16 (docs/UX-REMEDIATION-CHECKLIST.md).

Bottleneck: `Screens.kt` and `MainActivity.kt` tie, with four sequential edits each (T1, T4, T6, T9;
T2, T5, T7, T15). T10 waits for both T8 and T9, and T16 needs T15, so the wave runs seven batches.

### Wave 2 (12 tasks, 8 batches)

- **W2-a**: T17 (Screens.kt), T20 (AnswerView.kt), T23 (net/Diagnostics.kt), T25 (MainActivity.kt).
- **W2-b**: T18 (Screens.kt), T22 (AnswerView.kt).
- **W2-c**: T19 (Screens.kt).
- **W2-d**: T21 (Screens.kt).
- **W2-e**: T24 (Screens.kt).
- **W2-f**: T26 (Screens.kt).
- **W2-g**: T27 (Screens.kt).
- **W2-h**: T28 (docs/UX-REMEDIATION-CHECKLIST.md).

Bottleneck: `Screens.kt`, with seven sequential edits (T17, T18, T19, T21, T24, T26, T27). It sets
the floor at seven batches; the eighth is the checklist append (T28), which needs T27.

### Wave 3 (12 tasks, 6 batches)

- **W3-a**: T29 (AnswerStyle.kt), T33 (AnswerView.kt), T34 (MainActivity.kt), T36 (AndroidManifest.xml
  and the three new res files), T39 (docs/PRODUCTION-READINESS.md, docs/UX-REVIEW.md, GLOSSARY.md).
- **W3-b**: T30 (Screens.kt), T31 (AnswerStyle.kt).
- **W3-c**: T32 (Screens.kt), T38 (AnswerStyle.kt).
- **W3-d**: T35 (Screens.kt).
- **W3-e**: T37 (Screens.kt).
- **W3-f**: T40 (docs/UX-REMEDIATION-CHECKLIST.md).

Bottleneck: `Screens.kt`, with four sequential edits (T30, T32, T35, T37), ahead of `AnswerStyle.kt`
with three (T29, T31, T38). The final checklist (T40) adds one batch at the end.

---

## Longest dependency chain

Eight tasks: **T1 -> T2 -> T3 -> T4 -> T6 -> T16 -> T28 -> T40**. It runs from the banner fix through
the notice channel and the back/48 dp work, into the wave-1 checklist, the wave-2 checklist append,
and the final release pass. The other chains are shorter: the notification line is five
(T11 -> T13 -> T14 -> T39 -> T40), and the composer/insets line is seven
(T1 -> T2 -> T3 -> T4 -> T17 -> T37 -> T40).

---

## Post-review tasks

Found by the wave-1 code review. T16a is already fixed and committed; the other three are carried
into the wave named on each, so a finding cannot be lost by being only in a report.

### T16a. Keep the recovery message and live errors through a history load

- **Wave**: 1 (fix, after review). **Phase**: 2.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/ChatViewModel.kt`.
- **Change**: `reloadHistory` ended with `clearNotices()` to retire the session-move line, which took the message `recoverFromStartupCrash` had just posted, and any error still waiting to be read, with it inside a second. The session-move notice now keeps its id and only that id is dismissed; `clearNotices()` also clears the tracked id, because nothing is left to clear.
- **Check**: `assembleDebug` success, and `grep -n "clearNotices()" ChatViewModel.kt` shows only `forgetSession` and `clearCrashState`.
- **Blocked by**: T16.
- **Commit subject**: `Clear only the session line when its transcript arrives`
- **Status**: done (`b7e7761`)

### T17a. Back closes the palette before an open pin

- **Wave**: 2. **Phase**: 3.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: the pin's `BackHandler` is registered after the palette's, so the dispatcher serves it first and back closes the pin rather than the palette, which is the reverse of D3's order. Gate the pin's handler on the palette being closed, or register the palette's later than the content.
- **Check**: `grep -n "BackHandler" Screens.kt`, then read the `enabled` conditions to confirm the palette wins while both are open.
- **Blocked by**: T17.
- **Status**: done (e33af44)
- **Commit subject**: `Close the command palette first when both are open`

### T25a. A notice action survives a recreated screen

- **Wave**: 2. **Phase**: 12.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/MainActivity.kt`, `app/src/main/java/com/maverock24/pimobile/ui/ChatViewModel.kt`.
- **Change**: an actionable notice's `action` closure captures the composition that posted it, so after a rotation the "Allow installs for Pi Remote" entry's Install runs on a cancelled scope and holds the destroyed Activity. Re-post the entry when the composition is recreated, or carry the action as a value the screens map to behaviour instead of a captured lambda.
- **Check**: `assembleDebug` success, and on the phone: rotate with that entry on screen and tap Install.
- **Blocked by**: T25.
- **Status**: done (cec2d6b)
- **Commit subject**: `Make a notice action survive a recreated screen`

### T38a. The luminance check reads the palette from the source

- **Wave**: 3. **Phase**: 18.
- **Touches**: `scripts/luminance.py`.
- **Change**: the script hardcodes the hex values, so its docstring's claim that a change here follows a change there is false, and an `AnswerStyle.kt` regression goes unnoticed. Parse the palettes out of `AnswerStyle.kt` and check what is actually declared.
- **Check**: `python3 scripts/luminance.py` exits 0; then set one palette hex in `AnswerStyle.kt` to a failing value, confirm exit 1, and restore it.
- **Blocked by**: T38.
- **Commit subject**: `Read the palette out of the source it checks`

### T21a. Make the app bar carry its two lines at a large font scale

- **Wave**: 3. **Phase**: 19.
- **Touches**: `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`.
- **Change**: T21 raised the title to `maxLines = 2` and dropped the subtitle's cap, but Material3's small `TopAppBar` is `heightIn(max = TopAppBarSmallTokens.ContainerHeight = 64.dp)` with `clipToBounds()` (`AppBarKt$SingleRowTopAppBar`), so the extra lines render inside the same 64 dp and are cut off rather than growing the bar. The font-scale part of T21 is therefore not real. The fix is to replace the fixed-height bar (a plain `Column` bar, or a `TopAppBar` whose content is not height-capped) so the two lines can size themselves.
- **Check**: on the phone at `fontScale` 2.0, the bar shows both the title and the session subtitle whole, with no clipped descenders and no action pushed off.
- **Blocked by**: T21.
- **Commit subject**: `Make the app bar carry its two lines at a large font scale`
- **Status**: done (5cac18b)
