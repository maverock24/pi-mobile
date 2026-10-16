# Security review: WLAN web access for Pi Remote

Review only. Nothing was changed, no code was written, nothing was committed.

Target: `.scratch/wlan-web-access/design.md`, with `analysis.md` for context.
Checked against the code the design builds on: `bridge/extensions/pi-remote.ts`,
`bridge/lib/files.ts`, `bridge/lib/talks.ts`,
`app/src/main/java/com/maverock24/pimobile/net/PiRemoteClient.kt`,
`app/src/main/java/com/maverock24/pimobile/data/SettingsStore.kt`,
`app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, and
`docs/THREAT-MODEL.md` / `docs/PAIRING-REVIEW.md`.

The design's line numbers for the bridge held up when checked, which is a good sign
for the parts it describes accurately. The problems below are mostly in the parts it
leaves unspecified, plus two places where its own first slice contradicts its own
auth decision.

---

## F1. Proxied library files are served into the web UI's own origin, so agent-written HTML becomes stored XSS against the session credential

- Severity: **HIGH**
- Category: **design flaw** (the relay surface, 4.4, line 251) plus a **missing decision** (response handling)
- Design claim: the first slice reaches "the audio in the Files list" via the relay's file route (lines 221, 251), and "The browser never sees the bridge token" (line 66). The credential is held in `sessionStorage` in the relay's origin (line 309).

Evidence. The bridge serves library files with a content type derived from the file
extension: `bridge/lib/files.ts:70-121` maps `html` and `htm` to
`text/html; charset=utf-8` and `xml` to `application/xml`, and `serveLibraryFile`
(`bridge/lib/files.ts:295-297`) passes that type straight through `serveFile`
(`bridge/lib/talks.ts:341`). The library is "any file a pi session leaves in the
agent's talks directory" (`bridge/lib/files.ts:1-20`), and the names it accepts include
`evil.html` (`SAFE_NAME_RE`, `bridge/lib/files.ts:134-137`). The bridge's own function
list is not the issue; the issue is that the relay would copy a proxied response's
`content-type` into the browser on the same origin that holds the web session token.

Why it matters. If the relay forwards `/api/files/<name>` and lets the bridge's
content type stand, then a file named `evil.html` written by the agent renders as HTML
in `http://<wlan-ip>:<port>`, which is the web UI's origin. Script in that page reads
`sessionStorage`, so it can take the web session token and drive the relay, which holds
the root-capable bridge token. The agent can be asked to write that file, and prompt
injection already reaches it (`docs/THREAT-MODEL.md` F7). The precondition is an
approved session plus a browser that opens the file; the design's first slice lists the
Files view, so the file is offered to the human.

Fix. Do not proxy file bytes into the UI origin. Serve proxied bytes with
`Content-Disposition: attachment`, `Content-Type: application/octet-stream` for
anything that is not audio, and `X-Content-Type-Options: nosniff`; allow only
`audio/*` types for the `<audio>` path; add a strict `Content-Security-Policy` to the
bundled UI (`default-src 'self'`, no `unsafe-inline`, no external script). The stronger
version is a second listener or origin for file bytes, so a rendered file is never
same-origin with the session.

## F2. The "Authorization header on every request" auth model cannot carry the EventSource stream or the audio element the first slice depends on

- Severity: **HIGH**
- Category: **design flaw / internal contradiction** (sections 4.2 and 5.3)
- Design claim: "every request sends `Authorization: Bearer <web session>`" (line 309), the credential is "not a cookie, so a cross-site request cannot use it" (line 307-308), and the first slice uses "An `EventSource` on a relay SSE endpoint" (line 216) and "an `<audio>` element pointed at the relay's file route" (line 221).

Evidence. `EventSource` has no header option. The constructor takes only a URL and
`{ withCredentials }`; there is no way to set `Authorization`
(https://developer.mozilla.org/en-US/docs/Web/API/EventSource/EventSource, fetched
2026-10-16). An `<audio src>` element cannot set request headers either. Both are
exactly the two browser primitives the first slice names.

Why it matters. The design cannot be implemented as written. The likely workaround is
to put the session token in the query string for the SSE and audio routes, which puts a
bearer credential into browser history, server access logs and `Referer`, and turns a
cross-origin `EventSource` or `<audio>` request into a credential-bearing request that
the `Origin`/`Sec-Fetch-Site` check has to catch. That is a worse credential-handling
design than the one the document rejects in 5.2 option 1.

Fix. Pick one transport and state it. Either (a) use `fetch` with a `ReadableStream`
for the SSE stream, so the header can be set, and for audio fetch a range with a header
into a `Blob` (acceptable for short clips, not for 30 minute talks), or (b) send the
session as a `SameSite=Strict` cookie scoped to the relay origin, which `EventSource`
and `<audio>` will carry automatically and which a cross-site page will not, plus a
CSRF token on mutations. The design's blanket "never a cookie" is the part that has to
change.

## F3. The session is long-lived, cleartext, and replayable for as long as web access stays on, and the design calls that short-lived

- Severity: **MAJOR**
- Category: **claim contradicted by the design's own text**
- Design claim: "mints a short-lived, tab-scoped session token" (line 294), and option 6 is "a one-time exchange that mints a short-lived session" (line 289). The TTL is actually "while web access stays enabled, plus an idle timeout (30 to 60 minutes suggested)" (lines 310-311).

Evidence and the model's own words. `docs/THREAT-MODEL.md` F4 already treats cleartext
as a defect, and the human's standing constraint at the end of `docs/PAIRING-REVIEW.md`
is "i would not like to weaken defence". The design does disclose the cleartext replay
risk (lines 325-329), which is honest, but it then ships cleartext as the default and
offers TLS as an option (section 5.4, section 7 item 2).

Why it matters. "While web access stays enabled" can be hours or days. A passive
sniffer on the WLAN has a replay window as long as the feature is left on. The word
"short-lived" in the recommendation is doing work the TTL does not support; a reader
who skims section 5.3 will believe the token is short.

Fix. Make TLS with a shown fingerprint the default, not an option, because the threat
model already calls cleartext F4-level. If cleartext must stay for v1, cap the session
TTL at the idle timeout regardless of whether web access is still on, rotate it on
each request (sliding expiry with a hard maximum), and word section 5.3 as "long-lived
for the life of the listener" so the residual risk is stated correctly.

## F4. The approval channel is unauthenticated by design: many pending requests, a short code, no rate limit, and the token is released to whoever presents the code

- Severity: **MAJOR**
- Category: **missing decision** (section 5.3, lines 303-317)
- Design claim: "The page shows a short request code" and the phone shows "a pending request with the browser's WLAN address and the same code" (lines 303-306). On Allow the relay "mints a 32-byte session token and returns it in the response body" (line 307). A typed code is "the same single-use, short-TTL, constant-time-compared, lockout-guarded pattern the bridge already uses for `/api/pair`" (lines 313-315).

Evidence and gaps. The bridge's pair code is 16 random bytes
(`crypto.randomBytes(16)`, comment at `bridge/extensions/pi-remote.ts:794` and the
handler at `:896`), two-minute TTL, single spend, five failures per address then a
lockout (`:951-963`). The design's primary browser code is only described as "short",
and nothing states rate limiting, a cap on concurrent pending requests, or how the
minted token is bound to the requester.

Why it matters. Any device on the WLAN can open the page and create a pending request,
repeatedly. Each one can raise a notification with its own source address and code, so
the human has to pick the right code among several, which is consent fatigue and a
wrong-tap path to admitting an attacker. If the approval is keyed only on the code and
the code is short, an attacker can brute-force or race the claim endpoint. On cleartext,
a sniffer sees the code on the wire before approval.

Fix. Mint the code on the phone and require it to be typed into the browser (the
direction the bridge already uses: code on the laptop, typed on the phone), so it never
travels to the browser before approval. Hold the requesting HTTP connection open and
return the token on that same connection rather than on a code-keyed poll. Allow one
pending request at a time, rate-limit creation per source address, and require
`Origin`/`Sec-Fetch-Site` and `application/json` on the request endpoint (section 5.5
already lists these, and they apply here most of all).

## F5. The allowlist is the only control keeping the browser off `/api/pair` and `/api/diagnostics`, and the design never says how the path is canonicalized

- Severity: **MAJOR**
- Category: **missing decision** (section 4.4, lines 241-255)
- Design claim: "A transparent 'forward anything' proxy is rejected" and "Never proxy: `/api/pair`, `/api/release/*`, `/api/diagnostics` ..." (lines 242, 254).

Evidence. The bridge builds its route from `new URL(req.url || "/", "http://localhost")`
(`bridge/extensions/pi-remote.ts:1195`). The WHATWG URL parser rewrites path segments:
checked with Node on this machine, `new URL("/api/files/%2e%2e/%2e%2e/api/pair", base)`
resolves to `/api/pair`, and `/api/files/..\..\api\pair` resolves to `/api/pair`.
A prefix check on the raw path and the forwarded target are therefore different strings
unless the relay canonicalizes once and uses the same string for both.

Why it matters. Browsers use the same parser, so a browser `fetch` cannot smuggle
`%2e%2e`; the bypass needs a non-browser WLAN client that holds a session, or a relay
that decodes the path before the check but forwards the decoded path. Both are in scope
for "any device on the WLAN". The failure mode is that the browser reaches
`/api/pair`, whose response body is the bridge token itself
(`sendJson(res, 200, { ok: true, device: label, token })`,
`bridge/extensions/pi-remote.ts:934`), so a single canonicalization bug contradicts the
design's central invariant.

Fix. Canonicalize the request target once (reject any encoding of `/`, `\`, `.` and
`..` segments, reject absolute-form and authority-form targets, reject a missing or
duplicate `Host`), then match method plus the exact canonical path against a literal
table, then forward that same canonical path. Do not forward the raw target.

## F6. Blindly forwarding `/api/events` leaks far more than the answer: state paths, host and port, pid, and tool arguments and results

- Severity: **MAJOR**
- Category: **missing decision** (section 4.2, lines 215-217; section 4.4)
- Design claim: the relay SSE endpoint "forwards the bridge's `message_start` / `message_update` / `message_end` frames" (lines 216-217).

Evidence. The bridge's event stream is one connection that emits every event type,
not just those three. `state` carries `sessionFile`, `cwd`, `host`, `port`,
`boundHost`, `boundPort` and `pid` (`bridge/extensions/pi-remote.ts:601-625`).
`tool_execution_start` carries `args` and `tool_execution_end` carries `result`
(`:2167-2177`). The SSE route writes a `state` frame and a `question` frame
immediately on connect (`:1450-1472`), and `emit` fans every event to every client
(`:564`).

Why it matters. A browser session that only needs the transcript also receives absolute
filesystem paths, the bridge's bound address and port, and the agent's tool inputs and
outputs, which routinely include file contents and command output. That is wider than
F6 in the threat model ("`/api/state` discloses paths", open) and wider than the UI
needs.

Fix. The relay must filter by event `type` on its own bridge connection and forward only
the message frames the UI uses. It must not forward `state`, `tool_execution_*` or
`question` unless the UI renders them, and it must never pass raw `args` or `result`.

## F7. Request and response header handling is unspecified, and the safe set is narrow

- Severity: **MAJOR**
- Category: **missing decision** (sections 4.4 and 5.5)
- Design claim: the relay "proxies" the allowlist "using the phone's existing OkHttp client and token" (lines 241-243). Header handling is not described.

Evidence and gaps. The shared client sends `Authorization: Bearer <token>` and
`X-Pi-Client` on every request (`PiRemoteClient.kt:44-47`). If the relay forwards the
browser's own `Authorization`, Node joins duplicate `Authorization` headers, so the
bridge's `matchToken` sees a malformed value and the call fails closed; if it forwards
the browser's `Cookie`, those go upstream too. On the response side, the relay serves
the bundled UI and the proxy from one origin, so copied response headers become the
UI's headers (`serveFile` sets `content-type`, `content-length`, `content-range`,
`accept-ranges`, `cache-control`; `bridge/lib/talks.ts:334-407`).

Fix. Build a fixed upstream header set, not a copy: method, canonical path and query,
`Authorization` from the relay's own token store, and only `Content-Type`, `Range` and
`Accept` from the browser. Strip hop-by-hop headers and never forward `Cookie` or the
browser's `Authorization`. On the way back, copy only status, `Content-Type` (after the
F1 restrictions), `Content-Length`, `Content-Range` and `Accept-Ranges`, and set
`X-Content-Type-Options: nosniff` and a CSP. Set `X-Pi-Client` to a fixed
`web-relay` value, never to a browser-supplied one.

## F8. Session lifecycle claims are not true, and prompts sent from the browser are not attributable

- Severity: **MINOR**
- Category: **claim contradicted by the design** plus a **missing decision**
- Design claim: "Closing a tab drops its token; 'Stop web access' drops all of them at once" (lines 310-311). The relay labels relay-originated prompts via `X-Pi-Client` (lines 75-77).

Evidence. There is no cookie and no connection binding, so the server has no signal
that a tab closed; the token stays valid until the idle timeout or a global stop. On
attribution, the bridge records the device label and `X-Pi-Client`
(`bridge/extensions/pi-remote.ts:1474-1500`), and the phone's `X-Pi-Client` is the app
version (`PiRemoteClient.kt:47`). Every web session shares the phone's one bridge token,
so the bridge audit log cannot tell which browser tab sent a prompt.

Fix. Say plainly that closing a tab only clears `sessionStorage` and the server keeps
the token until timeout. Add a per-session list with a revoke action on the phone. Have
the relay set `X-Pi-Client` from its own session id (for example `web:<short-id>`), never
from a browser header, so one web session can be told from another in the audit log.

## F9. Android details the design needs but has not fixed: the battery-optimization intent needs a permission the app lacks, and two manifest entries are missing

- Severity: **MINOR**
- Category: **missing decision** (sections 3.4 and 6)
- Design claim: the app can prompt for the Doze exemption with `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (lines 371-376). The new foreground service is `connectedDevice` with `CHANGE_WIFI_STATE` (lines 178-186).

Evidence. `app/src/main/AndroidManifest.xml` declares `INTERNET`,
`ACCESS_NETWORK_STATE`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`,
`REQUEST_INSTALL_PACKAGES`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `CAMERA`. It has no `CHANGE_WIFI_STATE`, no
`FOREGROUND_SERVICE_CONNECTED_DEVICE` and no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
The device battery-exemption intent requires the
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission to be held by the caller, so the
design's plan cannot fire that intent until the manifest is changed.

Fix. Add `CHANGE_WIFI_STATE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` and (if the
exemption prompt stays) `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to the manifest and
record the permission increase. The `connectedDevice` choice itself is sound: Android 15
capped `dataSync` foreground services at six hours per day, and the design correctly
avoids that type. The `ACCESS_LOCAL_NETWORK` item (section 7 item 11, lines 427-430) is
version-dependent and the design already flags it as unverified; treat it as a planned
change when `targetSdk` moves, not as a settled fact.

## F10. The proxied upstream call inherits the app client's redirect and retry defaults

- Severity: **MINOR**
- Category: **missing decision**
- Evidence. The app's shared `OkHttpClient` uses `retryOnConnectionFailure(true)` and
  the OkHttp default of following redirects (`PiRemoteClient.kt:33-39`). The client does
  not disable either.

Fix. The relay's upstream calls should use a client with `followRedirects(false)` and
`retryOnConnectionFailure(false)`, so a redirect cannot carry the bridge token to a
different host and a retried `POST /api/prompt` cannot deliver a prompt twice. Set an
upstream read timeout for non-SSE calls; the shared client's `readTimeout(0)`
(`PiRemoteClient.kt:36`) means a hung bridge never times out.

---

## Where the design is right

These were checked and hold up, so the review is not uniformly negative.

- The multi-client SSE reasoning is accurate. `let clients = new Set<http.ServerResponse>()`
  is at `bridge/extensions/pi-remote.ts:432`, `emit` fans to all of them at `:564`, and
  the footer count is at `:586`. One more bridge client is genuinely not a new model.
- Byte-range serving is real: `serveFile` at `bridge/lib/talks.ts:341` answers `206`
  with `Content-Range` at `:393`, so an `<audio>` element can seek, assuming the
  credential transport is fixed (F2).
- `X-Pi-Client` is read from the request header at `bridge/extensions/pi-remote.ts:965`,
  so relay labelling is possible, subject to F8.
- The pasted auth baseline is accurate: per-device tokens in `tokens.json`
  (`:200-245`), timing-safe `matchToken` (`:249`), the unauthenticated `POST /api/pair`
  with a 16-byte, two-minute, single-use code (`:896-940`), and per-address lockout
  (`:951-963`).
- The F1 countermeasures as stated are the right ones for authenticated routes: no
  CORS, an `Authorization` header a cross-origin simple request cannot set, and
  `application/json` on mutations. They correctly repeat the fix recorded in
  `docs/THREAT-MODEL.md` F1.
- `Host` header validation for DNS rebinding (line 159) is necessary and correct. A
  page on the WLAN can point its own domain at the phone's Wi-Fi address, and an exact
  `Host` match against the bound address and port is what stops it. Keep this, and make
  it an exact match that rejects a missing `Host`.
- Binding the Wi-Fi IPv4 address rather than `0.0.0.0` is the right call, and the
  reasoning about not exposing the Tailscale interface and cellular is sound. Binding a
  specific local address restricts accepted destination addresses.
- `com.sun.net.httpserver` is not usable on Android. Correct.
- The design discloses the cleartext, ARP and DNS-spoofing risk instead of hiding it.
  The disclosure is accurate; my F3 is about the default and the word "short-lived",
  not about the honesty of section 5.4.

## Claims checked and not falsified

- "The browser never sees the bridge token" holds for the direct credential. No route
  in `bridge/extensions/pi-remote.ts` echoes the `Authorization` header, and the only
  response that carries a bridge token is `handlePair` at `:934`, which is not in the
  allowlist. The claim is still fragile: it depends entirely on F5 (allowlist
  canonicalization) and F1 (same-origin script cannot read the session storage), so it
  should be restated as "the bridge token never leaves the phone provided the allowlist
  is not bypassable".
- The bridge's file route is not itself a traversal hole: `FILE_ITEM_RE` matches one
  segment (`:1212-1213`), the name is `decodeURIComponent`-ed (`:1243-1250`), and
  `isSafeFileName` rejects `/`, `\` and a leading dot (`bridge/lib/files.ts:134-137`).
  The risk F1 describes is the content type, not the path.
- The design's bridge line citations (`:432`, `:564`, `:586`, `:965`, `:1176`,
  `:1218`, `:970`, `:249`, `talks.ts:341` and `:393`) all resolved to the code they
  claim.
