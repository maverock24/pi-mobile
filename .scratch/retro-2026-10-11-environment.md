# Retro: the pi-mobile environment, 2026-10-11

Improvements to the agent's environment, from one long session on this app.
Ordered by severity. Evidence is from that session, not from theory.

## 1. The phone's crash trace has no route to the agent (information access)

The app records its own crash trace to `filesDir` and shows it in Settings, and
commit `e976063` built that machinery deliberately after a launch crash whose
trace "went with the data". But nothing lets the laptop read it: the trace
exists only on the phone, behind a UI screen, and the session that chased the
same crash had to (a) ask the human to attach the phone by USB, (b) run
`adb logcat -d -b crash`, and (c) still work out the mechanism by hand. The
human's own constraint was "fix it without me providing exact logging", which
makes this the highest-value gap in the session.

**Change:** on the next launch, POST the trace to the bridge, and have the
bridge keep it beside the audit log. Then a trace costs one local read, on every
future crash, with no cable and nobody relaying anything. The app already
connects to the bridge on launch, so this is one request in `PiRemoteApp` plus
one handler.

## 2. `Explore` spawns 404 unless a working model id is named (tool economy)

The global `AGENTS.md` says to pass a model to `Explore` because this machine
has no Anthropic credential, but does not say which. Two spawns died with
`404 Not Found` and zero output: one with a guessed id, one relying on the
documented default (`claude-haiku-4-5`, which cannot authenticate here). Both
cost a turn and produced nothing.

**Change:** name one working id in the `AGENTS.md` line, or say to use `worker`
(which inherits the session model and is known good here) for reconnaissance
instead. A pointer that names a required argument should name a valid value.

## 3. The repo has no guardrail at all (automated checks)

`/.github/workflows/android-release.yml` runs exactly one command:
`./gradlew --no-daemon --stacktrace assembleRelease`. No `lint`, no `test`, no
`check`. `lintDebug` appears only inside a commit message, run by hand. There is
no `app/src/test`, and no pre-commit hook.

Two mistakes this session would have been caught by wiring what already exists:

- `@file:Suppress("INVISIBLE_REFERENCE")` in `ui/AnswerView.kt`, calling an
  **internal** Compose API. Lint can flag that shape, and a two-line CI grep
  certainly can. Worth wiring because the compiler's own warning says the
  behaviour is "UNSPECIFIED and WON'T BE PRESERVED".
- Nothing at all catches the class this session's crash belonged to, because
  there are no tests (see 4).

**Change:** add `lintDebug` to the release workflow, and a CI step that fails on
`@file:Suppress("INVISIBLE_REFERENCE")` or `INVISIBLE_MEMBER` anywhere under
`app/src/main`.

## 4. The crash was testable, and no test could exist (automated checks)

The launch crash was a `NullPointerException` reading a `mutableStateOf`
property whose delegate was still null, because `viewModelScope` is
`Dispatchers.Main.immediate` and the `init` launched a coroutine that ran inline
during construction. A single JVM test **constructing the ViewModel with a
cached transcript present** fails on exactly that, in milliseconds, with no
device. The repo has no test source set, so the class of bug cannot be caught
except by a human with a cable.

**Change:** a test source set, and that one test first. The same runner decision
was already flagged in `docs/SESSION-MANAGEMENT.md` §10 for the bridge.

## 5. `AGENTS.md` is missing three things the session needed (steering)

Each of these cost turns to discover:

- **No "push to main, never open a merge request" rule.** The human had to state
  it mid-session ("Don't create MRs always push to main"). It is durable and
  belongs in the always-loaded file.
- **No branch or worktree convention.** This repo had two worktrees on different
  branches, one carrying another session's uncommitted work. A worker was
  spawned on the wrong-branch assumption and correctly refused; the near miss was
  committing someone else's in-flight restyle. One line, "confirm
  `git rev-parse --abbrev-ref HEAD` and a clean tree before editing or
  committing", prevents it.
- **No pointer to `.scratch/ACTIVE`.** QRSPI efforts live in `.scratch/<slug>/`
  and this session spent turns rediscovering that an active effort owned the
  branch, the artifacts and the file it wanted to edit.

## 6. `javap` cannot prove Kotlin visibility (coding standards, verification)

I checked that `SelectionContainer`'s `selection` / `onSelectionChange` overload
was public by running `javap`, which reports JVM flags. Kotlin's `internal`
compiles to JVM-public, so the check passed and the feature was **not** callable
from app code. I told the human it was feasible on that basis; a compiler run
proved otherwise. This is a mechanical rule, so it should be a check where
possible (3) and a standards line where not: verify Kotlin visibility by
compiling against the API, never by `javap`.

## 7. Expensive tool calls worth streamlining (tool economy)

- **`adb` blocks forever with no device attached.** A bare `adb shell …` hung
  for the full 120-second command timeout. Always wrap adb in `timeout N`.
- **Subagent notifications truncate the report.** Every worker's summary arrived
  cut off mid-sentence, forcing a second `get_subagent_result` call to read what
  was already written. Asking workers for a short answer (a few hundred
  characters) plus the detail in a file — the artifacts already exist — removes
  a round trip per delegation.

## 8. Steering that does not steer (no-ops)

- The global `AGENTS.md` says "Say the tier first" for every coding task. Across
  a session of many edits it was never declared once and nothing noticed. Either
  wire the QRSPI gate to warn, or drop the line: as it stands it is context load
  with no effect.
- A deleted extension file does not unload. `smart-zone.ts` was deleted from
  `~/.pi/agent/extensions/` on Oct 5 and its in-memory copy kept injecting a
  `CONTEXT HYGIENE` turn into every session for days, which is what the phone was
  complaining about. One line in the notes: deleting an extension needs a
  `/reload`, not just an `rm`.
