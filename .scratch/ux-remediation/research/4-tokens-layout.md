# Research: colour tokens and layout sizing (questions 18 to 22 and 23 to 27)

Line numbers are against the working tree at `HEAD`. The typography line heights used
below are the Material3 defaults, confirmed by decompiling the resolved artifact in the
Gradle cache: `material3-android-1.3.1` (`androidx/compose/material3/tokens/TypeScaleTokens.class`),
pulled in by `androidx.compose:compose-bom:2024.10.01` (`app/build.gradle.kts:76-77`).
The app never overrides `Typography` (`grep -rn "Typography\|typography =" app/src/main/java/com/maverock24/pimobile/` returns no code match), so
`MaterialTheme.typography.*` are those defaults. Values taken from the jar:

| style | size | lineHeight |
| --- | --- | --- |
| titleMedium | 16.sp | 24.sp |
| bodyLarge | 16.sp | 24.sp |
| bodyMedium | 14.sp | 20.sp |
| bodySmall | 12.sp | 16.sp |
| labelMedium | 12.sp | 16.sp |
| labelSmall | 11.sp | 16.sp |
| labelLarge | 14.sp | 20.sp |

Compose's default `PlatformTextStyle` in this artifact is `PlatformTextStyle(false)`
(`androidx/compose/material3/internal/DefaultPlatformTextStyle_androidKt.defaultPlatformTextStyle`),
so `includeFontPadding` is off and a single-line `Text` measures at exactly its
`lineHeight`. `sp` scales with the system `fontScale` while `dp` does not; the app does
not override `LocalDensity` or set `android:configChanges` anywhere, so the system
`fontScale` applies unchanged.

---

## 18. What roles `deriveScheme` sets, and which are left at baseline

`deriveScheme` (`app/src/main/java/com/maverock24/pimobile/ui/AnswerStyle.kt:151-187`) reads
six inputs off the incoming `base`:

- `val accent = base.primary` (`:152`)
- `val surface = base.surface` (`:153`)
- `val background = base.background` (`:154`)
- `val foreground = base.onBackground` (`:155`)
- `val error = base.error` (`:156`)
- `val onFill = if (error.luminance() > 0.5f) Color(0xFF14181F) else Color(0xFFF6F8FB)` (`:159`)

It then `base.copy(...)`s 25 roles (`:161-185`):

| role | input |
| --- | --- |
| `primaryContainer` | `lerp(surface, accent, 0.22f)` (`:161`) |
| `onPrimaryContainer` | `foreground` (`:162`) |
| `inversePrimary` | `accent` (`:163`) |
| `surfaceTint` | `accent` (`:164`) |
| `secondary` | `base.surfaceVariant` (`:165`) |
| `onSecondary` | `base.onSurfaceVariant` (`:166`) |
| `secondaryContainer` | `lerp(surface, accent, 0.14f)` (`:167`) |
| `onSecondaryContainer` | `foreground` (`:168`) |
| `tertiary` | `accent` (`:169`) |
| `onTertiary` | `base.onPrimary` (`:170`) |
| `tertiaryContainer` | `lerp(surface, accent, 0.30f)` (`:171`) |
| `onTertiaryContainer` | `foreground` (`:172`) |
| `errorContainer` | `lerp(surface, error, 0.20f)` (`:173`) |
| `onErrorContainer` | `foreground` (`:174`) |
| `onError` | `onFill` (`:175`) |
| `inverseSurface` | `surface` (`:176`) |
| `inverseOnSurface` | `background` (`:177`) |
| `scrim` | `Color(0xFF000000)` (`:178`) |
| `surfaceDim` | `background` (`:179`) |
| `surfaceBright` | `lerp(surface, foreground, 0.05f)` (`:180`) |
| `surfaceContainerLowest` | `background` (`:181`) |
| `surfaceContainerLow` | `lerp(background, surface, 0.25f)` (`:182`) |
| `surfaceContainer` | `lerp(background, surface, 0.5f)` (`:183`) |
| `surfaceContainerHigh` | `lerp(background, surface, 0.75f)` (`:184`) |
| `surfaceContainerHighest` | `surface` (`:185`) |

The remaining 11 roles are supplied by the caller. Each dark palette passes the same
11 named arguments to `darkColorScheme(...)` (midnight `:205-220`, indigo `:230-245`,
amber `:255-270`, forest `:280-295`) and the light scheme to `lightColorScheme(...)`
(`:321-334`): `primary`, `onPrimary`, `background`, `onBackground`, `surface`, `onSurface`,
`surfaceVariant`, `onSurfaceVariant`, `outline`, `outlineVariant`, `error`.

25 derived + 11 named = the 36 colour roles `ColorScheme` has in material3 1.3.1
(decompiled field list of `androidx/compose/material3/ColorScheme.class`; the resolved
`darkColorScheme` has a 36-long argument list). **No role is left at the Material
baseline.** The baseline values still exist as the defaults of the arguments the call
does not name, but every one of them is overwritten by the 25 `copy` assignments. For
example `primaryContainer` is not named in the `darkColorScheme` call, so its value at
call time is Material's baseline; `:161` replaces it.

---

## 19. Every `MaterialTheme.colorScheme.<role>` read

`val scheme = MaterialTheme.colorScheme` aliases appear too (Screens.kt:135, AnswerView.kt:91,
and the `scheme.` reads below). Counts are per file.

### Screens.kt - direct reads

`onSurfaceVariant` (33):

| line | what it paints |
| --- | --- |
| 374 | app-bar subtitle (`vm.lastPrompt` / `vm.attachedLabel`, bodySmall) |
| 418 | disconnected status line (bodySmall) |
| 431 | "Showing the transcript saved on this phone" line |
| 457 | `vm.sessionNotice` handover line (bodySmall) |
| 486 | empty-state "thinking…" / "no results yet" (bodyMedium) |
| 941 | ViewModeSwitch inactive label (bodyMedium) |
| 979 | PinsView empty message (bodyMedium) |
| 1016 | PinRow snippet (bodySmall) |
| 1218 | SearchPanel "searching…" (bodyMedium) |
| 1226 | SearchPanel "no matches" (bodyMedium) |
| 1265 | SearchResultRow prompt line (bodySmall) |
| 1413 | `val tint = ...` for the copy/share/pin icons |
| 1566 | AnsweredQuestion cancelled-answer text (bodySmall) |
| 1652 | CommandPalette "no commands to offer" / "no matching command" (bodySmall) |
| 1677 | CommandPalette command description (bodySmall) |
| 1722 | Composer dictation partial text (bodySmall) |
| 1919 | Settings pairing help text (bodySmall) |
| 1963 | Settings bridge-URL help text (bodySmall) |
| 1975 | Settings token help text (bodySmall) |
| 1997 | Settings "session: …" label (bodySmall) |
| 2025 | Settings theme explanation (bodySmall) |
| 2052 | App Updates intro text (bodySmall) |
| 2059 | UpdateStatus.Checking text (bodySmall) |
| 2119 | Troubleshooting intro text (bodySmall) |
| 2126 | "still set aside" text (bodySmall) |
| 2142 | "The text selects…" note (bodySmall) |
| 2191 | version label (bodySmall) |
| 2287 | SettingsSection subtitle (bodySmall) |
| 2301 | SettingsSection chevron tint |
| 2361 | SegmentButton inactive label (labelMedium) |
| 2444 | VersionCard "Version code" (bodySmall) |
| 2451 | VersionCard "N MB" (bodySmall) |
| 2543 | QuestionCard "pi is waiting for an answer" / "N of M answered" (labelMedium) |

`primary` (12):

| line | what it paints |
| --- | --- |
| 191 | `val accent` for the working edge sweep |
| 389 | "waiting" text (labelMedium, SemiBold) |
| 939 | ViewModeSwitch active label (bodyMedium) |
| 1454 | pinned pin-icon tint |
| 1568 | AnsweredQuestion answered "✓ …" text (bodySmall, SemiBold) |
| 1687 | CommandPalette source label (labelSmall) |
| 1735 | Composer "/" affordance (titleMedium) |
| 2267 | SettingsSection icon-square background, `primary.copy(alpha = 0.14f)` |
| 2273 | SettingsSection icon tint |
| 2341 | `val accent` for SegmentButton active state |
| 2461 | Pill "release" |
| 2562 | QuestionCard answered "✓ $answer" (bodySmall) |

`outline` (9): 221 (`val color` for the bar hairline), 1001 (PinRow border), 1113
(PinDetail prompt border), 1546 (AnsweredQuestion border), 2311 (SettingsSection panel
border), 2347 (SegmentButton inactive border), 2393 (StatusCard default `border`),
2426 (VersionCard border), 2529 (QuestionCard border).

`surfaceVariant` (6): 192 (`val track` for the working-edge dim line), 1000 (PinRow
Surface), 1112 (PinDetail prompt Surface), 1545 (AnsweredQuestion Surface), 2309
(SettingsSection panel Surface), 2528 (QuestionCard container).

`onBackground` (5): 1011 (PinRow title, promptSize/promptLineHeight), 1121 (PinDetail
prompt text), 1259 (SearchResultRow snippet, bodyMedium), 1558 (AnsweredQuestion
question, bodyMedium), 1671 (CommandPalette command name, bodyMedium).

`error` (5): 1149 (PinDetail "Delete this pin?" text, bodyMedium), 1204 (SearchPanel
`vm.searchError` text, bodySmall), 2062 (`StatusCard(border = ...)` for
`UpdateStatus.Failed`), 2066 (`status.message` text, bodySmall), 2151 (settings clear-data
warning, bodySmall).

`surface` (3): 354 (TopAppBar `containerColor`), 2397 (StatusCard Surface colour), 2424
(VersionCard Surface colour).

Singletons: 1580 `errorContainer` and 1585 `onErrorContainer` (NoticeBar error fill and
text); 1582 `secondaryContainer` and 1587 `onSecondaryContainer` (NoticeBar non-error
fill and text); 2323 `outlineVariant` (`HorizontalDivider`); 2136 `onSurface` (last-crash
monospace text); 2593 `onPrimary.copy(alpha = 0.85f)` (QuestionCard option description).

### Screens.kt - through the `scheme` local

`scheme.primary`: 868 (DeckCard accent bar), 884 (DeckCard chevron tint), 1317 (TurnPrompt
accent bar), 1333 (TurnPrompt chevron tint). `scheme.onBackground`: 135 (`LocalContentColor`
provider), 876 (DeckCard prompt text), 1325 (TurnPrompt prompt text). `scheme.surfaceVariant`:
842 (DeckCard Surface), 1300 (TurnPrompt Surface). `scheme.outline`: 843 (DeckCard border),
1301 (TurnPrompt border). `scheme.background`: 155 (`drawRect` page fill), 1298 (TurnPrompt
background behind the sticky header). This adds 13 reads; Screens.kt total is 93.

### AnswerView.kt

Direct: 244 `onPrimary.copy(alpha = 0.85f)` (LinkButton URL line, bodySmall). Through
`val scheme = MaterialTheme.colorScheme` (`:91`): `onBackground` 107, 115, 157, 175, 280,
307, 321 (heading, paragraph, bullet item, code, diff line, table header, table cell);
`surfaceVariant` 167, 299, 373x2 (code Surface fill, table header row, shimmer gradient
ends); `onSurfaceVariant` 131, 148, 210, 261 (quote text, bullet marker, "Link"/"Links"
label, diff path); `primary` 92, 125, 373 (link colour, quote accent bar, shimmer gradient
centre); `outline` 168, 195 (code border, `Rule` divider); `background` 93, 255 (the
`luminance() < 0.5f` dark check for chip/diff); `outlineVariant` 314 (table row divider).
24 reads total.

### Tactile.kt

114 `MaterialTheme.colorScheme.outline` is the default `edge` parameter of
`Modifier.tactile`, so every keyed control's extruded block is painted in `outline`.

`onSurfaceVariant`, `primary`, `error`, `surface`, and `surfaceVariant` are all read but
not set by `deriveScheme` (see Q18): they are the 11 roles the palette constructors pass
explicitly, so none of them sources from the baseline.

---

## 20. Colour literals that are not palette scheme colours

`grep -rn "Color\.\|Color("` over `app/src/main/java` returns hits only in
`AnswerStyle.kt`, `AnswerView.kt` and `Screens.kt`.

**Screens.kt**

- `val amber = Color(0xFFF0A83C)` (`:2421`) and `val green = Color(0xFF46C97E)` (`:2422`).
  Painted at `:2462-2465` as the "Update available" / "Up to date" `Pill`; `Pill` (`:2484-2499`)
  paints them as the pill text (`color = color`, `:2494`) and as the pill fill
  (`color.copy(alpha = 0.16f)`, `:2489`). The comment at `:2419-2420` says they are
  deliberately not theme colours.
- `Color.Transparent`: `:164` and `:171` (outer stop of the two radial sky glows),
  `:203` (working-edge gradient ends), `:345` (ChatScreen `Scaffold` `containerColor`),
  `:1875` (SettingsScreen `Scaffold` `containerColor`), `:2346` (SegmentButton inactive
  fill).
- `SolidColor(Color.Black)`: `:1362` and `:1386`, the path fill of the hand-built
  `CopyIcon` and `PinIcon` vectors. The `Icon` call sites pass a `tint`
  (`MaterialTheme.colorScheme.onSurfaceVariant` at `:1429`/`:1447`, `primary` or `tint`
  at `:1455`), which replaces the black fill, so black is never painted.
- `palette.scheme.background` `:2377`, `palette.scheme.outline` `:2378`,
  `palette.scheme.primary` `:2385`: ThemeSwatch. These are a palette's scheme colours,
  not new literals.

**AnswerStyle.kt**

- `private val accentDark = Color(0xFF0AD6FF)` (`:134`): used as midnight's `primary`
  (`:209`). Painted wherever `primary` is read (Q19/Q21).
- `private val accentLight = Color(0xFF3562D6)` (`:135`): used as the light scheme's
  `primary` (`:323`). Same readers in light mode.
- `val onFill = ... Color(0xFF14181F) ... Color(0xFFF6F8FB)` (`:159`): becomes `onError`
  (`:175`). No app code reads `onError` (Q19), so it is set and never painted.
- `scrim = Color(0xFF000000)` (`:178`): the scheme's `scrim`. No app code reads `scrim`.
- Sky fields, painted only by `skyBackground` (`Screens.kt:153-176`): `skyTop`/`skyMid`/`skyBottom`
  at `:159` (`Brush.verticalGradient`), `skyHorizonGlow` at `:164` and `skyMiddleGlow` at
  `:171` (`Brush.radialGradient`). Literals: midnight `:222-226`, indigo `:247-251`,
  amber `:272-276`, forest `:297-301`.
- `fun chipBackground(isDark)` (`:338-339`): `Color(0x240AD6FF)` / `Color(0x1A3562D6)`.
  Painted as the inline-code `SpanStyle(background = chipColor)` in `AnswerView.kt:403`.
- `fun diffAdd(isDark)` (`:341`): `Color(0x2886D99A)` / `Color(0x1F2F9E5B)`. Painted at
  `AnswerView.kt:271` and applied as the line background at `AnswerView.kt:283`.
- `fun diffDel(isDark)` (`:343`): `Color(0x28F08A8A)` / `Color(0x1FB4453F)`. Same sites
  (`AnswerView.kt:272`, `:283`).
- `onPrimary = Color.White` (`:324`) is a scheme-role input, so not a stray literal.

**AnswerView.kt**

- `Color.Transparent` at `:273` is the `DiffLine.Kind.CONTEXT` background applied at `:283`.

`AnswerStyle.kt:324` (`Color.White`), the per-palette values, and `accentDark`/`accentLight`
all feed a `ColorScheme` role; the `onFill` and `scrim` literals feed roles nothing reads.

---

## 21. Call sites for `onSurfaceVariant`, `error`, `primary`, `surface`, `surfaceVariant`

**`onSurfaceVariant`** - all of them paint secondary text or a tint, never a background:
Screens.kt 374 app-bar subtitle; 418 status line; 431 saved-copy line; 457 session notice;
486 empty state; 941 ViewModeSwitch inactive; 979 PinsView empty; 1016 PinRow snippet;
1218 "searching…"; 1226 "no matches"; 1265 SearchResultRow prompt; 1413 copy/share/pin
icon tint; 1566 cancelled answer; 1652 palette empty note; 1677 palette description;
1722 dictation partial; 1919, 1963, 1975, 1997, 2025, 2052, 2059, 2119, 2126, 2142, 2191
settings help/labels; 2287 settings subtitle; 2301 section chevron; 2361 SegmentButton
inactive label; 2444, 2451 VersionCard metadata; 2543 QuestionCard label. AnswerView.kt
131 quote text; 148 bullet marker; 210 "Link"/"Links" label; 261 diff path.

**`error`**: Screens.kt 1149 destructive-confirmation text; 1204 `searchError` text; 2062
Failed `StatusCard` border; 2066 Failed message text; 2151 clear-data warning text. All
text or a border, never a fill.

**`primary`**: Screens.kt 191 working-edge accent; 389 "waiting"; 939 ViewModeSwitch
active; 1454 pinned icon; 1568 answered-question check line; 1687 palette source label;
1735 "/" affordance; 2267 section icon-square background (`alpha 0.14f`); 2273 section
icon; 2341 SegmentButton accent; 2461 "release" pill; 2562 QuestionCard check line; plus
through `scheme`: 868, 884, 1317, 1333 accent bars and chevrons. AnswerView.kt 92 link
colour, 125 quote accent bar, 373 shimmer centre.

**`surface`**: Screens.kt 354 TopAppBar container, 2397 StatusCard surface, 2424
VersionCard surface.

**`surfaceVariant`**: Screens.kt 192 working-edge track; 1000 PinRow surface; 1112
PinDetail prompt surface; 1545 AnsweredQuestion surface; 2309 SettingsSection panel
surface; 2528 QuestionCard container; plus through `scheme` 842 DeckCard surface, 1300
TurnPrompt surface. AnswerView.kt 167 code block surface, 299 table header row, 373
shimmer gradient ends.

---

## 22. How `PiRemoteTheme` picks light vs dark, and how `appearance`/`theme` reach it

`PiRemoteTheme` (`Screens.kt:115-140`):

```
120:     val dark = when (mode) {
121:         "dark" -> true
122:         "light" -> false
123:         else -> isSystemInDarkTheme()
124:     }
128:     val palette = AnswerStyle.theme(theme)
129:     val scheme = if (dark) palette.scheme else AnswerStyle.lightScheme
130:     MaterialTheme(colorScheme = scheme) {
```

`AnswerStyle.theme(name)` (`AnswerStyle.kt:319`) returns `themes[name] ?: midnight`, so an
unrecognised stored name falls back to midnight. `themes` is the linked map
`midnight/indigo/amber/forest` (`:308-313`) and `defaultTheme = "midnight"` (`:316`). Light
mode always takes `AnswerStyle.lightScheme` (`:321`), whatever `theme` says; the palette's
sky is drawn only when `dark` (`Screens.kt:156-175`).

The flow into it:

- `MainActivity.kt:49` `val vm: ChatViewModel = viewModel()` then `:55`
  `PiRemoteTheme(mode = vm.appearance, theme = vm.theme) {`.
- `ChatViewModel.appearance` is `by mutableStateOf(store.appearance)` (`ChatViewModel.kt:303`),
  private set (`:304`); `theme` is `by mutableStateOf(store.theme)` (`:318`), private set (`:319`).
- Writes go through `updateAppearance` (`ChatViewModel.kt:941-944`:
  `store.appearance = value; appearance = value`) and `updateTheme` (`:959-962`:
  `store.theme = value; theme = value`). The settings screen is handed
  `appearance = vm.appearance` and `onAppearanceChange = vm::updateAppearance`
  (`MainActivity.kt:194-195`), `theme = vm.theme` and `onThemeChange = vm::updateTheme`
  (`:196-197`); the matching params are declared at `Screens.kt:1821-1824`.
- The store keys are `SettingsStore.appearance` (`data/SettingsStore.kt:43-45`, default
  `"dark"`) and `SettingsStore.theme` (`:72-74`, default `"midnight"`), backed by the
  `"pi-mobile"` `SharedPreferences` (`:8`). The settings picker offers `dark`/`light`/`system`
  for appearance (`Screens.kt:2011`) and one button per `AnswerStyle.themes` entry
  for theme.

---

## 23. Current layout of a command palette row

`CommandPalette` is `Screens.kt:1631-1694`. The column (`:1640-1645`):

```
1640:     Column(
1641:         modifier = modifier
1642:             .heightIn(max = 240.dp)
1643:             .verticalScroll(rememberScrollState())
1644:             .padding(horizontal = 12.dp, vertical = 4.dp),
1645:     ) {
```

There is no `verticalArrangement`, so the default is `Arrangement.Top` and **item spacing
is 0**. Max height is **240.dp**.

The row (`:1658-1665`):

```
1658:             Row(
1659:                 modifier = Modifier
1660:                     .fillMaxWidth()
1661:                     .clip(RoundedCornerShape(AnswerStyle.promptRadius))
1662:                     .clickable { onPick(command) }
1663:                     .tactile()
1664:                     .padding(horizontal = 8.dp, vertical = 8.dp),
1665:                 verticalAlignment = Alignment.CenterVertically,
1666:             ) {
```

`AnswerStyle.promptRadius = 10.dp` (`AnswerStyle.kt:97`). `tactile()` with no arguments is
`tactile(haptics = false, enabled = true, pressScale = 0.97f, depth = 0.dp, ...)`
(`Tactile.kt:110-114`), so it adds no height (the `padding(bottom = depth)` at `Tactile.kt:186`
is skipped when `depth == 0`). Row padding is 8.dp top and 8.dp bottom.

Text inside (right sibling in the same `Row`):

- `"/${command.name}"`, `style = MaterialTheme.typography.bodyMedium` (`:1668-1672`) -
  14.sp / 20.sp, line height from the Material default.
- `command.description`, `style = ...bodySmall`, `maxLines = 1`, ellipsised
  (`:1674-1680`) - 12.sp / 16.sp, Material default.
- `command.source`, `style = ...labelSmall`, `color = primary`,
  `modifier = Modifier.padding(start = 8.dp)` (`:1683-1689`) - 11.sp / 16.sp, Material
  default; it is vertically centred and shorter than the column, so it does not set the
  row height.

**Adds up to** (see Q25): 52.dp with a description, 36.dp without, at `fontScale` 1.0.

---

## 24. Current layout of `SearchResultRow`

`Screens.kt:1248-1272`:

```
1250:     Column(
1251:         modifier = Modifier
1252:             .fillMaxWidth()
1253:             .clickable(enabled = openable, onClick = onOpen)
1254:             .padding(horizontal = 16.dp, vertical = 10.dp),
1255:     ) {
1256:         Text(
1257:             text = hit.snippet,
1258:             style = MaterialTheme.typography.bodyMedium,
1259:             color = MaterialTheme.colorScheme.onBackground,
1260:         )
1261:         if (hit.prompt != null) {
1262:             Text(
1263:                 text = hit.prompt,
1264:                 style = MaterialTheme.typography.bodySmall,
1265:                 color = MaterialTheme.colorScheme.onSurfaceVariant,
1266:                 maxLines = 2,
1267:                 overflow = TextOverflow.Ellipsis,
1268:                 modifier = Modifier.padding(top = 4.dp),
1269:             )
1270:         }
1271:     }
```

- Padding: 16.dp horizontal, 10.dp vertical (`:1254`).
- Snippet: `bodyMedium` (14.sp / 20.sp, Material default), **no `maxLines`**, so it wraps.
- Prompt: `bodySmall` (12.sp / 16.sp, Material default), `maxLines = 2` (`:1266`), plus
  4.dp top padding (`:1268`).
- The rows sit in `LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f))`
  (`SearchPanel`, `Screens.kt:1229-1237`) with no `verticalArrangement` and no
  `contentPadding`, so rows are adjacent.

---

## 25. Row heights at `fontScale` 1.0 and 2.0

`fontScale` multiplies `sp` only: a `20.sp` line height is 20.dp of layout at 1.0 and
40.dp at 2.0. All paddings below are `dp` and do not change.

**Command palette row** (`Screens.kt:1658-1690`)

| case | fontScale 1.0 | fontScale 2.0 |
| --- | --- | --- |
| with description | 20 (bodyMedium) + 16 (bodySmall) + 8 + 8 = **52.dp** | 40 + 32 + 8 + 8 = **88.dp** |
| without description | 20 + 8 + 8 = **36.dp** | 40 + 8 + 8 = **56.dp** |

**`SearchResultRow`** (`Screens.kt:1250-1270`)

| case | fontScale 1.0 | fontScale 2.0 |
| --- | --- | --- |
| snippet + prompt | 20 + 4 + 16 + 10 + 10 = **60.dp** | 40 + 4 + 32 + 10 + 10 = **96.dp** |
| snippet only | 20 + 10 + 10 = **40.dp** | 40 + 10 + 10 = **60.dp** |

Line heights used, and their source: every style above is a `MaterialTheme.typography`
value with no explicit `lineHeight` at the call site, so the Material3 defaults apply
(bodyMedium 20.sp, bodySmall 16.sp, labelSmall 16.sp; source table at the top of this
document). At `fontScale` 1.0: palette row 52/36.dp, search row 60/40.dp. At
`fontScale` 2.0: 88/56.dp and 96/60.dp.

`needs-device`: the values above are the **single-line** heights and are derivable from
source. The palette row's description is capped at one line (`:1678`) but has no explicit
`lineHeight`, so it is one Material line; the only wrap risk is the command name itself.
`SearchResultRow`'s snippet has no `maxLines` (`:1256-1260`) and its prompt allows two
lines (`:1266`), so on a real device a long snippet or a two-line prompt makes the row
taller than the table. The exact wrapped height depends on the screen width, the font
metrics and the text, and can only be confirmed on a device: `needs-device`.

For the 48.dp floor the ticket names: at 1.0 the palette row is 36.dp when a command has no
description (`:1673` guards the second `Text`), and the search row is 40.dp when
`hit.prompt` is null (`:1261`); a single-line prompt lifts the search row to 60.dp. At 2.0
both rows exceed 48.dp. None of these is an R6 recommendation, just the current numbers.

---

## 26. The other fixed-size spots the review flags

**`Modifier.height(24.dp)` waiting slot** - `Screens.kt:384`:

```
384:                     Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
```

It holds either `Text("waiting", style = ...labelMedium, color = ...primary,
fontWeight = SemiBold)` (`:386-391`, labelMedium 12.sp / 16.sp) or `WorkingShimmer()`
(`:392`), which is `Modifier.width(64.dp).height(8.dp)` (`AnswerView.kt:366-369`). The
Text's line box is 16.dp at 1.0 (fits 24.dp) and 32.dp at 2.0 (exceeds the 24.dp Box).
`needs-device` for what the Box does when its child is taller.

**Fixed bullet column** - `AnswerView.kt:146-152`:

```
146:                                 Text(
147:                                     text = if (block.ordered && !item.marker) "$counter." else "•",
...
149:                                     fontSize = AnswerStyle.bodySize,
150:                                     lineHeight = AnswerStyle.bodyLineHeight,
151:                                     modifier = Modifier.width(AnswerStyle.bulletIndent),
152:                                 )
```

`AnswerStyle.bulletIndent = 20.dp` (`AnswerStyle.kt:106`), `bodySize = 16.sp` (`:47`),
`bodyLineHeight = 24.sp` (`:54`), both explicit at the call site. The column stays 20.dp
wide while the glyph is 16.sp at 1.0 and 32.sp at 2.0, so an ordered list's "10." grows
against a fixed 20.dp. `needs-device` for the exact clip/wrap.

**App-bar `maxLines = 1` lines**:

- `:360-365` title `Text(vm.barTitle, style = ...titleMedium, maxLines = 1, overflow = Ellipsis)`
  - titleMedium 16.sp / 24.sp, so 24.dp at 1.0 and 48.dp at 2.0.
- `:371-377` subtitle `Text(vm.lastPrompt ?: vm.attachedLabel, style = ...bodySmall,
  color = ...onSurfaceVariant, maxLines = 1, overflow = Ellipsis)` - bodySmall 12.sp /
  16.sp, 16.dp at 1.0 and 32.dp at 2.0.

Both are ellipsised to one line, so they never wrap; the fixed container is the app bar
itself. The Material3 small `TopAppBar` container height is 64.dp
(`TopAppBarSmallTokens.ContainerHeight = 64.0d` in the resolved material3 1.3.1 jar). At
2.0 the two lines' 48 + 32 = 80.dp exceeds 64.dp. `needs-device` for whether the bar grows
or clips.

**Status line `maxLines = 1`** - `Screens.kt:414-422`:

```
417:                     style = MaterialTheme.typography.bodySmall,
418:                     color = MaterialTheme.colorScheme.onSurfaceVariant,
419:                     maxLines = 1,
420:                     overflow = TextOverflow.Ellipsis,
421:                     modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
```

bodySmall 12.sp / 16.sp, one ellipsised line; the container is not fixed, so it grows from
16 + 8 = 24.dp at 1.0 to 32 + 8 = 40.dp at 2.0 while the text is clipped to one line.

---

## 27. `heightIn(min = ...)` versus fixed `height(...)`/`size(...)`

**Screens.kt**

- `heightIn(min = ...)`: 15 sites - `heightIn(min = AnswerStyle.buttonHeight)` (56.dp) at
  1083, 1139, 1159, 1172, 1757, 1909, 1934, 1979, 2088, 2155, 2176, 2472, 2575, 2618
  (14 sites), and `heightIn(min = 48.dp)` at 2344 (SegmentButton). Plus one
  `heightIn(max = 240.dp)` at 1642 (command palette). Total `heightIn` = 16.
- Fixed `height(...)`: 8 sites - 384 `height(24.dp)` (waiting slot), 696 `height(1.dp)`
  (bottom spacer), 852 `height(IntrinsicSize.Min)` (DeckCard prompt row), 1179
  `height(12.dp)` (spacer), 1296 `height(IntrinsicSize.Min)` (TurnPrompt row), 1751
  `height(8.dp)` (spacer), 2187 `height(12.dp)` (spacer), 2194 `height(12.dp)` (spacer).
  Six are fixed `dp`; two are `IntrinsicSize.Min` and size to content.
- Fixed `size(...)`: 9 sites - 885 `size(22.dp)` and 1334 `size(22.dp)` (prompt/deck
  chevrons), 1429/1447/1455 `size(18.dp)` (copy, share, pin icons), 2265 `size(36.dp)`
  (settings section icon square), 2274 `size(20.dp)` (section icon), 2375 `size(18.dp)`
  (theme swatch), 2383 `size(8.dp)` (swatch dot).

**AnswerView.kt**

- `heightIn(min = ...)`: 2 sites - 123 `heightIn(min = 18.dp)` (quote accent bar) and 229
  `heightIn(min = AnswerStyle.buttonHeight)` (LinkButton). No `heightIn(max = ...)`.
- Fixed `height(...)`: 3 sites - 194 `height(1.dp)` (`Rule`), 314 `height(1.dp)` (table
  row divider), 369 `height(8.dp)` (WorkingShimmer). All fixed `dp`.
- Fixed `size(...)`: 0 sites.

The fixed-width sites in `AnswerView.kt` for completeness: 122 `width(AnswerStyle.accentBar)`
(3.dp), 151 `width(AnswerStyle.bulletIndent)` (20.dp, Q26), 368 `width(64.dp)` (shimmer).
