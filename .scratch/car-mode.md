# Car mode: design decisions

Worktree `/home/maverock24/github/pi-mobile-keys`, branch `main`, base HEAD
`2d97ac6` (QRSPI: kokoro-tts generalise the talks tab into a files library). The
sibling checkout `/home/maverock24/github/pi-mobile` was read only and not
touched.

## What the human asked for

"the app should have a car mode, making the user interface much easier to
navigate and use while driving." Design it around what driving allows, not
around shrinking the existing UI.

## The shape, and why

Car mode is a **mode of the same view model**, not a second app. It is a boolean
on `ChatViewModel`, persisted in `SettingsStore`, and `MainActivity` draws
`CarScreen` instead of `ChatScreen` while it is on. The transcript, the
dictation, the player and the settings are the ones the normal screens use, so
the mode changes which screen is drawn and nothing else. That is what keeps it
additive: the chat is not forked, and leaving car mode is a redraw, not a
restore of state that was torn down.

Inside the mode there are two halves, **Ask** and **Listen**, chosen by two
full-width buttons rather than a tab bar. This is the reading of "one thing per
screen, two or three choices at most" that the driver gets: the screen holds one
job, and the switch itself is two huge targets. Ask is first and is the default,
so entering the mode puts the mic on screen with no navigation.

### Ask: voice first

- A **176 dp round Talk button** is the primary action. One touch starts the
  platform recogniser, one more stops it, and the label says which will happen.
- **"Say "send it now" to send"** is on screen above it, in title type, so the
  driver does not have to remember the phrase. The phrase already works: it goes
  through `Dictation.onFinal` to `appendToDraft`, which is the same
  `promptBeforeSubmitTrigger` path the keyboard dictation uses, so voice-only
  submit is unchanged and the car mode only makes it the obvious action.
- The heard words are drawn large: the live partial while it is recognised, the
  settled draft once it is. A driver can glance and see the prompt took.
- A **96 dp Send** button is the fallback for when the phrase was not said. It
  runs the same `send` the phrase runs and is disabled while there is nothing.
- The same confirm or reject haptic the chat plays follows a send, because a
  driver cannot look at the screen to learn whether the prompt left.

### Listen: the other half of the trip

- One **96 dp Play/Pause** for the current audio, with its title in headline
  type. The button label says what it will do.
- A **read-only progress bar and clock**, no seek slider. Position is polled
  because the player does not push it.
- A **short list of up to three** audio and video files, each a full-width
  **72 dp** target. Files already on the phone sort first, because those play on
  the first tap; one that is not is downloaded and then played, so a tap is one
  action either way. A running download shows its percent and cannot be tapped.
- A render that is still going or has failed is left out of the list rather than
  offered as a control that cannot work.

## Getting in and out

- **In**: a "Car" text button in the chat's app bar calls
  `vm.updateCarMode(true)`. A word rather than an icon, because the core Compose
  icon set this app depends on has no car glyph and a new dependency was out of
  scope.
- **Out**: a big Exit button, always on screen in car mode, and the system back
  gesture, wired to the same exit. Exit is not destructive and does not touch
  playback.
- The choice is **persisted** so a process restart mid-drive comes back to the
  big controls rather than the dense UI. There is no auto-detection: no
  Bluetooth, no charging, no driving detection. The toggle is manual.

## What happens to audio when the mode is switched

**Nothing.** Entering car mode connects the player controller (idempotent) and
reads the library, but does not play, pause or stop anything. Leaving car mode
does not either. Audio keeps coming out through `TalkPlaybackService` and its
media notification across both switches, exactly as it does across a trip to
Settings today. The mode changes the screen, not the session.

## What I deliberately left out

- **No auto-detection** (Bluetooth, charging, car dock). The task says a manual
  toggle is enough and that auto-detection is not worth it unless genuinely
  simple, and a wrong guess that drops a driver into or out of the wrong UI is
  worse than a tap.
- **No sliders, no search field, no delete, no settings editors** in car mode.
  The File preview (text and images) is also left out: reading a long text or an
  image is not a driving task, and it is one tap away in the normal Files view.
- **No new player.** media3 and `TalkPlaybackService` are reused unchanged; car
  mode drives the same `TalkPlayer`.
- **No new icons or dependencies.** Play/Pause are words, not glyphs, because
  the core icon set has neither a car nor a pause here.
- **No seek-from-car-mode.** `TalkPlayer.seekTo` exists and the Files view still
  owns it; a precision drag is the one control a driver cannot use.

## The one change to the normal UI

A "Car" text button is added to the chat's app bar. It is the entry point the
toggle needs and it is purely additive: no existing control moved, changed label
or changed behaviour, and the composer, transcript, deck, pins and files views
are untouched.

## Uncertain

- Nothing in this environment runs an emulator, so the layout at a very large
  system font scale is reasoned about, not seen. The heard text scrolls rather
  than clipping and the buttons carry `heightIn(min = ...)` rather than a fixed
  height, which is what the rest of the app does for the same reason, but a real
  device check at fontScale 2.0 is still owed.
- The two-panel split has one screen each with the switch plus Exit on it. If a
  reviewer reads "two or three choices at most" as counting the switch and Exit,
  the honest answer is that they are the mode's chrome, not the job: Ask is Talk
  and Send, Listen is Play/Pause and the short list.

## Evidence

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew
  :app:compileDebugKotlin --offline` succeeds, with no compiler warnings on a
  `--rerun-tasks` pass.
