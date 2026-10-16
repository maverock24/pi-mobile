# WLAN web access: stage 2 implementation (auth and one route)

Branch `main`, based on `727226e` (stage 1). Stage 2 only, per design.md
section 11: the approval, the cookie, CSRF and Host checks, plus a proxy for one
read-only route. No prompts, no streaming, no questionnaire, no audio.

## What was built

- `app/src/main/java/com/maverock24/pimobile/web/WebApprovalGate.kt`: the
  approval step. `request()` (`:62`) registers one pending approval bound to the
  calling HTTP connection and suspends; `approve()` / `deny()` (`:90`, `:93`)
  complete it; `clear()` (`:96`) drops it on stop. Only one pending at a time,
  creation rate-limited per source address, about a two minute expiry. The user
  facing `pending` `StateFlow` is what the phone screen draws.
- `app/src/main/java/com/maverock24/pimobile/web/WebSessions.kt`: the session
  record and its lifetime. `create()` (`:43`) mints the id and the CSRF secret;
  `authenticate()` (`:61`) enforces the 30 minute idle window and the 4 hour
  absolute cap and touches the session; `cookieHeader()` (`:82`) is the cookie
  string; `secretsMatch()` (`:110`) is a constant-time compare.
- `app/src/main/java/com/maverock24/pimobile/web/WebGuard.kt`: the pure checks.
  `hostRefused()` (`:18`) requires exactly one `Host` equal to the bound
  authority; `originRefused()` (`:28`) and `secFetchSiteRefused()` (`:38`) are
  the origin controls; `targetRefused()` (`:50`) rejects absolute/authority form,
  backslashes and encoded separators (finding F5).
- `app/src/main/java/com/maverock24/pimobile/web/WebRelayServer.kt`: the routes.
  `GET /api/session` (`:84`), `POST /api/approve/request` (`:97`,
  `approveRequest` at `:138`), `GET /api/state` (`:99`, `proxyState` at `:170`),
  `POST /api/logout` (`:101`, `logout` at `:199`). Every request runs the guard
  first (`refuseIfUnsafe`, called from the interceptor at `:63`) and every
  response carries nosniff, CORP, a strict CSP and no CORS. `sessionOf()` (`:226`)
  resolves the cookie and checks the source address; `reduceState()` (`:278`)
  keeps only the state fields the UI uses.
- `app/src/main/java/com/maverock24/pimobile/web/BridgeUpstream.kt`: the one
  upstream call. `state()` (`:49`) sends the bridge token and the
  `web:<session>` label, with `followRedirects(false)` (`:44`) and
  `retryOnConnectionFailure(false)` (`:46`), the F10 fix.
- `app/src/main/java/com/maverock24/pimobile/web/WebPeers.kt` and the front
  (`WebTlsFront.kt:94`): the real WLAN peer, recorded against the loopback port
  the front dialled and read back as the request's remote port, so the phone can
  show who is asking even though the HTTP layer only sees 127.0.0.1.
- `WebAccessService.kt:90`: builds the server with the front's authority, its
  origin and a `BridgeUpstream` reading the app's own settings.
- `ui/WebAccessSection.kt:92`: the Allow / Deny prompt. The subtitle
  (`:45`) says a browser is waiting even when the section is collapsed.
- `assets/web/index.html`, `style.css`, `app.js`: the shell plus the session
  loop. The CSRF secret is a JavaScript variable, never in storage.

## The checks, and how each is proven

Tests, `:app:testDebugUnitTest --offline`: 28 tests, 0 failures, 0 errors.

- Host: `WebGuardTest`, and `WebRelayServerTest` drives a real Ktor server
  (`a wrong host header is refused`, `a missing host header is refused`,
  `a duplicate host header is refused`, `an absolute form target is refused`,
  `an encoded separator in the target is refused`).
- Cookie: `a request with no session cookie is refused` (401 on
  `/api/session` and `/api/state`), and `approval is answered on the holding
  connection with a strict cookie` asserts the `Set-Cookie` is `HttpOnly`,
  `SameSite=Strict`, `Secure`, `Path=/` and has no `Domain`.
- CSRF: `a mutation without or with a wrong csrf token is refused` (403, then
  the right token is 200 and the session is gone).
- Origin: `a foreign origin is refused`, `a cross site fetch is refused`.
- The proxy: `the proxy reduces the bridge state and never leaks the token`
  checks the reduced body keeps `sessionId` and `model` and drops `sessionFile`,
  `cwd`, `pid`, `boundPort`, `host` and the token marker; `a bridge that is not
  up becomes a bad gateway`.
- `BridgeUpstreamTest` proves the token and label go upstream and a 302 is not
  followed.
- The approval binding: `only one approval is pending at a time` and
  `a session is refused when it is used from a different address`.

Mutation checks, both restored byte-identical after:

- Disabled `hostRefused`: `a wrong host header is refused` failed.
- Made `secretsMatch` always true: `a mutation without or with a wrong csrf
  token is refused` failed.

## F1 and F2

**F2, the HIGH auth transport finding: closed.** The credential is now the
`HttpOnly`, `SameSite=Strict`, host-only, `Secure` cookie minted by the approval,
with a per-session CSRF token on mutations. `EventSource` and `<audio>` carry a
same-origin cookie with no header, so the transport the finding said could not
exist is in place. Stage 2 does not yet build the stream or the audio element, so
the two browser primitives are not exercised yet; what is proven here is the
credential and its checks, which is the part F2 was about.

**F1, the HIGH stored-XSS finding: not reintroduced, and the controls it asked
for are in place, but not fully closed until the file route exists.** Stage 2
serves no proxied file bytes at all: the only proxied route is `GET /api/state`,
reduced to non-path fields, so there is no way for agent-written HTML to render
on the session origin. The strict CSP (`script-src 'self'`, no inline), `nosniff`
and `Cross-Origin-Resource-Policy: same-origin` that F1's fix requires are set on
every response and tested. The audio-only file allowlist is stage 4 work and is
not built, so the finding is closed for the surface that exists, not for the
feature.

## Verified

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin --offline
    BUILD SUCCESSFUL in 5s

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --offline
    28 tests, 0 failures, 0 errors

    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:assembleDebug --offline
    BUILD SUCCESSFUL

## What only the human can confirm on the phone

- That a browser on the WLAN reaches the page, the page asks the phone to
  approve, and Allow lets it in. That the source address shown is the other
  computer's address (the `WebPeers` mapping through the TLS front is the one
  part of this that no JVM test covers).
- That tapping Allow fills the browser in and `Refresh bridge state` shows the
  bridge's live state, and that `Disconnect` ends the session.
- That a second browser, or a reload, behaves as the design says (a reload keeps
  the cookie and asks `/api/session`; a new browser creates a new pending
  request).
- That the idle stop still fires with the session routes in place.

## Deviations and open items

- One extra route, `POST /api/logout`, was added. Stage 2's only proxied route is
  read-only, so without a mutation there would be nothing for the CSRF control to
  guard. Logout is the smallest state change and matches the design's session
  lifecycle. It is the subject of the CSRF test.
- The source-address binding rejects a session used from a different address, as
  design section 5.3 says, with the DHCP caveat the design names. It is enforced
  in `sessionOf`.
- The peer address crosses the TLS front through a port-keyed registry rather
  than an injected header. Stage 1 suggested a header; a header would have meant
  parsing and rewriting the request head inside the byte pipe, so the registry is
  smaller and fails closed (an unknown port falls back to the loopback host).
- The design's file-type allowlist and the `Content-Disposition` rules belong to
  the audio stage and are not built.
- The typed one-time code fallback (design 5.3) is not built; stage 2 has only
  the phone approval.
- No session list or revoke screen on the phone yet; `Stop web access` drops
  every session, which is what exists.
- `/api/state` reduction drops `question`'s body and keeps `{pending}` only,
  because stage 2 does not render questions.
- Ktor is still 3.0.2 and the TLS front is still JSSE, as stage 1 recorded.
