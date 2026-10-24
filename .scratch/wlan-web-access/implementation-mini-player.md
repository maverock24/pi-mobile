# Mini player: implementation notes

Worktree `/home/maverock24/github/pi-mobile-keys`, branch `main`, base HEAD `4f897db`
(QRSPI: wlan-web-access play an episode from car mode and clear the phone's copies).
`/home/maverock24/github/pi-mobile` was not touched.

## What the human asked for

"when playing the mp3 there should be a mini player that stays visible in the app no
matter to which view i switch within the app".

## Where it is anchored, and why there

The bar is drawn once, by `MainActivity`'s content lambda, in a root
`Column { Box(weight 1f) { <screen> }; MiniPlayer(...) }` (`MainActivity.kt:292-392`).
The screens are the `Box`; the bar is its sibling. Because the bar is a sibling and not
an overlay, it takes its height out of the screens' measurement, which is what makes it
impossible for it to cover anything: nothing is drawn on top of anything.

It is not part of a screen, deliberately. `ChatScreen` holds the four tabs (transcript,
deck, pins, files) in one `Scaffold`, and `SettingsScreen` and `FilesScreen` are separate
destinations, so a bar added to each would be three copies to keep in step. One copy at
the root survives a tab switch, a trip to Settings and the full screen library, which is
the requirement.

The bar itself is `ui/MiniPlayer.kt`: a `Surface` in `colorScheme.surfaceVariant`, so it
takes the palette of whichever of the four dark themes is chosen and of the light scheme,
the same way the library rows and the car mode cards do. It shows the title from the
library row the player holds, the row's kind as the category (`audio`, `video`), the
clock and a read-only `LinearProgressIndicator`. Play/Pause is a text button running
`vm.playOrPause(name)`, the same call the library row's Play/Pause makes, and both land
in `TalkPlayer.toggle()`. There is no second player, no second service and no new
dependency. Words rather than icons because the core icon set has a play arrow but no
pause, which is the same call car mode made.

## The bottom of the window

The one non-obvious part. Every screen's `Scaffold` pads by `WindowInsets.safeDrawing`,
which includes the navigation bar's strip. If the bar simply sat under the screens, the
composer would end a navigation bar's height above the bar (an empty band between them),
and the bar's own content would need the same strip again below it.

The obvious fix, `Modifier.consumeWindowInsets` at the root, does not work with this
Compose version: material3 1.3.1's `Scaffold` pads by `WindowInsets.asPaddingValues()`,
which does not subtract the consumed insets (`foundation-layout 1.7.5`'s
`asPaddingValues` builds `InsetsPaddingValues` directly; only `windowInsetsPadding`
excludes the consumed value). Verified in the jars in the Gradle cache, not assumed.

So the insets are handed down instead, in one place:

- `LocalScreenInsets` (`MiniPlayer.kt:57-64`) and `screenContentInsets()`
  (`MiniPlayer.kt:66-72`) are what `ChatScreen`, `SettingsScreen` and `FilesScreen` pass
  as `contentWindowInsets`. With nothing set they return `safeDrawing`, which is exactly
  the previous behaviour.
- While the bar is on screen, `MainActivity` provides
  `insetsAboveMiniPlayer()` (`MiniPlayer.kt:74-88`): safe drawing without the navigation
  bar's bottom, unioned with `WindowInsets.ime`. The keyboard's inset is kept explicitly
  because the bar goes under the keyboard and the composer must not; the navigation
  bar's strip is dropped because the bar paints it.
- The bar's `Surface` runs to the bottom of the window and pads its own content above the
  gesture bar (`MiniPlayer.kt:122-135`), so the strip is part of the bar rather than an
  empty band.

With no bar (nothing playing, or car mode) the local is null, the screens pad by
`safeDrawing`, and the layout is byte for byte the one the app had before.

## Tapping it

`vm.openNowPlaying()` (`ChatViewModel.kt:2318-2322`) switches the chat's tab to the
library and sets `filesFocus` to the playing file's name. Both the tab and the full
screen library are the same `FilesView`, which scrolls its `LazyColumn` to that row and
drops the request (`FilesScreen.kt:85-97`), so the episode's own scrubber, Play/Pause and
script button are the thing you land on. No new screen was invented: the library is where
an episode has always been played, paused and scrubbed.

The tap closes Settings if it is open (it is a layer with its own editors and the episode
is behind it) but leaves the full screen library open, since that is already the list the
episode lives in.

## Dismissing it

The close button runs `vm.stopPlayback()` -> `TalkPlayer.stop()`. `TalkPlayer.stop` now
also clears `playingId`, `playing` and `durationMs` itself
(`player/TalkPlayer.kt:131-144`), rather than waiting for the controller to report the
cleared item on its own thread: the bar has to go on the tap that asked for the stop, not
a frame or two later. Clearing the media item is what the app already did when a file was
deleted or the phone's copies were cleared, so a bar left claiming a stopped file was a
latent bug in the state, not new behaviour.

## Car mode

The bar is not drawn in car mode at all: `miniPlayerVisible(playingId, carMode)`
(`MiniPlayer.kt:40-48`) is false whenever `carMode` is on, so the slot, the inset change
and the bar all go together. Car mode already puts one 96 dp play/pause in front of the
driver and a second set of the same controls is two places to look at while driving,
which is the thing that mode exists to avoid. `CarScreen` is untouched.

## What it does not cover

Because it is a sibling in the root `Column` and not an overlay:

- the composer, which now sits directly on top of the bar and above the keyboard;
- the question card, which is an item in the transcript and the deck lists (both inside
  the shortened `Box`);
- the bottom of any scrolling list: `Transcript`, `TurnDeck`, `PinsView`, `FilesView` and
  the Settings list are all measured in the space above the bar.

## The test, and that it can fail

`app/src/test/java/com/maverock24/pimobile/ui/MiniPlayerTest.kt` covers the two decisions
that a glance cannot check: the bar is shown while the player holds a file and hidden in
car mode, and the progress line is a clamped fraction that stays empty while the length
is unknown.

Mutation check, run for real: dropping `!carMode` and dropping the `coerceIn` from the
implementation made the task fail 2 of 6
(`hidden in car mode even while audio plays`, `progress never leaves the line` ->
`expected:<1.0> but was:<1.5>`), the file was restored and its sha256 matched the copy
taken before the mutation (`6648833d...c03a678`).

## Uncertain, for the phone

- No emulator runs here, so the geometry above is reasoned from the Compose and material3
  jars, not seen. What a device has to confirm: the composer sits flush on the bar with
  no gap, the bar's controls clear the gesture bar, and the keyboard still lifts the
  composer with the bar left underneath it.
- `enableEdgeToEdge()` plus `windowSoftInputMode="adjustResize"` is how the app already
  handled the keyboard (through `safeDrawing`, which includes the ime inset). The bar
  keeps that inset explicitly, but a keyboard that reports an inset smaller than the
  navigation bar would be the case to watch.
- A process restart while the service is still playing: whether the reconnected
  `MediaController` reports the existing item (and so brings the bar back) is not tested
  here. The bar is correct while the app itself started the playback.

## The browser now differs, knowingly

`design.md` section 13 says the browser and the phone should not drift, and section 16
decided the hand-written web UI stays and accepts that cost. This change is not mirrored:
the browser has no mini player, so audio started from the phone shows a bar in the app and
nothing in the browser. The relay's API is unchanged by this commit, so the state both
UIs read is still the same; only the phone draws the bar. Recorded here and in the commit
message, as section 16 requires.
