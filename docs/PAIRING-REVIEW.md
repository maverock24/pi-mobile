# Pairing review

Read-only review of the pairing flow and the Tailscale dependency. Code claims cite
`file:line`; Tailscale claims cite Tailscale's own pages.

## 1. The current flow, counted

From a bare machine to a working transcript, the human performs nine things.

One-time prerequisites:

1. Install and sign in to Tailscale on the laptop, and have a tailnet.
2. Install and sign in to Tailscale on the phone, on the same tailnet. Fails if the VPN
   is off, the key expired, or the phone is on a different tailnet.
3. Install pi, then run `bridge/install.sh` to copy the extension into
   `~/.pi/agent/extensions/` (README:21, `bridge/install.sh:20-21`).
4. Install `qrencode` and one image viewer. Nothing checks for these at setup; `/pair`
   fails at run time (`bridge/extensions/pi-remote.ts:1920`, `openImage:760`, `:1960`).

Per pairing:

5. Start pi in the serving pane, or `/reload` (README:27).
6. Run `/pair`. Fails on a non-TUI session (`canServe:481`), a foreign process on the
   port, a loopback bind (`:1907`), a missing `qrencode`, or `tailscale status --json`
   failing, which makes the QR carry a raw `100.x` address the app refuses
   (`pairHost:721-738`). With no image viewer it still writes the PNG, but the human
   must open it by hand (`:1960`).
7. Install the APK from the release.
8. Open Settings > Pairing and tap Scan, granting camera (`Screens.kt:1979`).
9. Scan the QR. Fails on the two-minute expiry (`PAIR_TTL_MS:80`), a wrong code, an
   unreachable `/api/pair`, or the cleartext policy rejecting the host
   (`Diagnostics.kt:79`, `network_security_config.xml:13`).

The documented manual fallback points at a file that is deleted. README:33 and
`Screens.kt:2049` say `cat ~/.config/pi-remote/token`, but the bridge migrates that
legacy file into `tokens.json` and removes it (`pi-remote.ts:158-186`); a
`pi-remote-token` helper is named (`:1960`) but is not in this repo. Eight or more
things can fail across the nine steps.

## 2. Which parts earn their keep

The one-time code with a two-minute TTL and single spend (`mintPairCode:707`,
`handlePair:809`) stops a screenshot or a scanned deep link from being a durable
credential. The QR is written to disk and shown on screen, so that threat is real.
Keep it.

The per-device bearer token (`tokens.json`, `matchToken:207`, `authorized:883`) is the
only gate between a tailnet node or a local process and a root-capable agent
(THREAT-MODEL F2). Tailscale does not replace it. Serve adds `Tailscale-User-Login` and
friends for tailnet traffic and strips client-supplied copies, but they identify a
user, not a device, are absent for tagged devices, and are only safe if the backend is
otherwise unreachable; the guide itself says to listen on localhost and accept that
other local services can tamper. A local process here can reach the loopback port and
forge the header, so the token stays.
(https://tailscale.com/kb/1312/serve)

Keystore encryption (`TokenCrypto.kt:56`) protects a copied preferences file at no user
cost. rotate and revoke (`:1730`, `:1740`) make a leaked root credential recoverable.
The QR carries only the URL and the code, never the token (`Pairing.kt:22-30`); typing a
64-character token is worse. The paste-link path is the only fallback when the camera
is denied. The explicit base URL is a fallback and the app ships no default
(`SettingsStore.kt:16`). All keep.

The loopback twin (`startLoopbackServer:939`) exists only so `tailscale serve` can front
the bridge (THREAT-MODEL F4). With no serve configured it is unused surface, including
an unauthenticated `/api/pair` reachable from any local process (`:966`). It should be
opt-in.

Tailscale is the transport and the reason no port is open to the internet. There is no
simpler replacement.

## 3. Simplification options, ranked

1. Front the bridge with `tailscale serve` and bind loopback only. Removes the
   tailnet-IP bind, the loopback fallback, the `host`/`boundHost` split (`:451`,
   `onBound:1225`), the host firewall service, and the cleartext-DNS trap (F4). Serve
   terminates a real certificate for `<machine>.<tailnet>.ts.net` and requires HTTPS
   certificates enabled in the tailnet. (https://tailscale.com/kb/1312/serve) Security
   is strictly better: TLS, and the tailnet IP stops answering. Cost: the cert toggle is
   currently refused on this tailnet (F4) and serve becomes a service to keep running.
   Mostly eases the human's job.
2. Auto-pair on first run. When the bridge starts with zero tokens, bind and mint a code
   instead of waiting for `/pair` (`:1374`). Removes step 6. Security: a code is live at
   startup, so limit it to the zero-token bootstrap and keep the TTL. Small build.
   Eases the human's job.
3. Fix the documented token path: README:33 and `Screens.kt:2049` should point at
   `tokens.json`, or the named `pi-remote-token` helper should exist. No security cost,
   trivial build. Eases the human's job.
4. Drop the host firewall and keep the tailnet ACL. ACLs are deny-by-default and
   device-scoped; the default with no `acls` section is allow-all, so the ACL must name
   the phone. (https://tailscale.com/kb/1018/acls) Trivial. Barely changes the human's
   job.
5. Remove the Android deep-link intent-filter. The system camera renders `pi-remote://`
   as inert text on this phone (F14), so it is exported surface for little gain; keep
   the paste field.

Address discovery does not help. MagicDNS resolves a name you already know
(https://tailscale.com/kb/1081/magicdns). Tailscale Services still needs an
admin-defined name and a tagged host, and identity headers are absent for tagged
devices (https://tailscale.com/kb/1552/tailscale-services). There is no Tailscale
discovery protocol a phone can query. Discovery adds console steps. I could not
establish any supported way for a third-party Android app to read Tailscale's local
state.

## 4. What must not be simplified

- Do not expose the bridge beyond the tailnet (no Funnel, no `0.0.0.0`).
- Do not remove the bearer token or accept Serve headers as the only gate while any
  local process can reach the port.
- Do not put a durable token in the QR or a deep link.
- Do not drop the TTL, single spend, or auth-failure backoff (`:854`).
- Do not drop `allowBackup=false`, Keystore encryption, or `FLAG_SECURE` on the settings
  screen.
- Do not trust client-supplied identity headers on a loopback-only bind.

## 5. Recommended path

Take option 1, then 2, then 3. Folding the extension install and the dependency check
into one installer, and auto-pairing on first run, removes steps 4 and 6, so nine steps
become seven; the failure classes that go are the cleartext-DNS trap, the host firewall,
and the address fallback. The smallest shippable slice, independent of the blocked HTTPS
toggle, is 3 plus making the loopback twin opt-in behind an env flag
(`PI_REMOTE_NO_LOOPBACK` already exists, `:940`); 2 follows on its own, and 1 ships when
the tailnet cert toggle is enabled.

## Constraint from the human, 2026-10-11

"i would not like to weaken defence but i also would like to simplify."

So take only the options whose security cost is nil. Everything in section 4,
and anything in section 3 that trades a protection for fewer taps, stays. The
test: does it remove a manual step while leaving the protocol underneath
untouched, or does it remove a check, a secret, or an exchange? The first is in
scope, the second is not.
