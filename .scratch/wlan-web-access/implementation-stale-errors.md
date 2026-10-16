# WLAN web access: stale error messages

Branch `main`, based on `c0e79ab`. The browser and its assets now render. The
human reports two error lines still on screen: the TLS handshake failure in the
web access section, and a handshake error under a Files entry called
"Microservices in Healthcare". The first is a real stale state in the app. The
second is not the app misattributing anything, and the investigation for it is
below.

## 1. The TLS handshake reason is never cleared while the listener serves

**Where it is stored.** The web access section has no error state of its own. It
renders `diagnosticsSummary(WebDiagnostics.state)` and turns the line red when
`ListenerDiagnostics.lastCloseReason` is not blank
(`app/src/main/java/com/maverock24/pimobile/ui/WebAccessSection.kt:129` and
`:131`). `lastCloseReason` is written only by `WebTlsFront.recordClose`
(`WebTlsFront.kt:248`), on a handshake that failed before a byte crossed
(`WebTlsFront.kt:225`) or a connection with no backend to forward to
(`WebTlsFront.kt:156`). Nothing wrote it back to blank.

`WebDiagnostics.reset()` runs once per server start
(`WebAccessService.kt:88`), so a *new* listener starts clean. The stale case is
inside one run: an early connection fails (the handshake was broken before the
`84c4c48` and `63989ac` fixes landed, or a real transient failure), and every
later connection succeeds. A success recorded nothing, so the one line kept
reporting the first failure as if it were happening now. That is exactly the
reported shape: the page loads, and the TLS failure is still shown.

**The fix.** A served connection clears the reason a previous one recorded.
`recordServed()` (`WebTlsFront.kt:242`) blanks `lastCloseReason`, and `pump`
calls it when the first application byte crosses in the handshake direction
(`WebTlsFront.kt:209`, `:213`). That byte is the handshake completing, which is
the moment the earlier failure stops being the current state. A later failure
sets the reason again, so the last event still wins. The failure *counts* are
left alone: `handshakeFailures` and `backendFailures` are a record of what
happened, not current state, and the summary line still reports them.

This is a change in the front, not the section, because the front is the only
writer and the rule holds for both consumers (the Settings line and
`/api/diagnostics`).

## 2. The Files entry error is that entry's own state, not a leak

**Where the text under a Files row comes from.** `FileRow.error` is set in one
place, `ChatViewModel.fileFrom` from the bridge payload
(`app/src/main/java/com/maverock24/pimobile/ui/ChatViewModel.kt:2018`,
`error = json.string("error")`), and shown under the row only when
`row.state == "failed"` (`FilesScreen.kt:302`). The browser does the same
(`assets/web/screens/files.js:158`). `/api/files` is a plain proxy of the
bridge route (`WebRelayServer.kt:153`, `BridgeUpstream.kt:106`); it adds no
diagnostics.

**The web access state never reaches that slot.** `WebDiagnostics` is read only
by `WebAccessSection` and by the relay's `/api/diagnostics`
(`WebRelayServer.kt:219`). It is not imported by the files screen, the view
model, or the bridge payload. So the same state is *not* shared between the web
section and the file list, and there is nothing to separate or scope. The
handshake string that appears under the row is not the app's TLS string at all.

**What the entry actually is.** On this machine the talks directory holds two
records with the same title, "Microservices in healthcare: architecture
decisions":

- `cc5793ff62ef72a4.json`: `state: "failed"`, `error:` the stored
  `aiohttp.client_exceptions.WSServerHandshakeError: 403, message='Invalid
  response status' ...` from the old edge-tts renderer.
- `93b76c59cce88f44.json`: `state: "ready"`, `error: null`, a 10.7 MB mp3, same
  `voice`.

Their `.txt` scripts are byte-identical (`md5 83f675993e1f7116ab11859cb6c5e303`
for both). The failed upload was rendered again under a new id 34 seconds
later and the second one succeeded. The bridge lists every valid talk record
(`bridge/lib/talks.ts:listTalks`), so both appear as Files rows under the same
title, and the failed row shows its own render error. The human reads the two
same-titled rows as one entry with a handshake error under it.

So this is not a web server handshake error, and it is not one component writing
into another's state. `WSServerHandshakeError` is a WebSocket handshake to the
TTS endpoint, and it belongs to that failed talk. The app renders each row's own
state correctly; section 16's distinction already holds. The real defect is a
stale failed talk record that a later success never removed, which is a data
lifecycle issue in the bridge, not the web UI, and is left as reported rather
than fixed here (see "Left undone").

## Tests

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin --offline
BUILD SUCCESSFUL
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
BUILD SUCCESSFUL
```

90 tests, 0 failures, 0 errors. Per class: BridgeUpstreamTest 5,
SelfSignedCertificateTest 15, WebAddressesTest 7, WebDiagnosticsTest 4,
WebGuardTest 7, WebRelayServerTest 45, WebTlsFrontTest 7 (was 6).

New on the plain JVM, no device:
`WebTlsFrontTest.a served connection clears the reason an earlier handshake
failed` (`WebTlsFrontTest.kt:179`). It opens a raw socket to the front and
writes bytes that are not a TLS ClientHello, waits for the failure to be
recorded, asserts it names the handshake, then makes a real `httpsGet`, asserts
`200`, and waits for `lastCloseReason` to go blank while the failure count stays.

Mutation check: removing the `recordServed()` call makes it fail with
`AssertionError at WebTlsFrontTest.kt:241`
(`timed out waiting until a served connection clears the stale reason`). The
file was restored and its `sha256` compared: clean.

## What only the human can confirm on the phone

- That after a first failed connection the Settings line stops showing the TLS
  failure once the browser reaches the page. On the build machine this is proven
  with a synthetic keystore; on the phone the key is an AndroidKeyStore key.
- That the entry they see is the failed talk record `cc5793ff62ef72a4`, not a
  ready one. Removing it on the laptop (`bridge` DELETE, or deleting the pair of
  files in `~/.pi/agent/talks`) should make the row and its error disappear.

## Left undone

- **The stale failed talk record is not fixed.** The correct fix is where the
  record lives, not in the UI: the bridge creates a new talk id per render and
  never removes a failed record when the same script later renders, so the old
  failure stays listed. That is a change to `bridge/lib/talks.ts` and the render
  path in `bridge/extensions/pi-remote.ts`, outside the app gate, and it needs a
  decision about how a re-render is recognised (identical script? title? a
  client-supplied key). Left as reported by instruction rather than guessed at.
- **A failed Files row has no way to be removed from the UI.** `FileRowCard`
  draws no control for `state == "failed"` (`FilesScreen.kt:326`), and the
  browser does the same (`files.js:158`), so the human cannot clear the stale
  entry from the app. Also left: it is a feature, not the reported bug.
- **What cannot be tested without a device:** completing a real handshake with an
  AndroidKeyStore key, and the exact ordering of a failed and a served connection
  on a phone under Doze. The clear-on-success rule is tested with the plain JVM
  keystore only.
