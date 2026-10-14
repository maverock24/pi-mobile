# Read answers aloud with Kokoro, on the phone

- **Slug**: kokoro-tts
- **Stage**: Research (complete). **Design NOT started**: it is the next stage, and its
  first question is the fork recorded at the end of this file.
- **Research result (`.scratch/kokoro-tts/research.md`)**: on-device Kokoro with
auto-read is **not viable on the target phone**, and that is a measured finding,
not a preference. Auto-read needs a real-time factor below 1.0. Published ARM
figures put Kokoro fp32 at 2.77 to 3.19 on a Raspberry Pi 4, the closest Android
field telemetry (sherpa-onnx, 63 devices) has a **median 3.48 with a best tenth at
2.33**, and a shipping Android Kokoro app reports 60 to 90 seconds per minute on
mid-range hardware. The Nokia XR20 is a Snapdragon 480, budget class today, and
nothing published puts Kokoro below 1.0 on hardware in that class. INT8 might
halve it, which is still above 1.0.

**The cheaper options, and what they cost (added to the research on request):**

| Option | RTF | Size | Licence | Keeps up? |
|---|---|---|---|---|
| Android built-in `TextToSpeech` | none published; it is what TalkBack reads with | **none** | **no new dependency** | yes, by design |
| SupertonicTTS 3 int8 | ~0.3 on a budget Android 11 e-reader, vendor demo | 145 MB, MIT weights | GPL-3.0 if used through sherpa-onnx | yes |
| Piper / VITS `en_US-lessac-medium` | 0.357 at four threads on a Pi 4 | 61 MB | GPL-3.0 via espeak-ng | yes |
| Matcha-TTS `en_US-ljspeech` | 0.411 at four threads | ~122 MB | GPL-3.0 via espeak-ng | yes |
| espeak-ng | the floor by construction | a few MB | GPL-3.0-or-later | yes, robotic |
| Kokoro (the original choice) | ~3.5 on Android phones | 114 MB int8 | GPL-3.0 via espeak-ng | **no** |

**The research's recommendation, which supersedes the Kokoro path:** use **Android's
built-in `TextToSpeech` for auto-read**. It needs no model download at all, so the
first-use download path designed here is not built; it is the only option that both
keeps up and avoids GPL-3.0, and it is the least integration because it plays its
own audio. Its honest limit: quality depends on the engine installed, and the API
exposes an `ERROR_NETWORK` path, so a neural voice is not guaranteed offline.

If a neural voice matters more than the licence, **Piper `en_US-lessac-medium`** is
the pick: the cheapest neural model with a published RTF below 1.0, reusing the same
sherpa-onnx and `AudioTrack` design with a 61 MB download instead of Kokoro's 150 MB.

**edge-tts, evaluated on request and excluded from the ranked options.** It is a
community client (`rany2/edge-tts`) that impersonates Edge: it opens the same
WebSocket Edge's Read Aloud uses with a hardcoded `TrustedClientToken`, an Edge
User-Agent and the Edge extension's `Origin`, and Microsoft rejects any SSML Edge
itself would not emit. **The client is LGPL-3.0, not GPL** (a correction to what a
first pass said). **The service has no licence at all**: the endpoint is
undocumented and Microsoft has published no terms permitting calls from a non-Edge
application, only a support-forum opinion. It is also actively gated: the
`Sec-MS-GEC` anti-abuse token added in 2024, hardcoded-token rotation, regional IP
blocks.

It **can** run on Android (`yynag/edge-tts-android`, a Kotlin engine, GPL-2.0), so
"cannot run on the phone" would be wrong. What is not fixable is that it is a
**network client**: every utterance is a WebSocket call, so lose signal and reading
stops. That contradicts the on-device decision more directly than any licence could.

**The official equivalent is what someone means when they say edge-tts: Azure
Speech.** Free tier (F0): 0.5M neural characters a month, 20 transactions per 60
seconds; then $15 per 1M characters. The same `*-Neural` voices, an official
Android SDK, explicit terms. Still cloud.

| | Voice | Offline | Licence/terms | Integration | Cost |
|---|---|---|---|---|---|
| Android `TextToSpeech` | engine-dependent | yes | none added | least | free |
| Piper | neural | yes | GPL-3.0 (espeak-ng) | sherpa-onnx plus player | free |
| edge-tts | Azure voices | **no** | LGPL client, unauthorised service | WebSocket client plus player | free, unsupported |
| Azure Speech | same Azure voices | **no** | commercial, key | official SDK | free to 0.5M chars, then $15/1M |

**The question this reopens, which only the human can answer:** is working
**offline a requirement or a preference**? If it is a requirement, the system engine
is the answer and Piper is the neural upgrade with GPL attached. If it is only a
preference, Azure's voices come back into play and the on-device decision relaxes.

## Decision 3, 2026-10-11: offline is a requirement, not a preference

**Superseded the same day: the human reopened it ("offline is negotiable").** So
the system engine is no longer forced by that decision, and the cloud options
return on their merits. Decision 1 (on the phone) is likewise relaxed. The
limitations that now decide it are recorded at the end of `research.md`, the
privacy one first.

So the stack is **Android's built-in `TextToSpeech`** for auto-read. It is the only
option that keeps up, it adds no dependency and no licence, and it needs no model
download, so the entire first-use download path designed for Kokoro is not built.
The feature is one API and a queue.

Piper `en_US-lessac-medium` stays the documented neural upgrade if the voice ever
comes to matter more than the GPL-3.0 it carries, and it reuses nothing new.

**edge-tts and Azure Speech are both excluded by this answer**, since each is
online-only. That is the whole reason the earlier Kokoro-with-auto-read plan was
replaced, and it is now written down as a decision rather than a preference.

**What Design still owes, none of which this decision touches:**

- The platform engine's quality depends on what is installed, and the API exposes
  an `ERROR_NETWORK` path, so the design owes a behaviour for an engine that is
  missing, muted, or carrying only a network voice: fall back, or go quiet.
- Auto-read queues whenever the reader is slower than the answers arriving. The
  system engine keeps up, but a question widget, a fresh prompt, or a tap must be
  able to interrupt it, and something has to say who wins.
- Nothing speaks unasked on first launch, so the feature needs its own enable step:
  auto-read that starts talking before the human expects it is worse than none.

**The fork Design must put to the human** is therefore three ways, not two:

| Path | Automatic reading | Voice | Licence |
|---|---|---|---|
| System `TextToSpeech` | yes, and least work | the phone's installed engine | adds nothing |
| Piper via sherpa-onnx | yes, 61 MB download | neural, good | GPL-3.0 |
| Kokoro behind a manual button | no, only on a tap | neural, best | GPL-3.0 |

The licence column is not incidental: every sherpa-onnx TTS path carries GPL-3.0
espeak-ng, and this repo has no `LICENSE` at all, which the onboarding audit
already flags as a blocker.

Three numbers must be measured on the device rather than read from a page, and
the research lists them: INT8 Kokoro's real-time factor on the XR20 warm, at two
and four threads, on a realistic 3 to 5 sentence answer; real memory at load,
since the 114 MB int8 model decompresses in RAM on a 4 to 6 GB phone; and the
warm-up cost on first use.

## Answers taken by the human, 2026-10-11

- **Where it runs**: everything on the phone. A quantised Kokoro model runs on
  the device, so reading aloud works offline and costs no round trip. The phone
  is a Nokia XR20 (Android, mid-range), which is the performance target to test
  the recommendation against.
- **When it speaks**: automatically, as each answer settles. This is the literal
  reading of "reads out loud responses to me", and it means synthesis cannot be
  slower than the time it takes to read, or the queue grows without bound.

## What is known before research

- Kokoro is not installed anywhere on this machine and no part of this repo uses
  it, so there is no existing pattern to copy.
- The app plays no audio at all today: `voice/Dictation.kt` is the only
  audio-adjacent file, and there is no `MediaPlayer`, `ExoPlayer` or
  `AudioTrack` anywhere in `app/src/main`. Whatever plays the audio is new.
- The bridge serves no audio either.
- The app is Kotlin + Compose, `minSdk` 26, `compileSdk` 35, and the same
  machine builds it with JDK 17.

## Open questions research must settle

1. The current Kokoro release and what "latest version" is today, with the
   package and model file names rather than a version remembered from a blog.
2. The on-device runtime: ONNX Runtime Mobile, sherpa-onnx, or something else,
   and which of them actually ships Kokoro with a phonemiser that works on
   Android. The phonemiser is the risk: espeak-ng has no pleasant JNI story.
3. Model size and quantisation, and whether it is downloaded on first use or
   shipped in the APK.
4. Latency on a phone in this class, for a paragraph of text, since auto-read
   turns that number into the feature's viability.
5. Licences: the model's, the runtime's and the phonemiser's, since this
   redistributes all three.

## Pivot, 2026-10-11: render a talk on the laptop, play it on the phone

The human proposed a different shape, and it removes the constraint that made
Kokoro unusable: **the pi session writes a script for a subject, the laptop renders
it to an MP3 with Kokoro, and the phone plays it, streaming or downloaded, with the
file kept so it can be listened to again.**

Why this is not the same problem as read-aloud:

- The phone **decodes an MP3**. No on-device inference, no Android phonemiser, no
  114 MB model, no RTF limit. Every limitation that mattered for automatic reading
  is absent, because nothing is synthesised on the phone.
- The render happens once, on a machine with cores and a mains supply, in a
  background job. A 30 minute talk at a plausible desktop RTF of 0.3 to 1.0 is 10
  to 30 minutes of rendering, and nobody waits on it. **The desktop RTF on this
  laptop is unmeasured**, and it is the first number to establish.
- Auto-read disappears as a concept, so RTF above 1.0 on ARM stops being a blocker.

Consequences worth carrying into design:

- A 30 minute MP3 is about 14 MB at 64 kbps and 29 MB at 128 kbps, so size is not a
  constraint, but a 30 minute file wants HTTP range support for seeking.
- The GPL-3.0 problem changes character: it evaporates if the repo only **invokes**
  a locally installed Kokoro as a subprocess and ships no GPL binary. Keep the
  invocation external for that reason.
- This is a different feature from reading answers aloud, and the two should not be
  conflated: one is a library of generated talks, the other is speech on demand.

## Decisions 4 and 5, 2026-10-11

- **Delivery**: download to the phone. The MP3 lives in the app's own storage and
  plays offline afterwards, so "keep it in pi remote" means a library on the phone.
- **Trigger**: a skill the session runs. The human gives pi a subject, the skill
  writes the script and hands it to a renderer. Nothing in the app starts a render
  by itself.

## The shape this implies

1. **A skill** (for example `talk`) that turns a subject into a script and calls a
   local render command. It must not render inside the pi process: it invokes a
   separately installed Kokoro, so the repo ships no GPL binary and the licence
   question evaporates.
2. **The bridge** publishes what was rendered: a list, the file itself with HTTP
   range support (a 30 minute file wants seeking), and a way to register a
   finished render.
3. **The app** gains a Talks view: the list, a download with progress, and
   playback. This is the app's **first audio playback** - there is no
   `MediaPlayer`, `ExoPlayer` or `AudioTrack` in it today - so a player, a media
   session for background play, and its notification controls are all new work.
4. **Storage on the phone**: downloaded MP3s with the script kept beside each, so a
   talk can be read or re-rendered. Wants a delete and a size cap.

## Design questions this leaves open

- A render takes 10 to 30 minutes for a 30 minute talk. Where the waiting shows,
  and what happens to a render if the app closes or the session ends.
- Whether the phone can read the script as text beside the audio.
- Whether deleting a downloaded talk deletes the master on the laptop.
- Seeking in a long file, and playing with the screen off.

## The player, confirmed by the human, 2026-10-11

"we would need to have a simple mediaplayer in the app that then plays the
rendered audio."

The player itself is small. What a 30 minute talk forces is the plumbing around it:

- **Use media3** (`androidx.media3:media3-exoplayer`, Apache-2.0, no GPL). It
  seeks in a long file properly and pairs with `media3-session` for background
  playback. The platform `MediaPlayer` would do for a foreground-only player and
  nothing more.
- **Background playback is effectively required, not optional**: nobody holds a
  phone awake for a 30 minute talk. That means a `MediaSessionService` and the
  notification it posts, which means `FOREGROUND_SERVICE_MEDIA_PLAYBACK` in the
  manifest and the notification permission on Android 13+. **This is the app's
  first service and its first foreground permission**, so it is a behavioural
  change rather than a dependency.
- Playback reads from the phone's own storage, since the download decision puts the
  file there. Streaming from the bridge becomes a later extension, not a
  prerequisite.
- The UI owes: play and pause, position and a scrubber for a long file, and the
  script readable beside the audio.

## The render machine, 2026-10-11

The human named the machine that would render: a **CSL VenomBox, Ryzen 7 8845HS
(8 Zen 4 cores, 16 threads), 32 GB DDR5, Radeon 780M**. Note that is **not** the
laptop this repo is developed on (a ThinkPad T495), so the render host is a
separate machine and the bridge must reach it or run there.

**Estimated render time for a 30 minute talk: about 3 to 6 minutes**, and that is
an estimate, not a measurement. It is scaled from the only published ARM figure,
Pi 4B at four threads: Kokoro fp32 RTF **2.774**. A 8845HS has roughly 16 to 18
times that machine's multi-core throughput, which puts RTF near **0.16**, and
30 minutes of audio at 0.16 is about 5 minutes. Thread scaling is sublinear in
practice (Piper goes 0.774 at one thread to 0.357 at four, a 2.2x gain), so treat
the range as 3 to 6 minutes single-process.

**Two things could make it faster, one cheap:**

- **Render the script as parallel chunks.** Four processes at four threads each,
  split at paragraph boundaries, should land near **1 to 2 minutes**. The joins have
  to fall between sentences, not inside them.
- **The 780M iGPU** could help further, but only with an ONNX Runtime build that
  addresses it (DirectML or ROCm), which is unverified and not worth planning on.

Use **fp32**, not the int8 the phone would have needed: 32 GB makes the model's
footprint irrelevant, and fp32 is the better voice.

**What to measure, first thing, before designing around a number:** time a
two minute script on the VenomBox. That gives the real RTF in about five minutes of
work and replaces every estimate above.

## Decision 6, 2026-10-11: edge-tts, and the car use case

The human chose **edge-tts** (`github.com/rany2/edge-tts`) because this is not a
commercial app. That settles the licence objection that made Azure the safer
cloud option: LGPL-3.0 is fine for personal use, and nothing is redistributed if
the bridge only calls an installed `edge-tts` as a subprocess. Kokoro stops being
the plan; its speed on the VenomBox stays recorded above as a fallback if edge-tts
proves unreliable.

**The use case, in the human's words: "basically while in the car, ask via pi
remote to create a 30min script about a topic and then read it to me."** This is a
hands-free, eyes-free scenario and it is the design's real constraint:

- The request arrives **from the phone, by voice**, so the skill must work with no
  typing and no confirmation step in the middle.
- The render takes minutes **while the human is driving**, so the app must be able
  to tell them when it is ready without them looking. A rendered talk that nobody
  knows about is a failure in the car.
- It must not require the screen to stay on, which is the same background-playback
  requirement recorded above.

**Neither the app player nor the notification is needed for the first usable
version**: the bridge can hand the phone a download link, and the phone's own
music player can play the MP3. That is worth doing first, because it works in the
car before the app has any audio code at all.

## Decisions 7 and 8, 2026-10-11: voice alone, and auto-play when ready

The human added two requirements, and together they make the app slice mandatory
rather than a nice-to-have:

- **Dictating in the open mic must submit by voice alone.** No tap to send. That
  means a voice-end trigger, either an explicit spoken word or an end-of-speech
  silence timeout, and the timeout's main risk is submitting mid-sentence. Where
  the trigger lives matters: if the text arrives through the keyboard's own
  dictation it lands in the composer as ordinary text, and the trigger has to sit
  above that.
- **A finished talk starts playing on its own.** The bridge already has an SSE
  stream at `/api/events`, so the render can announce itself instead of being
  polled; the app then downloads and plays without a tap. This closes the gap
  noted with decision 6, where a render finishing in a car was invisible.

Both are app-side work, so the next slice is: the media3 player, a
`MediaSessionService` for background play, the Talks list, the talk-ready SSE
event, auto-download and auto-play, and voice-only submit.

Open question for design: auto-play must not hijack the phone. It should fire when
the human is present for it, for example with the app open or on car audio, and it
must not interrupt other playback.

## The bees run, 2026-10-11: first real render, and a bridge bug

Rendered: script 4538 words at `~/.pi/agent/talks/scripts/bees.txt`, MP3
`~/.pi/agent/talks/bees.mp3`, **26 min 21 s**, 9.5 MB. It was produced by calling
the `edge-tts` CLI directly, because the bridge path failed.

**The bridge's render is broken, and this blocks the whole phone path.** It
resolved the binary (`edge-tts is installed at ~/.local/bin/edge-tts`) and then
failed to run it with `ModuleNotFoundError`. The CLI works from a shell, so the
fault is in how the bridge invokes it, not in the install: most likely the bridge
runs `python3 -m edge_tts` and gets an interpreter that does not have the package,
while `~/.local/bin/edge-tts` carries its own shebang into the environment it
belongs to (a pipx or venv install, which also explains why `python3 -c "import
edge_tts"` fails). Fix: spawn the resolved binary path with its own shebang intact,
and do not assume the bridge's Python has the module.

This must be fixed before auto-play can work, because the app learns about a talk
only through the bridge. Fix it, then `/reload` the owning pane, then re-run.

---

> Copied into the `pi-mobile-keys` worktree on 2026-10-13 because the original
> lives in the `pi-mobile` worktree, which was off limits for this task.
> Everything above is the original; the section below is new.

## Measured real-time factor, 2026-10-13 (replaces the estimates above)

The Kokoro the human means is installed on **this laptop**, the ThinkPad T495
that the `pi-mobile-keys` worktree lives on, not on the VenomBox:

- `~/.local/share/kokoro/venv` (Python 3.10.17) with `kokoro 0.9.4`,
  `torch 2.14.1+cpu`, `misaki 0.9.4`, `espeakng-loader 0.2.4`.
- Weights `hexgrad/Kokoro-82M` in `~/.cache/huggingface/hub`, voice `af_heart`.
- There is **no `kokoro` on PATH**; the CLI is the venv's own
  `~/.local/share/kokoro/venv/bin/kokoro`. `~/.local/share/kokoro/render.py` was
  the earlier paragraph-chunking wrapper, and `register-talk.py` beside it copied
  a finished render into the bridge's talks directory by hand.
- The bridge now ships `bridge/skills/talk/render-kokoro.py` and spawns the venv
  python with it, so the render is automated and version controlled.

Measured on CPU with all four threads this CPU has (`Ryzen 3 PRO 3300U`, 4 cores,
no SMT):

| Script | Speech | Wall (incl. one model load) | RTF = wall / speech |
|---|---|---|---|
| 195 words | 75.6 s | 94.1 s | 1.24 |
| 585 words | 232.3 s | 265.4 s | 1.14 |
| earlier browser-ai log (28 min) | 1697 s | 2358 s | 1.39 |

Subtracting the one-off model load (about 6 s per process) leaves a **marginal
RTF of about 1.1**; the whole-run figure including load is **1.14 to 1.24**, and
the earlier long run sat at 1.39. So the honest range on this laptop is **RTF 1.1
to 1.4**, and it moves with thermal and load.

**Consequence.** A 30 minute talk is **about 33 to 42 minutes** of rendering here
at RTF 1.1 to 1.4, not the 3 to 6 minutes estimated above for the VenomBox. That
estimate (RTF ~0.16) was for an 8-core Ryzen 7 8845HS, a different machine that
nothing here was measured on. On this laptop a 30 minute talk is half an hour of
wall clock, so "minutes, not an hour" is false here. The number to design around
is **RTF ~1.1**. Getting a 30 minute talk under ten minutes needs the parallel
chunk render recorded above, or the VenomBox.
