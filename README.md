# Pi Remote

## Quickstart

Android client for a pi coding session running on your own machine. Prompt pi from your
phone, dictate instead of typing, follow the agent's output live, and stop a run that is
going the wrong way.

This is the whole path from a fresh machine to a working transcript. You do not build the
app: a signed APK is published on every push, so the Android SDK, the JDK, the Gradle build
and the release signing key never come up. Building is only for changing the app, and it is
further down under [Building the app](#building-the-app-optional).

### What you need

- **pi**, the coding agent the phone drives. The bridge is a pi extension, so there is
  nothing to talk to without it. Install it with
  `npm install -g @earendil-works/pi-coding-agent`.
- **Node.js**, version 22.19.0 or newer. pi runs on Node, and the install above needs it.
- **Tailscale**, installed and signed in on both the laptop and the phone, on the same
  tailnet, with MagicDNS enabled. The bridge binds only to your tailnet and the phone
  reaches it only over Tailscale. MagicDNS matters because the app asks for the laptop by
  name, `<laptop>.<tailnet>.ts.net`, and not by raw address.
- **`qrencode`** and an image viewer (`eog`, `gwenview`, `feh`, or `xdg-open`). `/pair`
  draws the pairing QR with `qrencode` and shows it in the viewer. After copying the files,
  `bridge/install.sh` names either one if it is missing.

### 1. Install the bridge

The bridge is three files under `~/.pi/agent`, and the extension imports the other two, so
all of them have to land there. One script does it:

```
git clone https://github.com/maverock24/pi-mobile.git
cd pi-mobile
bridge/install.sh
```

It prints where each file went, the sha256 it wrote, and the git revision it came from.
Read the notes at the end: they name anything missing for pairing.

### 2. Start pi

Point the bridge at this laptop's Tailscale address, then start pi:

```
export PI_REMOTE_HOST="$(tailscale ip -4)"
pi
```

The built-in default for `PI_REMOTE_HOST` is the author's own tailnet address. That address
does not exist on your machine, so without this line the bind falls back to `127.0.0.1` and
the phone cannot reach it.

The first time pi starts with no phone paired yet, the bridge mints a pairing code on its
own and puts a QR in front of you, with no `/pair` needed. It shows in the terminal or in an
image window, and it is valid for two minutes and single use. If it expires before you scan,
run `/pair` to show a fresh one.

The phone never asks you to type the laptop's address. When it pairs, the bridge fills the
QR with the laptop's MagicDNS name, read from `tailscale status`, and the app adopts that
address and its device token. `PI_REMOTE_HOST` is only about which interface the laptop
listens on, not about anything you type on the phone.

### 3. Install the app

Download the signed APK from the latest release and open it to install (Android will ask
you to allow installing from the browser):

https://github.com/maverock24/pi-mobile/releases/latest

### 4. Pair

Open **Settings > Pairing** in the app, tap **Scan the pairing QR**, and point it at the QR
from step 2. The app reads the address and the token out of the code and saves them. If the
code expired or was already spent, run `/pair` for a new one.

### It works when

- the pi pane prints `pi-remote listening on <your-tailnet-ip>:8787`;
- the app's title bar names the pi session's folder, the status line reads `thinking…` while
  pi works, and the transcript fills with answers once you send a prompt;
- `Settings > Connection` shows the bridge URL and token already filled in, with no 401 (bad
  token) or timeout (Tailscale down, or bridge not running) in the status line.

## Where to go next

- The full pairing walkthrough, including the manual fallback, is under [Pairing](#pairing).
- Why it is designed this way, and what the one unauthenticated endpoint trusts, is in
  [`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md).
- If you want to simplify the flow further, the current assumptions and their costs are in
  [`docs/PAIRING-REVIEW.md`](docs/PAIRING-REVIEW.md).

## How it fits together

The phone talks to a small HTTP + SSE bridge that the `pi-remote` extension runs *inside*
the pi process, so the app drives the same session you have in front of you on the desktop.
Everything travels over Tailscale: nothing is exposed to the internet, and no port is
opened on the LAN.

```
Pixel/phone ──Tailscale (WireGuard)──► laptop:<laptop>.<tailnet>.ts.net:8787
                                          │  pi-remote extension (HTTP + SSE)
                                          ▼
                                      pi session (the live one, same process)
```

## What the app shows

Answers, and nothing else. The transcript lists finished (and in-flight)
assistant answers; prompts, tool calls, model names, context usage and cost stay
out of the way. The only status on screen is "thinking…" while pi works.

One line under the title bar names the session this phone is attached to: the
folder it runs in and the name it carries, as the bridge reports them. The title
bar itself carries the latest prompt once there is one, so without that line a
phone pointed at the wrong pane looks exactly like the right one.

URLs in an answer are rendered as tappable links, because reading an answer on
the phone usually ends with going to check the source. Bold and inline code are
rendered too, and the whole answer is selectable for copying.

## Dictation

Tap **Mic** and speak. Android's recogniser restarts itself after each pause, so
you can dictate a long prompt in several breaths; finished phrases are appended to
the composer and partial words show above it. Nothing is sent until you tap
**Send**, so you can fix a misheard word first.

## Pairing

The quickstart pairs with the QR the bridge shows at startup. This section is the
full walkthrough, including the manual paths, for when you need them.

Run `/pair` in the pi session that owns the bridge. It writes a QR to
`/tmp/pi-remote-pair.png`, opens that file in an image viewer, and mints a one-time code
valid for two minutes:

```
pi-remote://pair?v=1&u=<base64url of http://<laptop>.<tailnet>.ts.net:8787>&c=<code>
```

Open `Settings > Pairing` in the app and tap **Scan the pairing QR**. The app runs its own
scanner (ZXing, bundled, no Play services component), posts the code to `/api/pair`, and
adopts the address and token that come back, so the phone never depends on the system camera
resolving a custom scheme. A scanned or copied link can also be pasted into the field beside
the button, and a device that still resolves `pi-remote://` can open the app directly.
Nothing extra is kept on the phone.

The scanner is the only reason the app asks for the camera. The permission is requested at
runtime when you tap the button, and denying it leaves the paste field working;
`android.hardware.camera` is declared `required="false"` so the app still installs without a
camera. ZXing decodes on the device and no frame is stored or sent anywhere.

The QR lives in the image window, not in the terminal. pi caps widget content at ten lines
(`MAX_WIDGET_LINES` in `interactive-mode.js`) and appends `... (widget truncated)` past
that, while a QR for this payload needs about 23 lines, so `/pair` puts only a caption and
the file path in the widget. The viewer is chosen in the order `eog`, `gwenview`, `feh`,
`xdg-open`: `xdg-open` alone resolves to a Chromium-based handler here, and Chromium refuses
to run as root. If no window appears, open `/tmp/pi-remote-pair.png` by hand.

`/pair` refuses if another pi session holds the bridge port, and says which pid does. Only a
process that really bound the port writes a lease under `~/.config/pi-remote`, so a second pi
session cannot evict the one that is serving; run `/remote claim` in the session you want the
phone to talk to.

The token is not in the QR. A QR is a screenshot waiting to happen, and a deep link lands in
the camera history, so the code is what travels: single use, two minutes, minted per pairing.
The endpoint is the only unauthenticated route on the bridge; its guards are under F12 in
`docs/THREAT-MODEL.md`.

`Settings > Connection` still takes a base URL and token by hand. That is the fallback when
the camera will not open a custom scheme, and the only way on builds older than v0.0.27. The
address is the MagicDNS name, `http://<laptop>.<tailnet>.ts.net:8787`; a raw tailnet IP is
refused (see the note on cleartext below). The token is one device's entry from
`~/.config/pi-remote/tokens.json`. A 401 in the status line means the token is wrong. A
timeout means Tailscale is not connected on the phone or the bridge is not running.

## Updates

Every push to `main` builds a signed release APK in GitHub Actions and replaces the
rolling `latest-build` release, together with a `latest.json` manifest carrying the
versionCode, APK url, sha256 and commit. That release is public, so the app needs no
credential to update:

1. On launch, and on **Update** in the title bar, the app fetches
   `releases/latest/download/latest.json`.
2. If the versionCode is newer, it downloads the APK from the url in the manifest.
3. It installs only what passes both checks: the sha256 from the manifest, and a
   comparison of the APK's signing certificate against the installed app's. The
   second one is the check that matters, since it is pinned on the device rather
   than fetched.

The manifest url is allowlisted to `https://github.com/maverock24/pi-mobile/releases/`,
so a tampered manifest cannot redirect the download elsewhere.

An optional second path exists if this repository ever goes private again: the bridge
carries `GET /api/release/latest.json` and `GET /api/release/apk` behind the device
token, fed by `pi-remote-release-sync`. The app does not use it today.

### The bridge needs a reload, not just the app

The app's half arrives with the APK. The bridge's half does not, and it takes two
steps in this order: `bridge/install.sh` copies `bridge/` into `~/.pi/agent`, and
then `/reload` loads that copy. A running pi keeps the copy it loaded at startup,
and `/reload` reads the installed file rather than the repo, so skipping the
install makes a reload look like it did nothing.

Reload **the pane that owns the bridge**, which is the one whose status line
shows `!!!PI-Remote connected!!!`. The lease is what names it:

    cat ~/.config/pi-remote/owner-*.json    # owner pid, and the cwd it runs in

Reloading any other pane does nothing, and that is a confusing way to spend an
evening: the app keeps talking to the process that still holds the address, so a
bridge fix looks broken while it sits on disk unloaded. Ownership moves, when
someone runs `/pi-remote` in another pane or the owner restarts, so check the
lease rather than trusting which pane you are sitting in.

## Building the app (optional)

Only needed if you are changing the Android app. A normal setup downloads the published
APK in the [Quickstart](#quickstart) instead and never touches any of this.

What a local build needs, stated honestly:

- **JDK 17 or newer.** CI uses Temurin 21. You do not install Gradle: the repo has the
  wrapper, `./gradlew`.
- **An Android SDK** (compileSdk 35). A clone has no `local.properties` (it is gitignored),
  so point Gradle at the SDK with `ANDROID_HOME`, or create `local.properties` with
  `sdk.dir=<path>`.
- **A release signing key, which a clone does not have.** `assembleRelease` signs only when
  four `ANDROID_*` environment variables are set; otherwise it produces an unsigned APK that
  will not install as an update. The real keystore lives only in CI secrets, so you cannot
  reproduce the published release locally. Use a debug build for local work.

```
./gradlew assembleDebug     # debug APK, needs an Android SDK
```

Version code and name come from `ANDROID_VERSION_CODE` / `ANDROID_VERSION_NAME`
(defaults `1` / `0.0.1`). CI uses the workflow run number, so installed builds
compare sensibly against `latest.json`.

A debug APK, with no secrets needed, is also built on demand by the
`.github/workflows/android-debug.yml` workflow, which is `workflow_dispatch` only.

## Releases and secrets

The workflow needs four repository secrets, all about the signing key:

| Secret | Contents |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0` of the release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Keep the original keystore file safe: Android only accepts updates signed with the
same key. Without these secrets the build fails on purpose rather than publishing
an unsigned APK.

## Layout

```
app/src/main/java/com/maverock24/pimobile/
├── data/SettingsStore.kt     bridge URL + token
├── net/PiRemoteClient.kt     REST calls and the SSE event stream
├── ui/ChatViewModel.kt       state, event handling, history
├── ui/Screens.kt             chat and settings screens
├── update/UpdateChecker.kt   manifest check, download, sha256, installer intent
└── voice/Dictation.kt        SpeechRecognizer wrapper
```

## Security notes

- The bridge binds to a single address, normally the Tailscale interface. Do not
  rebind it to `0.0.0.0`.
- One bearer token guards the whole API, and the token can send prompts to an
  agent that has shell access to the laptop. Treat it like an SSH key: do not put
  it in a chat app, and rotate it by deleting `~/.config/pi-remote/token` and
  restarting pi.
- Cleartext HTTP is allowed only for the configured tailnet address
  (`network_security_config.xml`), everything else is HTTPS-only.
- `REQUEST_INSTALL_PACKAGES` exists solely so the app can install its own updates
  from the sha256-verified download.
