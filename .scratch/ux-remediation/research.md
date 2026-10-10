# Research

Merged from the six cluster files in `research/`. All line numbers are against `e976063`.

## 1. Every mechanism that puts a message in front of the user

- `MainActivity.notice`: decl `MainActivity.kt:62`; 9 writes `:94`, `:96`, `:114`, `:124`,
  `:142`, `:145`, `:150`, `:153`, `:231`; no read; no dismiss; cleared only by the next write
  to itself, and never rendered.
- `ChatViewModel.lastError`: decl `ChatViewModel.kt:299`; writes `:749`, `:758`, `:845`,
  `:1205`, `:1220`, `:1334`, `:1359`; read/render `Screens.kt:444-446` (NoticeBar, `isError`);
  dismiss `dismissError` (`ChatViewModel.kt:1238`); also `clearCrashState` (`:1269`).
- `bootNotice`: decl `ChatViewModel.kt:236`; write `:500-508` (only when
  `PiRemoteApp.startupSafeMode`, `:490`); read `Screens.kt:441-443`; dismiss
  `dismissBootNotice` (`ChatViewModel.kt:1243`); also `:1268`.
- `searchError`: decl `ChatViewModel.kt:338`; write `:1016`; read `Screens.kt:1200-1207`;
  no dismiss; cleared `:976`, `:980`, `:1030`.
- `outcome`: decl `ChatViewModel.kt:349`; writes `:1194`, `:1207`, `:1325`, `:1336`; read
  `Screens.kt:335-339` (haptic only, no text); replaced by next write, no dismiss/timeout.
- `actionNotice`: decl `Screens.kt:276`; writes `:298`, `:478`, `:495`, `:501`; read
  `Screens.kt:447-448`; dismiss or 2 s timeout `:277-281`.
- `UpdateStatus`: decl `Screens.kt:2203-2215`; constructed `MainActivity.kt:63`, `:70`, `:74`,
  `:78`, `:82`, `:100`; rendered settings `Screens.kt:2055-2080`, `:2086-2096`, `:2218-2222`,
  chat banner via `MainActivity.kt:217`; replaced by next check, no dismiss.
- `statusLine`: decl `ChatViewModel.kt:297`; producers `:538`, `:541`, `:549`, `:565-567`,
  `:579`, `:745`, `:1229`, `:1232`, `:1233`, `:1270`; readers `Screens.kt:414-420` (chat) and
  `:1990-1992` (settings); no dismiss.
- `sessionNotice`: decl `ChatViewModel.kt:400`; set `:724`; clear `:525`, `:827`; read
  `Screens.kt:453-459`; no dismiss.
- `commandsNote`: decl `ChatViewModel.kt:384`; write `:1085`; read via `Screens.kt:523` and
  rendered `:1649-1651`; no dismiss; cleared `:1074`, `:531`.
- `lastCrash` / `quarantinedData`: decl `ChatViewModel.kt:240`, `:244`; writes `:255`, `:256`,
  `:499`; read `MainActivity.kt:207-208`, rendered `Screens.kt:2104-2106`, `:2116-2121`,
  `:2129-2145`; cleared by `clearCrashState` `:1266-1267`.
- `showingSavedCopy`: decl `ChatViewModel.kt:266`; writes `:468`, `:717`, `:823`, `:862`,
  `:1112`, `:1262`; read `Screens.kt:427-434`; no dismiss.
- `composerBlock`: `ChatViewModel.kt:1278` `get() = if (connected) null else "Not connected"`;
  read as composer placeholder `Screens.kt:531-535`, `:1772`.
- `pairingStatus`: decl `Screens.kt:1852`; writes `:1856`, `:1858`, `:1868`; read `:1891-1893`,
  `:1939-1941`; no dismiss; lost when `SettingsScreen` leaves composition.
- `SessionNotice` (capitalised) does not exist anywhere under `app/src/main`; the symbol in
  that role is `ChatViewModel.sessionNotice` (`ChatViewModel.kt:400`). `pairingStatus` and
  `bootNotice` exist as named.

## 2. Writer with no reader, or reader with no writer

`MainActivity.notice` is the writer with no reader. It has nine write sites
(`MainActivity.kt:94`, `:96`, `:114`, `:124`, `:142`, `:145`, `:150`, `:153`, `:231`). The
tenth `notice =` occurrence, `MainActivity.kt:217`, is the `ChatScreen` named argument and does
not touch the variable. No composable or callback reads it; `onDismissNotice`
(`MainActivity.kt:237-242`) reads only `updateStatus`. No reader-without-writer was found among
the cluster-A states; every read state has at least one write.

## 3. The exact `ChatScreen(notice = …)` expression

`MainActivity.kt:217-219`:

```kotlin
notice = (updateStatus as? UpdateStatus.Available)?.let {
    "Update ${it.info.versionName} ready — tap to install"
},
```

It is non-null only while `updateStatus is UpdateStatus.Available`. Because it ignores
`MainActivity.notice`, every write to that variable is unreachable at the UI:
`MainActivity.kt:94`, `:96` (install results), `:114` (any `Dictation` error, `Dictation.kt:49`,
`:84`, `:95`, `:122`, `:130`), `:124`, `:231` (dictation), `:142`, `:145`, `:150`, `:153`
(pairing). Silent failures therefore include "That link is not a pairing code" (`:142`),
"Pairing failed: …" (`:153`), "Microphone permission is required for dictation" (`:124`),
"Could not start dictation: …" (`:231`), and every `Dictation` `onError` string (`:114`). The
pairing success line `"Paired as ${paired.device}"` (`:150`) is also invisible; that path sets
`showSettings = false` (`:151`), so the settings `pairingStatus` text is not on screen either.

## 4. Bars that can stack above the content

`Column` at `Screens.kt:406`, top to bottom:

1. Status line `:414-420`, condition `!vm.connected && vm.statusLine.isNotBlank()`, plain `Text`.
2. Saved-copy line `:427-434`, condition `!vm.connected && vm.showingSavedCopy`, plain `Text`.
3. Update banner `:435-437`, condition `notice != null` (the parameter from
   `MainActivity.kt:217`), `NoticeBar`.
4. Boot/crash banner `:441-443`, condition `bootNotice != null`, `NoticeBar`.
5. Error banner `:444-446`, condition `vm.lastError != null`, `NoticeBar(isError = true)`.
6. Action banner `:447-449`, condition `actionNotice != null`, `NoticeBar`.
7. Session line `:453-459`, condition `vm.sessionNotice != null`, plain `Text`.

`NoticeBar` is `Screens.kt:1576-1609`; items 3, 4, 5, 6 render through it, items 1, 2, 7 are
plain `Text`. The seven conditions are independent state reads with no mutual exclusion, so all
seven can be non-null in one frame.

## 5. What clears each message state

- Dismiss callback: `lastError` (`Screens.kt:445` -> `ChatViewModel.kt:1237-1239`),
  `bootNotice` (`Screens.kt:442` -> `ChatViewModel.kt:1242-1244`), the update `notice`
  (`Screens.kt:436`, dismiss installs), `actionNotice` (`Screens.kt:448`).
- Timeout: `actionNotice` only, 2000 ms (`Screens.kt:277-281`).
- Replacement by next write: `lastError`, `bootNotice`, `searchError`, `updateStatus`,
  `statusLine`, `sessionNotice`, `commandsNote`, `pairingStatus`, `outcome`.
- `searchError`: no user dismiss; cleared only by a new search (`ChatViewModel.kt:976`, `:980`,
  `:1030`) or replaced (`:1016`), rendered as plain `Text` (`Screens.kt:1200-1207`).
- `UpdateStatus.Failed`: no user dismiss; replaced by the next `checkForUpdates`
  (`MainActivity.kt:70`) or failure (`:82`, `:100`); visible only in settings.
- `sessionNotice`: no dismiss, no timer; cleared `ChatViewModel.kt:827`, `:525`.
- States with a user-facing dismiss today: `lastError`, `bootNotice`, the update `notice`,
  `actionNotice`. Without one: `searchError`, `UpdateStatus.Failed`, `sessionNotice`,
  `commandsNote`, `statusLine`, `pairingStatus`, `lastCrash`/`quarantinedData`.

## 6. `actionNotice`

Type `String?` (`Screens.kt:276`, plain `remember`, not saveable). Expiry
`LaunchedEffect(actionNotice)` `delay(2000)` then `actionNotice = null` (`Screens.kt:277-281`);
every new value restarts it. Writes: `Screens.kt:298` `"Copied text added to prompt"` (clipboard
capture), `:478` `onConfirm` (PinsView), `:495` `onAnswerConfirmed` (TurnDeck), `:501`
`onAnswerConfirmed` (Transcript). Strings produced at `Screens.kt:1079` `"Title saved"`, `:1135`
`"Prompt sent"`, `:1156` `"Pin deleted"`, `:1422` `"Answer copied"`, `:1440` `"Answer ready to
share"`, `:1499`/`:1520` `"Prompt pinned"`/`"Already pinned"`. Read/render `Screens.kt:447-448`.

## 7. `searchError`

Decl `ChatViewModel.kt:338`. Write `ChatViewModel.kt:1016` (404 means "this bridge has no search
yet; reload it on the laptop", else `Diagnostics.describe`). Clears `:976` (blank query), `:980`
(search start), `:1030` (`clearSearch`). Read/render `Screens.kt:1200-1207` (plain `Text`, no
dismiss) and gates the empty state at `Screens.kt:1221`. Not user-dismissible; only a new or
cleared search changes it.

## 8. `UpdateStatus`

Variants `Screens.kt:2203-2215`: `Checking` (`:2205`), `UpToDate(versionName)` (`:2208`),
`Available(info)` (`:2211`), `Failed(message)` (`:2214`). Constructed only in `MainActivity`:
`:70` `Checking`, `:74` `Available`, `:78` `UpToDate`, `:82`/`:100` `Failed`. Rendered settings
`Screens.kt:2055-2080` (with `Install update` on `Available`), `:2086-2096`, `:2218-2222`; chat
banner via `MainActivity.kt:217` after `as? UpdateStatus.Available`. No declined/dismissed
variant exists; `Available` carries only `UpdateChecker.Info`. Settings and banner read the same
value declared at `MainActivity.kt:63` and passed to both (`:198`, `:217`).

## 9. `sessionNotice`

Decl `ChatViewModel.kt:400`. Set `ChatViewModel.kt:724` `"The bridge moved to $label; this screen
follows it"`, inside `adoptSession`, only when `moved` (`:719`). Cleared `:827` (end of successful
`reloadHistory`) and `:525` (`forgetSession`). Read/render `Screens.kt:453-459` (plain `Text`,
`onSurfaceVariant`). No dismiss, no timer.

## 10. `statusLine`

Decl `ChatViewModel.kt:297`. Producers: `:538` "Add the bridge token in Settings"; `:541`
`"connecting to ${store.baseUrl}"`; `:549` "connected"; `:565-567` on close, "disconnected" or
`Diagnostics.describe`; `:579` open failure `Diagnostics.describe`; `:745`
`listOf(if (connected) "connected" else statusLine, model).joinToString(" · ")`; `:1229`
`Diagnostics.probe`; `:1232` `" · HTTP OK"`; `:1233` `" · <describe>"`; `:1270` token message
(`clearCrashState`). Shared builder `Diagnostics.describe` (`Diagnostics.kt:101-104`) returns
`"failed · $baseUrl · $detail · ${hint(error)}"`; `hint` at `Diagnostics.kt:77-99`. Readers:
`Screens.kt:414-420` chat, `maxLines = 1` + ellipsis; settings `Screens.kt:1990-1992`, no
`maxLines`, wraps to column width; passed at `MainActivity.kt:191`. No dismiss, never nulled.

## 11. How the app chooses what is on screen

`showSettings` is a single `rememberSaveable` (`MainActivity.kt:59`). `MainActivity.kt:187`
`if (showSettings)` -> `SettingsScreen` (`:188`), `:212` `else` -> `ChatScreen` (`:213`); exactly
one is in composition. Inside `ChatScreen`, five-way chain `Screens.kt:462-504`:
`searchOpen` (`:261`) -> `SearchPanel` (`:463`); `vm.viewMode == "pins"` (`ChatViewModel.kt:311`)
-> `PinsView` (`:476`); `turns.isEmpty() && pending == null` (`turns` `Screens.kt:253`,
`pending` `:254`) -> empty `Box` (`:482`); `vm.viewMode == "deck"` -> `TurnDeck` (`:493`); else
`Transcript` (`:499`). The empty-`Box` branch (`:481`) precedes `deck` (`:489`), so an empty
transcript in deck mode renders the empty `Box`.

## 12. Back interception

`grep -rn "BackHandler|onBackPressed|OnBackPressedDispatcher|PredictiveBack|BackInvoked"` over
`app/src/main/` returns nothing: no callback and no `android:enableOnBackInvokedCallback`.
`AndroidManifest.xml:24-31` declares one activity (`android:name`, `android:exported`,
`android:windowSoftInputMode` only), `MAIN`/`LAUNCHER` filter `:32-35`, `pi-remote://pair` filter
`:39-45`; no `parentActivityName`, no second activity. `MainActivity` is a `ComponentActivity`
(`MainActivity.kt:33`); `activity-1.9.3` builds its dispatcher with a fallback runnable that
calls framework `onBackPressed()`, whose default is finish. With no enabled callback registered,
system back finishes the Activity.

## 13. Every "open or selected" state, its owner, its readers

Composable-owned (declaration -> readers/writers):

| State | Decl | Notes |
| --- | --- | --- |
| `showSettings` | `MainActivity.kt:59` | read `:176`, `:187`; written `:151`, `:204`, `:210`, `:237` |
| `searchOpen` | `Screens.kt:261` | read `:307`, `:321`, `:322`, `:395`, `:397`, `:398`, `:462`, `:534-575`; written `:395`, `:468` |
| `query` | `Screens.kt:262` | read `:307`, `:465`, `:534`, `:547`, `:554`; written `:535`, `:553`, `:573` |
| `paletteOpen` | `Screens.kt:267` | read via `paletteVisible` `:322`; written `:526`, `:562`, `:565` |
| `paletteDismissed` | `Screens.kt:271` | read `:322`; written `:324`, `:527`, `:563`, `:566` |
| `actionNotice` | `Screens.kt:276` (plain `remember`) | read `:447`; written `:283`, `:298`, plus `:478`, `:495`, `:501` |
| `explicitTurn` | `Screens.kt:607` (Transcript) | read `:610`; written `:638`, `:663`, `:649` |
| `followNewest` | `Screens.kt:608` (Transcript) | read `:610`; written `:612`, `:637`, `:662` |
| `openTurn` | `Screens.kt:724` (TurnDeck) | read `:801`, `:805`; written `:805` |
| `heldTurn` | `Screens.kt:727` (TurnDeck, plain `remember`) | read `:749`; written `:741`, `:762` |
| `openedId` (pin detail) | `Screens.kt:963` (PinsView) | read `:964`; written `:971`, `:989` |
| `editing` (pin) | `Screens.kt:1041` `rememberSaveable(pin.id)` | internal to `PinDetail` |
| `titleDraft` (pin) | `Screens.kt:1042` `rememberSaveable(pin.id)` | internal |
| `confirmingDelete` (pin) | `Screens.kt:1043` `rememberSaveable(pin.id)` | internal |
| `openSection` | `Screens.kt:1841` (SettingsScreen) | read/written via `toggle` `:1842` |
| `confirmingClear` | `Screens.kt:1846` (plain `remember`) | internal |
| `typed` (question answer) | `Screens.kt:2522` (QuestionCard) | internal |

ViewModel-owned: `pendingQuestion` (`ChatViewModel.kt:301`), `pendingJump` (`:345`), `viewMode`
(`:311`), `searchResults`/`searching`/`searchError` (`:334`, `:336`, `:338`). The question
surface has no open flag; it renders while `vm.pendingQuestion != null` inside `Transcript` and
`TurnDeck` (`Screens.kt:713` region), closed by `vm::cancelQuestion`/`vm.answerQuestion`. Every
state above is closed by an in-screen control or a branch switch; no back callback touches any.
`viewMode`, `pendingQuestion`, `pendingJump`, `searchResults`/`searching`/`searchError`,
`draft`, `messages`, `pins` live in the ViewModel and no back path touches them.

## 14. `activity-compose` version and back APIs

Declared `androidx.activity:activity-compose:1.9.3` at `app/build.gradle.kts:71`. The two inputs
disagree on the line: `3-capabilities-verification.md` cites `:71`, `2-navigation-state.md` cites
`:74` (which is the Compose BOM on disk). `compileSdk = 35` (`app/build.gradle.kts:20`),
`minSdk = 26` (`:24`), `targetSdk = 35` (`:25`); `2-navigation-state.md` cites `:40`/`:41`, which
on disk are `buildTypes`/`release`, not the SDK levels.

Resolved `activity-compose-1.9.3-api.jar` exposes `androidx/activity/compose/BackHandlerKt`
(`BackHandler(boolean, Function0<Unit>, …)`), `PredictiveBackHandlerKt`
(`PredictiveBackHandler(boolean, Function2<Flow<BackEventCompat>, …>)`), and
`LocalOnBackPressedDispatcherOwner`. `activity-1.9.3-api.jar` has `OnBackPressedDispatcher`
(with `setOnBackInvokedDispatcher`, `dispatchOnBackStarted/Progressed/Cancelled`),
`OnBackPressedCallback`, `OnBackPressedDispatcherOwner`, `BackEventCompat`. `ComponentActivity`
gates the platform dispatcher on API 33 and has `Api33Impl`/`Api34Impl`. The manifest flag
`android:enableOnBackInvokedCallback` is absent.

## 15. Resolved library versions

Method: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline :app:dependencies
--configuration debugRuntimeClasspath` succeeded (`BUILD SUCCESSFUL in 16s`); then the BOM POM
and cache directories.

- AGP 8.7.3 (`build.gradle.kts:2`), Kotlin 2.0.21 (`:3`), Compose compiler plugin 2.0.21
  (`:4`), Gradle 8.14.3 (`gradle/wrapper/gradle-wrapper.properties:3`).
- `androidx.core:core-ktx:1.13.1` (`app/build.gradle.kts:70`).
- `androidx.activity:activity-compose:1.9.3` (`:71`).
- `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7` (`:72`), `lifecycle-viewmodel-compose:2.8.7`
  (`:73`).
- `androidx.compose:compose-bom:2024.10.01` (`:74`), `ui` (`:75`), `material3` (`:76`).
- BOM pins: material3 1.3.1; ui 1.7.5; foundation 1.7.5; runtime 1.7.5; runtime-saveable 1.7.5.
  Resolved: `ui:1.7.5` (and `ui-android`, `ui-text`, `ui-graphics`, `ui-unit`, `ui-util`),
  `foundation:1.7.5`/`foundation-layout:1.7.5`, `material3:1.3.1`/`material3-android:1.3.1`,
  `runtime:1.7.5`/`runtime-saveable:1.7.5`, `activity:1.9.3`.
- Compose compiler is the Kotlin 2.0.21 plugin (`build.gradle.kts:4`); no
  `kotlinCompilerExtensionVersion`. `settings.gradle.kts` declares only `google()`/`mavenCentral()`.

## 16. API presence in those versions

| API | Present? | Deciding artifact |
| --- | --- | --- |
| `SingleChoiceSegmentedButtonRow` / `SegmentedButton` | present | material3 1.3.1 (`SegmentedButtonKt`, `SegmentedButtonDefaults`) |
| `semantics { selected = … }` | present | ui 1.7.5 (`SemanticsPropertiesKt.setSelected`) |
| `stateDescription` | present | ui 1.7.5 (`setStateDescription`) |
| `LiveRegionMode` / `semantics { liveRegion = … }` | present | ui 1.7.5 (`LiveRegionMode`, `setLiveRegion`) |
| `Role.Button` | present | ui 1.7.5 (`Role$Companion.getButton`) |
| `Modifier.heading()` | present as `heading(SemanticsPropertyReceiver)` inside `semantics { }`; no separate `Modifier.heading()` | ui 1.7.5 |
| `enableEdgeToEdge` | present | activity 1.9.3 (`EdgeToEdge.enable`, JvmName) |
| `WindowInsets.safeDrawing` | present | foundation-layout 1.7.5 (`WindowInsets_androidKt.getSafeDrawing`) |
| `Modifier.imePadding` | present | foundation-layout 1.7.5 (`WindowInsetsPadding_androidKt.imePadding`) |
| `MotionDurationScale` | present | ui 1.7.5 (`MotionDurationScale`, `MotionDurationScaleImpl`) |
| `LocalMotionDurationScale` | absent | no such symbol in ui 1.7.5; `CompositionLocalsKt` has none |
| `rememberSaveableStateHolder` | present | runtime-saveable 1.7.5 (`SaveableStateHolderKt`) |

## 17. Experimental APIs and existing opt-ins

None of the Q16 APIs carries an opt-in marker in the resolved versions (checked with `javap -v`
and `strings`): material3 1.3.1 `SegmentedButtonKt` is non-experimental; the only
`ExperimentalComposeUiApi` in `SemanticsPropertiesKt` is on `invisibleToUser`; `getSafeDrawing`
and `imePadding` are free (other `*IgnoringVisibility` members carry `ExperimentalLayoutApi`);
`MotionDurationScale`, `EdgeToEdge`, `SaveableStateHolderKt` are non-experimental.

Existing opt-ins, all in `Screens.kt`: `@OptIn(ExperimentalMaterial3Api::class,
ExperimentalFoundationApi::class)` on `ChatScreen` (`:240`); `@OptIn(ExperimentalFoundationApi::class)`
on `Transcript` (`:592`); `@OptIn(ExperimentalMaterial3Api::class)` on `SettingsScreen` (`:1813`).
Import `Screens.kt:11`. No other file carries an opt-in.

## 18. Roles `deriveScheme` sets, and what is left at baseline

`deriveScheme` (`AnswerStyle.kt:151-187`) reads six inputs off `base`: `accent = base.primary`
(`:152`), `surface = base.surface` (`:153`), `background = base.background` (`:154`),
`foreground = base.onBackground` (`:155`), `error = base.error` (`:156`), and `onFill = if
(error.luminance() > 0.5f) Color(0xFF14181F) else Color(0xFFF6F8FB)` (`:159`). It sets 25 roles
via `base.copy(...)` (`:161-185`): `primaryContainer` (`:161`, `lerp(surface, accent, 0.22f)`),
`onPrimaryContainer` (`:162`, `foreground`), `inversePrimary` (`:163`, `accent`), `surfaceTint`
(`:164`, `accent`), `secondary` (`:165`, `base.surfaceVariant`), `onSecondary` (`:166`,
`base.onSurfaceVariant`), `secondaryContainer` (`:167`, `lerp(surface, accent, 0.14f)`),
`onSecondaryContainer` (`:168`, `foreground`), `tertiary` (`:169`, `accent`), `onTertiary`
(`:170`, `base.onPrimary`), `tertiaryContainer` (`:171`, `lerp(surface, accent, 0.30f)`),
`onTertiaryContainer` (`:172`, `foreground`), `errorContainer` (`:173`, `lerp(surface, error,
0.20f)`), `onErrorContainer` (`:174`, `foreground`), `onError` (`:175`, `onFill`),
`inverseSurface` (`:176`, `surface`), `inverseOnSurface` (`:177`, `background`), `scrim`
(`:178`, `Color(0xFF000000)`), `surfaceDim` (`:179`, `background`), `surfaceBright` (`:180`,
`lerp(surface, foreground, 0.05f)`), `surfaceContainerLowest` (`:181`, `background`),
`surfaceContainerLow` (`:182`, `lerp(background, surface, 0.25f)`), `surfaceContainer` (`:183`,
`lerp(background, surface, 0.5f)`), `surfaceContainerHigh` (`:184`, `lerp(background, surface,
0.75f)`), `surfaceContainerHighest` (`:185`, `surface`).

The other 11 roles are passed by the caller: every dark palette
(`darkColorScheme` midnight `:205-220`, indigo `:230-245`, amber `:255-270`, forest `:280-295`)
and `lightColorScheme` (`:321-334`) supply `primary`, `onPrimary`, `background`, `onBackground`,
`surface`, `onSurface`, `surfaceVariant`, `onSurfaceVariant`, `outline`, `outlineVariant`,
`error`. 25 derived + 11 named = the 36 roles material3 1.3.1 `ColorScheme` has. No role is left
at the Material baseline; every baseline default is overwritten by the copy.

## 19. Every `MaterialTheme.colorScheme.<role>` read

`Screens.kt` (93 reads; `val scheme = MaterialTheme.colorScheme` at `:135`):

- `onSurfaceVariant` (33): 374, 418, 431, 457, 486, 941, 979, 1016, 1218, 1226, 1265, 1413,
  1566, 1652, 1677, 1722, 1919, 1963, 1975, 1997, 2025, 2052, 2059, 2119, 2126, 2142, 2191,
  2287, 2301, 2361, 2444, 2451, 2543. All secondary text or a tint.
- `primary` (12): 191, 389, 939, 1454, 1568, 1687, 1735, 2267, 2273, 2341, 2461, 2562. Accents,
  active labels, icon squares, pills, check lines.
- `outline` (9): 221, 1001, 1113, 1546, 2311, 2347, 2393, 2426, 2529. Borders and hairlines.
- `surfaceVariant` (6): 192, 1000, 1112, 1545, 2309, 2528. Surfaces.
- `onBackground` (5): 1011, 1121, 1259, 1558, 1671. Primary text.
- `error` (5): 1149, 1204, 2062, 2066, 2151. Error text or border.
- `surface` (3): 354, 2397, 2424.
- Singletons: `errorContainer` 1580, `onErrorContainer` 1585, `secondaryContainer` 1582,
  `onSecondaryContainer` 1587, `outlineVariant` 2323, `onSurface` 2136, `onPrimary.copy` 2593.
- Through `scheme`: `primary` 868, 884, 1317, 1333; `onBackground` 135, 876, 1325;
  `surfaceVariant` 842, 1300; `outline` 843, 1301; `background` 155, 1298.

`AnswerView.kt` (24): direct `onPrimary.copy` `:244`; via `scheme` (`:91`) `onBackground` 107,
115, 157, 175, 280, 307, 321; `surfaceVariant` 167, 299, 373 (x2); `onSurfaceVariant` 131, 148,
210, 261; `primary` 92, 125, 373; `outline` 168, 195; `background` 93, 255; `outlineVariant`
314. `Tactile.kt:114` reads `outline` as the default `edge` of `Modifier.tactile`.

`onSurfaceVariant`, `primary`, `error`, `surface`, `surfaceVariant` are read but not set by
`deriveScheme`; they are among the 11 roles the palette constructors pass explicitly, so none
sources from the baseline.

## 20. Colour literals that are not scheme colours

`Screens.kt`: `amber = Color(0xFFF0A83C)` (`:2421`), `green = Color(0xFF46C97E)` (`:2422`),
painted by `Pill` at `:2462-2465` (text `:2494`, fill `color.copy(alpha = 0.16f)` `:2489`);
comment `:2419-2420` says they are deliberately not theme colours. `Color.Transparent` at `:164`,
`:171`, `:203`, `:345`, `:1875`, `:2346`. `SolidColor(Color.Black)` at `:1362`, `:1386` (icon
vector fills, replaced by `tint` at `:1429`/`:1447`/`:1455`, so never painted). ThemeSwatch uses
`palette.scheme.*` at `:2377`, `:2378`, `:2385` (palette colours, not new literals).

`AnswerStyle.kt`: `accentDark = Color(0xFF0AD6FF)` (`:134`, midnight `primary` `:209`);
`accentLight = Color(0xFF3562D6)` (`:135`, light `primary` `:323`); `onFill` (`:159`, becomes
`onError` `:175`, read by no app code); `scrim = Color(0xFF000000)` (`:178`, read by no app
code); sky fields painted only by `skyBackground` (`Screens.kt:153-176`), `skyTop`/`skyMid`/
`skyBottom` `:159`, `skyHorizonGlow` `:164`, `skyMiddleGlow` `:171`, literals midnight `:222-226`,
indigo `:247-251`, amber `:272-276`, forest `:297-301`; `chipBackground` (`:338-339`) painted at
`AnswerView.kt:403`; `diffAdd` (`:341`) at `AnswerView.kt:271`, `:283`; `diffDel` (`:343`) same.
`onPrimary = Color.White` (`:324`) is a scheme input.

`AnswerView.kt`: `Color.Transparent` at `:273` (CONTEXT background applied `:283`).

## 21. Call sites for `onSurfaceVariant`, `error`, `primary`, `surface`, `surfaceVariant`

- `onSurfaceVariant`: Screens.kt 374, 418, 431, 457, 486, 941, 979, 1016, 1218, 1226, 1265,
  1413, 1566, 1652, 1677, 1722, 1919, 1963, 1975, 1997, 2025, 2052, 2059, 2119, 2126, 2142,
  2191, 2287, 2301, 2361, 2444, 2451, 2543; AnswerView.kt 131 (quote), 148 (bullet marker), 210
  ("Link"/"Links"), 261 (diff path). Paints secondary text, a status, a snippet, a session label,
  help text, or an icon tint, never a background.
- `error`: Screens.kt 1149 (destructive confirmation), 1204 (`searchError`), 2062 (Failed
  `StatusCard` border), 2066 (Failed message), 2151 (clear-data warning). Text or a border.
- `primary`: Screens.kt 191 (working edge), 389 ("waiting"), 939 (ViewModeSwitch active), 1454
  (pinned icon), 1568 (answered check), 1687 (palette source), 1735 ("/"), 2267 (icon-square
  background, `alpha 0.14f`), 2273 (icon), 2341 (segment accent), 2461 ("release" pill), 2562
  (question check); `scheme.primary` 868, 884, 1317, 1333 (accent bars/chevrons). AnswerView.kt
  92 (link), 125 (quote accent), 373 (shimmer).
- `surface`: Screens.kt 354 (TopAppBar container), 2397 (StatusCard), 2424 (VersionCard).
- `surfaceVariant`: Screens.kt 192 (working-edge track), 1000 (PinRow), 1112 (PinDetail prompt),
  1545 (AnsweredQuestion), 2309 (SettingsSection panel), 2528 (QuestionCard); `scheme.surfaceVariant`
  842 (DeckCard), 1300 (TurnPrompt). AnswerView.kt 167 (code block), 299 (table header), 373
  (shimmer ends).

## 22. How `PiRemoteTheme` picks light vs dark, and `appearance`/`theme` flow

`Screens.kt:115-140`: `dark = when (mode) { "dark" -> true; "light" -> false; else ->
isSystemInDarkTheme() }` (`:120-124`); `palette = AnswerStyle.theme(theme)` (`:128`); `scheme =
if (dark) palette.scheme else AnswerStyle.lightScheme` (`:129`); `MaterialTheme(colorScheme =
scheme)` (`:130`). `AnswerStyle.theme(name)` (`:319`) returns `themes[name] ?: midnight`;
`themes` is `midnight/indigo/amber/forest` (`:308-313`), `defaultTheme = "midnight"` (`:316`).
Light mode always uses `AnswerStyle.lightScheme` (`:321`), whatever `theme` says; sky is drawn
only when dark (`Screens.kt:156-175`). Flow: `MainActivity.kt:54` `viewModel()`, `:55`
`PiRemoteTheme(mode = vm.appearance, theme = vm.theme)`. `appearance`
`by mutableStateOf(store.appearance)` (`ChatViewModel.kt:303`), `theme` (`:318`), both private
set; writes `updateAppearance` (`:941-944`), `updateTheme` (`:959-962`); handed at
`MainActivity.kt:194-197`, declared `Screens.kt:1821-1824`. Store keys
`SettingsStore.appearance` (`data/SettingsStore.kt:43-45`, default `"dark"`) and `.theme`
(`:72-74`, default `"midnight"`), prefs `"pi-mobile"` (`:8`). Picker offers `dark`/`light`/`system`
(`Screens.kt:2011`) and one button per `AnswerStyle.themes` entry.

## 23. Current layout of a command palette row

`CommandPalette` `Screens.kt:1631-1694`. Column `:1640-1645`: `heightIn(max = 240.dp)` `:1642`,
`verticalScroll` `:1643`, `padding(horizontal = 12.dp, vertical = 4.dp)` `:1644`; no
`verticalArrangement`, so item spacing is 0; max height 240.dp. Row `:1658-1665`:
`fillMaxWidth`, `clip(RoundedCornerShape(AnswerStyle.promptRadius))`,
`AnswerStyle.promptRadius = 10.dp` (`AnswerStyle.kt:97`), `clickable`, `tactile()` (default
`pressScale = 0.97f`, `depth = 0.dp`, `Tactile.kt:110-114`, adds no height),
`padding(horizontal = 8.dp, vertical = 8.dp)` `:1664`. Texts: `"/${command.name}"` `bodyMedium`
(14.sp/20.sp) `:1668-1672`; `command.description` `bodySmall` (12.sp/16.sp), `maxLines = 1`,
ellipsised `:1674-1680`; `command.source` `labelSmall` (11.sp/16.sp), `primary`,
`padding(start = 8.dp)` `:1683-1689`. Totals (Q25): 52.dp with description, 36.dp without, at
1.0.

## 24. Current layout of `SearchResultRow`

`Screens.kt:1248-1272`. Column: `fillMaxWidth`, `clickable(enabled = openable)`,
`padding(horizontal = 16.dp, vertical = 10.dp)` `:1254`. Snippet `bodyMedium` (14.sp/20.sp),
`onBackground`, no `maxLines`, so wraps `:1256-1260`. Prompt `bodySmall` (12.sp/16.sp),
`onSurfaceVariant`, `maxLines = 2`, ellipsised, `padding(top = 4.dp)` `:1262-1269`. Rows sit in
`LazyColumn(fillMaxWidth().weight(1f))` (`Screens.kt:1229-1237`) with no `verticalArrangement`
and no `contentPadding`.

## 25. Row heights at `fontScale` 1.0 and 2.0

`fontScale` multiplies `sp` only; paddings are `dp` and unchanged.

Command palette row (`Screens.kt:1658-1690`): with description 20 + 16 + 8 + 8 = 52.dp at 1.0,
40 + 32 + 8 + 8 = 88.dp at 2.0; without description 20 + 8 + 8 = 36.dp at 1.0, 40 + 8 + 8 = 56.dp
at 2.0.

`SearchResultRow` (`Screens.kt:1250-1270`): snippet + prompt 20 + 4 + 16 + 10 + 10 = 60.dp at
1.0, 40 + 4 + 32 + 10 + 10 = 96.dp at 2.0; snippet only 20 + 10 + 10 = 40.dp at 1.0, 40 + 10 + 10
= 60.dp at 2.0.

Line heights are Material3 defaults (bodyMedium 20.sp, bodySmall 16.sp, labelSmall 16.sp), source
`material3-android-1.3.1` `TypeScaleTokens`; the app never overrides `Typography`. These are the
single-line heights and are derivable from source (`needs-device`: the single-line values).
`SearchResultRow`'s snippet has no `maxLines` (`:1256-1260`) and its prompt allows two lines
(`:1266`), so a long snippet or two-line prompt makes the row taller than the table; the exact
wrapped height depends on screen width, font metrics and text and can only be confirmed on a
device: `needs-device`. At
1.0 the palette row is 36.dp when a command has no description (`:1673` guards the second `Text`)
and the search row is 40.dp when `hit.prompt` is null (`:1261`); at 2.0 both rows exceed 48.dp.

## 26. The other fixed-size spots the review flags

- Waiting slot `Modifier.height(24.dp)`: `Screens.kt:384`, holds `Text("waiting", labelMedium
  12.sp/16.sp, primary, SemiBold)` `:386-391` or `WorkingShimmer()` (`AnswerView.kt:366-369`,
  64.dp x 8.dp). Text line box 16.dp at 1.0 (fits), 32.dp at 2.0 (exceeds). `needs-device` for
  what the Box does when the child is taller.
- Fixed bullet column: `AnswerView.kt:146-152`, `Text(..., fontSize = AnswerStyle.bodySize
  16.sp, lineHeight = bodyLineHeight 24.sp, Modifier.width(AnswerStyle.bulletIndent 20.dp))`
  (`AnswerStyle.kt:47`, `:54`, `:106`). Column stays 20.dp while the glyph is 16.sp at 1.0 and
  32.sp at 2.0. `needs-device` for exact clip/wrap.
- App-bar `maxLines = 1`: title `Screens.kt:360-365` (`titleMedium` 16.sp/24.sp, 24.dp at 1.0,
  48.dp at 2.0); subtitle `Screens.kt:371-377` (`bodySmall` 12.sp/16.sp, 16.dp at 1.0, 32.dp at
  2.0). Material3 small `TopAppBar` container is 64.dp (`TopAppBarSmallTokens.ContainerHeight`);
  at 2.0 the two lines' 48 + 32 = 80.dp exceeds it. `needs-device` for grow vs clip.
- Status line `maxLines = 1`: `Screens.kt:414-422` (`bodySmall`, ellipsised); container is not
  fixed, grows 16 + 8 = 24.dp at 1.0 to 32 + 8 = 40.dp at 2.0 while text stays one line.

## 27. `heightIn(min = …)` versus fixed `height(...)`/`size(...)`

`Screens.kt`: `heightIn(min = AnswerStyle.buttonHeight)` (56.dp) at 1083, 1139, 1159, 1172,
1757, 1909, 1934, 1979, 2088, 2155, 2176, 2472, 2575, 2618 (14 sites), plus `heightIn(min =
48.dp)` at 2344 and `heightIn(max = 240.dp)` at 1642; total `heightIn` 16. Fixed `height(...)` 8
sites: 384 `24.dp`, 696 `1.dp`, 852 `IntrinsicSize.Min`, 1179 `12.dp`, 1296 `IntrinsicSize.Min`,
1751 `8.dp`, 2187 `12.dp`, 2194 `12.dp` (six fixed dp, two intrinsic). Fixed `size(...)` 9
sites: 885/1334 `22.dp`, 1429/1447/1455 `18.dp`, 2265 `36.dp`, 2274 `20.dp`, 2375 `18.dp`, 2383
`8.dp`.

`AnswerView.kt`: `heightIn(min = ...)` 2 sites (123 `18.dp`, 229 `AnswerStyle.buttonHeight`), no
`heightIn(max = ...)`; fixed `height(...)` 3 sites (194 `1.dp`, 314 `1.dp`, 369 `8.dp`); fixed
`size(...)` 0. Fixed widths: 122 `AnswerStyle.accentBar` (3.dp), 151 `AnswerStyle.bulletIndent`
(20.dp), 368 `64.dp`.

## 28. Every control in the composer, in render order

`Composer` declared `Screens.kt:1697`, called `:533`; root `Column(fillMaxWidth().padding(12.dp))`
`:1717`.

1. Dictation partial text, not interactive: `if (listening && partialText.isNotBlank())`
   `:1718`, plain `Text` `bodySmall`, `onSurfaceVariant` `:1719-1725`.
2. `/` command affordance, the text field's `leadingIcon`, present iff `commandsEnabled`:
   `val commandAffordance: (@Composable () -> Unit)? = if (commandsEnabled)` `:1729`, else
   `null` `:1737-1739`; `IconButton(onClick = onToggleCommands)` `:1731` wrapping `Text("/",
   titleMedium, primary)` `:1732-1736`; always enabled.
3. `OutlinedTextField` `:1742-1748`: `value = text`, `fillMaxWidth`, `minLines = 1`,
   `maxLines = 6`, `leadingIcon = commandAffordance`, `placeholder = { Text(placeholder) }`;
   always present, always enabled.
4. Action `Row` `:1752-1756`, always present:
   a. Capture toggle, always present; `if (captureEnabled)` `FilledTonalButton "Auto-paste on"`
   `:1763-1770` (passes `haptics = true` `:1767`), else `OutlinedButton "Auto-paste"`
   `:1770-1776`; always enabled.
   b. Microphone `FilledTonalButton` `:1778-1783`, label `if (listening) "Mic on" else "Mic"`
   `:1782`, always present and enabled, `haptics = true` `:1780`.
   c. Primary action `Button` `:1787-1793`: `onClick = onAction`, `enabled = actionEnabled`
   `:1789`, label `actionLabel` `:1792`.
   d. Clear `OutlinedButton` `:1794-1800`: `enabled = text.isNotBlank()` `:1796`, label
   `"Clear"` `:1799`.
   e. Stop `OutlinedButton`, conditional `if (busy)` `:1801`: no `enabled` argument (always
   enabled) `:1802-1807`, label `"Stop"` `:1806`.

Conditional: `/` (`commandsEnabled`), capture variant (`captureEnabled`), Stop (`busy`). Mic,
action and Clear are unconditional.

## 29. Which parameter decides each control

All computed at the `ChatScreen` call `Screens.kt:533-585` (composer `:1697-1718`). `text = if
(searchOpen) query else vm.draft` `:534`. `placeholder`: search passes `"Search every prompt and
answer…"` `:537`; else `vm.composerBlock ?: "Prompt pi…"` `:539`. `partialText`/`listening` are
`ChatScreen` params (`Screens.kt:245-246`), held in MainActivity (`MainActivity.kt:61-62`), fed
by `Dictation` callbacks `:107-113`. `busy = vm.busy` `Screens.kt:542`; `vm.busy` decl
`ChatViewModel.kt:275`, set true on send `:1191` and on `"prompt_accepted"`, `"agent_start"`,
`"turn_start"` `:1371`, false on `"agent_settled"` `:1373`, stream close `:564`, `disconnect()`
`:617`, prompt failure `:1196`. `actionLabel = if (searchOpen) "Search" else "Send"` `:544`.
`actionEnabled`: search mode `query.isNotBlank()`, else `vm.draft.isNotBlank() &&
vm.composerBlock == null` `:547-551`. `commandsEnabled = !searchOpen` `:569`. `paletteVisible =
!searchOpen && ((slashQuery != null && !paletteDismissed) || paletteOpen)` `:562`.
`captureEnabled = vm.clipboardCapture` `:580` (decl `ChatViewModel.kt:326`). `vm.composerBlock`
`get() = if (connected) null else "Not connected"` (`ChatViewModel.kt:1280-1281`). `Send` is
disabled in exactly two facts: search mode with blank `query`; prompt mode with blank `draft` or
`connected == false`. `busy` does not enter `actionEnabled`, so `Send` stays enabled mid-run when
the draft is non-blank and connected.

## 30. What the row does when the controls exceed the screen

`Row(verticalAlignment = ..., horizontalArrangement = Arrangement.spacedBy(8.dp),
modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))` `Screens.kt:1752-1756`:
one non-wrapping row, 8.dp gaps, scrolls horizontally.
`heightIn(min = AnswerStyle.buttonHeight)` (56.dp, `AnswerStyle.kt:69`) `Screens.kt:1757` is a
minimum height, not a width limit. Order: capture, Mic, action, Clear, Stop (`:1763`, `:1778`,
`:1787`, `:1794`, `:1801`). Clear and Stop are last, so they are pushed off the initial
viewport; Stop exists only while `busy`. The exact off-viewport subset depends on text widths and
device width: `needs-device`.

## 31. The `/` affordance

Condition `commandsEnabled` = `!searchOpen` (`Screens.kt:1729`). Label `Text("/", titleMedium,
primary)` `:1732-1736` inside `IconButton(onClick = onToggleCommands)` `:1731`. No
`contentDescription` and no `Modifier.semantics` on it (`:1731-1737`). `tactile()` not called;
`tactile` appears on the row buttons (`:1767`, `:1780`, `:1790`, `:1797`, `:1804`) but not here,
so this control has no press scale (`Tactile.kt:108-115`).

## 32. `actionLabel`, `actionEnabled`, and the disabled-Send set

`actionLabel = if (searchOpen) "Search" else "Send"` (`Screens.kt:544`). `actionEnabled = if
(searchOpen) { query.isNotBlank() } else { vm.draft.isNotBlank() && vm.composerBlock == null }`
(`Screens.kt:547-551`). Search placeholder `"Search every prompt and answer…"` `:537`; prompt
placeholder `vm.composerBlock ?: "Prompt pi…"` `:539`. Full disabled set: (1) search mode and
`query.isBlank()`; (2) prompt mode and `vm.draft.isBlank()`; (3) prompt mode and
`vm.composerBlock != null`, which is exactly `connected == false` (`ChatViewModel.kt:1281`). No
other condition enters `enabled` (`Screens.kt:1789`).

## 33. Notifications, services, workers, alarms, wake locks today

None. `grep -rnE "Notification|WorkManager|WakeLock|AlarmManager|JobScheduler|ForegroundService|
startForeground|WorkRequest|JobIntentService" app/src/main/` exits 1 with no output; per-term
counts are 0. The two `Service` hits are `context.getSystemService(...)`
(`net/Diagnostics.kt:22-23`, `ui/Screens.kt:293`) plus a comment (`ui/ChatViewModel.kt:835`).
`grep -rnE "<service|POST_NOTIFICATIONS" app/src/main/` exits 1. `app/build.gradle.kts` has no
work/notification/foreground dependency. `AndroidManifest.xml` declares permissions `INTERNET`
(`:4`), `ACCESS_NETWORK_STATE` (`:5`), `RECORD_AUDIO` (`:6`), `REQUEST_INSTALL_PACKAGES` (`:7`),
`CAMERA` (`:10`); feature `android.hardware.camera` `required="false"` (`:11`); one
`<application .PiRemoteApp>` (`:13`), one `<activity .MainActivity>` (`:21`), one
`FileProvider` (`:40`). No `<service>`, no `POST_NOTIFICATIONS`, no `android:icon`.

## 34. What the bridge does when the SSE client disconnects

Handler `if (req.method === "GET" && url.pathname === "/api/events")`
(`bridge/extensions/pi-remote.ts:1007`). Headers `text/event-stream; charset=utf-8`,
`no-store`, `keep-alive` (`:1008-1010`). Retry directive `res.write("retry: 3000\n\n")`
(`:1012`); no other retry logic. `clients.add(res)` (`:1013`) then immediate `state`
(`:1016-1018`) and `question` (`:1019-1021`) frames. Disconnect `req.on("close", ...)`
(`:1022-1026`). No replay buffer: `emit` returns early when `clients.size === 0`
(`:486-488`). `Last-Event-ID` is not read anywhere. Every frame carries `seq: ++seq` (`:489`,
`:1017`, `:1020`) but never used to resume. No bridge-local README; the root `README.md` has no
reconnect/replay section.

## 35. Event numbering and whether the app reads it

Bridge `let seq = 0;` (`pi-remote.ts:355`), incremented in `emit`
`const frame = \`data: ${JSON.stringify({ seq: ++seq, at: nowIso(), type, data: shrink(data) })}\n\n\``
(`:489`) and in the connect-time frames (`:1017`, `:1020`). App does not read the bridge `seq`:
a grep for `seq` matches only local counters, `searchSeq` (`private var searchSeq = 0L`,
`ChatViewModel.kt:358`; `val seq = ++searchSeq` `:972`; guards `:984`, `:1012`; `searchSeq++`
`:1027`) and `outcomeSeq` (`:352`, used at `:1194`, `:1207`, `:1325`, `:1336`). Neither relates
to the bridge's `seq`; no last-event cursor is stored.

## 36. Bridge event types, app consumption, background reconnect

Bridge `emit` types (`pi-remote.ts`): `prompt_accepted` `:1061`, `question_answered` `:1142`,
`aborted` `:1168`, `question` `:1442`, `session_info_changed` `:1632`, `agent_start` `:1636`,
`agent_end` `:1641`, `agent_settled` `:1645`, `turn_start` `:1650`, `turn_end` `:1654`,
`message_start` `:1658`, `message_update` `:1666`, `message_end` `:1670`,
`tool_execution_start` `:1674`, `tool_execution_end` `:1678`, `model_select` `:1687`; connect
adds `state` and `question` (`:1016-1021`).

App `when (type)` in `handleEvent` (`ChatViewModel.kt:1364-1372`) handles `question` `:1368`,
`state` `:1369`, `prompt_accepted`/`agent_start`/`turn_start` `:1371`, `agent_settled` `:1372`,
`agent_end` `:1377`, `message_start` `:1386`, `message_update` `:1423`, `message_end` `:1425`,
`tool_execution_start`/`tool_execution_end` (`:1439-1441`, explicit no-op), `model_select`
`:1442`. It does not handle `session_info_changed`, `turn_end`, `aborted`, `question_answered`.

- "A question needs an answer": `question` (`pi-remote.ts:1442`), also on connect
  (`:1019-1021`); app reacts `:1368`.
- "A run finished": `agent_settled` (`pi-remote.ts:1645`), used to clear busy and commit the
  answer (`ChatViewModel.kt:1372-1376`); `agent_end` (`:1641`) only refreshes state
  (`:1377-1380`).
- "A run failed": no dedicated event. `agent_end` carries only `{ messageCount: ... }`
  (`pi-remote.ts:1641`); `tool_execution_end` carries `isError` (`:1678-1683`) but the app
  discards both tool events (`ChatViewModel.kt:1439-1441`); `aborted` (`pi-remote.ts:1168`) is
  never handled.
- Backgrounded socket: `scheduleReconnect()` from `onClosed` (`ChatViewModel.kt:574`, def
  `:625-640`, backoff 250/500/1000/3000 ms) and `startPolling()` (3 s loop `:646-660`); neither
  is gated on lifecycle. The only lifecycle hook is `ON_RESUME` -> `vm.ensureConnected()`
  (`MainActivity.kt:154-162`). Nothing keeps the process or socket alive while backgrounded
  (Q33).

## 37. What PRODUCTION-READINESS Tier 0.2, 0.3, 0.4 and 3.1 specify

- 0.2 `docs/PRODUCTION-READINESS.md:43`: "Service with a persistent notification ("pi working ·
  4m"), started on `agent_start`, stopped on `agent_settled`; screen-off run keeps receiving
  events."
- 0.3 `:44`: "Notification channel plus three triggers: question pending, run finished, run
  failed. Tapping opens the answer or the question card."
- 0.4 `:45`: "Question notification carries up to 3 option buttons plus "Open"; result
  notification carries "Copy answer" and "Reply"; verified from the lock screen."
- 3.1 `:93`: "Private topic the bridge publishes to; gives real background notifications without
  FCM and without a cloud account." (ntfy / UnifiedPush).

Overlap: 0.2 is the foreground service and its persistent notification, 0.3 the trigger set, 0.4
the notification actions.

## 38. `minSdk = 26`, `targetSdk = 35`: permission and channel APIs

Build: `compileSdk = 35` (`app/build.gradle.kts:20`), `minSdk = 26` (`:24`), `targetSdk = 35`
(`:25`). No notification permission and no channel are declared or created anywhere (Q33).
Platform requirements: `POST_NOTIFICATIONS` is a runtime permission on Android 13 (API 33) and
higher, required for non-exempt (including FGS) notifications
(https://developer.android.com/develop/ui/compose/notifications/notification-permission); with
`targetSdk = 35` it must be declared and requested at runtime on Android 13+. Since Android 8.0
(API 26) all notifications must be assigned to a channel
(https://developer.android.com/develop/ui/compose/notifications/channels); with `minSdk = 26` a
`NotificationChannel` plus `NotificationManager.createNotificationChannel` is required on every
supported device.

## 39. Per-screen state: ViewModel or composable

ViewModel-owned (`ChatViewModel`): `draft` (`ChatViewModel.kt:270`), `pendingQuestion` (`:301`),
`appearance` (`:303`), `viewMode` (`:311`), `theme` (`:318`), `clipboardCapture` (`:326`),
`searchResults` (`:334`), `searching` (`:336`), `searchError` (`:338`), `pendingJump` (`:345`),
`outcome` (`:349`), `commands` (`:365`), `commandsNote` (`:384`), `sessionNotice` (`:400`),
`messages` (`:259`), `turns` (`:428`), `pins` (`:247` region), `busy`, `connected`, `liveAnswer`,
`showingSavedCopy`.

Composable-owned: `showSettings` (`MainActivity.kt:59`), `listening` (`:60`), `partialText`
(`:61`), `notice` (`:62`), `updateStatus` (`:63`); `searchOpen` (`Screens.kt:261`), `query`
(`:262`), `paletteOpen` (`:267`), `paletteDismissed` (`:271`), `actionNotice` (`:276`);
`explicitTurn` (`:607`), `followNewest` (`:608`); `openTurn` (`:724`), `heldTurn` (`:727`);
`openedId` (`:963`), `editing` (`:1041`), `titleDraft` (`:1042`), `confirmingDelete` (`:1043`);
`baseUrl` (`:1836`), `token` (`:1837`), `openSection` (`:1841`), `confirmingClear` (`:1846`),
`pastedLink` (`:1851`), `pairingStatus` (`:1852`); `typed` (`:2522`); sweep `progress =
remember { mutableFloatStateOf(0f) }` (`AnswerView.kt:344`).

Of the composable-owned, `rememberSaveable`: `searchOpen`, `query`, `paletteOpen`,
`paletteDismissed`, `explicitTurn`, `followNewest`, `openTurn`, `openedId`, `editing`,
`titleDraft`, `confirmingDelete`, `showSettings`, `baseUrl`, `token`, `openSection`, `pastedLink`,
`typed`. Plain `remember`: `actionNotice`, `heldTurn`, `confirmingClear`, `pairingStatus`,
`listening`, `partialText`, `notice`, `updateStatus`, `progress`.

## 40. Does `ChatScreen` leave composition when settings shows

Yes. `MainActivity.kt:187` `if (showSettings)` and `:212` `else` are branches of one `if`, so
showing settings disposes `ChatScreen` and its whole subtree (`SearchPanel`, `PinsView`,
`TurnDeck`, `Transcript`, `Composer`), and hiding settings disposes `SettingsScreen`. No
`SaveableStateHolder` or `rememberSaveableStateHolder` is used
(`grep -rn "SaveableStateHolder" app/src/main/java/` returns nothing), so on the toggle each
`rememberSaveable` value from Q39 is disposed with its composable and nothing re-injects it; the
same for plain `remember`. Across a configuration change the composed branch is saved through
the Activity's `SavedStateRegistry` and restored; the `viewModel()` at `MainActivity.kt:54` is
not recreated.

## 41. State that survives a restart or screen switch via `SettingsStore`

`data/SettingsStore.kt` writes: `baseUrl` (`:15-16`), `token` (`:25-35`), `appearance` (`:43-44`,
default `"dark"`), `clipboardCapture` (`:52-53`), `viewMode` (`:62-63`, default `"transcript"`),
`theme` (`:72-73`), `sessionId` (`:81-82`), `sessionName` (`:87-88`). ViewModel fields initialised
from and written back to the store: `appearance` (`ChatViewModel.kt:303`, `:941`), `viewMode`
(`:311`, `:953`), `theme` (`:318`, `:959`), `clipboardCapture` (`:326`, `:947`), plus
`attachedSessionId`/`sessionTitle`/`sessionName` (`:390` region). They survive a screen switch and
process restart. Nothing else in Q39 is persisted: `draft`, `pendingJump`, `searchResults`,
`pendingQuestion`, `sessionNotice`, `commands`, `outcome` are ViewModel-only; `searchOpen`,
`query`, `paletteOpen`, `paletteDismissed`, `openedId`, `explicitTurn`, `followNewest`,
`openTurn`, and the pin/editing fields are composable-only.

## 42. Scope of the `viewModel()` at `MainActivity.kt:54`

Called inside `setContent {` (`MainActivity.kt:53`) with no explicit owner, so it uses
`LocalViewModelStoreOwner.current`. `MainActivity` is a `ComponentActivity`
(`MainActivity.kt:33`), which implements `androidx.lifecycle.ViewModelStoreOwner` and holds
`_viewModelStore` (in `activity-1.9.3-api.jar`); `activity-compose-1.9.3`
`ComponentActivityKt.setOwners` installs the view-tree owners so `viewModel()` resolves to that
store, and `lifecycle-viewmodel-compose-2.8.7` supplies `LocalViewModelStoreOwner`. The instance
is keyed to the Activity's `ViewModelStore`: it survives rotation and the `showSettings` toggle
(the store is not cleared while the Activity lives) and does not survive process death.
`ChatViewModel` is an `AndroidViewModel` (`ChatViewModel.kt:242`) and uses no `SavedStateHandle`
(`grep -n "SavedStateHandle" ChatViewModel.kt` returns nothing).

## 43. PRODUCTION-READINESS 0.8, 1.7, 1.13, 2.9 versus the code

The four items sit in the document's open tier tables, so the document claims each is open.

- 0.8 Pairing by QR code, doc `docs/PRODUCTION-READINESS.md:49` (acceptance names CameraX and a
  QR carrying the token). Ships: bridge `pair` command `bridge/extensions/pi-remote.ts:1857`,
  endpoint `:966`; `object Pairing` `net/Pairing.kt:24`, `parse` `:44`, `exchange` `:61`; scan
  launcher `Screens.kt:1853`, manual entry `:1925`, `onPairLink(scanned)` `:1859`. Mismatches:
  the scanner is ZXing, not CameraX (`app/build.gradle.kts:81`
  `com.journeyapps:zxing-android-embedded:4.3.0`; `Screens.kt:107` `ScanContract`), and the doc
  says the token is in the QR while `net/Pairing.kt:19` says it is deliberately kept out.
  `MainActivity.kt:140` parses the link.
- 1.7 History search, doc `:65` ("no way back"). Ships: `ChatViewModel.kt:970` `fun search`,
  `:1026` `clearSearch`, `:1092` `jumpToTurn`, `net/PiRemoteClient.kt:84` `suspend fun search`,
  bridge `/api/search` `pi-remote.ts:993`, rendered `Screens.kt:1191`/`:1231`/`:1249`.
- 1.13 Haptics and motion, doc `:71` (haptic tick plus "animations honour 'reduce motion'").
  Haptics ship: `ui/Tactile.kt:47` `object Haptics`, `:50` `press`, `:62` `confirm`, `:76`
  `reject`, `:108` `Modifier.tactile`; applied `Screens.kt:1766`, `:1780`, `:1790`, `:1804`,
  outcome `:339`. The reduce-motion half is absent: the custom sweep
  `AnswerView.kt:343` `rememberSweep` advances via `withFrameNanos` `:350` (KDoc `:337` says it
  bypasses the animation spec), so a `MotionDurationScale` of zero does not reach it; Compose's
  `animateFloatAsState` in `Tactile.kt:130` does obey it. `grep -rn "durationScale|
  MotionDurationScale|reduceMotion|Animator|Settings.Global" app/src/main/` returns nothing.
- 2.9 Crash and ANR visibility, doc `:85` (reporting or a "send diagnostics" action). Ships the
  local half only: `data/CrashLog.kt:32` class, `:84` `recordCrash`, `:106` `lastCrash`, `:111`
  `clearLastCrash`; installed `PiRemoteApp.kt:20`, `:30`; shown `Screens.kt:2105`, `:2129`,
  `:2140`. No ANR capture (`grep -rin "ANR|ApplicationExitInfo|ExitInfo" app/src/main bridge`
  gives one unrelated hit `update/UpdateChecker.kt:160`), no send/share diagnostics (only
  `Share` is for an answer, `Screens.kt:1438`).

## 44. The lab path cited by `AnswerStyle.kt`, and what is on disk

`AnswerStyle.kt:14` cites `pi-remote-design-lab/out/choices-latest.json` (on 2026-10-04). That
file does not exist. `ls -la` of `/home/maverock24/pi-remote-design-lab/out/`:
`choices-tactile-2026-10-05T12-35-38.json` (570 bytes, Oct 5 12:35),
`choices-tactile-latest.json` (570 bytes, Oct 5 12:35), `lab-token` (65 bytes, Oct 4 13:47, mode
0600), `requests.md` (0 bytes, Oct 4 13:47). `choices-tactile-latest.json` holds
`savedAt: "2026-10-05T12:35:38+0300"` and five tactile choices (`press_motion`, `tap_haptic`,
`outcome_haptic`, `surfaces`, `mechanism`); it is not the layout/typography document the comment
enumerates and carries no 2026-10-04 record. The same missing path is named at
`docs/UX-REVIEW.md:304` (and that line's `AnswerStyle.kt:18-20` range is stale; the path is on
`AnswerStyle.kt:14`). The lab server default is still `choices-latest.json`
(`serve.py:16`, `:185`).

## 45. What design record exists, and whether it defines the UI's vocabulary

Inside the repo: `GLOSSARY.md` defines only bridge vocabulary (`GLOSSARY.md:9-58`: Bridge, Owner,
Lease, Takeover, Handover, Release, Attached session, Pinned session, Device token, Bound
address). `docs/adr/0001-port-bind-is-the-authority.md:1` and
`docs/adr/0002-bridge-source-in-this-repo.md:1` are about the bridge, not the UI.
`research.md` (repo root) is a UI/UX rubric, not a recorded decision.
`.scratch/retro-2026-10-10-bridge-diagnosis.md` exists (not read here).

Outside the repo at `/home/maverock24/pi-remote-design-lab/`: `design-lab.html`,
`tactile-lab.html`, `tactile3d-lab.html`, `serve.py`; `out/choices-tactile-latest.json` and
`out/choices-tactile-2026-10-05T12-35-38.json` (identical), `out/requests.md` (0 bytes),
`out/lab-token` (65-byte secret); no `choices-latest.json`.

No record defines the UI's own words. `grep -ic "deck" GLOSSARY.md` = 0 and `README.md` = 0; the
labs contain no `deck`; `grep -ric "deck" docs/` matches only `docs/UX-REVIEW.md` (6).
`grep -in "appearance|theme|transcript|screen|ui " GLOSSARY.md` gives no matches.
`design-lab.html:309` uses "cards" only as a list style. The review notes the gap at
`docs/UX-REVIEW.md:314`. `docs/UX-REVIEW.md:307` says the README calls the deck "the deck", but
`grep -ic "deck" README.md` = 0, so that claim does not hold at HEAD.

## 46. Do the recorded lab choices corroborate or contradict the two divergences

Recorded set `out/choices-tactile-latest.json`: `"press_motion": "1a"`, `"tap_haptic": "2a"`,
`"outcome_haptic": "3a"`, `"surfaces": "4b"`, `"mechanism": "5a"`; summary "press motion: Scale
on every tappable control / tap haptics: Press and release pair / outcome haptics: Confirm on
success, reject on failure / which controls: Only controls that change pi's state / haptic
mechanism: Platform constants only".

Press-scale missing on four controls: the recorded option is `tactile-lab.html:179-182`
(`press_motion` value `1a`, "Scale on every tappable control", recommended). The code applies
`Modifier.tactile()` everywhere except four: copy `Screens.kt:1418` `IconButton(` (block
`:1419-1423`, no `.tactile(`), share `:1432` (block `:1433-1441`, no `.tactile(`), pin `:1450`
`IconButton(onClick = onPin) {`, and the composer `/` `:1731`. The code contradicts `1a`; the
review states the same at `docs/UX-REVIEW.md:143-146`.

`Haptics.confirm` on copy and share: the recorded option is `tactile-lab.html:297-300`
(`surfaces` value `4b`, "Only controls that change pi's state"), described at `:299` as "Send,
Stop, mic, questionnaire options, pairing, update, retry; not navigation and not opening a link."
The code rings confirm on copy `Screens.kt:1421` and share `:1439`, neither in the `4b` list nor a
state change, so it contradicts `4b` by omission. `Screens.kt:1400-1402` does not defer to the
record. Neither divergence is written into the record (`docs/UX-REVIEW.md:310-311`).

## 47. Test sources, lint configuration, CI jobs

Test source sets: `find app/src -type d` returns only `app/src/main` and subdirectories; no
`app/src/test`, no `app/src/androidTest`, no test `.kt` files. `app/build.gradle.kts:69-82` has
`implementation` only, no `testImplementation`/`androidTestImplementation`. No `lint.xml` outside
`app/build/`, no `lintOptions`/`lint { }` in either build file or `gradle.properties`, no detekt,
ktlint or `.editorconfig`. Lint runs with AGP 8.7.3 defaults; `:app:lintDebug` writes
`app/build/reports/lint-results-debug.{html,txt,xml}`.

CI: `.github/workflows/android-debug.yml` triggers on `workflow_dispatch` only; one job
`debug-apk` on `ubuntu-latest`, Java 21, `android-actions/setup-android` with
`packages: 'platform-tools'`, `./gradlew --no-daemon --stacktrace assembleDebug`, uploads
`app/build/outputs/apk/debug/app-debug.apk`. `.github/workflows/android-release.yml` triggers on
`push` to `main` and `workflow_dispatch`; one job `build-and-publish` on `ubuntu-latest`, Java 21,
`./gradlew --no-daemon --stacktrace assembleRelease`, writes `latest.json`, deletes the previous
`latest-build` release, publishes with `softprops/action-gh-release`, uploads `pi-remote-*.apk`.
Neither workflow runs a test or lint task.

## 48. Gradle tasks that run offline on this machine

All runs `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline ...` from the repo
root; default `java` is Java 25 (`openjdk version "25.0.4.1"`).

| Task | Offline result |
| --- | --- |
| `:app:dependencies --configuration debugRuntimeClasspath` | `BUILD SUCCESSFUL in 16s` |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL in 9s`; `NO-SOURCE` |
| `test` (root) | `BUILD SUCCESSFUL in 10s`; `testReleaseUnitTest NO-SOURCE`, `:app:test UP-TO-DATE` |
| `:app:lintDebug` | `BUILD SUCCESSFUL in 1m 22s`; report `0 errors, 9 warnings`: `GradleDependency` (5), `ObsoleteSdkInt` (2), `DataExtractionRules` (1), `MissingApplicationIcon` (1) |
| `lint` (root) | `BUILD SUCCESSFUL in 10s`; `:app:lint UP-TO-DATE` |
| `:app:assembleDebug` | `BUILD SUCCESSFUL in 5s`; `app-debug.apk` (10055478 bytes) |
| `:app:assembleRelease` | `BUILD SUCCESSFUL in 5m 37s`; `app-release-unsigned.apk` (7356755 bytes); no signing env vars, so unsigned |

## 49. Device-only versus machine-provable verification

On this machine today: lint (`:app:lintDebug`/`lint`, `0 errors, 9 warnings`, none about back,
touch targets, wrapping, insets, animation scale, font scale or accessibility roles);
compilation/packaging (`assembleDebug`/`assembleRelease`); unit tests run but `NO-SOURCE`; no
screenshot plugin and no `ui-test` dependency (`app/build.gradle.kts:69-82` has `implementation`
only; grep for `screenshot`, `robolectric`, `ui-test` finds nothing); no `app/src/androidTest` and
no emulator/system image (Q50).

- R2 (back gesture): device/emulator only; here only a source read plus lint.
- R6 (touch target size): declared `Modifier.height`/`size`/`padding` readable from source; lint
  currently reports no `TouchTargetSizeCheck`; the real hit target and a 48dp floor under
  `fontScale` need a device/emulator.
- R8 (wrap): rendering result, needs a device/emulator.
- R9 (composer layout): rendering/overflow, needs a device/emulator.
- R13 (notification from the lock screen): needs a device/emulator and Android 13+ for
  `POST_NOTIFICATIONS`.
- R15 (TalkBack, fontScale 2.0): needs a device/emulator with accessibility services; neither can
  be produced here.
- R19 (Android 15 insets): needs a device/emulator on API 35; only `android-35` compile platform
  is installed, no API 35 system image.
- R20 (system animation scale): needs a device/emulator.

Every recommendation in the list is device- or emulator-only today; the machine can prove only
compilation, packaging and the current lint result set.

## 50. Android SDK and emulator availability

`local.properties` has one line `sdk.dir=/usr/lib/android-sdk`. Installed under
`/usr/lib/android-sdk/`: `platforms/android-35`; `build-tools/34.0.0` and `build-tools/35.0.0`;
`platform-tools` revision 36.0.0 (`adb --version` 1.0.41, 36.0.0-13206524; `adb devices` empty);
`licenses/android-sdk-license`. Absent: no `emulator/`, no `system-images/`, no `cmdline-tools/`.
An AVD exists but its runtime is missing: `~/.android/avd/Small_Phone_API_28.ini` targets
`android-28`, `config.ini` has `abi.type=x86`, `hw.lcd.width=720`, `hw.lcd.height=1280`,
`image.sysdir` absent; `emu-launch-params.txt` points at `/home/maverock24/Android/Sdk/emulator`
(that directory does not exist, though `PATH` contains it). `which emulator` returns nothing;
`find / -maxdepth 5 -name emulator -type f`, `find / -maxdepth 7 -name system.img` and
`find / -maxdepth 7 -type d -name system-images` all return nothing. The AVD targets `android-28`
and `platforms/android-28` is not installed (only `android-35`). An instrumentation test cannot
run here: no `app/src/androidTest`, no emulator binary, no system image; font-scale and TalkBack
proofs cannot be automated on this machine.

## Appendix: facts worth carrying into Design

- Compose BOM 2024.10.01 pins material3 1.3.1 and ui/foundation/runtime 1.7.5
  (`app/build.gradle.kts:74`; BOM POM in the Gradle cache).
- AGP 8.7.3, Kotlin 2.0.21, Compose compiler plugin 2.0.21, Gradle 8.14.3
  (`build.gradle.kts:2-4`; `gradle/wrapper/gradle-wrapper.properties:3`).
- `activity-compose:1.9.3` is declared at `app/build.gradle.kts:71`; the siblings disagree
  (`2-navigation-state.md` cites `:74`, `3-capabilities-verification.md` cites `:71`).
- `LocalMotionDurationScale` is absent in ui 1.7.5, so no composition local exposes the system
  animation scale.
- `rememberSaveableStateHolder` is present in runtime-saveable 1.7.5 but unused by the app
  (`Screens.kt`/`MainActivity.kt` have no `SaveableStateHolder`).
- The notice regression: `ChatScreen(notice = ...)` is derived only from
  `UpdateStatus.Available` (`MainActivity.kt:217-219`), so all nine writes to
  `MainActivity.notice` (`:94`, `:96`, `:114`, `:124`, `:142`, `:145`, `:150`, `:153`, `:231`)
  are dead; it is the only writer-without-reader found.
- No back handling exists anywhere; system back finishes the single Activity
  (`AndroidManifest.xml:24-31`; grep over `app/src/main`).
- Lint offline reports `0 errors, 9 warnings` with no `TouchTargetSizeCheck`
  (`:app:lintDebug`, report `app/build/reports/lint-results-debug.html`).
- No test source set exists; `testDebugUnitTest` is `NO-SOURCE` and the build has no test
  dependency (`app/build.gradle.kts:69-82`; `find app/src`).
- No emulator binary, no system image and no `app/src/androidTest`; instrumentation cannot run
  here (`/usr/lib/android-sdk` listing; `local.properties`; Q50).
- `POST_NOTIFICATIONS` is absent and no notification/service code exists; `minSdk = 26` forces a
  channel on every supported device (`AndroidManifest.xml`; `app/build.gradle.kts:24`; Q33/Q38).
- The bridge's `/api/events` has no replay buffer and ignores `Last-Event-ID`; `emit` returns
  early with no client (`bridge/extensions/pi-remote.ts:486-488`, `:1007-1026`).
- Haptics `confirm`/`reject` ship and the three answer action icons plus the `/` affordance lack
  press scale (`ui/Tactile.kt:62`, `:76`; `Screens.kt:1418`, `:1432`, `:1450`, `:1731`).
- `AnswerStyle.kt:14` cites `pi-remote-design-lab/out/choices-latest.json`, which does not exist;
  only `choices-tactile-latest.json` is on disk (`~/pi-remote-design-lab/out`).
- `PRODUCTION-READINESS.md` marks 0.8, 1.7, 1.13, 2.9 open, but the code ships 0.8 via ZXing (not
  CameraX), 1.7 search, 1.13 haptics (without reduce-motion) and 2.9's local crash log
  (`docs/PRODUCTION-READINESS.md:49`, `:65`, `:71`, `:85`).
