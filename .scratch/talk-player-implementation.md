# Talk player: app-side implementation

Branch `main`, worktree `/home/maverock24/github/pi-mobile-keys`, base commit
`c2b65b6` (the bridge half, `QRSPI: kokoro-tts render talks with edge-tts`). The
sibling checkout `/home/maverock24/github/pi-mobile` was not touched.

## What was built

1. media3 dependency: `app/build.gradle.kts:82` adds
   `androidx.media3:media3-exoplayer:1.5.1` and `androidx.media3:media3-session:1.5.1`
   (Apache-2.0). 1.5.1 was chosen as the newest line that only needs `compileSdk 35`,
   which is what the module already has.
2. `data/TalksStore.kt` (new): app-private storage under `files/talks`. One talk is
   `<id>.mp3` plus `<id>.json` (title, duration estimate, voice, createdAt).
   `isSafeId` gates every path on the bridge's own id shape `[A-Za-z0-9_-]{1,64}`, so a
   hostile id cannot walk out of the directory. `list()` skips a record whose audio is
   gone or whose JSON will not read.
3. `net/PiRemoteClient.kt`: `talks()` reads `GET /api/talks`; `downloadTalk(id, dest,
   onProgress)` streams `GET /api/talks/<id>.mp3` through a `.part` file and renames on
   success, so a dropped connection never leaves a half file that plays as noise.
   (`PiRemoteClient.kt:103` and `:111`.)
4. `player/TalkPlaybackService.kt` (new): a `MediaSessionService` owning the ExoPlayer
   and MediaSession, with `mediaPlayback` as its foreground type. This is the app's
   first service. It holds the process up while audio plays (so a 30 minute talk
   survives the screen going off) and posts the media notification. `onTaskRemoved`
   stops it only when nothing is playing.
5. `player/TalkPlayer.kt` (new): the app's `MediaController` handle on the service.
   `play` queues a request made before the async connection is ready and replays it on
   connect. Exposes `playing`, `playingId`, `durationMs`, `positionMs()` (polled, since
   ExoPlayer does not push position).
6. `ui/ChatViewModel.kt`: the library state and the event wiring.
   - `TalkRow` (`:187`), `talks`, `downloadedIds`, `downloadProgress`, `talksLoading`,
     `talksError`, `player` (`:349`).
   - `loadLocalTalks()` seeds the list from phone storage at startup, so an offline
     phone still has a list to play.
   - `refreshTalks()` merges `GET /api/talks` over the stored rows (local-only rows are
     kept so offline playback survives a bridge that forgets them).
   - `downloadTalk(id, autoPlay)`, `playTalk`, `playOrPause`, `seekPlayback`,
     `deleteTalk`, `maybeAutoPlay` (`:1970`-`:2051`).
   - `handleEvent` gains `"talk"` and `"talk_removed"` (`:1558`).
7. `ui/TalksScreen.kt` (new): the list, a now-playing strip with a scrubber (a 30
   minute file needs one), download with a progress bar, and a delete that reclaims the
   space. Opened from a PlayArrow action in the chat's app bar
   (`Screens.kt:260` signature, `:413` action; `MainActivity.kt:295` branch).
8. Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and the
   `TalkPlaybackService` declaration with `foregroundServiceType="mediaPlayback"`
   (`AndroidManifest.xml:11`-12, `:62`).
9. `notify/Notifier.kt`: a third notification kind, "talk ready", sharing the existing
   `pi-runs` channel. `post` was generalised from a boolean to a title plus id; the two
   existing entry points produce byte-identical notifications.
10. Voice-only submit (`voice/Dictation.kt:18`-`26`, `:156`): a whole spoken submit
    phrase calls a new `onSubmit` callback instead of appending.

## Decisions 5 and 6, the two open questions

### Auto-play must not hijack the phone (decision 7, "when ready")

Chosen rule, in `maybeAutoPlay` (`ChatViewModel.kt:2044`): auto-play runs only when
**all three** hold at the moment the download completes:

- the app is in front (`PiRemoteApp.isResumed`),
- no other audio is playing (`AudioManager.isMusicActive` is false; this is what "never
  interrupt other playback" means concretely), and
- this app's own player is idle (`player.playing` is false), so a talk already being
  listened to is never cut off by a newer one.

If any fails, the talk is not dropped: a "talk ready" notification is posted instead, so
a render that finishes while the phone is pocketed (the car case) is still announced.
The tap opens the app, where the ready talk waits.

Why this and not more: "the human is present" is hard to know for certain, and the
brief asks for the simplest behaviour that cannot hijack the phone. Foreground is the
one presence signal the app already has and trusts. It does **not** cover the case the
human named first, playback starting on car audio while the app is not open; detecting
that means reading the audio output route and guessing, which is more surface for a
wrong guess. Recorded as the deliberate limitation: the notification covers that case
instead of an auto-play that might fire in the human's pocket.

### A spoken trigger, not a silence timeout (decision 8, voice-only submit)

Chosen rule (`Dictation.kt:18`): submit when the recogniser returns a **whole utterance**
that is one of `"send it"` or `"send it now"` (case and trailing punctuation ignored),
as detected by `isSubmitTrigger`. Anything else is appended as before.

Why a trigger and not a silence timeout: the brief's own warning is that a timeout
"must not submit mid-sentence", and any timeout short enough to feel responsive will
fire during a normal thinking pause. A trigger that only matches a complete utterance
cannot fire mid-sentence by construction, and it needs no new timer. `"send it"` buried
in a sentence ("I will send it tomorrow") is a longer utterance and is kept, never sent.

The trigger sits above the keyboard: the app uses its own platform-recogniser
`Dictation`, not the keyboard's dictation, so the phrase is read in `onResults` before
any text reaches the composer. The mic stays on after a submit, which is the existing
toggle behaviour; nothing here turns it off.

## Verification

Gate, run in the worktree:

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin --offline
BUILD SUCCESSFUL in 11s
15 actionable tasks: 1 executed, 14 up-to-date
```

Stronger check, also offline, to prove the service and media3 package:

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug --offline
BUILD SUCCESSFUL in 1m 16s
```

Merged manifest confirms the new pieces landed:

```
grep -o 'FOREGROUND_SERVICE[A-Z_]*\|TalkPlaybackService\|mediaPlayback\|media3.session.MediaSessionService' \
  app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml | sort -u
FOREGROUND_SERVICE
FOREGROUND_SERVICE_MEDIA_PLAYBACK
media3.session.MediaSessionService
mediaPlayback
TalkPlaybackService
```

media3 was not in the local Gradle cache, so the first (online)
`:app:compileDebugKotlin` populated `/root/.gradle/caches/modules-2/files-2.1/androidx.media3`
with 1.5.1; every gate run above was then `--offline` and resolved from that cache.

## Uncertainties and things not done

- **No runtime test.** There is no emulator here and the project has no test source set,
  so nothing about the download, playback, background service or the spoken trigger was
  executed. The evidence stops at "compiles and assembles". Adding a unit test would
  need a JUnit dependency, which the brief forbids, so `isSubmitTrigger` is reasoned
  through rather than exercised: `"Send it."` normalises to `"send it"` (true),
  `"send it now"` is in the set (true), `"I will send it"` and bare `"send"` are not
  (false).
- **Car-audio-only presence is not detected**; see the auto-play decision above. The
  notification is the fallback.
- **No size cap and no script beside the audio.** The state's "shape this implies" wants
  both, but the slice is decisions 4 to 8, which ask for download plus offline playback.
  Delete is present because it is the way storage is reclaimed. The script is not
  downloaded because the bridge serves no script-text endpoint; the app has nothing to
  fetch.
- **Deleting a download leaves the master on the laptop.** Whether the two should be
  linked is listed in the state as an open design question, so the tap only removes the
  phone's copy and the code says so.
- **The duration is the bridge's estimate**, not read from the MP3, so it is shown as
  "about N minutes". edge-tts `--write-subtitles` would give real timings and is not
  wired.
- **`MediaController` streaming for a 30 minute file**: the bridge's single-use link
  cannot be resumed, which is why the app downloads with the bearer and plays the local
  file. Seeking works locally; streaming from the bridge is not built.
