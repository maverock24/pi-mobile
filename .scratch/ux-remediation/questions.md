# Questions

Each one is a fact about what exists, answerable with a `file:line` or a primary-source citation.
No question here is about what to build. All line numbers are against `e976063`, the commit
`docs/UX-REVIEW.md` was written against and the current `HEAD` of the working tree.

## A. The message and notice system

1. **Enumerate every mechanism that puts a message in front of the user and, for each, give the
   declaration site, every write site, every read site, whether it can be dismissed, and what
   clears it.** Cover at least: `MainActivity.notice` (`MainActivity.kt:62`), `ChatViewModel.lastError`,
   `bootNotice`, `searchError`, `outcome`, `ChatScreen.actionNotice`, `UpdateStatus`, `statusLine`,
   `sessionNotice`, `commandsNote`, `lastCrash`/`quarantinedData` (settings). Which of the names the
   ticket lists (`pairingStatus`, `SessionNotice`) have no such symbol, and what plays their role?
   *Why: R1 and R7 replace this with one channel; the write/read/clear matrix is the specification.*

2. **Which message states have a writer but no reader, or a reader but no writer?** Give each as a
   `file:line`. For `MainActivity.notice`, name the ten write sites and show that no read site
   exists.
   *Why: a dead state is either wired back up or deleted, and either choice changes R1.*

3. **What exact expression is passed as `ChatScreen(notice = …)`?** (`MainActivity.kt:217`.) Which
   `notice` writes does that expression make unreachable, and which pairing and dictation failures
   are therefore silent?
   *Why: this is the regression R1 reverses; it decides whether the fix is one variable or a channel.*

4. **List the bars that can stack above the content, in render order, with the condition that shows
   each, and say which can be non-null at the same time.** (`Screens.kt:414-458`: status, saved copy,
   update, crash, error, action, session line.) Which of them render through `NoticeBar`
   (`Screens.kt:1576-1609`) and which are plain `Text`?
   *Why: R7 needs the current stack order and the set of simultaneously visible lines.*

5. **What clears each message state: a dismiss callback, a timeout, replacement by the next write,
   or recomposition?** In particular, which of `lastError`, `bootNotice`, `searchError`,
   `actionNotice`, and `UpdateStatus.Failed` has a user-facing dismiss today?
   *Why: R7 gives confirmations a timer and errors none; this is the per-state current behaviour.*

6. **`actionNotice`: what is its type, where is it written, what is its expiry mechanism and
   duration, and what list of callers set it?** (`Screens.kt:276-281`; callers at `:298`, `:478`, `:495`,
   `:501`.)
   *Why: R7 keeps auto-expiry for confirmations only, so its current lifetime is the baseline.*

7. **`searchError`: every write site, its read site, and every clear site.** Can the user dismiss it,
   or is it only replaced and cleared by the next search (`ChatViewModel.kt:976`, `:980`, `:1016`,
   `:1030`; rendered `Screens.kt:1208-1218`)?
   *Why: an error with no dismiss is exactly what R7 changes.*

8. **`UpdateStatus`: what are its variants, where is each constructed, and where is each rendered?**
   Is there a state that means "available but declined", and does the settings section and the chat
   banner read the same value? (`MainActivity.kt:59`, `:70-92`, `:216-221`; `Screens.kt:` settings
   update section.)
   *Why: R3 needs a dismiss that does not install, which requires knowing whether such a state exists.*

9. **`sessionNotice`: set site, clear sites, lifetime, and whether it is dismissible.**
   (`ChatViewModel.kt:525`, `:724`, `:827`; rendered `Screens.kt:450-458`.)
   *Why: R7 must decide whether the handover line joins the one channel or stays separate.*

10. **`statusLine`: every producer, how the string is composed, every reader, and the exact
    truncation applied on the chat screen versus settings.** (`ChatViewModel.kt:538-579`, `:745`,
    `:1229-1233`, `:1270`; `Diagnostics.kt:104-108`; `Screens.kt:414-420`, `:1991-1993`.)
    *Why: R8 puts the hint first and wraps to two lines; this is the current format and clip.*

## B. Navigation and back

11. **How does the app choose what is on screen, exactly?** Give the `showSettings` branch
    (`MainActivity.kt:186-212`) and the five-way if/else inside `ChatScreen`
    (`Screens.kt:462-500`) with the state variable that selects each branch.
    *Why: R2's back order is the reverse of this selection chain.*

12. **Does any back interception exist in the app, and what is the default behaviour of a single
    Activity with no back stack on system back?** Confirm with a grep over `app/src/main` for
    `BackHandler`, `onBackPressed`, `OnBackPressedDispatcher`, and any predictive-back callback.
    *Why: R2 starts from nothing, and the default finishes the Activity.*

13. **Enumerate every piece of "open or selected" state that back would close, and say which
    composable owns each and what reads it.** At least `showSettings` (`MainActivity.kt:58`),
    `searchOpen`, `paletteOpen`, `paletteDismissed`, `openedId`, the pin detail/edit state, and any
    question surface.
    *Why: R2's close order must match how the chain disposes each state, and whether any is
    unreachable from back.*

14. **What `activity-compose` version is declared, and does the resolved artifact expose
    `androidx.activity.compose.BackHandler`, `PredictiveBackHandler`, and the
    `OnBackPressedDispatcher` interop?** (`app/build.gradle.kts:74`.) Which `compileSdk`/`targetSdk`
    and which Android version gate predictive back, and is the `android:enableOnBackInvokedCallback`
    flag set anywhere?
    *Why: R2 picks between a simple `BackHandler` and the predictive-back path, and PRD 0.7's
    edge-to-edge work may need the same Activity wiring.*

## C. Compose and AndroidX capabilities actually available

15. **Resolve the exact library versions the build uses:** the Compose BOM `2024.10.01` mapping to
    `compose-ui`, `foundation`, and `material3`; plus `activity-compose 1.9.3`, `lifecycle
    2.8.7`, `core-ktx 1.13.1`, and the Kotlin/Compose compiler plugin version
    (`app/build.gradle.kts`, `settings.gradle.kts`, root `build.gradle.kts`). State the method used
    (`./gradlew :app:dependencies --offline` or the BOM POM) and whether it succeeded offline.
    *Why: every capability answer below is decided by these versions, not by memory.*

16. **For each API below, report present or absent in those exact versions, with the version that
    decides it:** `SingleChoiceSegmentedButtonRow`/`SegmentedButton` (material3);
    `Modifier.semantics { selected = … }` and `stateDescription`; `LiveRegionMode` and
    `Modifier.semantics { liveRegion = … }`; `Role.Button`; `Modifier.heading()`;
    `enableEdgeToEdge` (androidx.activity); `WindowInsets.safeDrawing` and `Modifier.imePadding`;
    `MotionDurationScale`/`LocalMotionDurationScale`; `rememberSaveableStateHolder`.
    *Why: R10 (segmented control), R15 (roles, selection, live region, headings), R19
    (edge-to-edge and insets), and R20 (animation scale) each stand or fall on one of these.*

17. **Which of those APIs are experimental in the resolved versions and need an `@OptIn`?** Which
    opt-ins does the code already carry (`Screens.kt:240`, and elsewhere)?
    *Why: an experimental-only API forces the same annotation pattern and may change the API shape.*

## D. Theme and colour tokens

18. **What roles does `deriveScheme` set, and what input does each derived value come from?**
    (`AnswerStyle.kt:152-189`.) Which `ColorScheme` roles are left at the Material baseline?
    *Why: R4 and R5 change one role and must know what else reads it and what derives from it.*

19. **Enumerate every `MaterialTheme.colorScheme.<role>` read across the UI, with per-file counts
    and each role's uses.** Which roles are read but not set by `deriveScheme`, and therefore fall
    back to the baseline scheme?
    *Why: a token change reaches only its readers, and an unset role is sourced from the fallback.*

20. **Every colour literal in the app that is not a theme scheme colour.** Include
    `Screens.kt:2421-2422` (`amber`, `green`), `AnswerStyle.chipBackground`, `diffAdd`, `diffDel`,
    `accentDark`, `accentLight`, and every `sky*` field. For each, say where it is painted.
    *Why: R4 moves the update pills into tokens; the rest are deliberate and must not move.*

21. **List every call site for `onSurfaceVariant`, `error`, `primary`, `surface`, and
    `surfaceVariant`** as `file:line`, and for each say what kind of text or surface it paints
    (hint, status, snippet, session label, error, card, field).
    *Why: R5's 4.5:1 fix must cover every secondary and error reader, and this is that set.*

22. **How does `PiRemoteTheme` choose between the light scheme and a dark palette, and how do
    `appearance` and `theme` flow from the ViewModel/store into it?** (`Screens.kt:115-140`;
    `SettingsStore` keys at `data/SettingsStore.kt:43`, `:72`.)
    *Why: R5 must fix both the dark palettes and the light scheme, and needs the selection path.*

## E. Layout and sizing facts behind the P0 fixes

23. **The exact current layout of a command palette row:** the row modifier and padding, every text
    style and its line height, the source label style, and the palette column's max height and
    item spacing. (`Screens.kt:1636-1694`, especially the row at `:1660-1668`.)
    *Why: R6 raises this row to a 48dp minimum; this is the number it is being raised from.*

24. **The exact current layout of `SearchResultRow`:** padding, the snippet style, the prompt
    style, and the prompt's `maxLines`. (`Screens.kt:1245-1263`.)
    *Why: R6's second target; same measurement.*

25. **What is each of those two rows' actual height at `fontScale` 1.0 and at 2.0?** Compute from
    the declared `sp` sizes, line heights, and padding; state whether the 2.0 value is derivable
    from source or requires a device measurement. Mark the measured value `needs-device` if so.
    *Why: R6's 48dp floor must still hold when the text doubles.*

26. **The other fixed-size spots the review flags:** the `Modifier.height(24.dp)` waiting slot
    (`Screens.kt:384-386`), the fixed bullet column (`AnswerView.kt:151`), the app bar's `maxLines = 1`
    lines (`Screens.kt:363`, `:375`), and the status line's `maxLines = 1` (`:419-420`). Give each
    line, its size, and the text style it holds.
    *Why: R15's fontScale work needs the exact list of clipped containers.*

27. **How many `heightIn(min = …)` versus fixed `height(...)`/`size(...)` modifiers are used in
    `Screens.kt` and `AnswerView.kt`?** Give counts and the fixed-size sites.
    *Why: it says which layouts already survive a scale and which are the exceptions R15 fixes.*

## F. The composer

28. **List every control in the composer in render order, and for each give its label logic, its
    enablement condition, and whether it is present conditionally.** (`Screens.kt:1741-1809`.)
    *Why: R9 pins the primary action and keeps `Stop` in `Send`'s slot; this is the current set.*

29. **Which parameters decide each control (`captureEnabled`, `commandsEnabled`, `busy`,
    `listening`, `actionEnabled`, `actionLabel`), and where does `ChatScreen` compute each one?**
    What is `vm.composerBlock`, and what exactly disables `Send`?
    *Why: R9's fixed slot layout depends on which controls are always present.*

30. **What does the action row do when the sum of the control widths exceeds the screen?** Confirm
    the `horizontalScroll`, give the current order, and say which controls are off the initial
    viewport on a narrow phone.
    *Why: R9 replaces or augments this scroll model; this is the behaviour to change.*

31. **The `/` affordance: what is its condition, its label, its semantics or
    `contentDescription`, and does it carry `tactile()`?** (`Screens.kt:1727-1739`.)
    *Why: it is both an R15 target (announcement) and one of the ticket's four press-scale fixes.*

32. **`actionLabel` and `actionEnabled`: where are they computed, what placeholder and label does
    the search mode pass, and what is the full set of conditions under which `Send` is disabled?**
    *Why: R9's primary slot must preserve these conditions while changing the position.*

## G. Notifications, services and lifecycle

33. **Does any notification, service, worker, job, alarm, or wake-lock code exist today?** Grep
    `app/src/main` for `Notification`, `Service`, `WorkManager`, `WakeLock`, `AlarmManager`,
    `JobScheduler`, `ForegroundService`. What permissions and components does
    `AndroidManifest.xml` declare?
    *Why: R13 (and PRD 0.2-0.4) starts from zero, and the manifest is the ground it builds on.*

34. **What does the bridge do when the SSE client disconnects?** Read the `/api/events` handler in
    `bridge/extensions/pi-remote.ts` for the retry directive, heartbeat or keep-alive, any replay
    buffer, and whether it honours `Last-Event-ID`. (`pi-remote.ts:1007-1022` and around.)
    *Why: a foreground service that keeps a socket alive is a different design if the bridge
    cannot replay what it missed.*

35. **Does the bridge number events with `seq`, where is that counter incremented, and does the app
    read `seq` anywhere?** Distinguish the bridge's `seq` (`pi-remote.ts:355`, `:489`, `:1017`,
    `:1020`) from the app's `searchSeq`/`outcomeSeq` (`ChatViewModel.kt:972`, `:1194`).
    *Why: PRD 0.5's replay exists on the bridge side only if the app keeps a cursor; R13 depends on
    which side already has the pieces.*

36. **Enumerate the bridge's emitted event `type` values and which of them the app consumes.**
    (Bridge `emit(...)` sites ~`pi-remote.ts:485-1687`; app dispatch at `ChatViewModel.kt:1365-1372`.)
    Which events cover "a question needs an answer" and "a run finished or failed", and does the app
    already react to both? Does the app's reconnect loop cover a socket that dies while backgrounded?
    *Why: R13's triggers must map to events that already exist or the bridge needs a new one.*

37. **What exactly do `PRODUCTION-READINESS.md` Tier 0.2, 0.3, 0.4, and 3.1 specify** (their
    acceptance criteria), and where do they overlap R13? (`docs/PRODUCTION-READINESS.md` Tier 0 and
    the Tier 3 table.)
    *Why: R13's scope is defined by the backlog entries the ticket names, not by the review alone.*

38. **Given `minSdk = 26` and `targetSdk = 35`, what runtime permission and channel APIs does a
    notification require, and what does the app currently declare for `POST_NOTIFICATIONS`?**
    *Why: R13 needs a permission flow and a channel, and neither exists yet.*

## H. State retention across screens

39. **Enumerate every piece of per-screen state and say whether it lives in the ViewModel or in
    `rememberSaveable`/`remember` inside a composable.** At least: `searchOpen`, `query`,
    `paletteOpen`, `paletteDismissed`, `actionNotice`, `openedId`, the pin title edit, the typed
    question answer, the transcript accordion/deck position, `draft`, `viewMode`, `appearance`,
    `theme`. (`Screens.kt:261-281`, `:963`, `:1041-1043`, `:2522`, `:607-608`, `:724`;
    `ChatViewModel.kt:274-275`.)
    *Why: R11 moves what is lost; this is the exact inventory of what is where.*

40. **When the settings screen is shown, does `ChatScreen` leave composition (and vice versa)?**
    (`MainActivity.kt:186-212`.) What happens to `rememberSaveable` state, plain `remember` state,
    and the `viewModel()` instance across that switch and across a configuration change?
    *Why: R11 needs to know whether the loss is a disposal of the composition or a disposal of the
    state, which decides ViewModel hoisting versus `SaveableStateHolder`.*

41. **Which of those state items already survives a restart or a screen switch because it is written
    to `SettingsStore`, and therefore does not need hoisting?** (`data/SettingsStore.kt:43-87`.)
    *Why: R11 should move only what is actually lost.*

42. **What is the scope of the `viewModel()` instance obtained in `setContent`**
    (`MainActivity.kt:49-52`)? Does it survive rotation, process death, and the settings toggle, and
    what `ViewModelStoreOwner` backs it?
    *Why: it decides whether R11 hoists into the ViewModel or into a saveable holder.*

## I. The design record and the backlog

43. **For each of `PRODUCTION-READINESS.md` 0.8, 1.7, 1.13, and 2.9, what does the document claim
    is open, and what does the code on disk actually ship?** Give the doc line and the code that
    refutes or confirms it (e.g. pairing via `net/Pairing.kt`, search via `ChatViewModel.search`,
    haptics via `ui/Tactile.kt`, crash visibility via `data/CrashLog.kt`).
    *Why: R18 updates the record to match reality, and this is the drift list.*

44. **What lab path does `AnswerStyle.kt:14-20` cite, and what is actually present on disk under
    `~/pi-remote-design-lab/out`?** List the files and their dates.
    *Why: R18 fixes the dead reference; the replacement path is whichever file the comment should
    actually name.*

45. **What design record exists in or near the repo, and where?** Find ADRs, `GLOSSARY.md`, the two
    design labs, and any retro notes. Does any of them define the UI's own vocabulary (for example
    `Cards` versus `deck`, `appearance` versus `theme`)?
    *Why: R10 and R18 need to know whether the naming is already settled somewhere authoritative.*

46. **Do the recorded lab choices corroborate or contradict the two code-versus-record divergences
    (press-scale missing on four controls; `Haptics.confirm` on copy and share)?** Read
    `~/pi-remote-design-lab/out/choices-tactile-latest.json` and any other lab file on disk.
    *Why: R18 either fixes the code or amends the decision; the record decides which.*

## J. Verification

47. **What test sources, lint configuration, and CI jobs exist today?** Confirm test source sets
    under `app/src`, any `lint.xml`/`lintOptions`, and both workflows under `.github/workflows`
    with their triggers and jobs.
    *Why: R21 and R15's proof options depend on what runs without a device.*

48. **Which `./gradlew` tasks exist, and which can run offline with the Gradle cache present on
    this machine** (`test`, `:app:testDebugUnitTest`, `lint`, `:app:lintDebug`, `assembleDebug`,
    `assembleRelease`, `:app:dependencies`)? State exactly which succeeded offline.
    *Why: it decides what the Implement stage can verify itself and what the human must check.*

49. **For each recommendation with a verification question that needs hardware, say so:** R2 (back
    gesture), R6 (touch target size), R8 (wrap), R9 (composer layout), R13 (notification from the
    lock screen), R15 (TalkBack, fontScale 2.0), R19 (Android 15 insets), R20 (system animation
    scale). Which of these can be proven by unit test, screenshot test, or static analysis, and
    which are provable only on a device or emulator?
    *Why: the ticket states the human verifies device-only claims, so each one must be labelled.*

50. **Is an Android SDK and an emulator available in this environment?** Read
    `local.properties` and the installed platforms/build-tools, and say whether an instrumentation
    test could run here at all.
    *Why: it decides whether fontScale and TalkBack proofs can be automated or must be handed to
    the human.*
