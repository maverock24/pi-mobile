# Research: navigation and state retention (questions 11 to 14 and 41 to 44)

All line numbers are against the working tree at `HEAD`. Dependency facts are read from the
resolved artifacts in `/root/.gradle/caches/8.14.3/`, not from memory.

---

## 11. What is on screen, and what selects each branch

The top-level switch is one `if`/`else` in `setContent`, keyed on one composable-owned
`rememberSaveable`:

- `MainActivity.kt:59` - `var showSettings by rememberSaveable { mutableStateOf(false) }`
- `MainActivity.kt:187` - `if (showSettings) {`
- `MainActivity.kt:188` - `SettingsScreen(`
- `MainActivity.kt:212` - `} else {`
- `MainActivity.kt:213` - `ChatScreen(`

So exactly one of `SettingsScreen` and `ChatScreen` is in composition at a time; they are
branches of the same `if`, not siblings in a stack.

Inside `ChatScreen` there is a five-way chain, `Screens.kt:462-504`, with these selectors:

- `Screens.kt:462` - `if (searchOpen) {` -> `Screens.kt:463` `SearchPanel(`.
  Selector `searchOpen`, `Screens.kt:261`
  `var searchOpen by rememberSaveable { mutableStateOf(false) }`.
- `Screens.kt:472` - `} else if (vm.viewMode == "pins") {` -> `Screens.kt:476` `PinsView(`.
  Selector `vm.viewMode`, `ChatViewModel.kt:311` `var viewMode by mutableStateOf(store.viewMode)`.
- `Screens.kt:481` - `} else if (turns.isEmpty() && pending == null) {` -> `Screens.kt:482`
  the empty `Box`. Selectors `turns` (`Screens.kt:253` `val turns = vm.turns`, backed by
  `ChatViewModel.kt:428`) and `pending` (`Screens.kt:254` `val pending = vm.pendingQuestion`,
  backed by `ChatViewModel.kt:301` `var pendingQuestion by mutableStateOf<PendingQuestion?>(null)`).
- `Screens.kt:489` - `} else if (vm.viewMode == "deck") {` -> `Screens.kt:493` `TurnDeck(`.
- `Screens.kt:498` - `} else {` -> `Screens.kt:499` `Transcript(`.

The chain has no early exit that bypasses it: the empty-`Box` branch (`:481`) sits before the
`deck` branch (`:489`), so an empty transcript with `viewMode == "deck"` renders the empty
`Box`, not the deck.

---

## 12. Back interception

`grep -rn "BackHandler\|onBackPressed\|OnBackPressedDispatcher\|PredictiveBack\|BackInvoked" app/src/main/`
returns nothing. There is no `BackHandler`, no `onBackPressed` override, no
`OnBackPressedDispatcher` callback, no predictive-back callback, and no
`android:enableOnBackInvokedCallback` anywhere in `app/src/main`.

The manifest declares one activity and no back stack:
`AndroidManifest.xml:24-31`

```
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
```

with a `MAIN`/`LAUNCHER` intent filter (`:32-35`) and a `pi-remote://pair` view filter
(`:39-45`). No `parentActivityName`, no second activity.

Default system back with no callback: the Activity finishes. `MainActivity` extends
`ComponentActivity` (`MainActivity.kt:33`, `:8`), which supplies the fallback runnable to its
dispatcher. In `activity-1.9.3-api.jar`, `ComponentActivity$onBackPressedDispatcher$2.invoke()`
constructs `new OnBackPressedDispatcher(<fallback Runnable>)`, and the fallback is
`access$onBackPressed$s1027565324`, which calls
`androidx.core.app.ComponentActivity.onBackPressed()` (decompile of that artifact); the
framework `Activity.onBackPressed()` default is the finish. The dispatcher only reaches the
fallback when no enabled callback is registered, which is the app's current state.

---

## 13. Every "open or selected" state, its owner, and its readers

Composable-owned open/selected state:

| State | Owner (declaration) | Readers / writers |
| --- | --- | --- |
| `showSettings` | `MainActivity.kt:59` `var showSettings by rememberSaveable { mutableStateOf(false) }` | read `MainActivity.kt:176`, `:187`; written `:151`, `:204`, `:210`, `:237` |
| `searchOpen` | `Screens.kt:261` | read `Screens.kt:307`, `:321`, `:322`, `:395`, `:397`, `:398`, `:462`, `:534`-`:575`; written `:395`, `:468` |
| `query` | `Screens.kt:262` `var query by rememberSaveable { mutableStateOf("") }` | read `Screens.kt:307`, `:465`, `:534`, `:547`, `:554`; written `:535`, `:553`, `:573` |
| `paletteOpen` | `Screens.kt:267` `var paletteOpen by rememberSaveable { mutableStateOf(false) }` | read via `paletteVisible` `Screens.kt:322`; written `:526`, `:562`, `:565` |
| `paletteDismissed` | `Screens.kt:271` `var paletteDismissed by rememberSaveable { mutableStateOf(false) }` | read `Screens.kt:322`; written `:324`, `:527`, `:563`, `:566` |
| `actionNotice` | `Screens.kt:276` `var actionNotice by remember { mutableStateOf<String?>(null) }` (plain `remember`, not saveable) | read `Screens.kt:447`; written `:283`, `:298` (via `onConfirm`/`onAnswerConfirmed` at `:478`, `:495`, `:501`) |
| `explicitTurn` | `Screens.kt:607` `var explicitTurn by rememberSaveable { mutableStateOf<String?>(null) }` (Transcript) | read `Screens.kt:610`; written `:638`, `:663`, `:649`-region via hit |
| `followNewest` | `Screens.kt:608` `var followNewest by rememberSaveable { mutableStateOf(true) }` (Transcript) | read `Screens.kt:610`; written `:612`, `:637`, `:662` |
| `openTurn` | `Screens.kt:724` `var openTurn by rememberSaveable { mutableStateOf<String?>(null) }` (TurnDeck) | read `Screens.kt:801`, `:805`; written `:805` |
| `heldTurn` | `Screens.kt:727` `var heldTurn by remember { mutableStateOf<String?>(null) }` (TurnDeck, plain `remember`) | read `Screens.kt:749`; written `:741`, `:762` |
| `openedId` (pin detail) | `Screens.kt:963` `var openedId by rememberSaveable { mutableStateOf<String?>(null) }` (PinsView) | read `Screens.kt:964`; written `:971`, `:989` |
| `editing` (pin edit) | `Screens.kt:1041` `var editing by rememberSaveable(pin.id) { mutableStateOf(false) }` (PinDetail) | read/written inside `PinDetail` (`:1041`-region) |
| `titleDraft` (pin title edit) | `Screens.kt:1042` `var titleDraft by rememberSaveable(pin.id) { mutableStateOf(pin.title) }` | internal to `PinDetail` |
| `confirmingDelete` (pin) | `Screens.kt:1043` `var confirmingDelete by rememberSaveable(pin.id) { mutableStateOf(false) }` | internal to `PinDetail` |
| `openSection` (settings section) | `Screens.kt:1841` `var openSection by rememberSaveable { mutableStateOf<String?>(null) }` (SettingsScreen) | read/written via `toggle` `Screens.kt:1842` |
| `confirmingClear` (settings) | `Screens.kt:1846` `var confirmingClear by remember { mutableStateOf(false) }` | internal to `SettingsScreen` |
| `typed` (question answer) | `Screens.kt:2522` `var typed by rememberSaveable { mutableStateOf("") }` (QuestionCard) | internal to `QuestionCard` |

ViewModel-owned open/selected state: `pendingQuestion` (`ChatViewModel.kt:301`), `pendingJump`
(`ChatViewModel.kt:345` `var pendingJump by mutableStateOf<String?>(null)`), `viewMode`
(`:311`), `searchResults`/`searching`/`searchError` (`:334`, `:336`, `:338`).

The question surface has no "open" flag of its own: it is rendered whenever
`vm.pendingQuestion != null`, inside `Transcript` (`Screens.kt` `if (pending != null) { item(key = "pending-question") }`)
and `TurnDeck` (`Screens.kt:713` region). Closing it is `onCancel = vm::cancelQuestion`
(`Transcript` item) and `vm.answerQuestion`, not a composable state.

Unreachable-from-back states: every row above is closed by an in-screen control or by
switching branch, never by a back callback (see 12). `vm.pendingQuestion`, `vm.pendingJump`,
`vm.searchResults`/`searching`/`searchError`, `vm.viewMode`, `vm.draft`, `vm.messages` and
`vm.pins` live in the ViewModel and no back path touches them.

---

## 14. activity-compose version and back APIs

`app/build.gradle.kts:74` `implementation("androidx.activity:activity-compose:1.9.3")`.
`compileSdk = 35` (`app/build.gradle.kts:20`), `targetSdk = 35` (`app/build.gradle.kts:41`),
`minSdk = 26` (`app/build.gradle.kts:40`).

Resolved artifact `/root/.gradle/caches/8.14.3/transforms/c823127d83998b1d4f4878b94639ed67/transformed/activity-compose-1.9.3-api.jar`
contains:

- `androidx/activity/compose/BackHandlerKt.class`, public signature
  `BackHandler(boolean, Function0<Unit>, Composer, int, int)`.
- `androidx/activity/compose/PredictiveBackHandlerKt.class`, public signature
  `PredictiveBackHandler(boolean, Function2<Flow<BackEventCompat>, Continuation<Unit>, Object>, ...)`.
- `androidx/activity/compose/LocalOnBackPressedDispatcherOwner.class`.

The interop lives in `activity-1.9.3-api.jar`
(`/root/.gradle/caches/8.14.3/transforms/01c4f262cc824e56822639fcc44f1a09/transformed/`):
`androidx/activity/OnBackPressedDispatcher.class` (with `setOnBackInvokedDispatcher(...)`,
`dispatchOnBackStarted(BackEventCompat)`, `dispatchOnBackProgressed`, `dispatchOnBackCancelled`),
`OnBackPressedCallback.class`, `OnBackPressedDispatcherOwner.class`, `BackEventCompat.class`.
`ComponentActivity` implements `OnBackPressedDispatcherOwner` and its dispatcher is built with
an API gate: `ComponentActivity$onBackPressedDispatcher$2` reads `Build.VERSION.SDK_INT` and
compares with `33` before wiring the platform dispatcher. The API split is present in the
artifact as `OnBackPressedDispatcher$Api33Impl` (uses `android.window.OnBackInvokedCallback`)
and `OnBackPressedDispatcher$Api34Impl` (uses `OnBackAnimationCallback` /
`createOnBackAnimationCallback`). So the library branches on Android 13 (API 33) for the
`OnBackInvokedDispatcher` opt-in and Android 14 (API 34) for the predictive-back animation.

The manifest flag is absent: `grep -rn "enableOnBackInvokedCallback" app/src/main/` returns
nothing, and `AndroidManifest.xml:24-31` sets only `android:name`, `android:exported` and
`android:windowSoftInputMode` on the activity.

---

## 39. Per-screen state: ViewModel or composable

ViewModel-owned (`ChatViewModel`): `draft` (`ChatViewModel.kt:270`
`var draft by mutableStateOf("")`), `pendingQuestion` (`:301`), `appearance` (`:303`),
`viewMode` (`:311`), `theme` (`:318`), `clipboardCapture` (`:326`), `searchResults` (`:334`),
`searching` (`:336`), `searchError` (`:338`), `pendingJump` (`:345`), `outcome` (`:349`),
`commands` (`:365`), `commandsNote` (`:384`), `sessionNotice` (`:400`), `messages` (`:259`),
`turns` (`:428`), `pins` (`:247` region), `busy`, `connected`, `liveAnswer`, `showingSavedCopy`.

Composable-owned: `showSettings` (`MainActivity.kt:59`), `listening` (`:60`), `partialText`
(`:61`), `notice` (`:62`), `updateStatus` (`:63`); `searchOpen` (`Screens.kt:261`), `query`
(`:262`), `paletteOpen` (`:267`), `paletteDismissed` (`:271`), `actionNotice` (`:276`);
`explicitTurn` (`:607`), `followNewest` (`:608`); `openTurn` (`:724`), `heldTurn` (`:727`);
`openedId` (`:963`), `editing` (`:1041`), `titleDraft` (`:1042`), `confirmingDelete` (`:1043`);
`baseUrl` (`:1836`), `token` (`:1837`), `openSection` (`:1841`), `confirmingClear` (`:1846`),
`pastedLink` (`:1851`), `pairingStatus` (`:1852`); `typed` (`:2522`); the sweep
`progress = remember { mutableFloatStateOf(0f) }` (`AnswerView.kt:344`).

Of the composable-owned values, those declared with `rememberSaveable` are `searchOpen`,
`query`, `paletteOpen`, `paletteDismissed`, `explicitTurn`, `followNewest`, `openTurn`,
`openedId`, `editing`, `titleDraft`, `confirmingDelete`, `showSettings`, `baseUrl`, `token`,
`openSection`, `pastedLink`, `typed`. Plain `remember`: `actionNotice`, `heldTurn`,
`confirmingClear`, `pairingStatus`, `listening`, `partialText`, `notice`, `updateStatus`,
and `progress`.

---

## 40. Does ChatScreen leave composition when settings shows

Yes. `MainActivity.kt:187` `if (showSettings) {` and `MainActivity.kt:212` `} else {` are two
branches of one `if`; only one is composed. Showing settings disposes `ChatScreen` and its whole
subtree (`SearchPanel`, `PinsView`, `TurnDeck`, `Transcript`, `Composer`), and hiding settings
disposes `SettingsScreen`.

There is no `SaveableStateHolder` or `rememberSaveableStateHolder` anywhere:
`grep -rn "SaveableStateHolder" app/src/main/java/` returns nothing. So on the settings toggle,
each `rememberSaveable` value listed in 39 is disposed with its composable; nothing re-injects
it when the branch returns. The same applies to plain `remember` values.

Across a configuration change the composition that is present at that moment is saved through
the Activity's `SavedStateRegistry` and restored (the mechanism `rememberSaveable` uses), so the
branch that is composed survives. The `viewModel()` instance at `MainActivity.kt:54` is not
recreated by a configuration change.

---

## 41. State that survives a restart or a screen switch because SettingsStore holds it

`data/SettingsStore.kt` writes these SharedPreferences keys (`prefs` at `:7`):

- `baseUrl` - `SettingsStore.kt:15-16`.
- `token` - `SettingsStore.kt:25-35`.
- `appearance` - `SettingsStore.kt:43-44` `get() = prefs.getString("appearance", "dark") ?: "dark"`.
- `clipboardCapture` - `SettingsStore.kt:52-53`.
- `viewMode` - `SettingsStore.kt:62-63` `get() = prefs.getString("viewMode", "transcript") ?: "transcript"`.
- `theme` - `SettingsStore.kt:72-73`.
- `sessionId` - `SettingsStore.kt:81-82`.
- `sessionName` - `SettingsStore.kt:87-88`.

The ViewModel fields that are initialised from the store and written back on change are
`appearance` (`ChatViewModel.kt:303`, `:941`), `viewMode` (`:311`, `:953`), `theme` (`:318`,
`:959`), `clipboardCapture` (`:326`, `:947`), plus `attachedSessionId` and
`sessionTitle`/`sessionName` (`:390`-region). Because they are read from the store at ViewModel
construction and written through on every change, they survive both a screen switch and a
process restart.

Nothing else in 39 is written to `SettingsStore`. `draft`, `pendingJump`, `searchResults`,
`pendingQuestion`, `sessionNotice`, `commands` and `outcome` are ViewModel-only; `searchOpen`,
`query`, `paletteOpen`, `paletteDismissed`, `openedId`, `explicitTurn`, `followNewest`,
`openTurn` and the pin/editing fields are composable-only and not persisted.

---

## 42. Scope of the `viewModel()` at MainActivity.kt:54

`MainActivity.kt:54` `val vm: ChatViewModel = viewModel()` is called inside
`setContent {` (`MainActivity.kt:53`) with no explicit `ViewModelStoreOwner`, so it uses
`LocalViewModelStoreOwner.current`. `MainActivity` is a `ComponentActivity`
(`MainActivity.kt:33` `class MainActivity : ComponentActivity()`), which implements
`androidx.lifecycle.ViewModelStoreOwner` and holds a `_viewModelStore`
(artifact `activity-1.9.3-api.jar`, `androidx.activity.ComponentActivity`:
`implements ... androidx.lifecycle.ViewModelStoreOwner`, field `private ViewModelStore _viewModelStore`,
`public ViewModelStore getViewModelStore()`). `activity-compose-1.9.3` also provides
`androidx.activity.compose.ComponentActivityKt.setOwners(ComponentActivity)`, which installs the
view-tree owners so `viewModel()` in `setContent` resolves to that same store; and
`lifecycle-viewmodel-compose-2.8.7` supplies
`androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner`.

Consequences: the instance is keyed to the Activity's `ViewModelStore`; it survives a
configuration change (rotation) and the `showSettings` toggle (the store is not cleared while
the Activity lives), and it does not survive process death. `ChatViewModel` is an
`AndroidViewModel` (`ChatViewModel.kt:242` `class ChatViewModel(app: Application) : AndroidViewModel(app)`)
and uses no `SavedStateHandle` (`grep -n "SavedStateHandle" ChatViewModel.kt` returns nothing).

---

## 43. PRODUCTION-READINESS claims versus the code on disk

- **0.8 Pairing by QR code** - `docs/PRODUCTION-READINESS.md:49` acceptance:
  "app scans it with CameraX and fills Settings; manual entry still works". On disk the scan
  ships, but not with CameraX: `app/build.gradle.kts:81`
  `implementation("com.journeyapps:zxing-android-embedded:4.3.0")`, and the scanner is ZXing
  (`Screens.kt:107` `import com.journeyapps.barcodescanner.ScanContract`, launch sites
  `Screens.kt:1866`, `:1903`). `Pairing.kt` exists (`net/Pairing.kt:24` `object Pairing`,
  `:44` `fun parse`, `:61` `suspend fun exchange`), the link is handed off at
  `Screens.kt:1859` `onPairLink(scanned)`. So the item ships; the named mechanism does not.
- **1.7 History search** - `docs/PRODUCTION-READINESS.md:65` claims "no way back". On disk
  search ships: `ChatViewModel.kt:970` `fun search(query: String)`, `:1026` `fun clearSearch()`,
  `:1092` `fun jumpToTurn(turnId: String)`, rendered by `SearchPanel` (`Screens.kt:1191`).
- **1.13 Haptics and motion** - `docs/PRODUCTION-READINESS.md:71` acceptance: "Haptic tick on
  send, on option tap, and on run completion; animations honour 'reduce motion'". Haptics ship:
  `ui/Tactile.kt:47` `object Haptics`, `:62` `fun confirm`, `:76` `fun reject`; outcome wiring
  `Screens.kt:339` `if (event.ok) Haptics.confirm(view) else Haptics.reject(view)`. The
  reduce-motion half is absent: `grep -rn "durationScale\|MotionDurationScale\|reduceMotion\|Animator\|Settings.Global" app/src/main/` returns nothing.
- **2.9 Crash and ANR visibility** - `docs/PRODUCTION-READINESS.md:85` acceptance: "Opt-in,
  self-hosted or privacy-preserving crash reporting, or at minimum a 'send diagnostics' action
  that packages the last error". On disk there is a local crash log and an in-app view, but no
  reporting and no send/share action: `data/CrashLog.kt:84` `fun recordCrash`,
  `:106` `fun lastCrash()`, `:111` `fun clearLastCrash`; shown in `Screens.kt:2129`
  `lastCrash?.let { trace ->` under the Troubleshooting section (`Screens.kt:2103`
  `title = "Troubleshooting"`). The only `Share` use is for an answer (`Screens.kt:1438`
  `context.startActivity(Intent.createChooser(send, "Share answer"))`), not diagnostics.

---

## 44. The lab path cited by AnswerStyle.kt

`AnswerStyle.kt:14-16` reads:

```
 * Design tokens for rendering answers, taken from the design lab choices saved
 * on 2026-10-04 (pi-remote-design-lab/out/choices-latest.json):
```

The cited relative path `pi-remote-design-lab/out/choices-latest.json` does not exist under
`~/pi-remote-design-lab/out`. The files present are:

- `choices-tactile-2026-10-05T12-35-38.json` (570 bytes, Oct 5 12:35)
- `choices-tactile-latest.json` (570 bytes, Oct 5 12:35)
- `lab-token` (65 bytes, Oct 4 13:47, mode 0600)
- `requests.md` (0 bytes, Oct 4 13:47)

`choices-tactile-latest.json` holds `savedAt: "2026-10-05T12:35:38+0300"` and five tactile
choices (`press_motion`, `tap_haptic`, `outcome_haptic`, `surfaces`, `mechanism`); it is a
different document from the layout/typography choices the comment enumerates (`layout document`,
`font sans`, `scale compact`), and it carries no `2026-10-04` record. No file named
`choices-latest.json` is present.
