# WLAN web access: stage 4 implementation (the remaining screens)

Branch `main`, based on `74c1cff` (stage 3). Stage 4 only, per design.md section
11 as amended by section 13: the remaining phone screens in the browser. That is
pins, files, talks and settings; the transcript and composer already exist from
stage 3 and are re-used. Audio playback, and the routes a media player needs, are
stage 5 and are not built here.

The change is browser-side plus the relay routes those screens need. No app-side
Compose screen changed, so there is no new app UI style to match.

## What was built

Relay, in `app/src/main/java/com/maverock24/pimobile/web/`:

- `BridgeUpstream.kt:37,83`: `WebUpstream.files()` and its `/api/files` call. The
  library route is the one the phone's own Files screen uses; talks arrive in it
  as audio rows, so `/api/talks` is not proxied (design 4.4 allows it only if the
  Files list shows talks, and the phone's list does not call it).
- `WebRelayServer.kt:116`: `GET /api/files` (`proxyFiles`, `:255`) reduces each
  row to the fields the phone draws (`FILE_FIELDS`, `:804`) and answers 502 when
  the bridge is down, so a failed hop is a different thing from an empty library.
- `WebRelayServer.kt:126-128`: `GET /api/pins`, `POST /api/pins/rename`,
  `POST /api/pins/delete` (`listPins` `:278`, `renamePin` `:296`, `deletePin`
  `:314`). These are relay-local, like `/api/session`, because pins are the
  phone's own data and not a bridge route. The two POSTs are mutations and go
  through `requireMutation`, so they need the session, `application/json` and the
  CSRF header exactly like `/api/prompt`.
- `WebRelayServer.kt:589`: the event filter now forwards a reduced `talk` row
  (`reduceTalk`, `:678`) and `talk_removed` (`reduceTalkRemoved`, `:685`), so the
  Files list refreshes when a render advances or a talk goes. `talk` is reduced to
  the same fields `/api/files` exposes and carries no path or pid.
- `WebPins.kt`: the `WebPin` record and the `WebPins` interface (relay stays
  plain-JVM testable), with an `Empty` default.
- `StoredPins.kt:15`: the `WebPins` surface over the phone's own `PinsStore`, so a
  pin renamed or deleted from the browser is the phone's pin. Wired in
  `WebAccessService.kt:101`.
- `WebAccessService.kt:101`: the relay is handed `StoredPins(PinsStore(...))`.

Front end, in `app/src/main/assets/web/`:

- `lib/events.js`: one shared `EventSource` the shell owns, so a screen switch
  does not drop the stream and a live stream holds the server open while the
  browser is open. Screens subscribe rather than each opening their own.
- `lib/theme.js`: appearance (dark/light/system) and the four palettes, the same
  names and hex values as `ui/AnswerStyle.kt`. Browser-local (localStorage), see
  the deviation below.
- `screens/files.js`: the library list, one card per row with the kind, the render
  state, the duration (marked as an estimate), the size, the voice and, for a
  failed render, the error. A rendering file shows an indeterminate bar and no
  control; a ready audio or video row says playback is the next stage; any other
  kind says it cannot be shown in the browser, because the design refuses
  non-audio bytes on the session origin. Loading, empty and error read differently.
- `screens/pins.js`: the pin list and the one-pin detail the phone shows, with
  edit title, a two-tap delete and Send prompt (which re-uses `/api/prompt` and
  then opens the chat).
- `screens/settings.js`: the phone's sections, kept to what a browser session can
  act on. Session identity applies; appearance applies as the browser's own copy;
  the server's start/stop and the fingerprint comparison are said to live on the
  phone; pairing, app updates, troubleshooting, search, commands, dictation and
  car mode are named as phone-only rather than drawn as dead controls.
- `app.js`: the shell now builds a tab row (Chat, Files, Pins, Settings), owns the
  event bus and the theme, and mounts one screen at a time. The approval and
  disconnect flows are unchanged.
- `screens/chat.js`: changed only to subscribe to the shared bus instead of owning
  its own `EventSource`. The screen's state and rendering are untouched.
- `index.html`, `style.css`: the nav, the theme variables and the new screens'
  styles. The palettes are the phone's own.

## The route allowlist after stage 4

Proxied bridge routes (all session-authenticated): `GET /api/state`,
`GET /api/history`, `GET /api/question`, `GET /api/events`, `GET /api/files`,
`POST /api/prompt`, `POST /api/answer`.

Relay-local routes (served by the app): `GET /api/session`,
`POST /api/approve/request`, `POST /api/logout`, `GET /api/pins`,
`POST /api/pins/rename`, `POST /api/pins/delete`.

Still not proxied: `/api/pair`, `/api/release/*`, `/api/diagnostics`,
`/api/search`, `/api/commands`, `POST /api/abort`, `/api/talks*`,
`/api/files/<name>`, the talk-link route and every token path. `/api/files/<name>`
and `/api/talks` are stage 5 audio work.

## Tests and how the security claim was checked

`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline`
46 tests, 0 failures, 0 errors (stage 3 had 41). Per class: BridgeUpstreamTest 4,
SelfSignedCertificateTest 3, WebGuardTest 7, WebRelayServerTest 32.

New in `WebRelayServerTest`:

- `every files and pins route needs a session`: 401 without a cookie on
  `/api/files`, `/api/pins` and both pins mutations.
- `the files proxy keeps the row fields and drops the private ones`: keeps the
  title, the `rendering` state, a failed row's error and the duration; drops
  `ext` and `createdAt`.
- `a bridge that is down on files is a bad gateway, not an empty library`: 502.
- `the pins list comes from the phone store`: the fake store's pin is served.
- `a pin rename and delete need the csrf token and change the phone store`: 403
  without the token and the store is unchanged; 200 with it and the store changes.
- `the event stream forwards only the reduced message and question frames`: now
  also asserts the `talk` and `talk_removed` frames are forwarded and the talk's
  `createdAt` is dropped.
- `no new route leaks the bridge token`: the responses of state, history, files,
  pins, question, events, prompt and answer are each asserted not to contain the
  marker token the fake upstream returns, and every upstream label starts `web:`.

**How the token claim was verified.** The fake upstream returns a marker token in
every place a real bridge could carry one in: `state.token`, the history
`sessionFile`, the question `secret`, the prompt and answer bodies, and now the
files `ext` and `createdAt` and the talk frame's `createdAt`. Each relay response
is asserted not to contain it. Faking a leak confirms the assertion is real
(mutation checks below). The browser is never handed the bridge token on any
route: the token lives only in `BridgeUpstream.requestBuilder` and no response
path reads it. The pins response carries the phone's pins, not the bridge token.

Mutation checks, all restored byte-identical (`diff -q` clean; the first also
restored to the same SHA-256):

- Made `requireMutation`'s CSRF check always pass: `a pin rename and delete need
  the csrf token and change the phone store`, `an answer without or with a wrong
  csrf token is refused`, `a mutation without or with a wrong csrf token is
  refused` and `a prompt without or with a wrong csrf token is refused` all
  failed.
- Made `reduceFiles` return the raw row: `the files proxy keeps the row fields
  and drops the private ones` and `no new route leaks the bridge token` failed
  (the token in `ext`/`createdAt` reached the browser).
- Made `reduceTalk` return the raw data: `the event stream forwards only the
  reduced message and question frames` and `no new route leaks the bridge token`
  failed (the talk's `createdAt` reached the browser).

JS syntax: each of the eight asset files parsed with `node --check` on an `.mjs`
copy (`OK` for all).

## Gate

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin --offline
    BUILD SUCCESSFUL in 3s

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
    BUILD SUCCESSFUL in 4s

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug --offline
    BUILD SUCCESSFUL in 6s

## What only the human can confirm on the phone

- That the Files tab shows the bridge's library with the same rows the phone
  shows, including a talk still rendering and a failed render's error.
- That a talk render advancing or a talk going updates the list live, over the
  shared stream.
- That the Pins tab shows the phone's pins, that editing a title and deleting a
  pin change the phone's own pins, and that Send prompt moves to the chat and the
  answer streams in.
- That the tabs, the appearance control and the four themes look like the phone,
  and that a theme choice survives a reload.
- That the shared `EventSource` keeps the server alive while the browser is open
  across a screen switch, and the idle stop still fires after the browser goes.

## Deviations and open items

- **Themes are the browser's own copy, not the phone's choice.** The phone keeps
  its appearance and theme in `SettingsStore` and the browser cannot read that
  file (it is not on any API), so following the phone's choice would need a new
  relay setting route. The design's section 13 asks to say so rather than guess,
  so the Settings screen says it and the browser keeps its own choice in
  localStorage. This is the one place where "the same interface" is the same
  controls and the same palettes rather than the same stored value.
- **The pins routes are a new relay-local surface.** The design's relay-local list
  named only the session routes. Pins are phone-local and section 13 requires the
  screen, so this is the only way to serve them; the same session and CSRF checks
  apply. This should be read as an extension of the design, not a bridge route.
- **The `talk` event is reduced to the file-row fields, a superset of the design's
  "id, state, title".** The phone upserts the whole row from the talk event, and
  these are the same non-sensitive fields `/api/files` already exposes, so the row
  stays correct on a render advancing. No path, pid or token is among them.
- **The chat screen no longer owns its `EventSource`.** It subscribes to the
  shell's shared stream so a screen switch does not drop it. The screen's state,
  event handling and rendering are unchanged; only `start`/`stop` changed.
- **Creating a pin from the browser is not built.** The phone's Pins screen does
  not create pins either (that happens from an answer elsewhere), so this keeps
  the pin screen matching the phone. Rename, delete and send prompt are the
  actions the Pins screen itself offers.
- **Non-audio files cannot be opened in the browser**, by the design's F1 rule that
  refuses non-audio bytes on the session origin. The row says so instead of
  offering an Open that does nothing. This is the design's stated loss, not a
  regression.
- Ktor is still 3.0.2 and the TLS front is still JSSE `WebTlsFront`, as stages 1-3
  recorded.
