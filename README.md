# Pi Remote

Android client for a pi coding session running on your own machine. Prompt pi from
your phone, dictate instead of typing, follow the agent's output live, and stop a
run that is going the wrong way.

The phone talks to a small HTTP + SSE bridge that the `pi-remote` extension runs
*inside* the pi process, so the app drives the same session you have in front of you
on the desktop. Everything travels over Tailscale: nothing is exposed to the
internet, and no port is opened on the LAN.

```
Pixel/phone ──Tailscale (WireGuard)──► laptop:192.0.2.1:8787
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

URLs in an answer are rendered as tappable links, because reading an answer on
the phone usually ends with going to check the source. Bold and inline code are
rendered too, and the whole answer is selectable for copying.

## Phone side

Install the APK from the latest release, open Settings, and paste:

- Bridge URL: `http://192.0.2.1:8787`
- Token: the value from `~/.config/pi-remote/token`

A 401 in the status line means the token is wrong. A timeout means Tailscale is
not connected on the phone or the bridge is not running.

### Dictation

Tap **Mic** and speak. Android's recogniser restarts itself after each pause, so
you can dictate a long prompt in several breaths; finished phrases are appended to
the composer and partial words show above it. Nothing is sent until you tap
**Send**, so you can fix a misheard word first.

## Pairing

Run `/pair` in the pi session that owns the bridge. It draws a QR in a widget above the
editor and in a PNG window, and mints a one-time code valid for two minutes:

```
pi-remote://pair?v=1&u=<base64url of http://<tailnet-ip>:8787>&c=<code>
```

Scan it with the phone camera. Android opens Pi Remote, the app posts the code to
`/api/pair`, and the reply carries the device token, stored exactly as the manual flow
stores it. Nothing extra is kept on the phone.

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
