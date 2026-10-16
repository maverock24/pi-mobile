# WLAN web access: stage 3 implementation (the core loop)

Branch `main`, based on `643dcc6` (stage 2). Stage 3 only, per design.md section 11:
send a prompt, stream the answer, answer a questionnaire in the browser. No audio,
no pins, files, talks or settings (those are now stage 4 per section 13).

## What was built

Relay, in `app/src/main/java/com/maverock24/pimobile/web/`:

- `BridgeUpstream.kt`: the upstream surface grew from one method to the routes the
  loop needs, all fixed to a literal path. `WebUpstream` now has `state`, `history`,
  `question`, `prompt`, `answer` and `events`; `BridgeUpstream` implements them with
  the phone's token and the `web:<session>` label. A second OkHttp client with
  `readTimeout(0)` is used only for the event stream; every request route keeps the
  finite timeout (F10). `WebUpstreamStream` is the live stream (status, `readLine`,
  `close`); closing cancels the OkHttp call.
- `WebRelayServer.kt`: five new routes plus the asset map.
  - `GET /api/history` (`proxyHistory`, `:220`) reduces the bridge history to
    `{messages:[{role,text,id}]}` and drops `sessionFile`, tool calls and tool results.
  - `GET /api/question` (`proxyQuestion`, `:246`).
  - `GET /api/events` (`proxyEvents`, `:277`) streams the bridge SSE, filtered and
    reduced (`filterEvent`, `:498`).
  - `POST /api/prompt` (`proxyPrompt`, `:318`) and `POST /api/answer` (`proxyAnswer`,
    `:350`) are mutations and go through `requireMutation` (`:401`), which is the
    shared session + `application/json` + `X-CSRF-Token` check. `POST /api/logout`
    now uses the same function, so the mutation defence is in one place.
  - The bundled page is served from a literal `ASSETS` map (`:645`), so the new
    modules are served without adding a file server.
  - Reductions: `reduceStreamMessage` (keeps role, text and a `toolCall` flag, drops
    tool arguments), `reduceStreamUpdate` (keeps `text_delta` only),
    `reduceStreamQuestion`, `reducePending`, `reduceHistory`, `messageText`. They
    mirror the app's `textOf` / `parsePendingQuestion` / `handleEvent` rules.

Front end, in `app/src/main/assets/web/`:

- `lib/relay.js`: the relay client. Holds the CSRF secret in memory only and adds the
  `X-CSRF-Token` header on every POST.
- `screens/chat.js`: the chat screen, built as a screen with an `element` / `start` /
  `stop` interface so the section 13 screens can be added the same way. It mirrors the
  phone's ChatViewModel state: `messages`, `liveAnswer`, `runCandidates`, `pending`,
  `busy`, `sessionId`. `EventSource("/api/events")` carries the cookie automatically
  (UX1/F2); on open it fetches state, history and the question; a 5 second state poll
  is the missed-question safety net (UX3). The question card renders each question's
  options as buttons numbered as on the phone, a free-text field for the first
  unanswered question, and a Cancel, and posts to `/api/answer` with the CSRF header.
- `app.js`: the shell. Holds the Relay and the approval flow, mounts the chat screen
  when a session exists, and remounts after a 401 (with a guard so a lost session is
  not reported twice).
- `index.html`, `style.css`: the shell and the desktop layout (capped measure,
  transcript, question card, composer).

## The questionnaire, end to end

1. A prompt is sent from the browser: `POST /api/prompt` with `{text, sessionId}`.
   The relay checks the CSRF header, then `BridgeUpstream.prompt` posts to the bridge
   `/api/prompt` with the phone's token and `X-Pi-Client: web:<session>`.
2. If the agent opens a question tool, the bridge emits `question` with the full
   pending object. The relay's `filterEvent` reduces it (`reducePending`) and
   re-emits `{"type":"question","data":{"pending":...}}`.
3. The browser's `handleEvent` calls `applyQuestion`, which parses the same shape the
   phone's `parsePendingQuestion` reads and renders the card. The selection behaves
   like the phone's widget: options are buttons numbered from 1 with their
   description, the first unanswered question gets the free-text field, and Cancel
   dismisses it.
4. A choice posts to `/api/answer` with `{questionId, value, custom, cancel,
   sessionId}` and the CSRF header. The relay forwards only those fields to the
   bridge `/api/answer`. This is the route the design's v1 allowlist held back
   (UX2) and the scope change in section 13 brings back, now that the options are
   rendered.
5. The widget in pi picks the answer up, the pending file clears, and the bridge's
   watcher emits `question` with `pending: null`, which closes the card. The browser
   reloads history a moment later, as the phone does.
6. `/api/question` is fetched on open/reconnect and when the state poll reports a
   pending question the browser does not have, so a question event missed while the
   stream was down is still answerable (UX3).

## The route allowlist

Proxied bridge routes after stage 3, all session-authenticated:

- `GET /api/state` (existing, reduced)
- `GET /api/history` (new, reduced)
- `GET /api/question` (new, reduced)
- `GET /api/events` (new, filtered)
- `POST /api/prompt` (new, CSRF)
- `POST /api/answer` (new, CSRF)

Still not proxied: `/api/pair`, `/api/release/*`, `/api/diagnostics`, `/api/search`,
`POST /api/abort`, the talk-link route, every token path. `/api/abort` is deliberately
out: the task asked for the prompt/stream/question loop, and abort is a separate
control that belongs with a stop button. This is a literal map, not a prefix match.

## Tests

`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline`
41 tests, 0 failures, 0 errors (stage 2 had 28).

New: `BridgeUpstreamTest` (4) checks the token and label go upstream on every route,
that the browser's cookie is never forwarded, that the stream is read line by line,
and that a redirect is not followed. `WebRelayServerTest` (27) adds, against a real
Ktor server with a fake upstream:

- `every core loop route needs a session` (401 without a cookie on history, question,
  events, prompt, answer).
- `the history proxy keeps text and drops paths and tool output`.
- `the question proxy reduces to the fields the card draws`.
- `a prompt without or with a wrong csrf token is refused` and
  `an answer without or with a wrong csrf token is refused` (403, and the bridge is
  never called).
- `a prompt with the csrf token reaches the bridge and does not leak the token` and
  `an answer with the csrf token reaches the bridge`.
- `no new route leaks the bridge token`: state, history, question, events, prompt and
  answer responses are each asserted not to contain the marker token the fake upstream
  returns, and every upstream label starts with `web:`.
- `the event stream forwards only the reduced message and question frames`: the fake
  emits `state`, `tool_execution_start`, message frames, a thinking delta, a question
  and `tool_execution_end`; the relayed body keeps message_start/update/end and the
  question and drops state, pid, tool execution, tool arguments, tool results and the
  thinking delta.
- `a bridge that is not up on the stream becomes a bad gateway`.
- `a live event stream holds the server open` (the idle watchdog must not stop the
  server while a browser stream is attached).

How the token claim was verified: the fake upstream returns a marker token in every
response a real bridge could carry one in (`state.token`, the history `sessionFile`,
a `secret` field on the pending question, and a prompt/answer body). Each relay
response is asserted not to contain it. Breaking `reduceState`, or forwarding every
event type, makes the test fail (mutation checks below), so the assertion is real.

Mutation checks, all restored byte-identical (`diff -q` clean, SHA-256 unchanged):

- Made `requireMutation`'s CSRF check always pass: `a prompt without or with a wrong
  csrf token is refused`, `an answer without or with a wrong csrf token is refused`
  and `a mutation without or with a wrong csrf token is refused` all failed.
- Changed `filterEvent`'s default from `return null` to forwarding `data`:
  `the event stream forwards only the reduced message and question frames` and
  `no new route leaks the bridge token` failed.
- Made `reduceState` return the raw value: `the proxy reduces the bridge state and
  never leaks the token` and `no new route leaks the bridge token` failed.

JS syntax checked with `node --check` on copies with an `.mjs` extension: all three
files parse.

## Gate

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin --offline
    BUILD SUCCESSFUL in 2s

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
    BUILD SUCCESSFUL in 4s

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug --offline
    BUILD SUCCESSFUL

## What only the human can confirm on the phone

- That a browser on the WLAN can send a prompt and see the answer stream in as it is
  generated. The SSE path (EventSource over the TLS front, chunked, flushed per frame)
  is the part no JVM test covers; the fake upstream ends the stream, it does not hold
  it open across a real run.
- That a prompt that opens a question shows the card in the browser and that tapping
  an option, typing an answer and Cancel each make pi continue. This is the bug the
  car mode shipped; it is written but not run against a real pi question tool.
- That the question card looks and behaves like the phone's widget on a real desktop
  browser, and that the transcript reads well on a wide screen.
- That the 5 second poll recovers a question that arrived while the stream was down.
- That the idle stop still fires after the browser goes, and does not fire while a
  stream is attached.

## Deviations and open items

- **The relay opens one upstream SSE per browser stream, not one shared relay-owned
  connection.** Design section 6 chose a relay-owned connection so the relay could run
  with no activity, but the on-demand lifecycle in section 10 means the server only
  runs while a browser is using it. Per-request is simpler and keeps the idle
  bookkeeping honest (a live browser stream counts as in flight). One shared
  connection remains the design's stated preference; it is not taken here.
- **The bridge's original idea of forwarding raw message frames was tightened.**
  Message frames are reduced to role, text and a `toolCall` flag, and thinking and
  tool-argument deltas are dropped, so F6 holds for message content and not only for
  `tool_execution_*`. Consequence: the phone marks an assistant message as toolCall
  from the raw content; the relay reproduces that flag so the browser's transcript
  matches the phone's.
- **Question traces are not in the reloaded history.** History is reduced to
  user/assistant text, so a reload does not show a question the person answered; the
  card and the optimistic trace cover it live. The phone shows a question trace from
  the tool result. This is stage 4 parity work.
- **`/api/abort` is not in the allowlist.** No stop control in this stage.
- **No markdown rendering.** Answers are plain text with preserved whitespace, as the
  design's first slice said; fenced code is not styled as a block yet.
- The source-address binding, the exact Host/Origin checks and the cookie stay as
  stage 2 built them; nothing in this stage weakens them.
- Ktor is still 3.0.2 and the TLS front is still JSSE `WebTlsFront`, as stage 1
  recorded.
