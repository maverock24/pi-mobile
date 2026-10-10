# Outline

Effort `ux-remediation`. This is the Structure artifact: the settled tree cut into vertical slices,
in build order. A slice is a complete path through every layer it touches and ships on its own.

Reconciled against the amended tree (29 decisions, D1 to D29) and the second pass. Waves come from
D15 as amended: **wave 1 is the bugs plus the notification work** (R1, R2, R3, R4, R5, R6 and the
notification triggers), **wave 2 is the structure set** (D5, D6, D7, D8, R14, R16), **wave 3 is the
polish set** (D4 remainder, D10, D13, D14, D11, D12, D20 and the final accessibility pass). The
per-wave checklist is not a wave: it is written with wave 1 and extended each wave (D15, D23).

All paths are relative to `app/src/main/java/com/maverock24/pimobile/` unless stated. Other paths
(`app/build.gradle.kts`, `AndroidManifest.xml`, `app/src/main/res/`, `docs/`, `scripts/`) are given
in full.

## How each slice is checked

Per D16 and D23, on this machine, and nothing else:

- Compile and package:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug`
  (`assembleRelease` at wave boundaries and at the end).
- Lint: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline :app:lintDebug`.
  Baseline is 0 errors, 9 warnings (research Q48).
- Pure logic: a scratch JVM harness. The Kotlin compiler bundled with the Gradle distribution
  (`/root/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar`)
  compiles the real source file against stubbed Android classes, and the result runs on the JDK.
  This is the mechanism the review used to verify `CrashLog` across 18 checks.
- Structural fact: `grep`.
- Everything visual or interactive: the human, on the phone, from the written checklist (slice 8).
  Research Q49 is explicit that no recommendation is provable on this machine alone.

There is no JUnit or Hamcrest in the offline cache and no test source set, so a harness is a scratch
program, not a `src/test` suite. **No test dependency is in scope** (PRD 2.1 owns it). A
`testImplementation` added now would fail under `--offline`.

---

# Wave 1 - bugs and notifications

## Slice 1. The update banner can no longer install by accident

- **Wave**: 1
- **Covers**: D2 (the action-label half), R3.
- **Files**: `ui/Screens.kt`, `MainActivity.kt`.
- **Change**: split the one banner action into two. The update banner gets an `Install` button and a
  separate dismiss; `MainActivity.onDismissNotice` (`MainActivity.kt:238-241`) stops calling
  `installUpdate`, and a new `onInstallUpdate` owns the download. `NoticeBar`'s single `OK` label
  (`Screens.kt:1605`) becomes a per-call label.
- **Machine check**: `assembleDebug`; `grep -n 'Text("OK")' ui/Screens.kt` returns nothing;
  `grep -n 'onInstallUpdate\|onDismissNotice' MainActivity.kt` shows the split.
- **Phone check**: with an update available, the banner button reads `Install`; tapping dismiss makes
  the banner go and no package installer opens.
- **Blocked by**: none.
- **Risk**: the banner changes shape. This is the first P0 bug the human will test.

## Slice 2. One message channel for every message

- **Wave**: 1
- **Covers**: D2, D1, D28; R1, R7.
- **Files**: `ui/ChatViewModel.kt`, `ui/Screens.kt`, `MainActivity.kt`.
- **Change**: add a `Notice` type (kind error / actionable / confirmation, text, optional action
  label and action) and `ChatViewModel.notices` with the order error, then actionable, then
  confirmation. A confirmation expires after 2 s; an error persists until dismissed or superseded;
  `dismissNotice(id)` closes one. Fold in `lastError`, `bootNotice`, `sessionNotice`, `searchError`
  and the composable-owned `actionNotice`. Delete `MainActivity.var notice` (`MainActivity.kt:62`)
  and its nine writes, routing them through `vm.notifyError` / `vm.notifyConfirmation` (dictation at
  `:114`, `:124`, `:231`; pairing at `:142`, `:145`, `:150`, `:153`; install results at `:94`,
  `:96`). Replace the four `NoticeBar` renders (`Screens.kt:436`, `:442`, `:445`, `:448`) with one.
  Drop the `notice` parameter from `ChatScreen`.
- **Boundary (D28)**: the channel carries messages, not state. The status line
  (`Screens.kt:414-420`, D18) and the saved-copy line (`:427-434`) stay one plain line each. The
  session-move line (`:453-458`) folds in, because it is a one-time announcement. The update-install
  messages map directly: `Installer launched` is a confirmation, `Allow installs for Pi Remote` is
  an actionable entry that persists, `Update failed` is an error that persists.
- **Machine check**: `assembleDebug`; `:app:lintDebug`; `grep -c 'var notice' MainActivity.kt` is 0;
  `grep -c 'NoticeBar(' ui/Screens.kt` is 1 render site; `grep -n 'notice =' MainActivity.kt`
  returns nothing.
- **Phone check**: deny the mic permission and tap Mic: the reason appears. Dismiss it. Start an
  install and dismiss: no install. Let a confirmation sit: it goes at 2 s while an error stays.
- **Blocked by**: 1 (slice 2 re-homes the action label and the two-button banner).
- **Risk**: this is the notice channel replacing seven stacked bars. Errors and confirmations now
  compete for one area, and a person notices.

## Slice 3. System back closes one thing at a time

- **Wave**: 1
- **Covers**: D3; R2.
- **Files**: `MainActivity.kt`, `ui/Screens.kt`.
- **Change**: add `BackHandler`s in the D3 order: palette, then search, then the open pin, then
  Settings. In `ChatScreen`, one handler for the palette while `paletteVisible`, then one for
  `searchOpen`; in `PinsView`, one while `openedId != null`; in `MainActivity`,
  `BackHandler(enabled = showSettings) { showSettings = false }`. When no handler is enabled, back
  keeps the platform default and finishes the Activity.
  `androidx.activity.compose.BackHandler` is in activity-compose 1.9.3 (`app/build.gradle.kts:71`),
  non-experimental.
- **Machine check**: `assembleDebug`; `grep -rn 'BackHandler' app/src/main` shows the chain;
  `:app:lintDebug` unchanged.
- **Phone check**: with the palette open, back closes it; with search open, back closes search and
  keeps the query; with a pin open, back returns to the list; in Settings, back returns to the chat;
  with nothing open, back leaves the app.
- **Blocked by**: none.
- **Risk**: system back changes behaviour at four depths and is the easiest thing to get wrong.
- **Note**: nesting decides precedence. `PinsView` registers after `ChatScreen`'s direct handlers, so
  it wins while a pin is open, which is correct because search and pins are exclusive branches.

## Slice 4. A 48 dp floor on the palette and the search rows

- **Wave**: 1
- **Covers**: D17; R6.
- **Files**: `ui/Screens.kt`.
- **Change**: `Modifier.heightIn(min = 48.dp)` on the command palette row (`Screens.kt:1660-1668`)
  and on `SearchResultRow` (`:1250-1255`). Both measure 36 dp and 40 dp at fontScale 1.0 when the
  optional second line is absent (research Q25).
- **Machine check**: `assembleDebug`; `grep -n 'heightIn(min = 48.dp)' ui/Screens.kt`.
- **Phone check**: with a one-line command and a one-line search hit, a thumb lands on each row
  without hitting its neighbour.
- **Blocked by**: none.

## Slice 5. Secondary and error text meets AA in every theme

- **Wave**: 1
- **Covers**: D4 (the contrast half), R4 (the readable-light-pair half), R5.
- **Files**: `ui/AnswerStyle.kt`, `ui/Screens.kt`, new `scripts/luminance.py`.
- **Change**: raise `onSurfaceVariant` and `error` in Midnight and Indigo to at least 4.5:1
  (currently 4.38/4.03 and 4.43/4.43). Give the light scheme's update pills
  (`Screens.kt:2421-2422`, `Color(0xFFF0A83C)` and `Color(0xFF46C97E)`, measured 1.71:1 and 1.77:1
  through `Pill` at `:2488`) a readable text colour. The new script computes every pair from the
  source hex values and becomes the repo check for the D4 claim.
- **Machine check**: `python3 scripts/luminance.py` prints every pair at or above 4.5:1 and exits
  non-zero otherwise; `assembleDebug`.
- **Phone check**: in light mode both update pills are readable; in Midnight and Indigo the secondary
  text and error lines are readable.
- **Blocked by**: none.
- **Risk**: raising two roles shifts text colour across the dark side; the human checks all four
  themes.
- **Limitation**: the pills are readable but still off-token here. Slice 20 moves them onto
  `AnswerStyle` (D4 remainder, R17).

## Slice 6. A notification the app can post, and the "needs an answer" trigger

- **Wave**: 1
- **Covers**: D9 (first trigger), D21, D26, D27, D29; R13.
- **Files**: new `notify/Notifier.kt`, `PiRemoteApp.kt`, `MainActivity.kt`, `ui/ChatViewModel.kt`,
  `app/src/main/AndroidManifest.xml`, new `app/src/main/res/drawable/ic_stat_run.xml`,
  `docs/PRODUCTION-READINESS.md`.
- **Change**: declare `POST_NOTIFICATIONS`, create the channel with id `pi-runs` and user-visible
  name "pi runs", and add a monochrome notification icon vector (the app has no icon at all, D29).
  Request the runtime permission on API 33+. Track the resumed activity count in `PiRemoteApp` with
  `registerActivityLifecycleCallbacks`, and have `Notifier` suppress every post while an activity is
  resumed (D26). Post with an `Open` action only (D27), fired when `handleEvent` sees the `question`
  event (`ui/ChatViewModel.kt:1369`), carrying the question title; tapping opens `MainActivity`. Add
  the honest line to `docs/PRODUCTION-READINESS.md`: the triggers ship without a foreground service,
  so a run that needs an answer while the phone is pocketed still produces nothing (D21, the case
  Tier 0.3 was written for). Record the deferred "run failed" trigger (D9).
- **Machine check**: `assembleDebug`; `grep -n 'POST_NOTIFICATIONS' AndroidManifest.xml`;
  `grep -n 'createNotificationChannel' PiRemoteApp.kt`; `grep -n 'registerActivityLifecycleCallbacks' PiRemoteApp.kt`;
  `:app:lintDebug` adds no error.
- **Phone check**: first launch asks for notification permission; drive a question from the laptop
  while the app is in front and no notification appears (D26); put the app in the background, drive
  a question, and the notification arrives with a single `Open` action; tapping it opens the app on
  the question card.
- **Blocked by**: none. **Prefactor**: it builds the channel, the permission and the `Notifier` API
  that slice 7 reuses, which keeps slice 7 to a few lines.
- **Risk**: a new runtime permission prompt on first launch.
- **Limitation**: R13 without a service (D21), without option buttons and reply (D27), and without
  the run-failed trigger (D9). Tapping opens the app, where the question and the answer are
  reachable, which is what R13's wording asks. PRD 0.4 and Tier 0.2 stay open and the doc says so.

## Slice 7. The "run finished" trigger

- **Wave**: 1
- **Covers**: D9 (second trigger); R13.
- **Files**: `ui/ChatViewModel.kt`.
- **Change**: fire the `Notifier` on `agent_settled` (`ui/ChatViewModel.kt:1372`), after `commitRun`,
  with the first line of the committed answer. The suppression from slice 6 applies unchanged.
- **Machine check**: `assembleDebug`; `grep -n 'agent_settled' ui/ChatViewModel.kt` shows the call.
- **Phone check**: send a prompt, put the phone down with the app in the background; the notification
  arrives when the run settles and tapping opens the app on the answer.
- **Blocked by**: 6.
- **Limitation**: same as slice 6. Nothing keeps the socket alive in the background, so the
  pocketed-phone case still produces nothing.

## Slice 8. The per-wave phone checklist

- **Wave**: 1 (written with wave 1, extended each wave)
- **Covers**: R21; D16, D23.
- **Files**: new `docs/UX-REMEDIATION-CHECKLIST.md`.
- **Change**: write the eight core flows with the one phone check from each slice, sectioned per wave,
  plus the fontScale 1.0 and 2.0 pass and the one TalkBack pass that slice 24 records.
- **Machine check**: the file exists; `grep -c 'Wave 1\|Wave 2\|Wave 3' docs/UX-REMEDIATION-CHECKLIST.md`
  is at least 3.
- **Phone check**: the human runs the wave-1 section and notes pass or fail. A failure is a fix before
  wave 2, not a note for later.
- **Blocked by**: none.
- **Note**: R21 is not a wave. It is written with wave 1 and extended each wave, per D15 and D23.
  Slice 24 is the final pass and the record.

---

# Wave 2 - structure

## Slice 9. Three fixed slots in the composer

- **Wave**: 2
- **Covers**: D5; R9.
- **Files**: `ui/Screens.kt` (`Composer` `:1697`, the call at `:533-585`).
- **Change**: row 1 is fixed and holds the `OutlinedTextField`, the primary action and `Stop` while
  `busy`: three fixed slots, not a swap, so the action and the escape are both reachable at once and
  neither can scroll away. Row 2 holds `Auto-paste`, `Mic` and `Clear` in a `horizontalScroll`. The
  `Stop` button moves out of today's scrolling row (`Screens.kt:1800-1809`).
- **Machine check**: `assembleDebug`; `grep -n 'horizontalScroll' ui/Screens.kt` shows it only on the
  second row.
- **Phone check**: on a narrow screen with a long draft, the primary action and, while busy, `Stop`
  are on screen without scrolling; the second row scrolls on its own.
- **Blocked by**: none.
- **Risk**: the composer's two rows are the change the human notices most.

## Slice 10. The primary action is labelled Send or Steer

- **Wave**: 2
- **Covers**: D22.
- **Files**: `ui/Screens.kt` (the `actionLabel` at `:544`).
- **Change**: `actionLabel = if (searchOpen) "Search" else if (vm.busy) "Steer" else "Send"`.
  `actionEnabled` and the haptics stay as they are: `Send` is already enabled mid-run when the draft
  is non-blank and connected (research Q29/Q32).
- **Machine check**: `assembleDebug`; `grep -n '"Steer"' ui/Screens.kt`.
- **Phone check**: mid-run the pinned primary button reads `Steer`; idle it reads `Send`; a second
  prompt still steers into the running turn.
- **Blocked by**: 9.
- **Risk**: low; the label only changes while busy.

## Slice 11. One segmented control for pick-one-of-N

- **Wave**: 2
- **Covers**: D6; R10.
- **Files**: `ui/Screens.kt` (`ViewModeSwitch` `:924`, `SegmentButton` `:2333`, the appearance and
  theme pickers in `SettingsScreen`).
- **Change**: `SingleChoiceSegmentedButtonRow { SegmentedButton(...) }` for view mode and for
  appearance and theme. Delete the hand-built `SegmentButton` and the `"• "` prefix. material3 1.3.1
  ships it non-experimental (research Q16). `ThemeSwatch` becomes the segment's icon.
- **Machine check**: `assembleDebug`; `grep -c 'SegmentButton(' ui/Screens.kt` shows the material3
  call; `grep -n '• ' ui/Screens.kt` returns nothing; `:app:lintDebug`.
- **Phone check**: the view switch and the theme picker read as one control; the selected segment is
  visibly filled.
- **Blocked by**: none. **Prefactor**: it gives slice 12 a real selection control to expose to
  TalkBack, which is why it comes first.
- **Risk**: the segmented buttons replace two visual languages.

## Slice 12. Roles and selection for TalkBack

- **Wave**: 2
- **Covers**: D8 (roles, selection); R15.
- **Files**: `ui/Screens.kt`.
- **Change**: a button role on the seven clickable rows that are not announced as controls: the
  view-mode and theme segments (slice 11), `PinRow` (`:997`), `TurnPrompt` (`:1286`), the deck card
  header (`:852`), `SearchResultRow` (`:1249`), the palette row (`:1660`), the settings section row
  (`:2247`). Add `selected` and `stateDescription` on the selection segments. The APIs are present
  and non-experimental in ui 1.7.5 (research Q16/Q17).
- **Machine check**: `assembleDebug`; `:app:lintDebug`;
  `grep -c 'Role.Button\|stateDescription' ui/Screens.kt`.
- **Phone check**: with TalkBack on, each row reads as a button and each selected segment says which
  one is chosen.
- **Blocked by**: 11.
- **Risk**: screen-reader output changes; the human does one TalkBack pass.

## Slice 13. The streaming answer is announced and headings are marked

- **Wave**: 2
- **Covers**: D8 (live region, headings); R15.
- **Files**: `ui/AnswerView.kt`, `ui/Screens.kt` (the streaming answer in `TurnBody`).
- **Change**: `liveRegion = LiveRegionMode.Polite` on the answer while `busy`; a heading semantic on
  the `AnswerBlock.Heading` text in `AnswerView.kt`.
- **Machine check**: `assembleDebug`; `grep -n 'liveRegion\|heading()' ui/AnswerView.kt ui/Screens.kt`.
- **Phone check**: with TalkBack on, a new answer is announced as it streams and headings can be
  jumped to.
- **Blocked by**: none.
- **Risk**: a live region can be chatty; the human judges the cadence.

## Slice 14. The three fixed-size spots survive fontScale 2.0

- **Wave**: 2
- **Covers**: D8 (fixed-size spots); R15.
- **Files**: `ui/Screens.kt`, `ui/AnswerView.kt`.
- **Change**: the 24 dp waiting slot (`Screens.kt:384`) becomes `heightIn(min = 24.dp)`; the fixed
  20 dp bullet column (`AnswerView.kt:151`) grows with the glyph; the two `maxLines = 1` app bar texts
  (`Screens.kt:363`, `:375`) grow or ellipsise inside a taller bar instead of clipping.
- **Machine check**: `assembleDebug`; `grep -n 'height(24.dp)\|width(AnswerStyle.bulletIndent)' ui/Screens.kt ui/AnswerView.kt`
  no longer shows the fixed clips.
- **Phone check**: at fontScale 2.0, "waiting" is not clipped, `10.` fits its column, and the app bar
  title and subtitle stay legible.
- **Blocked by**: none.
- **Risk**: only visible at fontScale 2.0.
- **Limitation**: the exact clip behaviour needs the phone; research Q26 marks it `needs-device`.

## Slice 15. The status line leads with the hint and wraps to two lines

- **Wave**: 2
- **Covers**: D18; R8.
- **Files**: `net/Diagnostics.kt`, `ui/Screens.kt`.
- **Change**: `Diagnostics.describe` (`net/Diagnostics.kt:104-108`) reorders to hint first, then base
  URL and detail, so ellipsis no longer eats the actionable half. The chat status line
  (`Screens.kt:414-420`) becomes `maxLines = 2`. The settings screen keeps the one string and already
  wraps.
- **Machine check**: `assembleDebug`; `grep -n 'failed ·' net/Diagnostics.kt` shows the new order;
  `grep -n 'maxLines = 2' ui/Screens.kt`.
- **Phone check**: with the bridge down, the chat line shows the likely fix first and wraps to two
  lines.
- **Blocked by**: none.
- **Risk**: settings now shows the hint first too, which is the point but is a change.

## Slice 16. The empty state names the situation

- **Wave**: 2
- **Covers**: D19; R14.
- **Files**: `ui/Screens.kt` (the empty `Box` at `:481-487`).
- **Change**: three states from `isConfigured`, `connected` and `turns.isEmpty()`. Not paired: a line
  plus an action that opens Settings. Paired but not connected: the status line's failure and a
  `Retry` that calls `vm.ensureConnected()`. Connected with no turns: today's text.
- **Machine check**: `assembleDebug`; `grep -n 'Retry' ui/Screens.kt` near the empty branch.
- **Phone check**: a fresh install shows the not-paired state and its button opens Settings; a paired
  but down bridge shows Retry; connected and empty shows the old text.
- **Blocked by**: 15 (it reuses the failure string).
- **Risk**: the first screen of a fresh install changes.

## Slice 17. State survives the trip to Settings

- **Wave**: 2
- **Covers**: D7; R11.
- **Files**: `MainActivity.kt`, `ui/Screens.kt`.
- **Change**: wrap the `if (showSettings)` branch (`MainActivity.kt:187` / `:212`) in
  `rememberSaveableStateHolder().SaveableStateProvider(key)`, keyed by destination, so `ChatScreen`'s
  subtree keeps its `rememberSaveable` state across the trip. Confirm the seventeen states in research
  Q39/Q40 are saveable; `heldTurn` (`Screens.kt:727`) and `confirmingClear` (`:1846`) are plain
  `remember` and become saveable where the loss is user-visible.
- **Machine check**: `assembleDebug`;
  `grep -n 'rememberSaveableStateHolder\|SaveableStateProvider' MainActivity.kt`.
- **Phone check**: open a pin and start renaming it, go to Settings and back: the pin is open and the
  edit is kept. Same for an open search and a half-typed answer.
- **Blocked by**: none.
- **Risk**: state now persists, which is a visible behaviour change.

## Slice 18. Settings trims its prose and the commands copy on tap

- **Wave**: 2
- **Covers**: D24; R16.
- **Files**: `ui/Screens.kt` (`SettingsScreen`, the prose at `:1915-1918`, `:1973`).
- **Change**: section subtitles stay one line; the explanation folds behind the section; the two laptop
  commands (`On the laptop run /pair…` and `cat ~/.config/pi-remote/token`) become tap-to-copy. No
  Help screen and no new destination, so D3's chain stays palette, search, pin, settings.
- **Machine check**: `assembleDebug`; `grep -n 'ClipboardManager' ui/Screens.kt`.
- **Phone check**: a section opens to a one-line subtitle and a folded explanation; tapping the laptop
  command copies it and shows the copied confirmation.
- **Blocked by**: 2 (the copied confirmation goes through the message channel).
- **Risk**: the settings text changes; the human reads it.

---

# Wave 3 - polish

## Slice 19. Token pass, spacing and sizing

- **Wave**: 3
- **Covers**: D13; R17.
- **Files**: `ui/AnswerStyle.kt`, `ui/Screens.kt`.
- **Change**: name the repeated raw dp literals on `AnswerStyle` and use them; replace the
  `promptPadding - 2.dp` arithmetic (`Screens.kt:1296-1308`, `:852-859`) with a token that says what
  it is for. Research Q27 counts 16 `heightIn` sites against 8 fixed `height` and 9 fixed `size`
  sites; the fixed ones that are structural, not animation, get a named intent.
- **Machine check**: `assembleDebug`; `grep -n 'promptPadding - 2.dp' ui/Screens.kt` returns nothing.
- **Phone check**: the screens look identical; nothing moved.
- **Blocked by**: none. **Prefactor**: it gives slice 20 named colour tokens to hang the pill pairs on,
  which is why it comes first.
- **Risk**: a mis-applied token is a layout regression; the human catches it.

## Slice 20. The update pills move onto AnswerStyle tokens

- **Wave**: 3
- **Covers**: D4 (remainder); R4, R17 (colour half).
- **Files**: `ui/AnswerStyle.kt`, `ui/Screens.kt` (`VersionCard` `:2421-2422`, `Pill` `:2488`),
  `scripts/luminance.py`.
- **Change**: the amber and green stop being two literals in `Screens.kt` and become two semantic
  pairs on `AnswerStyle`, one per scheme, each with an explicit text colour for the 16% tint, so the
  light scheme keeps the readable pair slice 5 gave it. Extend `scripts/luminance.py` to cover every
  pair in the table.
- **Machine check**: `python3 scripts/luminance.py` passes; `grep -n 'Color(0xFFF0A83C)\|Color(0xFF46C97E)' ui/Screens.kt`
  returns nothing; `assembleDebug`.
- **Phone check**: all four themes still show readable update pills.
- **Blocked by**: 19 (and 5).
- **Risk**: the tokens replace the last hard-coded pair; the human checks light mode once more.

## Slice 21. The working sweep obeys the system animation scale

- **Wave**: 3
- **Covers**: D10; R20.
- **Files**: `ui/AnswerView.kt` (`rememberSweep` `:343`), plus a small helper reading
  `Settings.Global.ANIMATOR_DURATION_SCALE`.
- **Change**: `rememberSweep` reads the scale once and holds the sweep still when it is zero, so the
  indicator stops instead of animating against a reduce-motion setting. `LocalMotionDurationScale` is
  absent in ui 1.7.5 (research Q16), which is why the value comes from the platform setting.
- **Machine check**: `assembleDebug`; `grep -n 'ANIMATOR_DURATION_SCALE' ui/AnswerView.kt`.
- **Phone check**: set Developer Options animation scale to 0 and start a run; the sweep holds still.
  Restore the scale and it moves again.
- **Blocked by**: none.
- **Risk**: only visible at scale 0.

## Slice 22. Edge-to-edge insets and an adaptive icon

- **Wave**: 3
- **Covers**: D14; R19.
- **Files**: `MainActivity.kt`, `ui/Screens.kt` (both `Scaffold`s), `app/src/main/AndroidManifest.xml`,
  new `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` plus foreground and background drawables.
- **Change**: call `enableEdgeToEdge()` and pad both scaffolds with `WindowInsets.safeDrawing`, so the
  composer clears the gesture bar; add the adaptive icon. Both are backlog Tier 0.7 and 0.1. The
  notification icon from slice 6 stays a separate monochrome drawable.
- **Machine check**: `assembleDebug`; `:app:lintDebug` loses the `MissingApplicationIcon` warning
  (9 becomes 8); `grep -n 'enableEdgeToEdge\|safeDrawing' MainActivity.kt ui/Screens.kt`.
- **Phone check**: on Android 15 the composer sits above the gesture bar; the launcher shows the new
  icon.
- **Blocked by**: 9 (the composer must be at its final shape first).
- **Risk**: insets move every screen's padding, a visible layout change.

## Slice 23. The design record matches the code and the review

- **Wave**: 3
- **Covers**: D11, D12, D20; R18.
- **Files**: `ui/Screens.kt`, `ui/AnswerStyle.kt`, `docs/PRODUCTION-READINESS.md`, `docs/UX-REVIEW.md`,
  `GLOSSARY.md`.
- **Change**: add `Modifier.tactile()` to the three answer action icons and the composer `/`
  affordance (`Screens.kt:1418`, `:1432`, `:1450`, `:1731`); remove the `CONFIRM` haptic from copy and
  share (`:1421`, `:1439`), which the lab's `4b` excludes; correct `PRODUCTION-READINESS.md` for 0.8,
  1.7, 1.13 and 2.9; fix the dead lab path at `AnswerStyle.kt:14`; correct `UX-REVIEW.md:304` and
  `:307`; add the UI vocabulary (transcript, cards, pins, answer, prompt, turn) to `GLOSSARY.md`,
  saying the code keeps `deck` and the UI keeps "Cards".
- **Machine check**: `assembleDebug`; `grep -n 'choices-latest.json' app/src/main` returns nothing;
  `grep -c 'Haptics.confirm' ui/Screens.kt` drops by two.
- **Phone check**: copy, share, pin and the `/` affordance show press scale; copy and share no longer
  ring.
- **Blocked by**: 9 (the `/` affordance lives in the composer).
- **Risk**: removing the copy and share haptic is felt; the record now says that is deliberate.
- **Note**: D12 leaves the `rubric.md` question to the human. This slice does not assume it.

## Slice 24. The final TalkBack and fontScale pass

- **Wave**: 3
- **Covers**: R21; D8 (final pass), D16, D23.
- **Files**: `docs/UX-REMEDIATION-CHECKLIST.md` (from slice 8).
- **Change**: run the eight core flows at fontScale 1.0 and 2.0 and one TalkBack pass, record the
  results, and file any failure as its own fix.
- **Machine check**: `assembleRelease` for the wave's build. Beyond that, this slice is the human's
  pass.
- **Phone check**: the pass itself.
- **Blocked by**: 8; in practice run at the end of wave 3.
- **Limitation**: no emulator exists here (research Q50), so this cannot be automated. It is the
  verification, not a formality attached to it.

---

# Decisions that need no code

- **D1**: the scope umbrella. Closed by every other slice.
- **D15**: the wave grouping. It orders this file; no code.
- **D16**: the verification method. Every slice names its check from it.
- **D23**: the same, plus "no emulator". It is why every phone check exists.
- **D25**: criticality and the release gate. No code, and it blocks repo writes outside `.scratch/`
  until the human answers at the end of Plan.

No app code, but document edits: **D12** and **D20** (both slice 23). **D21**'s no-service half is an
absence plus a doc line (slice 6).

# Coverage

| Decision | Slices |
| --- | --- |
| D1 | all |
| D2 | 1, 2 |
| D3 | 3 |
| D4 | 5 (contrast), 20 (tokens) |
| D5 | 9 |
| D6 | 11 |
| D7 | 17 |
| D8 | 12, 13, 14, 24 |
| D9 | 6, 7 |
| D10 | 21 |
| D11 | 23 |
| D12 | 23 |
| D13 | 19 |
| D14 | 22 |
| D15 | no code |
| D16 | 8, 24, and every check |
| D17 | 4 |
| D18 | 15 |
| D19 | 16 |
| D20 | 23 |
| D21 | 6, 7 |
| D22 | 10 |
| D23 | 8, 24 |
| D24 | 18 |
| D25 | no code |
| D26 | 6 |
| D27 | 6 |
| D28 | 2 |
| D29 | 6 |

Recommendations: R1 to 2; R2 to 3; R3 to 1; R4 to 5 and 20; R5 to 5; R6 to 4; R7 to 2; R8 to 15;
R9 to 9; R10 to 11; R11 to 17; R13 to 6 and 7; R14 to 16; R15 to 12, 13 and 14; R16 to 18; R17 to
19 and 20; R18 to 23; R19 to 22; R20 to 21; R21 to 8 and 24. R12 is out of scope by D1.

# Reconciliation notes

The nine findings the earlier Structure window raised are settled by the amended tree and folded
into the slices above rather than listed as questions:

- D15's waves versus the review's P0: D3 (R2) and D4 (R4, R5) are now wave 1 (slices 3 and 5).
- D5 against D22: the composer's fixed row holds three slots (slice 9) and the action reads
  `Send`/`Steer` (slice 10).
- R21's timing: the checklist is written with wave 1 and extended each wave (slice 8).
- The missing JVM harness: D16 names the Gradle-bundled Kotlin compiler against stubbed Android
  classes, and no test dependency is in scope (the preamble above).
- Foreground suppression: D26, in slice 6.
- Notification actions: D27 ships only `Open`, in slice 6.
- The channel boundary: D28, in slice 2.
- Install results as confirmations: D28, in slice 2.
- The channel name and icon: D29, in slice 6.

Still unsettled and left for the Plan or the human:

- D15's wave 2 list does not name R8, so D18 sits in wave 2 by the review's own P1 grouping.
- The D4 split (slice 5 contrast, slice 20 tokens) is an interpretation of "D4 remainder".
- D12's `rubric.md` question stays with the human (noted in slice 23).
- D21 and D27 keep PRD 0.4 and Tier 0.2 open; slice 6 records that rather than closing it.
