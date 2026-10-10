# Pi Remote — UI/UX review

Reviewed: commit `e976063` (v0.0.57), 2026-10-10. Files read in full: `ui/Screens.kt`,
`ui/AnswerView.kt`, `ui/AnswerBlocks.kt`, `ui/AnswerStyle.kt`, `ui/Tactile.kt`, `ui/Questions.kt`,
`ui/ChatViewModel.kt`, `MainActivity.kt`, `voice/Dictation.kt`, `net/Diagnostics.kt`,
`AndroidManifest.xml`, `res/values/themes.xml`, plus `docs/PRODUCTION-READINESS.md`, `GLOSSARY.md`
and the two design labs in `~/pi-remote-design-lab`.

## The standard this review used

The brief named a Medium roadmap, *The ultimate UI/UX design roadmap* (Precious Ossai, Bootcamp,
Aug 2023), and its links. Two things about that article matter before the analysis:

1. It is a **self-learning roadmap**, not an evaluation rubric. It has four stages: Step 2 learn UI
   design (train your eye; learn the fundamental principles of colour, layout, spacing, scale,
   typography, alignment, hierarchy, positioning; practice), Step 3 learn UX design (research,
   user flows, usability), Step 4 document the work as case studies, Step 5 improve through
   community and iteration. The article's own evaluative content is in the resources it links.
2. Two of its links are dead (CareerFoundry is ceasing operations and its UX short course 404s),
   and two are paid course pages that state no principles (`refactoringui.com`,
   `learnui.design`). The rubric below was distilled from what the remaining links actually state,
   plus the two books the article names (Norman, Krug).

**The rubric**, 48 principles under twelve headings, taken from: NN/g's ten usability heuristics
and *Usability 101* (the article links nngroup.com as a primary UX source), Refactoring UI (its
published table of contents), Learn UI Design (its public principles blog), CareerFoundry,
UserTesting. Principle numbers below (`P1`..`P48`) are from that distillation; the five that two
sources agree on are the load-bearing ones: double the whitespace (P19), use good fonts (P10/P13),
design the whole experience and not just the UI (P46), follow platform and real-world conventions
(P23/P24), and keep required information visible rather than hidden (P35/P37).

## 1. Visual hierarchy and layout

**What is right.** The reading column is capped at 460dp (`AnswerStyle.kt:120-126`, applied
`Screens.kt:1129`, `:1484`, `:1516`, `AnswerView.kt:99`), the prompt sits a step above the answer
in both size and weight (`AnswerStyle.kt:88-97`), one answer is open at a time as an accordion,
and prompts are sticky headers (`Screens.kt:656-666`). That is real hierarchy work (P2, P6, P11,
P19).

**Findings.**

- **Up to six competing message lines can stack above the content.** `Screens.kt:414-421` (status),
  `:427-434` (saved copy), `:435-438` (update notice), `:439-442` (crash notice), `:443-445`
  (error), `:446-448` (action confirmation), plus the session-move line at `:450-458`. Every bar
  renders through the same `NoticeBar` (`Screens.kt:1576-1609`), so nothing outranks anything else
  and a stale error can sit above a fresh confirmation. Fails P1 (de-emphasise to emphasise) and
  P21 (avoid ambiguous spacing).
- **The composer's action row scrolls horizontally.** `Screens.kt:1752-1809` holds up to five
  buttons in a `horizontalScroll` row: `Auto-paste`, `Mic`, the action (`Send`/`Search`), `Clear`,
  and `Stop` last. On a narrow phone the panic button is off-screen, and the primary action moves
  depending on how many buttons are present. Fails P33 (a clearly marked emergency exit), P37
  (obvious always wins), P35 (make options visible).
- **Two "pick one of N" controls with two visual languages.** `ViewModeSwitch`
  (`Screens.kt:924-949`) is three `TextButton`s marked with a literal `"• "` prefix;
  `SegmentButton` (`Screens.kt:2333-2368`) is a tinted, filled key. The active state is a glyph in
  one and a fill in the other. Fails P23 (internal consistency).
- **Empty state is bare.** `turns.isEmpty() && pending == null` renders the words `no results yet`
  (or `thinking…`) centred with no explanation and no action (`Screens.kt:481-487`). This is the
  first screen of a fresh install. Fails P8 (do not overlook empty states). Contrast: the pins
  empty state does it properly (`Screens.kt:975-982`).

## 2. Typography

**What is right.** There is a real scale (16sp body, 24sp line height = 1.5; 17sp/26sp prompts;
19sp headings; 14sp code) and the code records why it was raised from the design lab's 14sp/1.42
(`AnswerStyle.kt:44-56`). Line length is bounded (P11), line height is proportional (P12), and the
platform font is used rather than a decorative one (P10/P13).

**Findings.**

- Two type systems coexist: `MaterialTheme.typography.*` and the `AnswerStyle` sp values. Labels,
  notices and status lines use Material's scale (`bodySmall` 12sp at `Screens.kt:1601`, `:415`),
  while the answer uses the custom one. The smallest text in the app carries its most important
  short messages.
- Fixed-height containers hold text that can grow (see section 10).

## 3. Colour and contrast

The palettes are complete rather than ad hoc: four dark themes and one light scheme, with
`deriveScheme` (`AnswerStyle.kt:152-189`) filling the roles Material3 falls back on (P16). No grey
text on a coloured fill anywhere (P15).

Measured contrast (WCAG 2.1, sRGB relative luminance; AA needs 4.5:1 for normal text, and every
text in question here is 12sp to 14sp, so the large-text exemption does not apply):

| pair | Midnight | Indigo | Amber | Forest | Light |
| --- | --- | --- | --- | --- | --- |
| `onSurfaceVariant` on `background` | **4.38** | **4.03** | 4.66 | 5.32 | 5.51 |
| `onSurfaceVariant` on `surfaceVariant` | **3.96** | **3.61** | **4.15** | 4.75 | 5.31 |
| `error` on `background` | **4.43** | **4.43** | **4.33** | **4.24** | **4.10** |
| `primary` on `background` | 11.18 | 7.72 | 9.71 | 7.31 | 5.07 |
| `onBackground` on `background` | 16.95 | 16.74 | 16.65 | 16.61 | 16.61 |

So the accents pass comfortably, and the secondary and error text misses AA in most themes. The
secondary colour is what the app uses for hints, status lines, palette descriptions, search
snippets and the session label.

**The worst case is the light scheme's update pills.** `Screens.kt:2421-2422` hard-codes
`amber = Color(0xFFF0A83C)` and `green = Color(0xFF46C97E)`, and `Pill` paints
`background(color.copy(alpha = 0.16f))` with `color` as the text (`Screens.kt:2484-2499`). Over the
light card surface `#F6F7FA` that measures **1.71:1** for "Update available" and **1.77:1** for
"Up to date". In the dark themes the same pair measures 6.3:1 to 6.9:1, which is why it was not
noticed. These two colours are outside the token system, so no theme change re-checks them
(P14, P16).

## 4. Spacing and alignment

A spacing system exists and is named (`AnswerStyle.kt:58-126`: `paragraphGap` 10dp, `blockGap`
12dp, `headingGap` 14dp, `listItemGap` 8dp, `answerGap` 26dp, `buttonHeight` 56dp, `keyDepth` 5dp,
`promptPadding` 12dp, `accentBar` 3dp + 10dp). P20 is satisfied for the answer view.

It is only half applied: `Screens.kt` is full of raw `8.dp`, `10.dp`, `12.dp`, `14.dp`, `16.dp`,
`24.dp` literals in the same vertical run (for example `padding(horizontal = 16.dp, vertical =
14.dp)` at `:2258`, `padding(12.dp)` at `:1719`, `spacedBy(8.dp)` at `:1770` against
`spacedBy(10.dp)` elsewhere). Prompt rows even carry inline arithmetic to make the accent bar line
up: `promptPadding - 2.dp` (`Screens.kt:1296-1308`, `:852-859`). That is a token system the
screens do not obey (P20, P21).

## 5. Consistency and platform convention

- **System back is not handled anywhere.** There is no `BackHandler`, `onBackPressed`,
  `OnBackPressedDispatcher` or predictive-back callback in the whole app (verified by grep over
  `app/src/main`). With settings open, a pin open, search open, the palette open, or a question
  waiting, the system back gesture finishes the Activity and drops the user out of the app. The
  only way back is the on-screen `Back` button (`Screens.kt:1884-1886`) or `Back to pins`
  (`Screens.kt:1051-1053`). P25 names the Android back gesture explicitly; P23 and P33 apply too.
  This is the largest platform-convention gap in the app.
- **The `notice` state is write-only, so eight messages never reach the user.** `MainActivity.kt:62`
  declares `var notice`, written at `:94`, `:96`, `:114`, `:124`, `:142`, `:145`, `:150`, `:153`,
  `:231`, and read nowhere: the `ChatScreen(notice = …)` argument at `:217` is computed from
  `updateStatus` and never consults it. Since commit `39fe582` (2026-10-09, "Give the dark side
  four themes and rewrite the settings screen") replaced `notice = pendingUpdate?…` with the
  derived expression and deleted the `notice = null` clear, these have been silent:
  `Microphone permission is required for dictation`, `Could not start dictation: …`, every
  `Dictation` error (`Speech recognition is not available on this device`, `… stopped (error N)`),
  `That link is not a pairing code`, `Pairing…`, `Pairing failed: …`, `Allow installs for Pi
  Remote…`, `Installer launched`, `Update failed`. Pairing failure and microphone failure are
  silent. Fails P28, P29, P32.
- **The update banner's button says `OK` and installs an APK.** `NoticeBar`'s only button is a
  `TextButton` labelled `OK` (`Screens.kt:1607`), and `onDismissNotice` installs the update
  (`MainActivity.kt:236-241`). A control labelled OK that commits a package install mislabels
  itself (P24, P31).
- **Four tappable controls miss the press-scale the project decided on.** The tactile lab's
  recorded choice (`press_motion: 1a`, "scale on every tappable control") is applied through
  `Modifier.tactile()`, which is absent on the three answer action icons (`Screens.kt:1418`,
  `:1432`, `:1450`) and the composer's `/` affordance (`:1731`), while every other control has it.
- **Copy and Share ring the outcome haptic, which the design record reserves elsewhere.** The same
  lab chose `surfaces: 4b`, "only controls that change pi's state", explicitly excluding
  navigation and links; `Haptics.confirm` is called for copy and share at `Screens.kt:1421` and
  `:1439`, and the KDoc at `:1400-1402` states this is deliberate ("a tap always says it did
  something"). The record and the code disagree, which is a documentation problem more than a
  design one, but it will keep being re-litigated until one of them is amended.
- **Vocabulary drifts inside one screen.** The view switch offers `Transcript / Cards / Pins`
  (`Screens.kt:938-946`) while the state value, the composable and the README call it the deck
  (`ChatViewModel.kt` `viewMode == "deck"`, `TurnDeck` at `Screens.kt:707`). The GLOSSARY pins the
  bridge's vocabulary but not the UI's.

**What is right here.** Press behaviour, ripple and haptics are consistent where they are applied:
`tactile()` leaves the Material ripple alone (`Tactile.kt:98-100`), haptics use platform
`HapticFeedbackConstants` only (`Tactile.kt:49-80`), which needs no `VIBRATE` permission and
follows the system-wide haptics setting, and press motion is deliberately kept out of layout so
rows never shift (`Tactile.kt:88-97`, `Screens.kt:184-186`, `:384-385`). That matches the
project's own design lab, which cites Android's haptics guidance line by line.

## 6. Feedback and system status

**What is right.** The answer streams live, there is a working sweep, a `waiting` state for a
pending question, and a status line. The app never leaves a tap unanswered where `actionNotice` is
wired (copy, share, pin, title saved, prompt sent, pin deleted) with a 2s auto-clear
(`Screens.kt:277-281`).

**Findings.**

- The product position is "answers, and nothing else" (README). On a run that takes minutes the
  only signals are a 1.3s sweep (`AnswerView.kt:333`, `:343-355`) and, in the empty state, the word
  `thinking…`. Nothing distinguishes queued from running from stalled, and there is no elapsed
  time. This is a deliberate trade-off and the project's own backlog already flags it
  (`docs/PRODUCTION-READINESS.md` Tier 1.2: "The results-only view hides real information a long run
  needs"). It still fails P28 (keep users informed of system status).
- **Nothing tells you when the answer lands.** There is no notification code, no service and no
  wake lock (confirmed by grep and by `PRODUCTION-READINESS.md`). Locking the phone mid-run means
  coming back to check. For this product that is the single largest gap between the design and the
  job to be done.
- **The status line truncates its own advice.** It renders `maxLines = 1` with ellipsis
  (`Screens.kt:419-420`) and its content is `"failed · <url> · <detail> · <hint>"`
  (`Diagnostics.kt:104-108`), so the actionable part is cut off on a phone. The settings screen
  prints the same string unclipped (`Screens.kt:1991-1993`). The hints themselves are excellent
  plain-language advice (P32), which is exactly why hiding them is a waste.
- Message lifetimes are inconsistent: `lastError` is dismissible, `searchError` is not (only
  replaced, `Screens.kt:1208-1218`), `UpdateStatus.Failed` is only replaced, `actionNotice` expires
  in 2s, and `notice` is invisible.

## 7. Error prevention and recovery

**What is right.** Deleting a pin and clearing saved data both ask twice (`Screens.kt:1172-1176`,
`:2177-2183`), sending is disabled when the bridge is not connected (`ChatViewModel.kt:1280-1281`),
and a wrong-session refusal (HTTP 409) is caught, re-checked and explained rather than retried
blindly (`ChatViewModel.kt:1291-1298`).

**Findings.**

- **No undo anywhere** (P34). `Clear` in the composer and in search is immediate
  (`Screens.kt:1792-1800`), and in the composer it sits at the end of the scrolling row, one thumb
  slip from `Send`. `Stop` is immediate, which is correct, and `Cancel question` is immediate and
  records a cancellation.
- **Changing the bridge silently discards the phone's record.** Saving a different URL or token
  clears every message and forgets the session (`ChatViewModel.kt:436-449`), and pairing does the
  same (`ChatViewModel.kt:1067-1077`) with no warning that the cached transcript for the old bridge
  goes with it. A bridge handover also clears the screen and explains itself only afterwards
  (`ChatViewModel.kt:706-735`).
- The `Action` for installs is unconfirmed in-app (the OS installer does confirm), so that gap is
  acceptable.

## 8. User control and freedom

- **State is lost whenever the user follows the app's own advice.** `searchOpen`, `query`,
  `paletteOpen`, `paletteDismissed` (`Screens.kt:261-271`), `openedId` (`:963`), the pin title edit
  (`:1041-1043`), a typed question answer (`:2522`), and the transcript's accordion position
  (`:607-608`) and deck page (`:724`) all live in `rememberSaveable` inside screens that leave
  composition when the other screen is shown (`MainActivity.kt:189` vs `:212`). Open a pin, start
  renaming it, go to Settings to check the token, come back: the pin and the edit are gone. The
  message the app shows, `Add the bridge token in Settings`, sends the user straight into that
  loss. Fails P33 and P35.
- The composer draft does survive, because it lives in the ViewModel (`ChatViewModel.kt:274-275`).

## 9. Recognition over recall

**What is right.** The `/` palette lists commands with descriptions and their source, so a skill
reads as a skill (`Screens.kt:1621-1694`), the app bar carries the session label and the last
prompt so a phone pointed at the wrong pane is obvious (README, and `ChatViewModel.kt:381-398`),
and prompts stay visible as sticky headers while their answer scrolls.

**Findings.**

- The `/` affordance is a `Text` glyph inside an `IconButton` (`Screens.kt:1731-1737`) with no
  description, so TalkBack reads "slash" and nothing tells a new user commands exist (P35, P36).
- **The manual is in the settings prose.** Nine long paragraphs sit inside settings sections, and
  two of them instruct the user to act on the laptop (`Screens.kt:1915-1918`,
  `On the laptop run /pair…`; `:1973`, `On the laptop: cat ~/.config/pi-remote/token`). There is no
  help screen and nothing is searchable inside the app (P36, P47, P48). The README is the real
  manual, and it is not on the phone.
- `Clear the saved data and the trace` names internal mechanisms; the troubleshooting prose
  explains how the crash recorder works rather than what to do.

## 10. Accessibility

This is the weakest area, and it is untested in the project's own words
(`PRODUCTION-READINESS.md` Tier 1.9).

- **No semantics at all beyond images.** Zero `Modifier.semantics`, `clearAndSetSemantics`,
  `stateDescription`, `role =`, `LiveRegion` or `heading()` in `app/src/main`. `AnswerView.kt`, the
  main reading surface, contains no `contentDescription` and no heading markup, so an answer is one
  undifferentiated block to TalkBack (P39, P40, P35).
- **Eleven `contentDescription`s exist, all in `Screens.kt`** (`:398`, `:402`, `:883`, `:1052`,
  `:1105`, `:1332`, `:1427`, `:1445`, `:1453`, `:2272` (`null`), `:2300`).
- **Seven clickable controls are not announced as controls**, because they are a `clickable` `Box`,
  `Row` or `Surface` with no role: `SegmentButton` (`:2333-2347`), `PinRow` (`:997-1023`),
  `TurnPrompt` (`:1286-1303`), the deck card header (`:852-853`), `SearchResultRow`
  (`:1249-1257`), the palette row (`:1660-1668`) and the settings section row (`:2247-2259`).
- **The selected state is not exposed.** Which theme, appearance or view is active is conveyed by
  colour and a bullet only (`ViewModeSwitch` `:938-946`, `SegmentButton` `:2347`), with no
  `selected` or `stateDescription`, so a screen-reader user cannot tell (P17, P39).
- **A streaming answer is not announced.** With no live region, the text that arrives during a run
  is invisible to TalkBack until the user navigates to it (P28).
- **Two interactive rows are under the 48dp minimum.** The command palette row is 16dp of padding
  plus one 20sp line for a row with no description (about 36dp, `Screens.kt:1660-1668`), and
  `SearchResultRow` is 20dp of padding plus one line (about 40dp, `:1250-1255`). Everything else is
  stated at 48dp (`SegmentButton` `:2344`) or 56dp (`AnswerStyle.buttonHeight`) (P39).
- **Large font scales clip.** A fixed `Modifier.height(24.dp)` box holds the `waiting` label
  (`Screens.kt:384-386`); the bullet marker column is a fixed 20dp wide (`AnswerView.kt:151`), which
  clips `10.` as it grows; the app bar's two lines are `maxLines = 1` (`Screens.kt:363`, `:375`).
  Layouts are otherwise built on `heightIn(min = …)`, which is the right shape (P40).
- **Contrast**: see section 3.
- **No edge-to-edge or inset handling** with `targetSdk = 35` (no `enableEdgeToEdge`, no
  `WindowInsets`), which `PRODUCTION-READINESS.md` Tier 0.7 already tracks; on Android 15 the
  composer can sit under the gesture bar.

**What is right here.** Text is selectable (`AnswerView.kt:96`), links are real `LinkAnnotation`s,
tap targets are mostly 48dp or 56dp, haptics follow the system setting and need no permission,
the camera is optional (`required="false"`), there is no analytics, and `FLAG_SECURE` is set while
the token is on screen (`MainActivity.kt:174-187`). That last one is a privacy affordance most
apps at this size do not have.

## 11. Navigation and information architecture

One Activity, two destinations chosen by a boolean with no back stack (`MainActivity.kt:58`,
`:189`, `:212`). Five states share the chat screen through an if/else chain
(`Screens.kt:462-500`): search open, pins, empty, deck, transcript. The chain is sound and the
combinations it forbids are sensible, but two consequences follow: the app has no back stack to
pop (section 5), and while Pins is selected the waiting question's card is not reachable, though
the app bar still says `waiting` (`Screens.kt:386-394`), which is the right mitigation.

There is no session picker, no per-session history and no context or cost readout. Those are
product decisions with their own backlog entries (`PRODUCTION-READINESS.md` Tier 1.1, 1.2).

## 12. Documentation, help and the design record

The article's Step 4 (document the work) is where this project is unusually strong: two design
labs with recorded choices and cited platform guidance, a glossary, two ADRs, a production
readiness backlog, and retro notes.

The record has drifted from the code:

- `AnswerStyle.kt:18-20` cites `pi-remote-design-lab/out/choices-latest.json`, which no longer
  exists; the directory holds only `choices-tactile-latest.json`.
- `PRODUCTION-READINESS.md` still lists as open things that shipped: Tier 0.8 (pairing by QR),
  Tier 1.7 (history search), Tier 1.13 (haptics and motion, shipped through `Tactile.kt` and the
  tactile lab), Tier 2.9 (crash visibility, shipped today as `CrashLog`). A stale backlog hides what
  is actually left.
- The two divergences in section 5 (press-scale missing on four controls; confirm haptics on copy
  and share) are the code disagreeing with a recorded decision. Neither is in the record.

There is also no place for the UI's own vocabulary: the GLOSSARY covers the bridge (owner, lease,
handover) but not what the screens call things, which is why `Cards` and `deck` coexist.

## 13. Iteration and testing

Step 3 and Step 5 of the roadmap are about understanding users, testing with them, and iterating.
Understanding is covered (the labs are user research with an audience of one, which is the correct
audience here). Testing is not: no usability test has been run, no TalkBack pass, no fontScale
check, no device test in CI (which runs `assembleRelease` only, and no lint or test job,
`PRODUCTION-READINESS.md` Tier 2.2, 2.1). That is why a regression as visible as a write-only
`notice` survived a day of shipping: nothing was looking.

Two of the roadmap's own recommendations fit a project of this size without ceremony: test early
and often rather than at the end (P44), and a handful of testers is enough (P43). Here the tester
is the owner and the "handful" is one, run against the eight core flows.

## Recommendations

Effort: S is under half a day, M is a day or two, L is more. "PRD" notes an existing backlog entry.

**P0, bugs and correctness (do first)**

| # | Recommendation | Why (principle) | Evidence | Effort | PRD |
| --- | --- | --- | --- | --- | --- |
| R1 | Make `notice` reach the screen again, or replace both mechanisms with one message channel that dismisses. | P28, P29, P32 | `MainActivity.kt:62`, 10 write sites, `:217`; regression in `39fe582` | S | 2.9 |
| R2 | Handle system back: close settings, pin detail, search, palette, in that order. | P23, P25, P33 | no `BackHandler` anywhere | S | — |
| R3 | Label the update banner's button for what it does (`Install`), and let a notice be dismissed without installing. | P24, P31 | `Screens.kt:1607`, `MainActivity.kt:236-241` | S | — |
| R4 | Move the off-token amber and green into the theme tokens so light mode has a readable pair. | P14, P16 | `Screens.kt:2421-2422`; measured 1.71:1 and 1.77:1 in light | S | — |
| R5 | Raise secondary and error text in the Midnight and Indigo palettes to 4.5:1, or reserve them for text 18sp and up. | P14 | measured 3.96 to 4.43 across the table in section 3 | S | — |
| R6 | Give the two sub-48dp rows a 48dp minimum. | P39 | `Screens.kt:1660-1668`, `:1250-1255` | S | 1.9 |

**P1, structure (the design work)**

| # | Recommendation | Why | Evidence | Effort | PRD |
| --- | --- | --- | --- | --- | --- |
| R7 | One notice area with a priority order, one dismiss, auto-expiry for confirmations and no expiry for errors. | P1, P2, P21 | six lines can stack, `Screens.kt:414-448` | M | — |
| R8 | Show the diagnostic hint first and let it wrap to two lines; put the raw detail behind a disclosure. | P32 | `Screens.kt:419-420`, `Diagnostics.kt:104-108` | S | — |
| R9 | Pin the primary action out of the scrolling composer row, and keep `Stop` in the same slot as `Send`. | P33, P35, P37 | `Screens.kt:1752-1809` | M | — |
| R10 | One control for "pick one of N" (view mode and theme/appearance), with the selection exposed to TalkBack. | P23, P17 | `Screens.kt:924-949` vs `:2333-2368` | M | — |
| R11 | Keep the screen's state across a trip to Settings, by hoisting search, open pin, edit draft and accordion position into the ViewModel or a `SaveableStateHolder`. | P33, P35 | `Screens.kt:261-271`, `:963`, `:1041-1043`, `:2522`, `:607-608`, `:724` | M | — |
| R12 | Give a long run a status without breaking "answers only": elapsed time on the working indicator, and the last tool line once the run is older than a few seconds, both subtle. | P28 | `Screens.kt:484`, `AnswerView.kt:333`; PRD 1.2 agrees | M | 1.2 |
| R13 | Notify on "needs an answer" and "run finished", with the answer or the options reachable from the notification. | P28 | no notification code; the phone is usually pocketed | L | 0.2-0.4 |
| R14 | First-run state that says what is missing (not paired, not connected, no turns) and points at pairing. | P8, P36 | `Screens.kt:481-487`; `Add the bridge token in Settings` | S | 0.12 |
| R15 | Accessibility pass: roles on the seven clickable rows, selection state, a live region for the streaming answer, headings in `AnswerView`, and fontScale 2.0 layouts for the three fixed-size spots. | P39, P40, P28 | section 10 | M | 1.9 |
| R16 | A Help screen: move the long prose out of settings, keep one-line subtitles, make the laptop commands tap-to-copy. | P36, P47, P48 | `Screens.kt:1915-1918`, `:1973` | M | — |

**P2, polish and hygiene**

| # | Recommendation | Why | Evidence | Effort | PRD |
| --- | --- | --- | --- | --- | --- |
| R17 | Finish the token pass: replace the raw dp literals in `Screens.kt` with `AnswerStyle` tokens, and delete the `promptPadding - 2.dp` arithmetic. | P20, P21 | section 4 | M | — |
| R18 | Reconcile the design record: fix the dead lab path, update the readiness backlog with what shipped, and resolve the two code-versus-record divergences (either fix the code or amend the decision). | Step 4 | section 12 | S | — |
| R19 | Edge-to-edge and insets, and an app icon. | P23 | PRD 0.7, 0.1 | S | 0.7, 0.1 |
| R20 | Make the custom working sweep respect the system animation scale, which Compose animations already do. | P29 | `AnswerView.kt:343-355` bypasses `MotionDurationScale` | S | 1.13 |
| R21 | A release smoke pass: the eight core flows on the device at fontScale 1.0 and 2.0, plus one TalkBack pass, written down as a checklist. | P42, P43, P44 | section 13 | S | 2.1, 2.2 |

## What not to undo

This list is long, and most of it is about addition. These are deliberate and should survive it:

- The reading design: 460dp measure, 1.5 line height, prompt above answer, one answer open at a
  time, sticky prompts.
- Answers-only as the default. R12 asks for a quieter status, not for tool logs on the transcript.
- Restrained motion and layout stability: indicators sit in fixed slots so nothing shifts
  (`Screens.kt:184-186`, `:384-385`, `Tactile.kt:88-97`).
- Platform haptics only, no `VIBRATE`, follows the system setting.
- Privacy: `FLAG_SECURE` while the token is on screen, encrypted token at rest, no analytics,
  optional camera, `allowBackup="false"`.
- The prose style of `Diagnostics`: name the cause and the likely fix in one plain sentence.

## Limits of this review

No device was available. Nothing here was checked with TalkBack running, at fontScale 2.0, in
RTL, or on Android 15 where the insets problem lives. No usability test was run, which is the
roadmap's own Step 3; every judgement about comprehension (for example whether the six stacked
lines are actually confusing in the hand) is reasoned from the code, not observed. Contrast ratios
are computed from source hex values, not from screenshots, so they ignore Android's colour
management and the sky gradient behind some text.
