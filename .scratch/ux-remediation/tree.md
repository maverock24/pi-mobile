# Design tree

Effort `ux-remediation`. Inputs: `ticket.md`, `research.md` (all 50 questions answered), `rubric.md`.

## End state

Pi Remote at the end of this effort: no message is written and never shown; system back works in
every state; every theme meets WCAG AA on text; one message channel with a priority order replaces
seven independently-stacked bars; the composer's primary action cannot be scrolled off the screen;
a trip to Settings no longer discards the search, the open pin or a half-typed answer; the screens
carry semantics and selection state that TalkBack can read; a long run reports itself through a
notification while the app is alive, with the pocketed-phone case recorded as still open rather
than claimed; and the design record matches the code.

Answers-only is untouched. Nothing is added to the transcript that is not an answer.

## Out of scope

- R12 in all its forms: elapsed time, progress, last-tool line, details panel on the transcript.
  The human's decision, and it makes the notification work load-bearing.
- The product backlog the review did not recommend: session picker (PRD 1.1), model and thinking
  switching (1.3), diff review (1.5), attachments (0.10), share target (0.11), offline queue
  (1.11), Play Store path (2.11), localisation (1.8) beyond strings a change already touches.
- Bridge changes, except if a design decision cannot be met without one. Notifications need no
  bridge change; the missing "run failed" event is the one candidate and it is deferred (D9).
- Rewriting the visual language. The palette, the typography scale and the tactile key stay.

## Decisions

### D1. What is being built

- **Status**: settled
- **Blocked by**: none
- **Decision**: the recommendations R1, R2, R4 to R11, R13 to R21, minus R12. Recorded in
  `docs/UX-REVIEW.md`. The human chose all of them, prioritised.

### D2. How a message reaches the user

- **Status**: settled
- **Blocked by**: none
- **Decision**: one channel owned by the ViewModel, not seven independent state reads. Research
  Q4/Q5 shows the current seven conditions have no mutual exclusion, so all seven can be non-null
  in one frame, and three of them are undismissable. The channel holds at most one error plus a
  bounded list of confirmations, with this order: error, then actionable, then confirmation. A
  confirmation expires after 2 s; an error persists until dismissed or superseded. Each entry
  carries its own action label, so nothing is labelled "OK" while installing an APK (R3, R7).
  `MainActivity.notice` is deleted rather than re-wired: with the channel in the ViewModel, the
  regression cannot come back through a second mechanism (R1).

### D3. Back handling

- **Status**: settled
- **Blocked by**: none
- **Decision**: a `BackHandler` chain, in this order: the command palette, then search, then the
  open pin, then the settings screen. When none is open, no handler is enabled, so back keeps its
  platform default and finishes the Activity. `activity-compose:1.9.3` exposes `BackHandler`
  (`app/build.gradle.kts:71`). Research Q12 confirms nothing intercepts back today.

### D4. Colour, and how far the tokens go

- **Status**: settled
- **Blocked by**: none
- **Decision**: the update pills stop being two literals in `Screens.kt` and become two semantic
  pairs on `AnswerStyle`, each defined per scheme with an explicit text colour for the 16% tint,
  so the light scheme gets a readable pair (currently 1.71:1 and 1.77:1). `onSurfaceVariant` and
  `error` are raised to at least 4.5:1 in Midnight and Indigo (currently 4.38/4.03 and 4.43/4.43).
  The exact values are chosen in the Plan and checked by the same luminance script used in the
  review, which becomes a repo script so the claim stays checkable (R4, R5, R17).

### D5. Composer shape

- **Status**: settled
- **Blocked by**: none
- **Decision**: two rows. The first is fixed, and holds the field, the primary action, and `Stop`
  while a run is in progress: three fixed slots rather than a swap, because D22 makes the action
  read `Steer` mid-run and both must be reachable at the same time. Neither the action nor the
  escape can scroll away. `Auto-paste`, `Mic` and `Clear` move to the second, scrollable row.
  Research Q28 to Q30 confirms `Stop` is last in a `horizontalScroll` row today and `Clear` sits
  next to `Send`.

### D6. One control for "pick one of N"

- **Status**: settled
- **Blocked by**: none
- **Decision**: `SingleChoiceSegmentedButtonRow` with `SegmentedButton` for the view mode and for
  appearance and theme, replacing both the bullet-prefixed `TextButton` row and the hand-built
  `SegmentButton`. Research Q16 confirms material3 1.3.1 ships it non-experimental, and Q19 shows
  the two current controls read the scheme differently. Selection is announced (D8) (R10).

### D7. State across screens

- **Status**: settled
- **Blocked by**: none
- **Decision**: `rememberSaveableStateHolder` keyed by destination, so `ChatScreen`'s state
  survives the trip to `SettingsScreen` and back. Research Q39/Q40 lists the seventeen composable
  owned states that are discarded today and confirms `rememberSaveableStateHolder` is present in
  runtime-saveable 1.7.5 and unused (R11).

### D8. Accessibility

- **Status**: settled
- **Blocked by**: none
- **Decision**: roles on the seven clickable rows that are not announced as controls; `selected`
  and `stateDescription` where a selection exists; `liveRegion` on the streaming answer; heading
  semantics on answer headings; `heightIn(min = 48.dp)` on the two rows that fall under it; and the
  three fixed-size spots that clip at `fontScale` 2.0 (the 24 dp waiting slot, the 20 dp bullet
  column, the two single-line app bar texts). Research Q15 to Q17 confirms every API needed is
  present and non-experimental in ui 1.7.5 and material3 1.3.1 (R15, R6).

### D9. Notification triggers

- **Status**: settled
- **Blocked by**: none
- **Decision**: two triggers, not three. "A question needs an answer" maps to the `question` event
  (`bridge/extensions/pi-remote.ts:1442`). "A run finished" maps to `agent_settled` (`:1645`).
  "A run failed" has no event: `agent_end` carries only `messageCount` (`:1641`), the app discards
  `tool_execution_end` with its `isError` (`ChatViewModel.kt:1439-1441`), and `aborted` is never
  handled. Rather than infer failure from a payload that does not carry it, the third trigger is
  deferred and the gap is written into `PRODUCTION-READINESS.md` so it is not silently lost.
  After D21 these triggers fire from the app's own process, which is the only thing listening: no
  service keeps the socket alive, so a notification arrives while the app is alive and not
  otherwise.

### D10. Motion and the system animation scale

- **Status**: settled
- **Blocked by**: none
- **Decision**: the custom sweep reads the system animation scale itself and holds still when it is
  zero. Research Q16 found `LocalMotionDurationScale` absent from ui 1.7.5, so the value comes from
  `Settings.Global.ANIMATOR_DURATION_SCALE`. Compose's own animations already obey it (R20).

### D11. The design record

- **Status**: settled
- **Blocked by**: none
- **Decision**: the code moves to the record. Press scale is added to the four controls that lack
  it (`Screens.kt:1418`, `:1432`, `:1450`, `:1731`); the `CONFIRM` haptic is removed from copy and
  share (`:1421`, `:1439`), which the lab's `4b` excludes. `PRODUCTION-READINESS.md` is corrected
  for 0.8 (ships, via ZXing rather than CameraX, and the token is deliberately not in the QR),
  1.7 (ships), 1.13 (haptics ship, reduce-motion does not until D10) and 2.9 (the local crash log
  ships, reporting does not). `AnswerStyle.kt:14` stops citing a `choices-latest.json` that no
  longer exists (R18).

### D12. Corrections to the review itself

- **Status**: settled
- **Blocked by**: none
- **Decision**: two claims in `docs/UX-REVIEW.md` are wrong and get fixed in the same commit series.
  Line 307 says the README calls the deck "the deck"; `grep -ic deck README.md` is 0. The
  `AnswerStyle.kt:18-20` citation is stale; the lab path sits at `AnswerStyle.kt:14`. Research Q44
  and Q45 found both. The `rubric.md` kept in this effort's directory stays there rather than
  becoming a second doc: `docs/UX-REVIEW.md` already derives the rubric and names every source, so
  a separate file would be another thing to keep true. If it is still unused when the work is done,
  it is deleted with the rest of `.scratch/`.

### D13. Tokens for spacing and sizing

- **Status**: settled
- **Blocked by**: none
- **Decision**: the raw dp literals in `Screens.kt` move to named tokens on `AnswerStyle`, and the
  `promptPadding - 2.dp` inline arithmetic is replaced by a token that says what it is for.
  Research Q27 counts 16 `heightIn` sites against 8 fixed `height` and 9 fixed `size` sites, which
  is where the inconsistency lives (R17).

### D14. Platform chrome

- **Status**: settled
- **Blocked by**: none
- **Decision**: `enableEdgeToEdge()` plus `WindowInsets.safeDrawing` on the scaffolds, and an
  adaptive app icon, both already in the backlog as Tier 0.7 and 0.1 and both tracked here because
  the composer currently sits under the gesture bar on Android 15. Research Q16 confirms
  `enableEdgeToEdge` and `safeDrawing` are present (R19).

### D15. Delivery in waves

- **Status**: settled
- **Blocked by**: none
- **Decision**: three waves, each a release the phone can take, so a regression in one wave does
  not hold the others back. **Wave 1 is the bugs plus the notification work**: R1 (the message
  channel), R2 (system back), R3 (the update banner), R4 and R5 (the colour pairs and AA), R6 (the
  two short rows), and the notification triggers. Structure changes wait, because shipping a known
  silent-failure bug behind a composer redesign has the order backwards, and the review's own P0
  grouping put R2, R4 and R5 there. **Wave 2 is the structure set** (D5, D6, D7, D8, R14, R16).
  **Wave 3 is the polish set** (D4 remainder, D10, D13, D14, D11, D12, D20, and the final
  accessibility pass). R21 is not a wave: the checklist is written with wave 1 and extended each
  wave, per D23. The release question at the end of Plan covers the whole effort, not a wave.

### D16. What proves each change

- **Status**: settled
- **Blocked by**: none
- **Decision**: on this machine: compilation and packaging (`assembleDebug`, `assembleRelease`),
  lint (`:app:lintDebug`, currently 0 errors and 9 pre-existing warnings), a JVM harness for logic
  that has no Android dependency, and `grep`-based assertions where a fact is structural.
  The harness mechanism is the one proven in the review: the Kotlin compiler bundled with the
  Gradle distribution
  (`/root/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar`)
  compiles the real source file against stubbed Android classes, and the result runs on the JDK.
  That is how `CrashLog`'s state machine was verified across 18 checks. There is no JUnit or
  Hamcrest in the offline cache and no test source set, so a harness is a scratch program rather
  than a `src/test` suite, and adding a test dependency is out of scope (PRD 2.1 owns it).
  Everything visual or interactive is verified by the human on the phone, from a written checklist
  per wave (R21). Research Q49 is explicit that no recommendation in the list is provable on this
  machine alone.

### D17. Touch targets

- **Status**: settled
- **Blocked by**: none
- **Decision**: the command palette row and the search result row get a 48 dp floor. Research Q25
  computes them at 36 dp and 40 dp at `fontScale` 1.0 when their optional second line is absent
  (R6).

### D18. The status line

- **Status**: settled
- **Blocked by**: none
- **Decision**: the hint goes first, not last, and the line is allowed two lines on the chat
  screen. `Diagnostics.describe` keeps building one string for the settings screen, which already
  wraps. Research Q10 shows the chat renders `maxLines = 1` with ellipsis over a string whose
  actionable half is at the end (R8).

### D19. The empty and first-run state

- **Status**: settled
- **Blocked by**: none
- **Decision**: the empty state names which of the three situations it is in. Not paired: an action
  that opens Settings. Paired but not connected: the status line's failure and a retry. Connected
  with no turns: today's text. Research Q1 and Q10 supply the three states from `isConfigured`,
  `connected` and `turns.isEmpty()` (R14).

### D20. The record's home for UI vocabulary

- **Status**: settled
- **Blocked by**: none
- **Decision**: `GLOSSARY.md` gains the screen vocabulary it is missing (transcript, cards, pins,
  answer, prompt, turn), because research Q45 shows the file defines only bridge words and that is
  why "Cards" and "deck" coexist. The code keeps `deck` as the internal name and the UI keeps
  "Cards"; the glossary says which is which rather than renaming either.

## Settled at the Design round (round one)

The frontier was these five. All are settled; nothing is left silently assumed.

### D21. Notifications, and the service they will not have

- **Status**: settled
- **Blocked by**: none
- **Decision**: no foreground service. The two triggers in D9 fire while the app's process is
  alive, and the app does not keep the socket alive in the background. The limitation is recorded
  rather than hidden: `PRODUCTION-READINESS.md` keeps Tier 0.2 open and gains a line saying the
  trigger set ships without a service. The consequence to carry forward honestly: a run that
  finishes while the phone is in a pocket still produces nothing, which is the case Tier 0.3 was
  written for, so what this effort ships is a step towards that feature and not the feature.

### D22. The primary action while a run is in progress

- **Status**: settled
- **Blocked by**: none
- **Decision**: label it for what it does. `Send` when idle, `Steer` while `busy`. The behaviour is
  unchanged, since a second prompt is already steered into the running turn, and the composer
  already relabels one control by mode, so this is a label plus a check rather than a new path.
  Enablement and haptics stay as they are, so D5's "never off-screen" is the only structural change.

### D23. Verification

- **Status**: settled
- **Blocked by**: none
- **Decision**: no emulator is built here. This machine proves compilation, packaging, lint, the
  pure logic through a JVM harness, and structural facts by grep. Everything a person can see is
  verified by the human on the phone, from a written checklist per wave (R21). Every one of the
  recommendations sits on the phone's side of that line, so the checklist is the verification
  rather than a formality attached to it.

### D24. The long prose

- **Status**: settled
- **Blocked by**: none
- **Decision**: trim in place. Section subtitles stay one line, the explanation folds behind the
  section, and the two laptop commands become tap-to-copy. No new destination, so D3's back chain
  is the final one: palette, search, pin, settings.

### D25. Criticality

- **Status**: settled
- **Blocked by**: none
- **Decision**: the effort stays critical. No repo writes outside `.scratch/` until the human
  answers the release question at the end of Plan.

## Settled in the second pass, after Structure

S read the tree and reported nine gaps. The four that were real are settled here; the others are
answered by these or were already facts in the research. Every one is either my own earlier error
or a judgement the evidence covers, so none is put back to the human. The release question remains
their veto over all of it.

### D26. When a notification is suppressed

- **Status**: settled
- **Blocked by**: none
- **Decision**: nothing is posted while one of the app's activities is resumed. A notification
  exists to report something you cannot see, so posting one while the transcript is on screen is
  noise. `PiRemoteApp` tracks the resumed activity count through
  `registerActivityLifecycleCallbacks` and the notifier reads it, which needs no new dependency.

### D27. What a notification can do

- **Status**: settled
- **Blocked by**: none
- **Decision**: an `Open` action only, in this effort. The answer and question-option buttons from
  `PRODUCTION-READINESS.md` 0.4 stay open in the record: they need a reply path that survives the
  app not being in front, and D21 removed the only thing that would have made that safe.

### D28. Where the channel's boundary is

- **Status**: settled
- **Blocked by**: none
- **Decision**: the channel carries messages, not state. The connection status line and the
  saved-copy line stay one line each, because they describe a condition rather than announce an
  event, and the session-move line folds in because it is a one-time announcement like the rest.
  That leaves at most two state lines plus the channel, where the review measured seven independent
  lines that can all be present in one frame. The update-install messages map onto it directly:
  `Installer launched` is a confirmation, `Allow installs for Pi Remote` is an actionable entry
  that persists, `Update failed` is an error that persists.

### D29. The channel and the notification's face

- **Status**: settled
- **Blocked by**: none
- **Decision**: channel id `pi-runs`, user-visible name "pi runs". A notification needs a small
  monochrome icon and the app has none (no `android:icon`, PRD 0.1), so this effort adds one vector
  drawable for that and leaves the adaptive launcher icon to D14.
