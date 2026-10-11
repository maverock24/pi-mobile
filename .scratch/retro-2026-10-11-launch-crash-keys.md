# Retro: the launch crash, and the key class behind it

Written by a session that did not own this effort, so it is a file of its own
rather than an edit to `ux-remediation/state.md` or `worktree.md`: both were
uncommitted and being edited by the wave-3 session while this was written, and
two writers on one artifact is how work gets lost.

## What the human reported

"Even with the latest version installed, the app still crashes on start on the
phone again", with the explicit constraint that no crash details would be
provided from the phone.

## What was established

1. **The app is the wave build, not the session's build.** `main` is at
   `117c739` (wave 2, published v0.0.59); wave 3 is in progress on
   `qrspi/ux-remediation-w3` at `3418049`. The session's own earlier commits are
   ancestors of both, so nothing is stranded, but the crash is against wave 1-2
   code plus whatever wave 3 has not yet committed.
2. **The crash is in composition, not in the stores.** `e976063` added the crash
   recorder and a startup safe mode, and after a second startup crash nothing
   stored is read before the first frame. A crash that survives that is either
   in composition or outside every guard the stores have.
3. **The repo already names the class.** From `e976063`'s own message:
   *"Two rows with one id give two list items one key, which Compose refuses
   with an exception at layout time, and that is the one crash a stored file
   could still cause on its own."* The same failure mode applies to anything
   that reaches a keyed list, whatever wrote it, and several keyed lists have
   been added since: the transcript's `prompt-<id>` / `body-<id>` headers, the
   pins, the search results, the command palette, the deck's pager.
   `e976063` hardened the stores to drop repeated ids **while loading**, which
   covers those files only.
4. **No trace could be produced here.** No device is attached, and this machine
   has no emulator binary, no system image and no `cmdline-tools`, so producing
   one needs either USB debugging or `adb pair` over wireless. Nor is it worth
   guessing further: the class above is documented by the effort's own history
   and is cheap to close.

## What is left to do

**Close the key class, then prove it closed.** For every keyed list, the id it
keys on, where that id comes from, and what happens when two arrive with the
same one. Fix it where the id is built as well as where it is used, and keep a
turn's key stable so the accordion and the scroll position survive.

**Two blockers the next session must settle first**, neither of which this
session had the standing to decide:

- **Which branch.** The fix touches `ui/Screens.kt`, which exists differently on
  `main` and on `qrspi/ux-remediation-w3`. It belongs on the wave-3 branch, and
  therefore inside that effort's task list, which is the wave-3 session's call.
- **The uncommitted restyle.** `ui/Screens.kt` carries 92 lines of in-flight
  work (`16.dp` to `AnswerStyle.screenPadding` / `accentBarInset`) that is not
  this session's and must not be swept into an unrelated commit. A worker was
  started for the key fix, correctly refused to proceed on a dirty tree, and
  made no changes. It needs the restyle committed, stashed, or explicit
  permission to work on top of it.

## What this session did do

- Established the above and left the tree exactly as it found it.
- Confirmed the bridge on disk is current (`e2d404e3`), that the app builds with
  no `INVISIBLE_REFERENCE` warning, and that the four themes, pins, transcript
  cache, search, command palette and copy/share work are all present in
  v0.0.56 as built here.
- Told the human the one thing they can do without logs: the Troubleshooting
  section added by `e976063` clears the trace, quarantined copies, pins,
  transcript and attachment from inside the app, which is the in-app equivalent
  of the Clear storage that recovered the phone on Oct 9. Whether that helps is
  itself the diagnosis: if a clear brings it back the crash is fed by stored
  data, and if it returns immediately the crash is in composition.

## Open question for whoever owns the effort

Was the Oct 9 crash ever identified? `e976063`'s message says it was not ("this
does not fix an identified line"). If the same class is still live, this is the
second launch crash from it, and the fix deserves a task number rather than a
retro file.

## Decisions taken by the human, 2026-10-11

Both blockers above are settled, so the next session does not have to re-open
them:

- **The fix lands on `main`, after wave 3 is merged.** Not on
  `qrspi/ux-remediation-w3`, so it does not race that wave's tasks or its PR.
- **The 92 uncommitted lines in `ui/Screens.kt` are the wave-3 session's to
  commit first.** They look like one of the remaining wave-3 tasks in flight
  (T30 or T32, both of which touch that file), so they are the owning session's
  work to finish and commit, not to be swept up or stashed by anyone else. The
  key fix goes on top of a clean tree afterwards.

So the order is: wave 3 finishes its tasks, commits the restyle and merges,
and only then does the key fix start on `main`. Until that happens this crash
stays open, which is a deliberate choice about who owns the file rather than an
oversight.

## Done since, 2026-10-11

**The key class is closed, and verified.** `fix/duplicate-keys` (a worktree at
`/home/maverock24/github/pi-mobile-keys`, cut from `main` at `117c739`) is at
`6257802`, one file, `ui/ChatViewModel.kt` +28/-3:

- `turnsOf` gives every turn a unique id (`takeId`), so a repeated prompt id or
a `lead-N` that collides with a real one can no longer key two rows the same.
The first turn with a base keeps the plain id, since a search hit names the
prompt's own id to jump to.
- `search()` keeps the first hit per `entryId` and drops later ones.

Gate run here: `EXIT=0`, no visibility warnings. Every keyed list was checked
independently against the fix: the transcript's `prompt-<id>` and `body-<id>`
headers (unique now), the pending-question and end-marker constants, the pins
(`it.id`, already deduped while loading by `e976063`), the search results (deduped
now), the deck's pager (keys derived from the same `turn.id`), and the command
palette (a plain `Column`, so it takes no keys at all).

The branch is deliberately small and touches no other file, so it should rebase
or cherry-pick onto `main` cleanly once wave 3 merges. **Not pushed, not merged,
not on `main` yet.**

## Still open

- **A real stack trace.** The human is attaching the phone by USB, and the route
is `adb logcat -d -b crash` plus `adb shell dumpsys dropbox --print
  data_app_crash`, launching the app to reproduce if the buffer has rolled over.
  The crash is data-dependent, which is why it needs clearing storage to recover:
the fix above closes the class the repo documented, and the trace says whether
anything else is behind it.
- **A Tailscale indication, asked for explicitly:** *"i want indication if
  tailscale is not active instead of getting a general message about could not
  connect"*. A phone running Tailscale has an address in `100.64.0.0/10`, so the
  app can tell "Tailscale is off" from "Tailscale is on and the laptop is
  unreachable" and name the fix. Read what `a807157` ("Put the fix first in the
  connection hint") already did to that text before changing it.
- This session's `Diagnostics.hint` already names Tailscale for
  `UnknownHostException`, but a bare `100.x` address needs no DNS, so that case
  never fires for the common failure.

## Resolution, 2026-10-11: the crash was not the key class

A USB trace ended the guessing. The crash was in **construction**, not
composition and not the key class:

```
FATAL EXCEPTION: main
java.lang.NullPointerException: Attempt to invoke interface method
  'java.lang.Object androidx.compose.runtime.State.getValue()' on a null object reference
    ... MainActivity$onCreate$1.invoke(MainActivity.kt:340)
    ... viewModel() -> createViewModel
Suppressed: DiagnosticCoroutineContextException [StandaloneCoroutine{Cancelling},
  Dispatchers.Main.immediate]
```

`ChatViewModel`'s `init` (line 293) launched `restoreCache` through `safeLaunch`,
which is `viewModelScope.launch` on `Dispatchers.Main.immediate`, so the body ran
**inline during construction**, touching state declared below the `init` while
those delegates were still null. Six earlier crashes were the same bug from the
write side (`MutableState.setValue`). It fired only when a saved transcript
existed, which is why clearing the app's storage was the workaround.

`main` is `d28d2f5`, published as **v0.0.60**, with two commits beyond the wave-2
merge:

- `6257802` Make a repeated id unable to crash a keyed list (the documented
  class, closed at the render site and the source).
- `d28d2f5` Start the transcript restore after the view model is built (the real
  crash: the restore is posted so it cannot run before the constructor returns,
  and the three states it reads sit above the `init` now, with a comment so a
  reordering cannot reintroduce it).

Pushed straight to `main`, no merge request, per the human's rule.

**Still open:** the Tailscale indication, and the fact that the wave-3 branch
does not carry either fix (it was cut from `117c739`, so the later merge into
`main` has to reconcile that file).
