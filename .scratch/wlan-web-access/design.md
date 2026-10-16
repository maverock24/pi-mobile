# WLAN web access for Pi Remote: design

Status: design only, revised against `security-review.md` and `ux-review.md`. Nothing
here is implemented, and nothing is committed.
Scope: the Android app in this repo. The bridge, the tailnet and the host firewall do
not change.
Worktree: `/home/maverock24/github/pi-mobile-keys`, branch `main`, clean apart from
this `.scratch/wlan-web-access/` directory.

This document follows `.scratch/wlan-web-access/analysis.md`. Read that first for the
three architectures and the full fact-check of what exists today. Section 11 maps every
finding in `security-review.md` and `ux-review.md` to what changed. Section 7 lists what
still needs a human decision.

## 1. The human's answer, and what it settles

The human was asked which of the three readings the feature is. The answer, verbatim:

> the brige and tailnet are not effected. i just want to be able to use the app via a
> web server that the app i running and that i can connect to via wlan from another
> computer

That picks architecture C (the phone serves the page and relays), with two
clarifications. The network is the ordinary WLAN the phone already joins, not the
phone's own hotspot. And the bridge and the tailnet are off limits: the phone talks to
the bridge exactly as it does today, using the connection and the token it already has.

The architecture is therefore settled. Against the question list in analysis.md
section 6:

- Q1 shape: settled. C, same WLAN. The phone is the network hub only in the sense that
  the browser's requests land on it; it does not hand out the network.
- Q2 which machine serves the page: settled. The Android app.
- Q3 the phone's role: settled. Relay. The browser talks to the phone, the phone talks
  to the bridge over its existing authenticated link.
- Q4 when the browser's network cannot reach the bridge: settled as a consequence. The
  browser only needs to reach the phone. If the phone cannot reach the bridge, the web
  UI shows that and refuses; no public path is opened, and the phone does not fall back
  to anything else.
- Q5 who is allowed in and with what credential: settled in section 5. The browser
  holds a short-lived, session-bound cookie minted by an approval on the phone. The
  bridge token never leaves the phone.
- Full parity versus a first slice: settled in section 4.2 as a first slice, now
  including history catch-up and a question blocking line.
- Cleartext on the WLAN versus TLS: settled in section 5 as TLS by default with a
  fingerprint shown on the phone. Cleartext is a documented downgrade for a trusted
  WLAN only.
- Whether the browser may mutate the session or read only: settled as mutate, with a
  per-session CSRF token. A read-only UI is not by itself a control, because the relay
  holds a token that can mutate.

Two questions the earlier analysis did not ask are now in play and are listed in
section 7: the server library, and whether the relay keeps its own bridge connection
or shares one with the app UI.

## 2. The architecture

```
 another computer on the same WLAN                 the phone
 ┌──────────────────────────────┐     HTTPS       ┌──────────────────────────────────┐
 │ browser: phone-hosted SPA    │ ──────────────► │ Pi Remote app                    │
 │  · sends prompts             │  (WLAN only)    │  · web relay service (new)       │
 │  · reads streamed answers    │                 │     TLS server on WLAN address   │
 │  · plays audio from Files    │ ◄────────────── │     session auth (new)           │
 └──────────────────────────────┘      SSE        │  · bridge client (existing)      │
                                                  │     token from Keystore          │
                                                  └───────────────┬──────────────────┘
                                                                  │ WireGuard, unchanged
                                                                  ▼
                                                  laptop bridge :8787 (unchanged)
```

The phone is the only authenticated client of the bridge, as today. The browser never
sees the bridge token and never talks to the bridge or the laptop. The bridge's bind,
its routes, its token model, the tailnet, and the host firewall are untouched.

This is deliberately the shape the existing code already supports. The bridge keeps a
`Set` of SSE clients (`client` set at `bridge/extensions/pi-remote.ts:432`, fed by
`emit` at `:564`) and the pane footer already reads "N phones connected"
(`:586`). One more client from the phone is not a new connection model. Byte-range
serving already exists for the audio routes (`serveFile` at
`bridge/lib/talks.ts:341`, 206 responses at `:393`), so a browser `<audio>` element can
seek. The bridge sets `X-Pi-Client` from the request header
(`clientLabel`, `:965`), so relay-originated prompts can be labelled in the audit log
without touching the bridge.

## 3. Serving HTTP from the app

### 3.1 What to serve it with

The platform gives a raw `java.net.ServerSocket` and nothing above it. There is no
HTTP layer and no static-file serving. `com.sun.net.httpserver.HttpServer` is a JDK
class and is not present on Android; using it needs jars pulled in by hand and is not
supported (Stack Overflow "Is it possible to use com.sun.net.httpserver package in
android program?", and the javathinking write-up both say it cannot be used, which
matches the class not being in the Android boot classpath). So a dependency is
required.

The realistic choices:

- **Ktor server, CIO engine** (`io.ktor:ktor-server-cio`, `ktor-server-core`,
  optionally `ktor-server-sse`). Kotlin-first and coroutine-based, which matches this
  app. JetBrains actively tests Ktor against Android's D8 and ProGuard (Ktor 3.2.2
  release note). Current release is 3.6.0 (September 2026). It handles HTTP correctly,
  including range requests and long-lived streaming responses. Cost: a larger set of
  server jars in the APK.
- **NanoHTTPD** (`org.nanohttpd:nanohttpd`). Tiny, pure Java, BSD-3. But the latest
  release is 2.3.1, published 2016-08-12 (Maven Central listing). Ten years without a
  release, 162 open issues. It is a stable, frozen, unmaintained library. Smaller than
  Ktor, but a security-relevant HTTP parser that no longer receives fixes.
- **Hand-rolled HTTP/1.1 over `ServerSocket`.** The endpoint count is small, but a
  correct server needs keep-alive, chunked encoding, range responses, header limits,
  and careful request parsing. F1 in `docs/THREAT-MODEL.md` is exactly the lesson that
  a hand-written local service is where cross-site and parsing bugs live. Rejected.

**Recommendation:** Ktor server with the CIO engine. The app already ships large
dependencies (OkHttp, media3), the language match is exact, and it is the only option
that is maintained. The size cost is real and is an open decision (section 7).

Confirmed by inspection: the app has no server code today. `app/src/main/java` holds
only Compose UI, the OkHttp client, keystore and prefs helpers, and the media3 player
(the full file list is 26 files, none of them a listener), and
`app/build.gradle.kts` lists Compose, OkHttp, media3 and ZXing as the only
non-AndroidX dependencies.

### 3.2 Binding, address, and port

The server binds the phone's **Wi-Fi IPv4 address specifically**, not `0.0.0.0`. The
app's README security notes and `docs/THREAT-MODEL.md` section 5 both say the bridge
must not bind `0.0.0.0`, and the same reasoning applies here: `0.0.0.0` on the phone
would also expose the relay on the Tailscale interface and on cellular, which is
surface nobody asked for. Binding one address keeps the relay reachable only from the
WLAN, which is the requirement.

Getting the address is a small piece of work with a modern-API trap. `WifiInfo`
accessors are deprecated from API 31; the supported route is `ConnectivityManager` plus
`LinkProperties` (Android's own deprecation note, and the LinkProperties reference).
With a VPN active, the "active" network may be Tailscale, so the code must ask for the
network whose capabilities include `TRANSPORT_WIFI`, then read its link addresses. The
IPv4 is what is shown to the human and bound; the phone's LAN usually also has an IPv6
address, which is ignored for the first version.

When WLAN is off, or the address changes, the address must be re-read. The design is a
`ConnectivityManager.NetworkCallback` registered for the Wi-Fi transport:

- `onLinkPropertiesChanged`: if the IPv4 changed, stop the Ktor engine and start it
  again on the new address, then update the URL shown in the app.
- `onLost`: stop the engine and show "web access paused, not on WLAN".

Port: a fixed high port is simplest for the human, who has to type the URL on the other
computer. Fixed port plus fail-loudly if it is taken is recommended, rather than an
ephemeral port that changes each time. The exact number is an open decision.

The app shows the URL and a QR of it beside the toggle, so the person at the computer can
scan it rather than read it off the phone. A Wi-Fi address change still strands an open
tab, because a new origin cannot be redirected to (finding UX4, section 11).

### 3.3 The listener on an untrusted network

The relay is a network service on a network the app does not control. That is the exact
shape of F1 (`docs/THREAT-MODEL.md`), where a local helper that accepted cross-site
requests fed a root-capable agent. The relay is worse in kind, because it is reachable
by any device on the WLAN and it holds a token that can drive the agent. The controls
below are requirements, not options. They are the F1 fix, restated for a listener that
uses a cookie as its credential rather than a bearer header (section 5).

- No CORS headers at all. The web UI is same-origin, so no CORS grant is needed, and
  granting one would let any page in the browser read relay responses.
- The session cookie is `HttpOnly`, `SameSite=Strict` and host-only, so a page on any
  other origin never attaches it and a scripting bug cannot read it. See section 5.
- Every mutating route requires `Content-Type: application/json` and a matching
  per-session `X-CSRF-Token` header. A cross-site form or simple request cannot set
  either.
- Validate the `Host` header as an exact match against the bound WLAN address and port.
  Reject a missing or duplicate `Host`, absolute-form and authority-form targets, and
  encoded `/`, `\`, `.` and `..` separators. This is the DNS-rebinding control.
- Check `Origin` and `Sec-Fetch-Site` when present; reject any value that is not the
  relay's own origin.
- Set `X-Content-Type-Options: nosniff`, `Cross-Origin-Resource-Policy: same-origin`,
  and a strict `Content-Security-Policy` on every response.
- A literal allowlist of method plus canonical path (section 4.4), so the browser
  cannot reach every bridge route.

### 3.4 The foreground service

A server socket that must stay open while the screen is off needs a service, and since
Android 8, a background service is not enough: it must be a foreground service with a
persistent notification, or the process will be killed and the socket with it. Since
Android 12, a foreground service cannot be started from the background except in listed
cases, so it must be started while an activity is visible; enabling web access from the
Settings screen satisfies this (Android "Restrictions on starting a foreground service
from the background", and "Foreground service types", both fetched 2026).

Since Android 14, every foreground service declares a type and holds the matching
permission. The app currently has only `mediaPlayback` for `TalkPlaybackService`
(`app/src/main/AndroidManifest.xml`). A web relay is not media playback; the two
plausible types are:

- `connectedDevice` with `FOREGROUND_SERVICE_CONNECTED_DEVICE`. Its description is
  "interactions with external devices that require a ... network connection", and its
  prerequisites include declaring `CHANGE_NETWORK_STATE` or `CHANGE_WIFI_STATE`
  (normal permissions, no prompt). Good fit.
- `dataSync` with `FOREGROUND_SERVICE_DATA_SYNC`. No runtime prerequisites, but its
  description is data transfer rather than serving a connection.

`specialUse` also exists but requires a manifest property explaining the use and is a
last resort. Recommendation: `connectedDevice`, with `CHANGE_WIFI_STATE` declared. This
is an open decision because it adds a permission to the manifest.

## 4. The web front end

### 4.1 How it is shipped

Assets inside the APK, served by the relay from `app/src/main/assets/web/`. There is no
web front end in the repo today (analysis.md section 4 checked this; the only HTML in
the bridge is a `text/html` content type for file previews), so all of it is new.

Two ways to get the bundle into assets:

- **Hand-written static files, no build step.** `index.html`, a small `app.js`, a
  `style.css`, committed under `assets/web/`. The relay serves them and the phone's
  address as the origin. This adds no Node toolchain to the Android build and is the
  right size for the first slice.
- **A real SPA build** (Svelte/Vite etc.) with a Gradle task that runs the JS build and
  copies `dist/` into assets. The human has a precedent for this
  (`/home/maverock24/github/mobile-media-app`, SvelteKit with `adapter-static`), but it
  adds Node to CI and to every release. Not needed for the first slice.

Recommendation: hand-written static files for v1, revisit if the UI grows.

### 4.2 First slice

Deliberately smaller than the Compose UI, but complete enough that a run started in the
browser can be finished or read there.

1. Send a prompt. A textarea and a send button, posting to the relay, which proxies
   `POST /api/prompt`.
2. See the answer stream. `EventSource` on a relay SSE endpoint that forwards only the
   bridge's `message_start` / `message_update` / `message_end` frames (the bridge's event
   shapes are at `bridge/extensions/pi-remote.ts:2153-2173`). `EventSource` carries the
   session cookie automatically, which is why the cookie is the credential (section 5).
3. Survive a reconnect. On every SSE open and reconnect the page fetches the latest
   history and re-renders the newest assistant answer, and it keeps a slow poll (5
   seconds) as the app does (`ui/ChatViewModel.kt:881-887`). A reload or a WLAN blip
   shows a reconnecting state, not a blank page.
4. Do not strand a run on a question. `pi` blocks until a question is answered
   (`ui/Questions.kt`). The relay maps the bridge's `question` frame to a reduced
   `{pending, title}` signal, with no option values, and the page shows a blocking line
   that names the question and says to answer it on the phone. The browser is not given
   `POST /api/answer` in v1, so it cannot answer a question it does not render.
5. Reach the audio in the Files list. List `GET /api/files`, filtered to audio rows, and
   an `<audio>` element pointed at the relay's audio route, which forwards the bridge's
   range responses so seeking works. Non-audio rows are shown as non-interactive with a
   reason, or not listed at all. A talk still rendering shows a rendering state and is
   not tappable; the list refreshes on the `talk` and `talk_removed` events. Browser
   playback is tab-bound and stops when the tab closes or the WLAN drops, which is a
   stated limit, not a bug: the browser has no foreground media service like
   `TalkPlaybackService`.
6. Say what is missing. The page states that search, pins, commands, dictation, car mode
   and theming are not in the browser, rather than drawing an empty control.
7. Distinguish the failures. The page separates browser cannot reach the phone, phone up
   but bridge down, and bridge up but library empty, reuses the `Diagnostics` wording so
   the failed hop is named, and shows a connection indicator. It never says "network"
   unless the network is the failed hop (`net/Diagnostics.kt:99-105`).
8. Waiting, timeout and deny. Because joining is a phone step by design, the page shows a
   named waiting state ("waiting for approval, check the phone"), a timeout message, and
   a deny message, so the person at the computer knows what happened instead of facing a
   dead page.
9. A desktop layout, not a phone column. Cap the reading measure, render at least fenced
   code in a monospace block, and use the width for the file list or the session identity
   beside the transcript. Full markdown (headings, tables, diffs) is deferred because
   rendering agent text as HTML needs a sanitiser and adds an XSS surface, so it is a
   known loss rather than a v1 goal; see finding UX7 in section 11.

Not in the first slice: question option widgets, search, pins, commands, dictation, car
mode, the app's theming, and the update flow. Each can follow if wanted.

### 4.3 What the front end costs

This is the largest single piece of work in the feature, and it is all new. It is also a
second implementation of logic the app already has: the app's event handling
(`ChatViewModel.handleEvent`, and the reconnect and poll logic at
`app/src/main/java/com/maverock24/pimobile/ui/ChatViewModel.kt:772-907`) is the only
existing client. The browser version is a fresh, smaller copy. A service worker and
offline caching are left out of v1 as scope, not because they are impossible: with TLS
default (section 5) the relay origin is a secure context, so a service worker could be
added later. Offline caching is not wanted in v1 because the phone is the source of
everything and a cached answer could mask a stale bridge.

### 4.4 The relay surface

The relay is a reverse proxy in front of a literal allowlist of bridge routes, using a
dedicated upstream OkHttp client and the phone's existing token. A transparent "forward
anything" proxy is rejected: the bridge token gives full control (F2), and the browser
must not reach routes the UI does not use.

The allowlist is a table keyed by method and canonical path, not a prefix match. The
relay canonicalises the request target once with the same WHATWG URL parser the bridge
uses (`new URL(req.url || "/", "http://localhost")`,
`bridge/extensions/pi-remote.ts:1195`), then matches that canonical string and forwards
that same string. It rejects encoded `/`, `\`, `.` and `..` segments, absolute-form and
authority-form targets, and a missing or duplicate `Host`. The security review showed
that `%2e%2e` and `..\..` resolve to `/api/pair` under that parser (security F5), so the
raw target must never be forwarded.

Relay-local routes (served by the app, not proxied):

- `GET /api/session` returns `{ok, csrf}` when the session cookie is valid, 401
  otherwise.
- `POST /api/approve/request` creates or reports a pending approval (section 5).

Proxied bridge routes, all authenticated by the session cookie:

- `POST /api/prompt`, `POST /api/abort`
- `GET /api/state` (reduced to the fields the UI uses)
- `GET /api/history` (with a bounded `limit`)
- `GET /api/files`, `GET /api/files/<name>` (audio types only)
- `GET /api/events` (SSE, filtered by event type)
- `GET /api/talks` only if the Files list shows talks

Never proxied: `/api/pair`, `/api/release/*`, `/api/diagnostics`, `/api/search`,
`POST /api/answer`, the talk-link creation route, and any token or rotation path. None of
those belong to a WLAN browser. `POST /api/answer` is out in v1 because the browser does
not render question options (UX2); rendering them later brings the route back.

Two response rules matter more than the route list.

**File bytes never enter the UI's origin as a document.** The bridge serves any library
file the agent wrote, and for `.html`, `.htm` and `.xml` it serves `text/html` or
`application/xml` (`bridge/lib/files.ts:70-121`). A file named `evil.html` served on the
relay's origin would run script beside the session (security F1). So the relay's file
route serves only a strict audio extension allowlist, always with an audio content type,
`X-Content-Type-Options: nosniff`, and `Content-Disposition: inline`. Every other file
type is refused on that route in v1, and the Files list shows it as not available. The UI
shell also carries a strict `Content-Security-Policy` (`default-src 'self'; script-src
'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`) with no inline
script.

**The event stream is filtered, not forwarded raw.** The bridge emits every event type on
one connection, including `state` with absolute paths, pid and bind address, and
`tool_execution_start` / `tool_execution_end` with the agent's tool arguments and results
(`bridge/extensions/pi-remote.ts:601-625`, `:2167-2177`). The relay forwards only the
message frames the UI renders, plus a reduced `talk`/`talk_removed` summary (id, state,
title) for the Files refresh, and a reduced `question` frame (pending, title). It never
forwards `state` wholesale, `tool_execution_*`, or any raw `args` or `result` (security
F6).

### 4.5 Upstream client and headers

The relay uses its own `OkHttpClient`, not the shared one in `net/PiRemoteClient.kt`. The
shared client follows redirects by default and has `retryOnConnectionFailure(true)` with
`readTimeout(0)` (`PiRemoteClient.kt:33-39`), which would let a redirect carry the bridge
token to another host and would replay a `POST /api/prompt` (security F10). The relay's
client sets `followRedirects(false)`, `retryOnConnectionFailure(false)`, a finite read
timeout for non-stream calls, and a separate long-lived call for the SSE stream.

On each upstream call the relay builds a fixed header set: method, canonical path and
query, `Authorization: Bearer <phone token>` from the app's store, `X-Pi-Client:
web:<short-session-id>` (never a browser header), and only `Content-Type`, `Range` and
`Accept` from the browser. It never forwards the browser's `Cookie` or `Authorization`,
and it strips hop-by-hop headers (security F7, F8).

On the way back it copies only status, `Content-Type` (after the file rule above),
`Content-Length`, `Content-Range` and `Accept-Ranges`, and adds `nosniff`, the CSP, and
`Cross-Origin-Resource-Policy: same-origin`.

## 5. Authentication: one mechanism

### 5.1 The problem, stated plainly

Anything on the WLAN can reach a listener on the phone. The bridge token is effectively a
root credential because the agent runs as root (F2). The human's standing rule is "i
would not like to weaken defence" (`docs/PAIRING-REVIEW.md`). The browser needs a
credential the relay can check, that a hostile page cannot use, and that survives the
browser's own limits.

The two reviews collide here. The UX review found that `EventSource` takes only a URL,
and an `<audio src>` element takes only a URL, so neither can carry a body-issued Bearer
token (UX1). The security review's two HIGH findings live in the same place: an
`Authorization`-header model cannot carry the stream, and anything that fixes it tends to
weaken the cross-site and origin checks on a listener any web page can reach (security
F1, F2).

### 5.2 The decision

One mechanism, chosen to satisfy both reviews:

**The browser authenticates with an `HttpOnly`, `SameSite=Strict`, host-only session
cookie, minted by an approval on the phone over the connection that asked for it. Every
mutation also carries a per-session CSRF token. The relay is served over TLS by default,
with the certificate fingerprint shown on the phone, so the cookie is `Secure` and does
not travel in cleartext. The bridge token never leaves the phone.**

Why this resolves the collision:

- `EventSource` and `<audio>` attach same-origin cookies automatically, so the stream and
  the audio authenticate with no header and with no token in a URL (fixes UX1 and
  security F2).
- `SameSite=Strict` and the host-only scope mean a page on any other origin never has
  the cookie attached (defends the hostile page).
- `HttpOnly` means a scripting bug cannot read the cookie (defends the residue of F1).
- A separate CSRF token covers mutations independently of `SameSite` (defends same-site
  edge cases and older browsers).
- Exact `Host` validation defends DNS rebinding, and the cookie is keyed to the phone's
  address so a rebound hostname never receives it.
- TLS with a fingerprint is the only control that protects the cookie on a shared wire.
  Without it, the same mechanism is replayable by a sniffer (section 5.5).

`SameSite=Strict`, an `Origin` / `Sec-Fetch-Site` check, and per-session binding are all
required together. The cookie alone, or the token in a query string, is not enough.

### 5.3 The flow, concretely

- Web access is off by default. It is enabled from Settings while the app is in the
  foreground, which is also what lets a foreground service start (section 3.4).
- The browser opens `https://<wlan-ip>:<port>`. The UI shell (HTML, CSS, JS) is served
  without a session because there is no secret in it. Every API route needs a session.
- The page `POST`s `/api/approve/request` as JSON and the relay holds that connection.
  The relay records one pending request: a random nonce bound to that connection, the
  source address, and a short expiry (about two minutes). Creation is rate-limited per
  source address, and only one pending request exists at a time.
- The phone's notification or in-app screen shows the pending request with the source
  address and the nonce, and Allow / Deny buttons. A request the human did not start is
  visible rather than silent.
- On Allow, the relay generates a 32-byte session id and a 32-byte CSRF secret, stores a
  session record (created, last seen, absolute expiry, idle expiry, CSRF secret, source
  address), and answers the held connection with
  `Set-Cookie: pi_session=<sid>; HttpOnly; SameSite=Strict; Path=/; Secure; Max-Age=<idle>`
  and a JSON body `{ok: true, csrf: <secret>}`. No code is shown to the browser and none
  is typed into it. On Deny or expiry the held connection gets a plain refusal.
- The page keeps the CSRF secret in JavaScript memory only, never in `localStorage` or
  `sessionStorage`. Every mutation sends `X-CSRF-Token: <secret>` and
  `Content-Type: application/json`.
- After a reload the page calls `GET /api/session`; if the cookie is still live the relay
  returns a fresh CSRF secret, otherwise 401 and the page shows the approval flow again.
- The relay authenticates a request by: valid cookie, live session record, and for a
  mutation a matching CSRF secret. A source address that differs from the approval
  address is rejected as hardening, with the caveat that DHCP can move it.
- A session dies on the idle timeout (30 minutes), the absolute cap (4 hours), a revoke
  from the phone's session list, or "Stop web access", which closes the listener and
  drops every session at once. Closing a tab clears nothing server-side; saying otherwise
  would be false.

A typed one-time code remains the fallback for when the human cannot tap the phone. It is
minted on the phone and typed into the browser, single use, short TTL, constant-time
compared, lockout guarded, like the bridge's `/api/pair` (`handlePair`, `:896`,
`PAIR_TTL_MS`). The code never travels to the browser before approval.

### 5.4 The three attacks, walked through

**A hostile page on the same WLAN.** The page is on another origin. `SameSite=Strict`
means the browser never attaches the session cookie to its requests, so they are
unauthenticated. It cannot read the UI's HTML or the CSRF secret because of the
same-origin policy, and there is no CORS grant. It cannot set an `X-CSRF-Token` header
or `Content-Type: application/json` on a simple request, and a preflight is refused. It
can still send a no-CORS request, but that carries no cookie and no JSON, so the relay
rejects it. It can create pending approvals, but one is allowed at a time, creation is
rate-limited, and the phone shows its source address for Deny.

**DNS rebinding.** A page at `evil.example` rebinds the name to the phone's IP and sends
requests that arrive at the relay with `Host: evil.example`. The relay rejects any `Host`
that is not the exact bound `address:port`, so the rebind fails at the first request.
Even without that check, the cookie is keyed to the phone's address and is not sent to
`evil.example`, and `Origin` / `Sec-Fetch-Site` would fail. The exact `Host` match is the
primary control and must reject a missing or duplicate value.

**A shared or untrusted network.** This is where the mechanism stops being sufficient on
its own. `SameSite`, `Origin` and CSRF are browser-side controls and do nothing against a
non-browser attacker who has captured the cookie. Over cleartext HTTP a passive sniffer
reads the cookie and can replay it until it expires, and an on-path attacker can
man-in-the-middle the first exchange. TLS with a fingerprint is the control: the human
compares the fingerprint shown on the phone against the browser's certificate warning, so
a passive sniffer sees nothing usable and an active attacker after acceptance is
rejected. The honest boundary is the first connection. Before the human has compared the
fingerprint, a determined on-path attacker can win. That is the residual of any
self-signed, no-CA design, and the design does not pretend otherwise.

### 5.5 The honest tradeoff

- **TLS is the default** because the threat model already treats cleartext as a defect
  (F4) and the human's rule forbids weakening defence. If TLS is not used, web access is
  limited to a WLAN the human trusts and the cleartext replay risk is accepted in
  writing, with the idle timeout and absolute cap as the only bound.
- **Trust on first use.** The fingerprint must be compared on the phone, not clicked
  through. If it is clicked through, TLS degrades to encryption without authentication
  and an active attacker wins on any connection.
- **Consent fatigue and a denial of service.** The single pending slot can be occupied by
  an attacker on the WLAN, and repeated requests can wear the human down. One pending
  slot, a short expiry, per-address rate limiting and a visible source address bound the
  damage, but they do not remove it.
- **The token claim is conditional.** "The bridge token never leaves the phone" holds
  only if the allowlist canonicalisation (security F5) and the response handling
  (security F1) hold. It should be read that way.
- **Revocation is explicit.** A session lives until idle timeout, absolute cap or revoke.
  A shared device keeps the cookie until then, so the phone's session list must offer a
  revoke and "Stop web access" must drop everything.
- **The posture increase is recorded.** Adding a WLAN listener is a real increase over
  today's tailnet-only client. It should be recorded as a new finding in
  `docs/THREAT-MODEL.md` when it is built (section 7).

### 5.6 Guards regardless of the auth choice

The relay enforces the route allowlist (4.4), the cookie and CSRF checks, the
`application/json` requirement, the exact `Host` match, the `Origin` / `Sec-Fetch-Site`
check, the no-CORS rule, the filtered event stream, and the WLAN-only bind. These are the
F1 fix, restated, and they are required no matter which credential is chosen.

## 6. What the phone does when it is asleep or the app is not in the foreground

The point of the feature is driving from another machine while the phone is not in hand,
so this matters more than usual.

- **Foreground service.** The relay (the listener, the bridge SSE connection, and the
  token in memory) lives in a foreground service with a persistent notification. This
  keeps the process alive and the socket open. It is started from the visible app
  (Android 12+), which enabling web access satisfies.
- **App Standby.** A foreground service counts as foreground, so the app is not put
  into App Standby, and its network is not deferred for that reason (Android "Optimize
  for Doze and App Standby", the App Standby conditions list).
- **Doze.** Doze is separate and device-wide: when the phone is stationary, screen off,
  and unplugged, the system suspends network access regardless of foreground services.
  For the relay to keep working in that state, the app must be on the battery
  optimization exemption list, which the human sets in Settings > Battery > Battery
  Optimization, or which the app can prompt for with
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. The exemption is described by Android
  as letting a "partially exempt" app "use the network and hold partial wake locks
  during Doze and App Standby". This is not a nice-to-have: without it, the relay can
  stop when the screen is off. The app should surface this and offer the prompt.
- **Wi-Fi power save.** Even outside Doze, Wi-Fi may add latency when the screen is off.
  This is a latency risk, not a correctness one, for SSE.
- **Process death.** A killed process with `START_STICKY` may be restarted by the system,
  but restarting a foreground service from the background is restricted on Android 12+,
  so the relay may stay down until the app is opened again. The design should not promise
  automatic recovery. This needs testing on the actual phone with the OEM's battery
  manager, which is known to kill apps differently from stock Android.
- **Bridge connection ownership.** Today the SSE stream lives in the ViewModel
  (`ChatViewModel.connect`, `:772`) and dies with the activity; the app reconnects on
  `ON_RESUME` (`MainActivity` lifecycle observer) and holds a 3 second poll while the
  ViewModel exists. For the relay to run with no activity, it must own a bridge
  connection itself. Two ways:
  - **Relay keeps its own SSE connection** to the bridge, independent of the UI. The
    bridge already supports multiple clients. Two connections per phone, and the pane
    footer reads "2 phones connected". Simplest.
  - **One connection, shared.** The relay service owns the single bridge connection and
    both the Compose UI and the web UI read from it. Cleaner state, but it is a
    refactor of the app's core, not a new module. Larger.

  Decision for v1: the relay keeps its own bridge connection. The bridge, the tailnet
  and the host firewall do not change, so the bridge's footer will read "2 phones
  connected" while the relay is attached. That is accepted (finding UX9): the second
  client is the relay, the count is cosmetic, and changing the bridge's count would
  violate the invariant. The duplicated event handling is the cost; one shared connection
  is a larger refactor of the app's core and is not taken in v1.

## 7. What still needs a human decision

A background agent cannot ask questions, so these are listed for a later interactive turn.
Everything else in this document is decided.

1. **TLS default.** The design sets TLS with a shown fingerprint as the default. Confirm
   that accepting a self-signed certificate once per browser is acceptable, or state that
   cleartext on a trusted WLAN is enough. This is the one place where a human choice
   changes the security posture materially.
2. **Certificate generation.** The app must generate a self-signed certificate and key
   for the WLAN address and show its SHA-256 fingerprint. Which library does that
   (BouncyCastle, or a small helper) is not settled here; I have not verified a
   maintained Android path that avoids adding a dependency.
3. **Server library.** Ktor CIO (recommended, maintained, larger APK) or NanoHTTPD
   (smaller, unmaintained since 2016)?
4. **Foreground service type and permission.** `connectedDevice` with
   `CHANGE_WIFI_STATE` plus `FOREGROUND_SERVICE_CONNECTED_DEVICE` (recommended), and
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` if the Doze exemption prompt stays. This adds
   permissions to the manifest.
5. **Relay connection ownership.** The design keeps the relay's own SSE connection and
   accepts the footer reading two (finding UX9). If the footer matters more than the
   refactor cost, say so and the shared-connection design is the alternative.
6. **Port.** A fixed port (recommended, predictable URL) or an ephemeral one?
7. **Battery optimization.** Is the human willing to whitelist the app so the relay
   survives Doze, or is "screen on or charging while driving" acceptable?
8. **Threat model.** This is a real increase over the tailnet-only posture. Record it as
   a new finding in `docs/THREAT-MODEL.md` and accept it explicitly, or keep web access
   disabled unless the human asks for it each time?
9. **Android 17.** When `targetSdk` reaches 37, an inbound LAN TCP connection needs the
   `ACCESS_LOCAL_NETWORK` runtime permission (Android "Local network permission", table
   row "Accepting an incoming TCP connection"). Plan to add it then.

## 8. Rejected alternatives

- **Serve the web UI from the bridge (analysis.md reading A).** Rejected by the human's
  answer: the requirement is the phone serving the page, and the bridge must not change.
- **A LAN listener on the laptop (reading B).** Rejected by the human's answer and by
  the docs: it moves the bridge off the tailnet and lets any WLAN device reach a
  root-capable agent. "Do not expose the bridge beyond the tailnet"
  (`docs/PAIRING-REVIEW.md` section 4).
- **The phone as a hotspot (part of reading C).** Rejected by the human's answer. Not
  needed, which also avoids the permission-gated and OEM-variable `LocalOnlyHotspot`.
- **Reuse the bridge token in the browser.** Rejected, section 5.2.
- **Any LAN client with no auth.** Rejected, F5.
- **Transparent pass-through proxy of every bridge route.** Rejected, section 4.4.
- **Hand-written HTTP server.** Rejected, section 3.1, F1.
- **A service worker / offline web app in v1.** Not taken as scope. With TLS default
  (section 5) the relay origin is a secure context, so a service worker is possible, but
  offline caching is not wanted: the phone is the source of everything and a cached
  answer could mask a stale bridge.
- **mDNS `.local` hostname instead of the raw IP.** Android does not reliably advertise
  a hostname, and Chrome's Local Network Access treats `.local` specially. Show the
  literal IPv4; optionally also show a QR of the URL for convenience.
- **A Bearer token in `sessionStorage` sent on every request.** Rejected. It cannot
  authenticate `EventSource` or `<audio>`, and it is the credential a script could read
  (UX1, security F2).
- **The session token in a query string for the stream or audio.** Rejected. It puts a
  live credential into browser history, server logs and `Referer`, for no gain once the
  cookie carries the stream.
- **A single durable web token, typed once.** Rejected for v1. It is at rest in the
  browser and valid until revoked, which is the durable credential the design avoids.
- **Cleartext as the default transport.** Rejected. The threat model already treats
  cleartext as a defect (F4), and a cookie on a shared wire is replayable. Cleartext is
  the documented downgrade for a trusted WLAN only.

## 9. What I verified, and how

Code facts, read in this worktree:

- Bridge: single bind chosen by `PI_REMOTE_HOST`, loopback twin for `tailscale serve`
  (`startLoopbackServer`, `bridge/extensions/pi-remote.ts:1176`); bearer auth gate at
  `:1218`; `authorized` and timing-safe `matchToken` at `:970` and `:249`; SSE client set
  and `emit` at `:432` and `:564`; event shapes at `:2153` to `:2173`; range serving in
  `bridge/lib/talks.ts:341`, 206 at `:393`; no CORS anywhere (grep found none).
- App: pure OkHttp client (`net/PiRemoteClient.kt`); SSE and reconnect in
  `ui/ChatViewModel.kt:772-907`; foreground service is media playback only
  (`AndroidManifest.xml`, `player/TalkPlaybackService.kt`); dependencies in
  `app/build.gradle.kts`; cleartext policy in `res/xml/network_security_config.xml`.

External facts, from primary sources:

- Foreground service types and the Android 14 requirement, and the
  `connectedDevice` prerequisites: developer.android.com "Foreground service types".
- Foreground service start restrictions: developer.android.com "Restrictions on starting
  a foreground service from the background".
- Doze suspends network access; App Standby excludes foreground services; partial
  exemption allows network during Doze: developer.android.com "Optimize for Doze and
  App Standby".
- Inbound TCP on the local network needs `ACCESS_LOCAL_NETWORK` from targetSdk 37:
  developer.android.com "Local network permission".
- `com.sun.net.httpserver` is not usable on Android: Stack Overflow question 25094834.
- NanoHTTPD latest release 2.3.1, 2016-08-12: Maven Central directory listing.
- Ktor current release 3.6.0 and Android D8/ProGuard testing: ktor.io releases and the
  Ktor 3.2.2 announcement.
- Chrome Local Network Access, milestone 1 gates public-to-local requests and is planned
  to extend to local-to-local cross-origin requests: developer.chrome.com
  "New permission prompt for Local Network Access".

Code facts re-checked for this revision:

- File content types: `html`/`htm` map to `text/html` and `xml` to `application/xml` in
  `bridge/lib/files.ts:70-121`, and `serveLibraryFile` passes that type into `serveFile`
  (`bridge/lib/files.ts`, tail). `serveFile` sets `content-type`, `content-length`,
  `content-range`, `accept-ranges` and `cache-control` (`bridge/lib/talks.ts:334-407`).
- The app's shared client follows redirects by default, sets
  `retryOnConnectionFailure(true)`, and uses `readTimeout(0)`
  (`app/src/main/java/com/maverock24/pimobile/net/PiRemoteClient.kt:33-39`).
- The manifest holds `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO`,
  `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `CAMERA`, and no `CHANGE_WIFI_STATE`,
  `FOREGROUND_SERVICE_CONNECTED_DEVICE` or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  (`app/src/main/AndroidManifest.xml`).
- The bridge has no CORS handling, and the SSE route writes `state` and `question` on
  connect (`bridge/extensions/pi-remote.ts:1450-1472`). The `state` payload carries
  `sessionFile`, `cwd`, `pid`, `boundHost` and `boundPort` (`:601-625`), and
  `tool_execution_start`/`_end` carry `args` and `result` (`:2167-2177`).

## 10. What I could not verify

- Whether the phone really keeps the relay and the bridge SSE alive with the screen off
  under its OEM battery manager, and whether Doze is actually defeated by the exemption
  in practice. This needs the device.
- Whether a `ServerSocket` bound to the Wi-Fi address accepts inbound LAN connections
  while the Tailscale VPN is up. This should work, because the address is the Wi-Fi one,
  but per-app VPN handling on Android is exactly the kind of thing that surprises, and
  it has not been tested.
- Whether Android 17's local network protections treat an inbound listener as covered by
  `ACCESS_LOCAL_NETWORK` in all cases. The docs say accepting an incoming TCP connection
  requires the permission, so the design assumes yes, but this is untested and the app
  targets SDK 35 today.
- Whether Chrome's Local Network Access prompt appears for a same-origin SPA served from
  the phone's own address. Milestone 1 gates public-to-local, so it should not, but the
  specification is moving and this should be rechecked when the feature is built.
- Whether a self-signed certificate for a bare WLAN IPv4 works smoothly in the target
  browsers (Chrome and Firefox) without a name. The design assumes the person can proceed
  past the certificate warning and compare the fingerprint; this needs a real test on the
  actual browsers and phone.
- Which Android library to use to generate the self-signed certificate. I have not
  verified a maintained path that avoids adding a dependency.

## 11. Finding resolution table

Every finding from `security-review.md` (F1-F10) and `ux-review.md` (1-9), and what
changed. "Fixed" means the design text now describes the new behaviour; "Accepted" means
the residual is stated with its cost.

| Finding | Severity | Resolution | What changed |
| --- | --- | --- | --- |
| SEC F1 | HIGH | Fixed | File route serves audio types only; non-audio refused and listed as unavailable; `nosniff`, `Content-Disposition: inline`, strict CSP. Section 4.4. |
| SEC F2 | HIGH | Fixed | Session cookie (`HttpOnly`, `SameSite=Strict`, host-only, `Secure` under TLS) replaces the body-issued Bearer token, so `EventSource` and `<audio>` authenticate. CSRF token on mutations. Section 5. |
| SEC F3 | MAJOR | Fixed | TLS with a shown fingerprint is the default; cookie is `Secure`; session has a 30 minute idle timeout and a 4 hour absolute cap, enforced server-side. Sections 5.2, 5.5. Residual: TOFU first connection. |
| SEC F4 | MAJOR | Fixed | Approval is bound to the held connection and a nonce, one pending at a time, rate-limited, no code shown to the browser. Typed code mints on the phone as fallback. Section 5.3. Residual: the pending slot can be squatted. |
| SEC F5 | MAJOR | Fixed | Canonicalise once with the bridge's URL parser, reject encoded separators and absolute/authority targets, reject missing/duplicate `Host`, match method plus exact canonical path against a literal table, forward that same string. Section 4.4. |
| SEC F6 | MAJOR | Fixed | Relay filters events by type; forwards only message frames plus reduced `talk`/`talk_removed` and `question` payloads; never `state` wholesale or `tool_execution_*`. Section 4.4. |
| SEC F7 | MAJOR | Fixed | Fixed upstream header set; browser `Cookie` and `Authorization` never forwarded; response copies only the safe headers and adds `nosniff`, CORP and CSP. Section 4.5. |
| SEC F8 | MINOR | Fixed | Design says closing a tab does not drop the server session; per-session revoke list on the phone; `X-Pi-Client` is `web:<session-id>`, never a browser header. Sections 5.3, 4.5. |
| SEC F9 | MINOR | Fixed | Manifest gains `CHANGE_WIFI_STATE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` and (if the prompt stays) `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; permission increase recorded. Section 7. |
| SEC F10 | MINOR | Fixed | Relay upstream client sets `followRedirects(false)`, `retryOnConnectionFailure(false)` and a finite read timeout; SSE keeps the long timeout. Section 4.5. |
| UX1 | critical | Fixed | Same as SEC F2: the cookie carries the stream and media; no header, no token in a query string. Section 5. |
| UX2 | critical | Fixed | Relay maps `question` to `{pending, title}`; page shows a blocking line naming the question and saying to answer on the phone; `POST /api/answer` is not in the v1 allowlist. Sections 4.2, 4.4. |
| UX3 | major | Fixed | On SSE open and reconnect the page fetches history and re-renders the newest answer, with a 5 second poll and a reconnecting state. Section 4.2. |
| UX4 | major | Accepted | Joining requires the phone by design, and a Wi-Fi address change cannot redirect an open tab across origins. Cost: the human reads the phone once to join and again after a lease change. Mitigations: URL and QR beside the toggle, named waiting state, timeout and deny messages. Sections 3.2, 4.2. |
| UX5 | major | Fixed | Web UI distinguishes browser cannot reach the phone, phone up but bridge down, and bridge up but library empty; reuses `Diagnostics` wording and adds a connection indicator; never blames the network unless it is the failed hop. Section 4.2. |
| UX6 | major | Fixed | Design states browser playback is tab-bound and dies with the WLAN; Files view refreshes on `talk`/`talk_removed` and shows a rendering state; no tap on a not-ready file. Sections 4.2, 4.4. |
| UX7 | minor | Fixed | Desktop layout defined (capped measure, monospace code blocks, width used for files or session identity). Full markdown deferred, stated as a known loss; rendering it would add an XSS surface. Section 4.2. |
| UX8 | minor | Fixed | Files list is audio-only in v1; other kinds are shown non-interactive with a reason or omitted. Sections 4.2, 4.4. |
| UX9 | minor | Accepted | The bridge, tailnet and host firewall do not change, so the footer reads "2 phones connected" while the relay is attached. Cost: a confusing count during diagnosis; the second client is the relay. Section 6. |

Totals: 17 fixed, 2 accepted (UX4, UX9).

## 9. Human decisions, answered 2026-10-11

Section 7's open items are now settled by the human. Everything else in this document
was already decided.

1. **TLS default: self-signed TLS with the fingerprint shown.** The browser accepts it
   once per client. No cleartext fallback is described here, so section 5's TLS posture
   stands as written.
2. **Certificate generation: still a technical follow-up**, not a human choice. With TLS
   now confirmed, the app must generate a self-signed certificate and key for the WLAN
   address and show the SHA-256 fingerprint. Settle BouncyCastle versus a maintained
   Android path that avoids a new dependency, and record the choice.
3. **Server library: Ktor CIO.** Larger APK accepted for a maintained implementation of
   HTTP and TLS.
4. **Foreground service: as recommended in section 7**, `connectedDevice` with
   `CHANGE_WIFI_STATE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`, plus
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for the exemption in item 7.
5. **Relay connection ownership: the design's own SSE connection**, and the footer
   reading two is accepted (finding UX9). Not worth the refactor cost.
6. **Port: fixed**, as section 7 recommends, because the human has to type the URL on the
   other machine.
7. **Battery optimization: the app will ask to be whitelisted**, so the relay survives
   Doze. This is what makes the phone-in-pocket case work, and it is the reason item 4's
   extra permission is wanted.
8. **Threat model: record the increase in `docs/THREAT-MODEL.md` and accept it.** Not an
   opt-in switch: web access is part of the feature rather than something enabled per use.
9. **Android 17: plan for it.** When `targetSdk` reaches 37 an inbound LAN TCP connection
   needs the `ACCESS_LOCAL_NETWORK` runtime permission.

## 10. Revised: the web server is on demand, 2026-10-11

The human: "the web server needs to be started by the app everytime. once the client
disconnects the web server should be shutdown automatically".

So the listener is **not** always on. The app starts the web server on request, and it
stops itself when the client goes away. This supersedes parts of section 9:

- **Section 9 item 8 is reversed in effect.** It chose to record the threat-model
  increase and accept it rather than make web access opt-in per use. With an on-demand
  server the listener is not running when nobody is using it, so the exposure is bounded
  by the session rather than continuous. The threat-model entry is still wanted, but it
  describes a smaller surface than section 7 assumed. Say so when it is written.
- **Section 9 item 7 matters less.** A Doze exemption was chosen so the relay survives
  while the phone is asleep in a pocket. That is still wanted for a long listening
  session, but it no longer carries the whole feature.

Requirements this adds, which the implementation must respect:

- **Starting is an explicit act in the app**, and the app shows the URL to open at that
  moment, since the address is only useful while the server is up.
- **Shutdown on client disconnect needs a grace period.** A page refresh, a browser
  tab switch or a momentary network drop must not kill the server, or the human will
  lose the session for doing something ordinary. Prefer a short idle timeout with no
  active connection over reacting to the first disconnect, and keep an explicit stop
  control, which the foreground service notification is the natural home for.
- **The foreground service notification should say the server is on**, because it is the
  only place the human can see that a listener exists and stop it without opening the app.
- Shutdown must close the listening socket and drop the session cookie's validity, so a
  stale browser cannot reconnect to a server the human believes is off.

## 11. Readiness, 2026-10-11

**Decided.** The architecture (the phone relays, bridge and tailnet untouched), the auth
mechanism (HttpOnly, SameSite=Strict, host-only session cookie minted by a phone approval
bound to the requesting connection, with a per-session CSRF token and exact Host
validation), 17 review findings fixed and 2 accepted, the human decisions in section 9,
and the on-demand lifecycle in section 10.

**One technical decision is still open, and it blocks the first line of code**: how the
app generates a self-signed certificate and key for the WLAN address and shows its
SHA-256 fingerprint (section 9 item 2). BouncyCastle against a maintained Android path
that avoids a new dependency. Settle it first, because everything else in the server
depends on that object existing.

**Nothing here has ever been run.** There is no web front end and no HTTP server in this
repo, so this is a large first build of unfamiliar code, and the parts most likely to
surprise are the Android ones: binding a socket, TLS, certificate handling, and the
foreground service. Build it as a walking skeleton rather than one large change:

1. **Skeleton**: Ktor CIO serving a single static page over TLS with a generated
   self-signed certificate, with the fingerprint and URL shown in the app and the
   foreground service notification present. Proves the hard Android parts and nothing
   else. If this stage is painful, the rest of the plan changes.
2. **Auth and one route**: the approval, the cookie, CSRF and Host checks, plus a proxy
   for one read-only route. Proves the loop end to end with almost no surface.
3. **The prompt loop**: send a prompt, stream the answer, and answer a questionnaire in
   the browser. The questionnaire is not optional at this stage, because a prompt that
   asks one and cannot be answered is a dead end, and that exact bug already shipped in
   car mode.
4. **Audio**: reaching a rendered talk and playing it, compared against the phone path
   that works.

Each stage gets its own gate and push, so the human can try it before the next one starts.

## 12. Certificate generation, decided 2026-10-11

Section 9 item 2 is settled. The rule to design against: the private key is the secret,
so it should live where secrets live on Android and never be written into app storage.

- **Key: generate once, on first run, into the Android keystore** (`KeyStore` provider
  `AndroidKeyStore`), with a P-256 EC key rather than RSA. Hardware backing is used
  automatically where the device has it, so on a rooted phone the key still cannot simply
  be read off the disk, and there is no key file to leak, back up, or copy. EC over RSA
  because the handshake is faster and the key is smaller, which matters on a low-end
  phone serving a browser.
- **Certificate: assemble the self-signed X.509 with BouncyCastle** (`bcpkix`), signing
  with that keystore key. The JDK's own certificate-building classes are not part of the
  Android public API, which is why this one dependency earns its place. This is the
  "maintained Android path that avoids a new dependency" question from section 9, and the
  honest answer is that no maintained one exists: the dependency is the smaller risk
  compared with hand-rolling X.509.
- **Persist the certificate, never the key.** The certificate is public and lives in app
  storage; the key stays in the keystore under an alias. Regenerate both if the alias is
  missing, which is what makes this survive a reinstall without hand-managed files.
- **Long validity with a stable fingerprint**, because the human accepts the certificate
  once per browser and a rotating certificate would mean a fresh warning screen every
  time. The fingerprint shown in the app must be the SHA-256 of the certificate actually
  served, and the human compares it against the browser's warning.

Two things to verify against current documentation before writing this, because both are
version-sensitive and this document is not a substitute for the API reference: the
BouncyCastle artifact names for the pieces actually needed, and whether the chosen EC
curve and signature algorithm are accepted by the browsers the human will use. Also
confirm the behaviour when a device has no hardware-backed keystore, which should degrade
to a software keystore without changing the flow.

## 13. Scope change: the browser shows the same interface, 2026-10-11

The human: "when connected to the web server the connected device / computer should have
the same interface in the browser just like using pi remote on the phones ui".

This overrides the scoped-down web front end in section 3 and the UX review's list of
what the browser version deliberately would not have. The browser must present the same
interface and the same capabilities as the phone app, not a reduced console.

Two ways to satisfy that, and the choice is not obvious:

- **A hand-written web UI that mirrors the phone's screens.** Fast to start and ordinary
  to build, but it becomes a second UI to maintain, and every future phone-side change
  has to be made twice. That drift is the real cost, not the initial work.
- **Compose Multiplatform targeting web (Wasm), reusing the existing Compose UI.** This
  is the literal answer to "the same interface" and it cannot drift, because there is one
  implementation. The cost is a migration far larger than this feature, and it is not
  certain the existing screens, the media3 player and the Android-only pieces survive it
  cleanly. Verify the current state of Compose Multiplatform web support before betting
  on this.

Decide this before stage 3 goes far, because stage 3 either builds the prompt loop once
in a web UI that will then be extended to every screen, or it waits for the migration.

Staging changes accordingly: stage 3 remains the core loop (transcript, composer,
streaming answers, and the questionnaire, which must not be deferred), stage 4 grows from
"audio" to the remaining screens, pins, files, talks and settings, and stage 5 becomes
the audio path.

## 14. Direct connection instead of a router, 2026-10-11

The human asked whether the other computer could connect straight to the phone rather
than through the Wi-Fi router. Short answer: yes, and there are three ways, with
different costs.

- **The phone's own hotspot, turned on by the human in system settings.** The computer
  joins the phone's network and reaches the server on the phone's hotspot address. No app
  permission is needed, because the human does the toggling, and it is the simplest thing
  that works. This is the recommended default.
- **`LocalOnlyHotspot`, created by the app.** Nicer because the app owns the lifecycle,
  but section 8 already rejected it as permission-gated and OEM-variable, and that
  judgement was made on the grounds that it was not needed. It is now wanted, so this
  needs revisiting rather than dismissing: check the current permission requirements,
  including whether the app must be in the foreground, against the Android documentation
  rather than trusting this note.
- **Wi-Fi Direct.** Rejected: it is peer to peer, and a browser on an ordinary laptop
  cannot join it, which defeats the purpose.

**What changes in the design if this is used:**

- **Binding and address discovery.** The server currently binds the WLAN interface. On a
  hotspot the address is different, and the app must bind the hotspot interface and show
  the address that is actually reachable. Showing a stale address is worse than showing
  none, because the human will type it and get nothing.
- **The threat model improves substantially.** This removes the whole "any device on the
  human's home or work network" concern that made section 7 a real increase in exposure.
  Only devices on the phone's own hotspot can reach the server, and the human controls who
  joins. That is worth recording in `docs/THREAT-MODEL.md` when the entry is written, and
  it may mean the mitigation set can be smaller than section 5 requires.
- **The Doze exemption matters more, not less**, since the phone is now the whole network
  path rather than one host on an existing one.
- **The on-demand lifecycle from section 10 still applies unchanged**, and arguably reads
  better here: the server running only while someone is connected matches a hotspot that
  is only up while it is wanted.

## 15. Transport decided: the phone's own hotspot, 2026-10-11

The human chose the first option in section 14 and closed the question: the phone's own
hotspot, turned on by the human, is the transport. No app-created hotspot.

- **`LocalOnlyHotspot` stays rejected**, now for a better reason than "not needed": the
  system hotspot costs no permission and does not vary by manufacturer, which were the two
  objections in section 8. There is nothing left for the in-app version to buy.
- **The server binds the hotspot interface and shows that address.** The human has to type
  it on the other computer, so it must be the address that actually answers, and it must
  update when the hotspot comes up or goes down rather than showing a stale one.
- **The threat model entry shrinks accordingly.** Only devices on the phone's own hotspot
  can reach the server, and the human decides who joins. Record this in
  `docs/THREAT-MODEL.md` when that entry is written.

**Do not weaken the authentication because of this.** Strict SameSite cookies, the CSRF
token, the exact Host check and the approval bound to the requesting connection all stay
as section 5 specifies. The hotspot reduces who can reach the listener; it does not make
the listener safe to leave unauthenticated, and the same code has to be sound if the human
ever serves over a router instead. Removing reviewed defences to save effort is the one
change this decision does not justify.

## 16. Section 13 decided: the hand-written web UI stays, 2026-10-11

The reviewer was asked to decide and chose **not** to migrate to Compose Multiplatform.

The reasoning, so the question does not get reopened without new information:

- **A migration would not remove the duplication it promises to remove.** Compose
  Multiplatform targets the browser, but the parts of this app that cannot move are the
  ones that matter to the web feature: media3 playback, the foreground service, the
  Android keystore and the certificate handling. Those stay native either way, so the
  migration buys a shared transcript and composer at the cost of everything else.
- **It would put working software at risk.** Stages 1 to 5 work. A front-end migration
  large enough to matter would land on top of that, and the feature that exists today
  would be the thing that breaks.
- **Simplicity.** The human has said repeatedly that they want the simplest thing that
  works. A second front-end framework is not that, and the current web UI is small
  enough to read.

**The real cost of this choice is drift between two UIs, and it is accepted knowingly.**
The mitigation, which future work must follow:

- **The relay's API is the contract.** Both UIs talk to the same bridge routes through
  the same relay, so a behaviour difference is a bug rather than a design choice.
- **A new screen or a behaviour change lands in both UIs in the same change**, not as a
  follow-up. Where that is impossible, say so in the commit message rather than leaving
  the browser quietly behind.
- **The distinctions that have already caused bugs are the ones to keep aligned**: an
  empty list must look different from an unreachable bridge, and a prompt that triggers a
  questionnaire must be answerable in both places. Both have already shipped broken once.
