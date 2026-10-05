# Pi Remote

Android client for a pi coding session running on your own machine. Prompt pi from
your phone, dictate instead of typing, follow the agent's output live, and stop a
run that is going the wrong way.

The phone talks to a small HTTP + SSE bridge that the `pi-remote` extension runs
*inside* the pi process, so the app drives the same session you have in front of you
on the desktop. Everything travels over Tailscale: nothing is exposed to the
internet, and no port is opened on the LAN.

```
Pixel/phone ──Tailscale (WireGuard)──► laptop:<laptop>.<tailnet>.ts.net:8787
                                          │  pi-remote extension (HTTP + SSE)
                                          ▼
                                      pi session (the live one, same process)
```

## Laptop side

1. Copy the extension into place (already installed on the reference machine):

   ```
   ~/.pi/agent/extensions/pi-remote.ts
   ```

2. Run `/reload` in the pane you want the phone to control, or restart pi. The
   extension prints `pi-remote listening on <ip>:8787`.

3. Read the token (it is generated on first start):

   ```
   cat ~/.config/pi-remote/token
   ```

   Endpoints, config knobs and the single-owner rule are documented in the file
   header. `/remote` shows status; `/remote claim` takes the bridge over from
   another pane; `/remote release` gives it up.

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

## Phone side

Install the APK from the latest release. The app ships **no** default bridge URL: the
laptop's address reaches the phone only through the pairing QR (below), so it is not
readable in the source or in any published APK.

Pairing fills both fields. Doing it by hand in Settings means:

- Bridge URL: `http://<laptop>.<tailnet>.ts.net:8787` (the MagicDNS name — a raw tailnet
  IP is refused, see the note on cleartext below)
- Token: the value from `~/.config/pi-remote/token`

A 401 in the status line means the token is wrong. A timeout means Tailscale is
not connected on the phone or the bridge is not running.

### Dictation

Tap **Mic** and speak. Android's recogniser restarts itself after each pause, so
you can dictate a long prompt in several breaths; finished phrases are appended to
the composer and partial words show above it. Nothing is sent until you tap
**Send**, so you can fix a misheard word first.

## Pairing

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
process that really bound the port claims ownership in `owner.json`, so a second pi session
cannot evict the one that is serving; run `/remote claim` in the session you want the phone
to talk to.

The token is not in the QR. A QR is a screenshot waiting to happen, and a deep link lands in
the camera history, so the code is what travels: single use, two minutes, minted per pairing.
The endpoint is the only unauthenticated route on the bridge; its guards are under F12 in
`docs/THREAT-MODEL.md`.

`Settings > Connection` still takes a base URL and token by hand. That is the fallback when
the camera will not open a custom scheme, and the only way on builds older than v0.0.27.

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

## Local builds

```
./gradlew assembleDebug     # debug APK, needs an Android SDK
```

Version code and name come from `ANDROID_VERSION_CODE` / `ANDROID_VERSION_NAME`
(defaults `1` / `0.0.1`). CI uses the workflow run number, so installed builds
compare sensibly against `latest.json`.

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
