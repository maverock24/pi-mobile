# WLAN web access for Pi Remote

Analysis and interview. Read-only pass on the sibling checkout
`/home/maverock24/github/pi-mobile` (branch `qrspi/ux-remediation-w3`, clean) and on
this worktree `/home/maverock24/github/pi-mobile-keys` (branch `main`, clean). Nothing
was committed and nothing was written outside this file.

## The request, verbatim

> i want to build a new feature. The pi remote shuold allow to connect from another
> computer via WLAN so any close computer nearby could start using the app via web
> browser while being connected to the phone via WLAN. Analyse and interview me

The phrase is ambiguous in a way that decides the build. "start using the app via web
browser" points at a browser client. "while being connected to the phone via WLAN"
points at the phone as the network hub. Those are not the same feature, so the first
interview question resolves it.

## 1. What exists today

Facts checked in the source, not assumed.

**How the phone reaches the bridge.** The phone is a Tailscale node. It opens
`http://<laptop>.<tailnet>.ts.net:8787` over WireGuard. README "How it fits together"
puts it plainly: "Everything travels over Tailscale: nothing is exposed to the
internet, and no port is opened on the LAN." THREAT-MODEL section 1 draws the same
picture and names the three trust boundaries: tailnet membership, then the bearer
token, then app signing.

**What binds where.** The bridge binds one address, chosen by `PI_REMOTE_HOST`, whose
built-in default is the tailnet IP `100.78.153.24` (`pi-remote.ts:108`, `:147`).
`bindAddress` falls back to `127.0.0.1` only when the configured host is unavailable
(`EADDRNOTAVAIL`, `:1798-1813`). A second listener exists on `127.0.0.1` only so
`tailscale serve` can front the bridge with a real certificate
(`startLoopbackServer`, `:1176-1192`; PAIRING-REVIEW section 2). Nothing binds
`0.0.0.0`, and the README security notes say not to. So there is no LAN listener
today, and no configured path that produces one.

**What authenticates it.** One bearer token per device, kept in `tokens.json` beside
the legacy token file (`tokensPath` `:179`, `loadTokens` `:200`, `matchToken` `:249`).
Every route except `/api/pair` returns 401 without a valid token (`authorized` `:970`,
gate at `:1218-1222`). Comparison is timing-safe, rotation is live, and five failures
from one address trigger a lockout. `/api/pair` is the single unauthenticated route
(`:1203`, `handlePair` `:896`); it trades a single-use 16-byte code with a two-minute
TTL for a device token. The QR carries the address and the code, never the token.

**Whether anything already speaks to a browser.** No. The bridge answers JSON and SSE
only. There is no HTML page and no static asset serving anywhere in `bridge/`. The only
`text/html` mapping is a content type for previewing a library file, not a UI
(`bridge/lib/files.ts:120-121`). There is no CORS handling in the bridge at all.

One browser-shaped concession exists: the one-off talk download link puts a scoped key
in the query string, with the code comment saying "a browser cannot send an
Authorization header" (`:1208-1215`, `handleTalkLink` `:1145`). That key names one
talk, is single-use and expires, and the bearer token is still never a query
parameter. It is a precedent for how a browser spends a credential, not a UI.

**Multiple clients.** Already supported at the socket level: the bridge keeps a `Set`
of SSE responses (`:432`) and the pane footer says "N phones connected" (`:530`). A
browser would be one more client, not a new connection model.

**The security frame the docs already set.** THREAT-MODEL F2 says the token is
effectively a root credential because pi runs as root. F4 says cleartext HTTP to a DNS
name is an open finding. F5 says any tailnet member can reach the bridge. F1 is the
times the project already got bitten by a local helper that accepted cross-site
requests into the root agent. PAIRING-REVIEW section 4 says "Do not expose the bridge
beyond the tailnet (no Funnel, no `0.0.0.0`)". PRODUCTION-READINESS "What not to do"
repeats it. The human's standing constraint, recorded at the end of PAIRING-REVIEW on
2026-10-11, is "i would not like to weaken defence".

## 2. The three readings

**Reading A: the browser is a new client, the transport stays as it is.** A web UI is
served by the bridge; a nearby computer opens it. The phone remains a client, unchanged.

**Reading B: the browser UI, plus a LAN listener on the laptop.** Same web UI, but the
bridge also binds the laptop's WLAN address so any computer on that network can reach
it without Tailscale.

**Reading C: the phone becomes the network hub.** The Android app serves the page and
relays to the bridge over Tailscale. A nearby computer joins the phone's hotspot or the
same WLAN and opens the page at the phone's address. This is the only reading in which
"connected to the phone via WLAN" is literally true and the browser needs nothing else.

## 3. Architectures, with what each costs and gives away

### A. Bridge serves a web UI, transport unchanged

The bridge gains static asset serving plus browser-friendly routes, and a web client is
written. The bind stays `PI_REMOTE_HOST`, the token model stays per device.

Reuses: the whole JSON and SSE API, session ownership, the question channel, the
audit log, the failure backoff. The browser is just another client of a protocol that
already supports several.

Cost: the web client is the bulk. Static serving is small. No network change, no
firewall change, no new native code.

Gives away: nothing new, provided the browser is itself on the tailnet. The catch is
literal: a computer that is not a tailnet node cannot reach it, so it does not satisfy
"any close computer nearby". The honest version of A is "ask the nearby computer to
join the tailnet", which is a setup step for each machine. If that is acceptable, A is
the only option here that does not weaken defence at all.

### B. Add a LAN listener on the laptop

The bridge binds the laptop's WLAN address (or a second listener) so any device on the
same network reaches the page with no Tailscale. This is what "any close computer
nearby" most directly asks for.

Reuses: everything in A, plus nothing else.

Cost: a bind address and a firewall rule on top of A. The web client work is the same.

Gives away: the bridge leaves the tailnet. On a shared WLAN the page is reachable by
every device on it, cleartext HTTP is the default, and the bearer token is the only
gate. The threat model already treats that token as a root credential (F2) and already
counts cleartext to a name as an open finding (F4); a LAN bind makes both apply to
neighbours rather than tailnet members. F5 ("any tailnet member") becomes "anyone on
the WLAN". The host firewall rule that today drops tailnet traffic to 8787 from
anything but the phone would not cover a LAN bind unless a matching rule is added. This
is the largest attack-surface increase of the three and the one the docs warn against.
If it is ever taken, TLS and a separate browser credential are the minimum, not
optional extras.

### C. The phone hosts the UI and relays

The Android app runs a small HTTP server, ships the web bundle, and proxies to the
bridge over its existing Tailscale link with the token it already holds. A nearby
computer joins the phone's hotspot or the same WLAN and opens the page at the phone.

Reuses: the phone's device token and Tailscale link; the bridge API. The laptop is
untouched.

Cost: large and native. The app is a pure OkHttp client today (Compose, OkHttp, media3
are the only dependencies in `app/build.gradle.kts`; there is no server code in
`app/src/main`). Android has no first-class always-on HTTP server for an app, and a
local-only hotspot is permission-gated (`WifiManager.startLocalOnlyHotspot`) and varies
by OEM. The build needs a server, a shipped web bundle, and a proxy, plus the Android
background-process work that SESSION-MANAGEMENT and PRODUCTION-READINESS 0.2 already
flag as unsolved.

Gives away: the token never leaves the phone, which is better than B. But the phone now
runs a network service on a network it does not control, and that is exactly the shape
of the F1 bug: a local server that forwards into a root-capable agent. The browser
would drive root through the phone. The phone's WLAN and Tailscale must also coexist
while the relay runs.

### Ranked

1. **A** if "the computer joins the tailnet" is an acceptable step. No new exposure.
2. **C** if "any nearby computer, no Tailscale" is a hard requirement and a large
   native build is acceptable. New surface on the phone, token stays off the browser.
3. **B** only with TLS and a separate browser credential, and only if the network is
   trusted. As described it is the weakest posture.

## 4. Is the browser UI new work, or is there something to reuse?

The web UI is new. Nothing in this repo renders a page. The pieces to note:

- `~/pi-remote-design-lab/` holds `design-lab.html`, `tactile-lab.html` and `serve.py`.
  It is a design sketch, and `serve.py` was the helper with the F1 cross-site hole.
  Not a base.
- `/home/maverock24/github/mobile-media-app` is a SvelteKit + Capacitor media hub
  (`package.json`: Svelte 5, `@sveltejs/adapter-static`, `@capacitor/*`). It is the
  human's own precedent for one codebase that builds to the browser and to Android, and
  its `adapter-static` output is the shape a bridge-served UI would ship. It has no
  bridge client and a different domain (decks, player, Drive), so it offers a pattern,
  not code.
- The bridge API is plain JSON plus SSE behind a bearer token, so a browser client is a
  normal SPA against that API. The real cost is parity: prompt, abort, answer, question
  widgets, history, events, and the newer talks and files routes if the browser is a
  full client. A prompt-only or read-only first slice is much smaller.
- The app's UI is Compose. No Android view code crosses into a browser. What crosses is
  the protocol.

## 5. What I would need before writing a design

- Which reading above is meant (A, B, or C).
- Which computer serves the page (laptop bridge, or phone app) and which one the
  browser actually opens.
- The phone's role: same client as today, access point, or relay.
- What happens when the browser's network cannot reach the bridge (no Tailscale, a
  different WLAN). Fail closed, or relay through the phone.
- Who is allowed in, and with what credential (the existing per-device token, a
  separate browser token, or tailnet identity).
- Whether the web UI is full parity or a first slice, and which slice.
- Whether cleartext on a LAN is ever acceptable, or TLS is a hard requirement. The
  docs already treat cleartext as a defect (F4), so this is likely a hard requirement.
- Whether the browser may mutate the session (prompt, abort, answer) or read only. The
  token gives full control either way, so a read-only requirement means a second
  credential, not just a smaller UI.

## 6. Unanswered questions for the human

The interview was never held. `questionnaire` and `question` both answer
`UI not available (running in non-interactive mode)`; the retry in a later turn
failed the same way. These questions are therefore **unanswered**. No architecture
has been chosen, and nothing in section 3 should be read as decided. The concrete
options below are the ones to put to the human when the session is interactive.

**Q1. Which shape is the feature?**
- A: web UI on the bridge, transport unchanged; phones and nearby computers are all
  clients.
- B: web UI plus a LAN listener on the laptop; any computer on the WLAN, no
  Tailscale.
- C: the phone is the hub and serves the page; the nearby computer joins the
  phone's hotspot or WLAN, and the phone relays to the bridge over Tailscale.
- Something else.

**Q2. Which machine serves the web page?**
- The laptop bridge.
- The Android app.
- Either, decided in design.

**Q3. What does the phone do in the new setup?**
- Same as today, just another client.
- Access point: it hands out the WLAN.
- Relay: the browser talks to the phone, the phone talks to the bridge.
- No phone involvement in the new path.

**Q4. What happens when the browser's network cannot reach the bridge?**
- Refuse; the browser must be on the tailnet or the same LAN.
- Relay through the phone so the browser only needs the phone.
- Open a public path (Funnel or a port forward), which the docs forbid.

**Q5. What credential lets a browser in?**
- Reuse the per-device bearer token.
- A separate web token or pairing code, revocable on its own.
- Tailscale identity only; the browser must be on the tailnet.
- No extra auth on the LAN, which is the rejected option.

## 8. Answers from the human

**None. The questions in section 6 are unanswered.** The input tool could not run,
so no architecture was picked on the human's behalf.

## 7. Options considered and rejected

- **Tailscale Funnel or any public bind.** Rejected. PAIRING-REVIEW section 4 and
  PRODUCTION-READINESS "What not to do" both forbid exposing the bridge beyond the
  tailnet.
- **Bind `0.0.0.0`.** Rejected. The README security notes say not to, and THREAT-MODEL
  section 5 records the bind as tailnet plus loopback only.
- **Reuse the design-lab HTML as the web UI.** Rejected. It is a sketch, and its helper
  is the F1 bug the project already had to fix.
- **Serve the UI from a separate origin without CORS.** Rejected. Missing CORS is the
  same class as F1, and cross-site requests to a local bridge are the documented
  failure mode.
- **Put a durable token in a URL or QR.** Rejected. The talk link shows the project
  already scopes one-off, single-use keys and keeps the bearer token out of query
  strings.
- **Any LAN client with no extra auth.** Rejected. The token is the only gate, and the
  threat model calls it a root credential (F2, F5).
- **Reuse the Android Compose UI in a browser.** Not possible. Only the protocol
  carries over.
