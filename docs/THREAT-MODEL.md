# Pi Remote — threat model and attack surface

Audit date: 2026-10-04. Scope: the Android client (`maverock24/pi-mobile`), the bridge
extension (`~/.pi/agent/extensions/pi-remote.ts`), the question channel
(`~/.pi/agent/lib/remote-question.ts`), the design-lab helper
(`~/pi-remote-design-lab/serve.py`), and the laptop-side access paths they depend on
(sshd, mosh, the herdr socket).

Every claim below was checked on the machine, not assumed. Commands to reproduce are in
the last section.

## 1. What exists

```
 phone (Tailscale node)                 laptop
 ┌────────────────────┐                ┌───────────────────────────────────────────┐
 │ Pi Remote app      │  WireGuard     │ tailscaled  → 192.0.2.1               │
 │  · token (AES-GCM, │ ─────────────► │                                            │
 │    keystore key)   │  HTTP+SSE      │ pi-remote bridge :8787  (bearer token)     │
 │  · REQUEST_INSTALL │                │   └── pi process, RUNS AS ROOT             │
 │    _PACKAGES       │                │        └── bash/filesystem tools as root   │
 └────────────────────┘                │ question channel: ~/.local/share/pi-remote │
                                       │ herdr socket (pane control)                │
                                       │ sshd :22 (tailnet only, key-only, no keys) │
                                       │ mosh UDP 60000-61000 (tailnet only)        │
                                       │ design-lab helper :8123 (loopback, no auth)│
                                       └───────────────────────────────────────────┘
```

Trust boundaries, in order of what actually protects what:

1. **Tailnet membership** — the only thing between the internet and the bridge. The pcap
   filter is `IPProto [6,17,1,58] → 0.0.0.0/0 all ports`, from 14 tailnet CIDRs: any device
   that joins this tailnet reaches every port, including 8787 and 22.
2. **The bearer token** — the only thing between a tailnet member and a root-capable agent.
3. **Android's app signing** — the only thing between a malicious APK and your phone.

## 2. Assets, ranked by what an attacker gains

| # | Asset | Where | Loss means |
| --- | --- | --- | --- |
| A1 | pi's tool execution | the pi process runs as **root** | Full laptop compromise; every later item is moot |
| A2 | Release keystore **and its password** | `~/.android-keys/pi-mobile.keystore` + `pi-mobile-password.txt`, same directory | Sign a "Pi Remote update" Android accepts: mic, network, APK installs, everything the app may do |
| A3 | Bridge token | `~/.config/pi-remote/token` (0600 maverock24) + encrypted copy on the phone | Drive the agent (A1) from anywhere in the tailnet, and read session paths, cwd, costs, question text |
| A4 | The phone itself | app storage, dictation buffer | A3 plus the phone's other data; the token decrypts without a biometric prompt |
| A5 | Repo and CI secrets | GitHub, Actions | Publish releases, but *not* forge a signed update unless A2 leaks too |

## 3. Adversaries considered

| # | Adversary | Can reach |
| --- | --- | --- |
| T1 | Internet attacker, no tailnet access | Nothing directly; only if a tailnet node is compromised or the ACL is loosened |
| T2 | Hostile network the phone joins (rogue Wi-Fi, hostile carrier DNS) | The phone's DNS and any cleartext the phone emits |
| T3 | Another device in the tailnet (shared node, a friend's device, a compromised node) | Every port on the laptop, including 8787 and 22 |
| T4 | Unprivileged local process on the laptop (malicious npm postinstall, a bad script) | Everything `maverock24` can read or write |
| T5 | A web page open in the laptop's browser | `http://127.0.0.1:*` via cross-site requests |
| T6 | Physical access to the unlocked phone, or a malicious app on it | The app's keystore-encrypted token |

## 4. Findings

### F1 — CRITICAL, fixed during this audit: the design-lab helper accepted cross-site requests and forwarded them into the root agent

`serve.py` had no authentication, no `Origin` check, no `Host` check, and did not require a
JSON content-type. A `POST` with `content-type: text/plain` is a CORS *simple request*, so
no preflight happens, and the helper parsed the body regardless of content type. Any page
open in the laptop's browser could therefore do:

```js
fetch("http://127.0.0.1:8123/api/request", {
  method: "POST", mode: "no-cors",
  headers: { "content-type": "text/plain" },
  body: JSON.stringify({ note: "<instructions for a root-capable agent>", summary: "x" })
});
```

Reproduced: with `Origin: https://evil.example` and `Sec-Fetch-Site: cross-site` the helper
answered `200`, logged the attacker's text, and — on the live instance, pointing at the real
bridge — would have delivered it into the pi session as a prompt. That is remote prompt
injection into a root-capable agent, triggered by a web page.

Fixed: the helper now requires a per-install lab token (0600, injected into its own page and
sent as an `Authorization` header, which a cross-origin *simple* request cannot set), an
allowlisted `Host` (DNS rebinding), an `Origin`/`Sec-Fetch-Site` same-origin check, and
`application/json`. Verified refusals: no auth `401`, rebound host `403`, cross-origin with a
valid token `403`; legitimate same-origin request `200`.

### F2 — HIGH: the agent is root, so the phone's token is effectively a root credential

`pi` on the laptop runs as root (started through `sudo`), so every tool call the agent makes
— `bash`, file writes, package installs — executes as root. The bridge lets a token holder
send arbitrary prompts. There is no approval step, no allowlist, and no sandbox: the token
holder can ask the agent to run anything, and prompt-injected content can do the same.

Compounding it: the bridge token is now readable by any process running as `maverock24`
(it was chowned from `root:root` to `maverock24` so the design-lab helper could read it), and
the herdr socket (`srw------- maverock24`) lets any such process inject keystrokes into that
same root pi with `pane.send_text`. So a user-level compromise escalates to root through the
agent, without ever needing the sudo password.

Recommended: run the bridge-serving pi as an unprivileged user, or in a container/VM with
only the needed paths mounted; treat the token as a root credential in documentation; and
add approval rules for destructive tools.

### F3 — HIGH: the release keystore and its password sit in the same directory

`~/.android-keys/` holds `pi-mobile.keystore` (0600) **and** `pi-mobile-password.txt` (0600).
Both are readable by anything running as `maverock24`. Anyone who gets user-level access can
sign an APK that Android accepts as an update to Pi Remote, then use the app's
`REQUEST_INSTALL_PACKAGES`, `RECORD_AUDIO` and `INTERNET` permissions on the phone. The
self-update path makes this worse than a normal app compromise: the malicious build installs
itself without a trip to GitHub.

Recommended: move the password into a password manager (never a sibling file), keep the
keystore out of the home directory, rotate the key once the new one is in place (note that a
new key means a new app identity for installs), and consider a Play-signed or
hardware-backed distribution path.

### F4 — MEDIUM: cleartext HTTP plus a DNS name means a hostile network can collect the token

The app's default bridge URL is the MagicDNS name
(`http://your-laptop.your-tailnet.ts.net:8787`) and cleartext is whitelisted for
that host in `network_security_config.xml`. If the phone's Tailscale DNS is not in play (VPN
down, MagicDNS disabled, split DNS not applied), that name resolves through the local
resolver — a hostile Wi-Fi or carrier DNS can answer with its own address, and the app will
happily send `Authorization: Bearer <token>` in cleartext to it. The tailnet IP chosen
earlier as the default avoided this, because a route to `192.0.2.1` only exists inside
the tunnel.

Recommended: prefer `tailscale serve` HTTPS with its real certificate (name validation then
binds the identity to the certificate and the DNS attack is void), or use the raw tailnet IP
with cleartext and no DNS dependency, or pin the certificate/SPKI in the app.

### F5 — MEDIUM: any tailnet member can reach the bridge, with no rate limit and no rotation

The ACL allows every protocol and port from the whole tailnet CIDR space, so 8787 and 22 are
open to any node that joins. The token is 32 random bytes, so guessing is not a concern, but
there is no lockout, no per-device token, no rate limiting, and no rotation: one leak is valid
forever. The 256 KB body cap is the only request limit.

Recommended: a tailnet ACL that permits 8787 and 22 only from the phone's node; per-device
tokens with a revoke endpoint; a simple failure backoff; and an audit log of prompts received.

### F6 — MEDIUM: the bridge exposes session detail to any token holder

`GET /api/state` returns cwd, session file path, session id, model, context usage, pid and
bridge host/port; `/api/question` returns question text; `/api/history` returns conversation
content. Fine for a single trusted client, but it is information disclosure if the token
leaks, and it is more than the phone strictly needs to render an answer.

Recommended: return only what the UI uses, and consider dropping absolute paths.

### F7 — MEDIUM: prompt injection is now remotely triggerable, and the agent holds root plus the internet

The app and the bridge widen the input surface for prompt injection (dictation, and the
planned image attachments and share target). The agent already reads untrusted content
(repo files, web pages) while holding root and network access, which is the classic
private-data + untrusted-content + exfiltration combination. The bridge does not create this
risk, but it removes the human at the keyboard who would otherwise notice.

Recommended: sandbox or containerise the agent for untrusted repositories, restrict which
tools are enabled, keep secrets out of the agent's environment, and add approvals for
destructive operations.

### F8 — LOW-MEDIUM: the phone-side token is not bound to user authentication

`TokenCrypto` is implemented well (AES-256-GCM, Android Keystore key, random IV per
encryption, no plaintext fallback), but the key is created with
`setUserAuthenticationRequired(false)`, so an unlocked (or extracted) device decrypts the
token without a biometric prompt. There is no app lock, no `FLAG_SECURE` on the settings
screen, and Android's `allowBackup=false` is the only protection for the preferences file.

Recommended: optional biometric gate before sending prompts, `FLAG_SECURE` on screens that
show the token, and a short auto-lock for the app.

### F9 — LOW: self-update trusts a manifest from the same channel as the APK

`latest.json` provides both the download URL and the expected sha256, so it adds integrity
against corruption, not against a compromised publisher; Android's signature check is the
real gate (and it holds only as long as F3 does). CI actions were pinned to SHAs, which
removes tag-hijacking as a supply-chain path.

Recommended: keep the keystore out of the same trust domain as the repo where possible, and
publish the APK fingerprint in the repo so a sideloaded build can be compared by hand.

### F10 — LOW: nothing that will be shown on a lock screen should be sensitive by default

Notification support (production item 0.3/0.4) will put answer text and question options on
the lock screen. Answers routinely contain paths, tokens and customer data.

Recommended: when notifications land, set `VISIBILITY_PRIVATE` with a redacted public
version, and never include option text that leaks context.

### F11 — LOW: a root-owned question directory blocks non-root sessions

`~/.local/share/pi-remote` is `root:root 0700` because a root pi created it first. A
user-run pi cannot publish a question there, so the phone cannot answer that session's
questions.

Recommended: create the directory with the owning user in mind, or fall back to a per-user
path (`$XDG_RUNTIME_DIR`).

## 5. What already holds up

| Property | Evidence |
| --- | --- |
| Token entropy and handling | 32 random bytes, 0600, timing-safe comparison, never logged |
| Token at rest on the phone | AES-256-GCM, keystore-bound key, IV per encryption, no plaintext fallback |
| Bridge binding | Tailnet address + loopback only, never `0.0.0.0` |
| sshd | Tailnet-bound, `PasswordAuthentication no`, `AuthenticationMethods publickey`, `AllowUsers maverock24`; `authorized_keys` is currently **empty**, so there is no key-based path today |
| mosh | Server pinned to the tailnet address, UDP bound there only |
| App manifest | `allowBackup=false`, one exported component (the launcher activity), FileProvider not exported, no logging calls anywhere |
| Cleartext policy | Disabled by default; exceptions limited to the tailnet host and loopback |
| Release integrity | Signed APKs, sha256 in the manifest, pinned CI actions |
| Bridge blast radius | The HTTP API can only send prompts; it cannot run arbitrary commands or keystrokes directly, so every action still goes through the agent's own tool layer |

## 6. Attack scenarios, walked through

**S1 — hostile Wi-Fi, DNS spoof, token theft (T2).** Phone joins a café network with
Tailscale off. The app still tries the MagicDNS name; the attacker's DNS answers with their
own address; the app sends `Authorization: Bearer …` in cleartext; the token is now theirs.
They still need tailnet access to use it, but a token plus any later foothold (a compromised
node, a shared tailnet device) equals root. Mitigation: F4.

**S2 — malicious page in the laptop browser (T5).** Previously: open any page that runs the
fetch above while the design lab was running, and an attacker's text becomes a prompt to a
root agent. Fixed as F1. This is why a loopback service is not "safe by being local".

**S3 — user-level code on the laptop (T4).** A malicious npm postinstall reads
`~/.config/pi-remote/token`, writes a fake `question-pending.json`, and posts a prompt
through the bridge. The agent runs it as root. Mitigation: F2, F3, F5, F11.

**S4 — stolen unlocked phone (T6).** The thief opens the app and prompts the agent; the
token decrypts without a biometric prompt. If the keystore also leaked (F3), they can push a
malicious update. Mitigation: F8, F3.

**S5 — tailnet member (T3).** A friend's device or a compromised node scans the tailnet,
finds 8787 and 22, and can attempt both. The token makes 8787 impractical to brute force, and
sshd has no keys, but the port being open to every member means any *other* service on the
tailnet becomes the easier target. Mitigation: F5.

## 7. Fix order

1. Rotate the bridge token and delete the design-lab helper unless it is in use (or keep the
   hardened one; its token is now 0600 and only its own page carries it).
2. Move the keystore password out of `~/.android-keys/` (F3).
3. Restrict the tailnet ACL for 8787 and 22 to the phone's node (F5).
4. Run pi as an unprivileged user for bridge-served sessions, or accept the token as a root
   credential in writing (F2).
5. Switch the app to `tailscale serve` HTTPS, or use the raw tailnet IP, so no cleartext is
   sent to a name that DNS can lie about (F4).
6. Add per-device tokens with revocation, a failure backoff, and prompt audit logging (F5, F6).
7. Add the biometric gate and `FLAG_SECURE` (F8).
8. When notifications arrive, make them private by default (F10).

## 8. How to reproduce the checks

```bash
# what can a tailnet member reach?
sudo tailscale debug netmap | jq '.PacketFilter[].IPProto, .PacketFilter[].Dsts'

# is the bridge tailnet-bound only?
ss -tlnp | grep 8787

# who can read the token, the keystore, its password?
ls -l ~/.config/pi-remote/ ~/.android-keys/ ~/.local/share/pi-remote

# can a user-level process inject keystrokes into the root agent?
ls -l ~/.config/herdr/herdr.sock      # srw------- maverock24

# does the lab helper refuse a cross-site request? (expect 401/403, never 200)
curl -i -X POST http://127.0.0.1:8123/api/request \
  -H 'origin: https://evil.example' -H 'sec-fetch-site: cross-site' \
  -H 'content-type: text/plain' -d '{"note":"x"}'

# is sshd key-only and tailnet-only, and are there any keys at all?
sudo sshd -T | grep -E 'passwordauth|permitrootlogin|allowusers'
wc -c ~/.ssh/authorized_keys

# does the app ask for anything unexpected?
aapt2 dump permissions pi-remote-*.apk 2>/dev/null || unzip -p pi-remote-*.apk AndroidManifest.xml | strings -e l | grep -i permission
```

## 9. References

- OWASP MASVS / MASTG, network and platform interaction — https://mas.owasp.org/MASVS/
- Android network security configuration (cleartext, domain matching) — https://developer.android.com/privacy-and-security/security-config
- Android Keystore, user-auth-bound keys — https://developer.android.com/privacy-and-security/keystore
- Tailscale ACLs and tailnet policy file — https://tailscale.com/kb/1018/acls
- Tailscale Serve, HTTPS certificates — https://tailscale.com/kb/1223/tailscale-serve
- Cross-site requests and simple-request rules (why `text/plain` skips preflight) — https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS
- DNS rebinding against loopback services — https://en.wikipedia.org/wiki/DNS_rebinding
- Prompt injection and the "lethal trifecta" — https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/
- Termux process limits and background killing — https://github.com/termux/termux-app

---

## 10. Remediation status (2026-10-04, after the review)

| Finding | Status | What changed |
| --- | --- | --- |
| F1 design-lab helper cross-site | **Fixed** | Lab token (0600, injected only into its own page, sent as `Authorization`), `Host` allowlist for DNS rebinding, `Origin` and `Sec-Fetch-Site` same-origin check, `application/json` required. Verified: no auth 401, rebound host 403, cross-origin with valid token 403, legitimate same-origin 200 |
| F2 agent runs as root | **Documented, open** | Needs a workflow decision: run bridge-served pi as an unprivileged user, or accept the token as a root credential in writing. The token is no longer world-readable by accident, and the fs token is now labeled per device so it can be revoked |
| F3 keystore next to its password | **Fixed** | Password moved to `/root/pi-mobile-keys/keystore-password.txt` (root-only, user read denied). The keystore alone is useless now. GitHub Actions keeps its own copy for releases. A key rotation is still advisable once a new distribution path exists |
| F4 cleartext to a DNS name | **Partly fixed** | The bridge now also listens on loopback so `tailscale serve` can front it with a real certificate. Enabling HTTPS certificates is a tailnet-console toggle, which is why this is not fully closed |
| F5 any tailnet member can reach everything | **Partly fixed** | Bridge: per-device tokens in `tokens.json`, `/remote rotate` and `/remote revoke`, failed-auth backoff (429 after 5 attempts), and prompt/abort/rotation entries in `audit.log`. Host: `nftables` rule drops tailnet traffic to 8787 and 22 from anything but the phone, persisted by `pi-remote-firewall.service`. A tailnet ACL rule remains the belt-and-braces version |
| F6 `/api/state` discloses paths | **Open** | Audit logging and per-device labels landed; the state payload still returns absolute session paths. Cheap to trim when the UI is next touched |
| F7 prompt injection with root and internet | **Documented, open** | Structural: needs sandboxing or tool allowlists for untrusted repositories |
| F8 token not user-auth bound | **Partly fixed** | Settings is now `FLAG_SECURE`, so the token cannot be captured in a screenshot or the recents thumbnail. A biometric gate still needs on-device testing before it ships |
| F9 update manifest in the same channel as the APK | **Accepted** | Android's signature check is the real gate and now depends on F3, which is fixed |
| F11 root-owned question directory | **Fixed** | `~/.config/pi-remote` and `~/.local/share/pi-remote` are owned by the user (0700); root sessions still have access, so both can publish questions and answers |

Also fixed while in there: the extension no longer fails to load when the config
directory is unwritable (that used to take pi down with an `EACCES` at load time), an
empty token set disables the bridge instead of authorising everything, and `tick()` no
longer references a token captured at load, which had silently broken bridge startup on
the first version of this change.

### Still on you

1. **Re-pair the phone.** The token was rotated to a per-device `phone` entry, so the app
   needs the new value: run `pi-remote-token` on the laptop, paste it into Settings, then
   Test. Rotation takes effect for the bridge when that pane reloads the extension.
2. **Rotate the GitHub Copilot tokens** printed into an earlier session transcript.
3. **Optional ACL tightening** (the host firewall already enforces the same intent):
   replace the default allow-all with per-device rules, for example
   ```json
   { "action": "accept", "src": ["nokia-xr20"], "dst": ["your-laptop:*"] },
   { "action": "accept", "src": ["your-laptop"], "dst": ["nokia-xr20:*"] }
   ```
   Warning: this blocks every other tailnet device from the laptop. Extend the list before
   adding devices, or you will lock yourself out.
4. **HTTPS**: enable HTTPS certificates in the tailnet console, then
   `sudo tailscale serve --bg --https=443 http://127.0.0.1:8787` and switch the app URL to
   `https://your-laptop.your-tailnet.ts.net`.
5. **Biometric gate** and **unprivileged pi** remain open by design decision, not by oversight.
