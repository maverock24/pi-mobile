# WLAN web access: stage 5 implementation (audio playback)

Branch `main`, based on `c1187a3` (the hotspot fix). Stage 5 only, per design.md
section 11 as amended by sections 13 and 15: play a rendered talk in the browser,
with byte-range seeking, matching the phone's player. No app-side Compose screen
changed, so there is no new app UI style to match.

## What was built

Relay, in `app/src/main/java/com/maverock24/pimobile/web/`:

- `BridgeUpstream.kt:32`: `WebUpstreamFile`, a streamed file body (status,
  content type, content length, content range, `read`, `close`). `file()`
  (`:61` in the interface, `:110` on `BridgeUpstream`) GETs
  `/api/files/<encoded name>`, forwards the browser's `Range` when it sent one,
  and uses the long-lived `streamClient` so a slow read does not cut a talk short.
  The name is percent-encoded the way `PiRemoteClient.downloadFile` does it.
- `WebRelayServer.kt:141`: `GET /api/files/{name}` (`proxyFile`, `:315`), a
  single path segment so a name cannot walk out of the library.
  - The name must match the bridge's own file-name shape (`SAFE_FILE_NAME`,
    `:917`) and an audio extension (`audioContentType`, `:698`). Anything else is
    a 404 and the bridge is never asked, so a document can never be served on the
    session origin (F1).
  - The content type is chosen here from the extension, never copied from the
    bridge, so an audio route is only ever an audio document.
  - 200 and 206 are streamed by `respondFile` (`:364`) as an
    `OutgoingContent.WriteChannelContent` with `Accept-Ranges: bytes`, the
    upstream `Content-Range`, `Content-Disposition: inline` and the exact
    `Content-Length`. The body is written in 64 KiB chunks and flushed each
    chunk (`:388`), so a seek does not wait for the whole file. 416 is passed
    through with its `Content-Range` and an empty body; anything else is a 502.

Front end, in `app/src/main/assets/web/`:

- `screens/files.js`: an audio row now has a Play button. Pressing it holds one
  `<audio controls>` element in that row (`playRow`, `:82`; the element is
  re-attached on re-render, not recreated, so a poll does not stop playback). The
  native player carries the scrubber, so it sits in the talk's own row and is
  taken out when playback stops, which is the phone's layout
  (`ui/FilesScreen.kt:209`, `:311`). A video row says only audio plays, and a
  non-audio row still says it cannot be shown.
- `style.css`: `.file-actions` and `.file-audio`.
- `index.html`: the note now says audio plays and video and non-audio open on the
  phone.

## How the bytes reach the browser

The relay streams the bridge's range responses straight through. The browser's
`<audio>` issues a GET with a `Range`; the relay forwards that one header to the
bridge with the phone's token, and pipes the 206 back with the same
`Content-Range`. It does **not** download the file to the phone first, even though
`PiRemoteClient.downloadFile` can, and the phone's own player does exactly that.

**What streaming gives up, decided deliberately:**

- The phone is in the data path for the whole playback. Every byte crosses the
  TLS front twice (bridge to phone, phone to browser) and the phone pays the
  crypto and the copy for a 30 minute talk. Download-first would pay that once.
- Seeking is a new request, not a local seek. A drag on the scrubber aborts the
  open range and opens another; the bridge serves that range. This works because
  the range is forwarded, but it needs the bridge to stay reachable for the whole
  talk. Download-first would let a seek happen with the bridge gone.
- Nothing is stored, so a re-listen re-streams. A talk is not left on the phone,
  which is the storage the phone's download-first path spends.
- It is the smaller change: no download state, no `.part` files, no progress UI,
  and no second copy of the file to manage.

**On-demand shutdown (design section 10).** A file response is counted by the
same in-flight counter as every other request, so an open audio response keeps the
server up, and the response ending returns it to idle after the grace period. The
browser also holds one `EventSource` open while the page is open, which is in
flight for the same reason, so an idle stop never fires under an open tab. A seek
opens a fresh request inside the 90 second grace window, so the ordinary gap
between ranges does not end the session. Verified in
`WebRelayServerTest.a playing audio file holds the server open and goes idle when
it ends`.

## The range test

`WebRelayServerTest.a range request is forwarded and answered as a 206 with
content range` sends `Range: bytes=10-19` for a 100-byte fake file and asserts:
status 206, `Content-Range: bytes 10-19/100`, `Accept-Ranges: bytes`,
`Content-Length: 10`, `Content-Type: audio/mpeg`, the body is exactly bytes 10..19
of the fake, the upstream saw `bytes=10-19` and the name, and the label is
`web:<id>`. `a full file request is answered as a 200 with accept ranges` covers
the no-range case. `a large file streams in chunks rather than waiting for the
whole body` proves the flush: the fake produces one 64 KiB chunk then stalls for 5
seconds, and the test reads that chunk in under 3 seconds.

Mutation checks, all restored byte-identical (`diff -q` clean, SHA-256 unchanged):

- Dropped the `Content-Range` header: the range test failed.
- Removed the audio-name check and served audio/mpeg for any name:
  `the audio route serves audio only and never the bridge content type` failed
  (an `.html` name was served).
- Copied the upstream content type instead of the relay's:
  the same test failed (the token the fake put in the upstream type reached the
  response).
- Removed `channel.flush()`: `a large file streams in chunks` failed (the first
  chunk waited for the stalled body).
- Made `requireMutation`'s CSRF check always pass: the prompt, answer, logout and
  pin CSRF tests failed together, which is the shared path.

## How no token leaks, and the media-route URL check

The audio route is a GET, so it is session-authenticated by the cookie and needs
no CSRF token; mutations are unchanged and still require one, and the mutation
check above confirms the shared check is still the one in force.

A media route is the classic place a token ends up in a URL, so it was checked
three ways:

1. The browser builds the `src` as a plain path, not a token or a query
   (`files.js:95`): `el.src = "/api/files/" + encodeURIComponent(row.name ||
   row.id)`. Untracked assets checked in a grep over `app/src/main/assets/web/`
   find no `token=`, `csrf=`, `session=`, `?token` or `Authorization` anywhere.
2. `BridgeUpstreamTest.the file route sends the token and the range, never the
   browser cookie` runs a real HTTP exchange and asserts the token rides only in
   the `Authorization` header, the upstream path is `/api/files/t-1.mp3` and
   contains no token, and the browser's cookie is not forwarded.
3. `the audio route serves audio only and never the bridge content type` has the
   fake put the token in the upstream content type and asserts the response
   headers do not contain it and the type is `audio/mpeg`. The same fake token is
   fetched on the audio route in `no new route leaks the bridge token`.

## Gate

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin --offline
    BUILD SUCCESSFUL in 2s

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
    BUILD SUCCESSFUL in 5s
    63 tests, 0 failures, 0 errors (stage 4 and hotspot: 55)
    BridgeUpstreamTest 5, SelfSignedCertificateTest 3, WebAddressesTest 6,
    WebGuardTest 7, WebRelayServerTest 42.

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug --offline
    BUILD SUCCESSFUL in 2s

All eight web asset files parse with `node --check` on an `.mjs` copy.

## What only the human can confirm on the phone

- That a talk plays in the browser at all, over the TLS front with the session
  cookie on the `<audio>` request. No JVM test covers the browser's own media
  stack or the raw TLS front.
- **That seeking works on a real thirty minute talk**: drag the scrubber and hear
  it jump, rather than continuing from where it was. This is the range path end to
  end, and it is the one most likely to be subtly wrong.
- That the scrubber appears in the talk's own row while it plays and is gone when
  Stop is pressed, and that a Files refresh (the poll, or a talk event) does not
  interrupt playback because the element is re-attached rather than rebuilt.
- That a seek while the phone is idle keeps the session alive within the grace
  window, and that closing the tab stops the server after the grace period.
- That the browser will autoplay after the Play press, and that a browser which
  refuses still shows a usable native player.

## Deviations, uncertainties, and things left undone

- **The design section 13 scope now includes the browser player, and it is a
  streaming player, not the phone's download-then-play player.** Section 13 asks
  for the same interface; this is the same control in the same place with the same
  scrubber, but the byte path is different on purpose, for the reasons above. The
  phone's Download button has no browser equivalent, because there is nothing to
  store.
- **Only audio is served, not video.** Design 4.4 says the file route is audio
  types only, and stage 4 listed video rows, so a video row now says it opens on
  the phone. This is the design's stated loss, not a regression.
- **`/api/files/<name>` is a new proxied bridge route.** It is the route the
  design 4.4 already names and it is in the audio stage. It forwards only the
  `Range` header and returns only audio types; it is not a pass-through.
- **The write-flush is load-bearing and was found by test, not assumed.** Without
  `channel.flush()` per chunk, Ktor's engine held the small fake body until the
  writer finished, which would have made a real seek re-download everything before
  the range. The large-body test exists because of that.
- **`HEAD /api/files/<name>` is not implemented.** The design's allowlist names
  GET, the phone's client uses GET, and browsers use GET with `Range` for media.
  The bridge supports HEAD, so it can be added if a browser is observed asking.
- **Ktor is still 3.0.2 and the TLS front is still JSSE `WebTlsFront`**, as stages
  1-4 recorded.
- **The bytes still cross the phone twice and the phone has no download-free path
  for a talk.** If the double copy proves too slow on the device, the alternative
  is to stage the file into app storage through `PiRemoteClient.downloadFile` and
  serve it from there; that is a larger change with storage and eviction to own.
