# Talks tab implementation

Worktree `/home/maverock24/github/pi-mobile-keys`, branch `main`, base HEAD
`6f20487` (QRSPI: kokoro-tts play talks on the phone with media3).

## What already existed

Commit `6f20487` already added the whole talks feature:

- `data/TalksStore.kt` app-private storage (one talk = one MP3 under `files/talks`).
- `player/TalkPlayer.kt` and `player/TalkPlaybackService.kt`, the media3
  `MediaSessionService` that keeps playing with the screen off.
- `notify/Notifier.kt` for the "talk ready" shade post.
- `net/PiRemoteClient.kt`: `talks()` = `GET /api/talks`, `downloadTalk(id, dest,
  onProgress)` = `GET /api/talks/<id>.mp3`. Both go through the shared
  `builder(...)` which sets `Authorization: Bearer <token>` from the settings
  store (PiRemoteClient.kt:42-48). No second auth route exists or was added.
- `ui/ChatViewModel.kt`: `talks`, `downloadedIds`, `downloadProgress`,
  `talksLoading`, `talksError`, `player`, `refreshTalks`, `downloadTalk`,
  `playTalk`, `playOrPause`, `togglePlayback`, `seekPlayback`, `deleteTalk`.
- `ui/TalksScreen.kt`: a *full screen* list with a now-playing strip and a
  scrubber. It was reachable only from the app bar's play icon
  (`ChatScreen.onOpenTalks` -> `MainActivity.showTalks`), not from the tab bar.

So the list, the states, the download and the playback already worked. The only
thing missing was a tab. I wired the existing list up instead of writing a second
one.

## What I changed

1. `ui/TalksScreen.kt:53-106` (`TalksView`) and `:115` (`TalksScreen`): split the screen into
   - `TalksView(vm, modifier)` (new): the list body, no Scaffold/app bar.
   - `TalksScreen(vm, onBack)` (kept): the full screen, now just wraps `TalksView`
     so the tab and the app bar shortcut stay one implementation.
   The body was moved verbatim, only re-indented by four spaces.
2. `ui/Screens.kt:484-492`: new `else if (vm.viewMode == "talks")` branch in
   `ChatScreen` drawing `TalksView(vm)`, placed right after the pins branch and
   before the transcript-empty branch (so it shows even when the transcript is
   empty, exactly like pins).
3. `ui/Screens.kt:972` and its KDoc at 961-964: `ViewModeSwitch` modes list
   gained `"talks" to "Talks"` after `"pins" to "Pins"`, and the KDoc now names
   the four views. This is the
   one segmented row that is the app's tab bar (confirmed: no NavigationBar /
   BottomAppBar / TabRow anywhere).
4. `ui/TalksScreen.kt:286`: the ready-but-not-downloaded branch is now
   a "Play" button that calls `vm.downloadTalk(id, autoPlay = true)` instead of a
   "Download" button. The task says tapping a ready talk plays it, downloading
   first; the previous code needed two taps. rendering still shows only an
   indeterminate bar with no button; failed still shows the state and the
   bridge's error text with no button.
5. `ui/ChatViewModel.kt:1966`: updated the `downloadTalk` KDoc, which claimed
   `autoPlay` was only for a just-finished render. It is now also set by the
   tap-to-play path.

Not changed: `MainActivity.kt`. The app bar play icon and its full-screen route
are kept, and now share `TalksView` with the tab. It is redundant with the tab,
but removing it was not asked for; flagged here for the caller to decide.

## Evidence

Gate:

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin --offline
...
> Task :app:compileDebugKotlin
BUILD SUCCESSFUL in 15s
15 actionable tasks: 1 executed, 14 up-to-date
```

There is no test source set in the app (`find app/src -type d` has only
`main`), so there was no test to add or mutate. Verification is the compile plus
reading the wiring by hand.

Diffstat: 3 files changed, 84 insertions(+), 56 deletions(-). Most of the churn
is the body being re-indented when it moved into `TalksView`.

## Uncertain / not done

- No on-device run: this environment has no emulator, so the tab was not seen
  rendering. The wiring is compile-verified only.
- The four labels ("Transcript", "Cards", "Pins", "Talks") share the row at
  equal weight; on a very narrow phone at a large font scale "Transcript" may
  ellipsise. Not measured.
- A ready talk consumed through `downloadTalk(autoPlay = true)` runs
  `maybeAutoPlay`, which stays quiet (posts a notification instead of playing)
  if the app is not resumed or other audio is playing. Tapping from the tab has
  the app resumed, so it plays, but this is inherited behaviour, not changed.
