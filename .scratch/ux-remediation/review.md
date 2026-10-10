# Review: wave 1 of ux-remediation

Branch `qrspi/ux-remediation`, from `e976063` (v0.0.57) to the review fix. Wave 1 of three.

## Gate

| Check | Command | Result |
| --- | --- | --- |
| Debug build | `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline assembleDebug` | success |
| Release build, the CI path | the same with `assembleRelease` | success in 1m14s; `lintVitalRelease` and `packageRelease` both ran; unsigned locally, as expected |
| Lint | the same with `:app:lintDebug` | 9 warnings, 0 errors: the same nine as before the branch |
| Unit tests | the same with `:app:testDebugUnitTest` | `NO-SOURCE`: the repo has no test source set, so this check proves nothing yet |
| Contrast | `python3 scripts/luminance.py` | exit 0; 18 pairs, every one at or above 4.5:1 |

## Bookkeeping

Seventeen task commits, one artifact commit, one review fix. Every commit carries the trailer
`QRSPI: ux-remediation`. Every changed path is named in the plan. `.scratch/ACTIVE` is not
committed, which is the convention.

## What the code review found

Four findings. One was serious enough to fix before handover; the other three are recorded as
tasks T17a, T25a and T38a in `worktree.md`, attached to the wave that will carry them.

**1. MAJOR, fixed in `b7e7761`.** `reloadHistory` ended with `clearNotices()`, which was how the
session-move line was retired. Once every message moved into one channel, that call also destroyed
the message `recoverFromStartupCrash` had posted on the same launch, and any error still waiting to
be read, within about a second. It reintroduced the wave's own bug for the one message that matters
most, on the launch that needs it. Only the session-move id is dismissed now, and `clearNotices()`
is left to the two callers that mean it: an attachment change and the recovery button.

**2. MINOR, deferred to T17a.** The pin's `BackHandler` is registered after the palette's, so with
both open back closes the pin, not the palette, which is the reverse of D3's stated order. The
overlap is rare, which is why it is a task rather than a fix, and D3's order is the intended one.

**3. MINOR, deferred to T25a.** An actionable notice carries a lambda that captures the composition
which posted it. After a rotation the "Allow installs for Pi Remote" entry's Install runs on a
cancelled scope, so it silently does nothing and holds the destroyed Activity. Re-creating the
screen re-posts the update offer but not that entry.

**4. MINOR, deferred to T38a.** `scripts/luminance.py` hardcodes its hex values, so it cannot catch
a regression in `AnswerStyle.kt`, which is the thing it exists to guard.

**Verified sound by the same review.** The kinds sort error, then actionable, then confirmation;
confirmations expire after two seconds and errors do not; dismissal filters by id; no write-only
message state remains; the notification permission is checked before posting and a missing
permission does not throw; `isResumed` gates posting so nothing arrives while the transcript is on
screen; notification ids are stable, so an update replaces rather than stacks; the run-finished
trigger has one call site; both short rows reach 48dp; and Install and Dismiss are separate buttons,
so dismissing can no longer install.

## Deviations from the plan, and why

- **One worker per batch, not one per task.** The tasks share a checkout and a git index, so two
  workers committing at the same time would put one task's files in another's commit. Batches were
  dispatched in order, and the file-disjointness the work tree computed is what made each batch
  safe to hold in one session.
- **Three tasks needed a file beyond their declared `Touches`** (T2, T3, T4). The task tree was
  wrong rather than the work, and two of those commit bodies say so; T2's does not.
- **Four plan defects were caught by workers and fixed rather than shipped**: the resumed-activity
  counter would have leaked notifications onto a screen that was already in front, the
  install-failure message would have been filed as a self-clearing confirmation, an obsolete SDK
  guard added a lint warning, and one task's file list could not compile.
- **Plan line numbers were stale** in several tasks. Workers that trusted the symbol rather than the
  number were right to.

## What the human does next

Install wave 1 and run `docs/UX-REMEDIATION-CHECKLIST.md` on the phone. Everything in this wave is
visible or felt, so this checklist is the verification, not a formality attached to it: this
machine cannot run an emulator, and the repo has no instrumentation tests.

## Hand-off state

Wave 1 is code-complete on `qrspi/ux-remediation`. Waves 2 and 3 are not started. `state.md` records
the stage as Implement. The three deferred findings are tasks, not notes.

---

# Review: wave 2 of ux-remediation

Branch `qrspi/ux-remediation-w2`, from `f5122e8` (the wave-1 merge) to `5cac18b`. Nineteen commits.

## Gate

| Check | Result |
| --- | --- |
| `assembleDebug` | success |
| `assembleRelease`, the CI path | success in 48s |
| `:app:lintDebug` | 9 warnings, 0 errors: the baseline again |
| `:app:testDebugUnitTest` | `NO-SOURCE` |
| `python3 scripts/luminance.py` | exit 0, 18 pairs at or above 4.5:1 |

## Bookkeeping

Fourteen task commits, five review fixes and one record commit. Every commit carries the trailer
`QRSPI: ux-remediation`, and all six changed paths are in the plan. Two tasks needed a file beyond
their `Touches` list; T25a's commit body says so and T27's does not, for a one-line `MainActivity`
wiring change. That message was deliberately not rewritten: the record's status ticks cite these
SHAs, so rewriting history would make the record lie in order to fix a sentence.

## What the code review found

Six findings, and all three serious ones were consequences of changes this wave made rather than of
what it left alone. All six are closed: five fixed here, the sixth recorded as task T21a.

**1. MAJOR, fixed in `7593ecb`.** The saveable state holder wrapped both destinations, which
preserved `SettingsScreen`'s own fields. After a QR pairing, reopening Settings showed the
pre-pairing token field and Save wrote it back, silently unpairing the phone. D7 asked only for the
chat's state to survive; the holder now wraps the chat alone, and Settings composes keyless so its
editors reset as they did before.

**2. MAJOR, fixed in `59c35e2`.** The copy affordance posted its acknowledgement into the chat's
notice channel, which is not composed while Settings is on screen, and confirmations expire after
two seconds, so "Copied" could never be read. `LaptopCommand` now holds its own acknowledgement on
its own row and turns the haptic on.

**3. MAJOR, recorded as T21a.** Material3's small `TopAppBar` caps its container at 64dp and clips,
so `maxLines = 2` and lifting the subtitle's cap made the bar clip rather than grow: the app bar's
half of the font-scale fix was not real. The waiting slot and the bullet column are genuine fixes.
Replacing a fixed-height bar is its own change, so it is a wave-3 task, and the checklist no longer
claims the app bar survives a font scale of 2.0.

**4. MINOR, fixed in `785c2f0`.** `stateDescription` sat on top of the segmented control's own
`selected`, so the selection was announced twice and hardcoded English shadowed TalkBack's localized
text.

**5. MINOR, fixed in `6559a70`.** The connection status line rendered twice for a paired but offline
empty transcript.

**6. MINOR, fixed in `929b02a`.** The segments lost `weight(1f)` in the replacement, so the pickers
no longer spanned their row.

**Verified sound by the same review:** the composer split, the `Send`/`Steer` label, the live
region's scoping, `NoticeAction` as a value rather than a closure, roles on every clickable row,
heading semantics, and the status-line reorder with no consumer parsing the string.

## Corrections to the review itself

- R16 overstated its finding. The settings prose was already folded behind sections that are
  collapsed by default, so it was never nine open paragraphs; the real change was a subtitle's
  `maxLines` and splitting the `/pair` paragraph around its new command row. The worker said so
  rather than inventing work to fill the task.
- Seven task checks could not pass as written: greps that counted an import, a tautology that passed
  before any edit, and an instruction that contradicted the resolved library's own semantics. In each
  case the worker verified the intent by hand instead of bending the code to a broken assertion.
  Wave 3's tasks are told to read their check before trusting it.

## Hand-off state

Wave 2 is code-complete on `qrspi/ux-remediation-w2`. Wave 3 is not started: twelve tasks, plus
T21a, plus the record corrections D11, D12 and D20. `state.md` records the stage as Implement.
