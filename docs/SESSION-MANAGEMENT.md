# Session management

Status: implemented. Section 1 describes what was broken before it landed, and
sections 2 to 8 are what runs now. Decisions in
[adr/0001-port-bind-is-the-authority.md](adr/0001-port-bind-is-the-authority.md)
and [adr/0002-bridge-source-in-this-repo.md](adr/0002-bridge-source-in-this-repo.md).
Terms are defined in [../GLOSSARY.md](../GLOSSARY.md).

What the phone attaches to, who serves it, and what happens when that changes.

## 1. What is broken

Line references are to `~/.pi/agent/extensions/pi-remote.ts`, the revision
installed on 2026-10-05.

| id | symptom | cause |
| --- | --- | --- |
| S1 | `/remote claim` reports success and nothing happens. The pane keeps saying `claimed; binding`, forever. | `claim` writes the lease (`:1199`) then tries to bind; the old owner's heartbeat rewrites the lease unconditionally (`:947`) and reverts it. The claimant stops writing and never binds again. |
| S2 | A claim in one pane stops a bridge serving a different address. | The lease path is derived from the token file only (`:66-72`), so it is not keyed by host or port. |
| S3 | During a normal handover the address is refused for up to about ten seconds. | The old owner releases on its own tick (`:984-987`), the claimant only retries on its next tick (`:936-940`, `:1083`). |
| S4 | A port held by a non-pi process produces `idle` with no reason, and `/pair` blames "another pi session". | `EADDRINUSE` is swallowed silently (`:936-940`); `portHolder` matches any process (`:535`). |
| S5 | The phone is pointed at an address nothing is listening on. | On `EADDRNOTAVAIL` the bridge binds `127.0.0.1` (`:931-935`) but `/api/state` and `/remote` still report the configured host (`:432-433`), and `/pair` mints the tailnet URL from `pairHost()` (`:482`, `:1293`). |
| S6 | Two servers answer on different addresses at once. | One may bind the tailnet address while the other falls back to loopback; `handleRequest` never checks ownership (`:699`). |
| S7 | The phone and the human cannot tell "the same session" from "another pane in the same folder". | The lease carries no session id or name (`:288-295`); two panes in one repo share a `cwd`. |
| S8 | After an app restart on a phone that was showing one session, the app silently attaches to whatever is serving now and the user is never told. | `attachedSessionId` is memory-only (`ui/ChatViewModel.kt:160,334`); `SettingsStore` persists only URL, token and appearance (`data/SettingsStore.kt:16-48`). |
| S9 | Messages that do not match behaviour. | `/remote release` says "released bridge" and immediately re-claims (`:1218-1219`); `/remote claim` mentions a release the old owner performs by itself (`:1204`); `rotate` and `revoke` lowercase the label and `revoke` reports success for an unknown one (`:1222-1244`); `/remote` advertises a `url` subcommand that does not exist (`:1193`). |

S1, S3 and S4 are inferred from the installed source. The owner serving while
this was written was running an older revision, so its release behaviour is read
from the file, not observed. S8 is verified in the app source.

## 2. What the phone should see

One command on the laptop:

```
/pi-remote
```

Run it in the session that should serve the phone. That session becomes the owner
immediately. Every other session serving that address drops within milliseconds.
The phone follows on its own: it swaps the transcript to the new session and
loads that session's history, and one line says which session it moved to.

No second command, no attach button, no confirmation on the phone. A prompt typed
in the moment the bridge changes hands is refused by the bridge with 409 and left
in the composer, rather than delivered to the wrong session.

## 3. Owner states

One state machine per bridge address.

| state | meaning | leaves on |
| --- | --- | --- |
| `stopped` | this session does not try to serve | `/pi-remote`, or session start |
| `waiting` | ticking, another owner holds the address | lease stale, owner gone, takeover record |
| `binding` | a bind is in flight | bind ok, or `EADDRINUSE` |
| `serving` | holds the address and the lease | takeover naming another session, release, shutdown |
| `blocked` | a foreign process holds the address | the address becomes free |

There is no `yielding` state. The losing owner closes its server and returns to
`waiting`; the new owner is retrying every 50 ms, so the address is free and
rebound before anyone notices the gap.

## 4. Files

All in `~/.config/pi-remote`. The lease is one file per address:

```
owner-<host>-<port>.json
{
  "pid": 401331, "host": "100.78.153.24", "port": 8787,
  "boundHost": "100.78.153.24", "boundPort": 8787,
  "sessionId": "01a10d00-95ca-7e60-bcfe-0b17046364dc",
  "sessionName": "pi-mobile", "cwd": "/home/maverock24/github/pi-mobile",
  "mode": "tui", "sessionFile": "...jsonl",
  "claimedAt": "...", "heartbeatAt": "..."
}
```

`boundHost` is the address the bind actually took, which differs from `host`
after the loopback fallback. That is how S5 gets fixed.

The takeover record is one file per address, written by `/pi-remote`:

```
takeover-<host>-<port>.json
{ "pid": 401331, "at": "...", "nonce": "..." }
```

Valid for five seconds. It is never the lease, and writing it is the only way one
session tells another to drop.

## 5. Rules

1. The bind decides. The lease is written only after a bind succeeds, never
   before.
2. A heartbeat refreshes only a lease whose `pid` is this process's own. Anything
   else means this process does not own the address.
3. A serving session that sees a fresh takeover record naming another pid closes
   its server at once and does not touch the lease. It returns to `waiting`.
4. The reaction to a takeover record is a `fs.watch` on the state directory. The
   tick re-checks the takeover record as a backstop, because a watch event can be
   coalesced, arrive with a null filename, or fire while a bind is in flight. The
   tick stays for crash recovery and the heartbeat as well.
5. `/pi-remote` writes the takeover record, then retries the bind every 50 ms for
   five seconds, then reports the real outcome and the pid that blocked it.
6. `/pi-remote` in the session that is already serving says so and does nothing
   else.
7. Lease and takeover records are keyed by address, so two panes may serve two
   addresses and never interfere (S2, S6).
8. The lease records the address actually bound, and any report of what is
   serving uses that address (S5).
9. `/remote release` stops the tick timer, clears its own lease and closes its
   server. It does not restart the timer (`:1219`).
10. A serving session never answers a request for another session: `sessionId` on
    a mutating call that does not match gets 409 and no side effect.

## 6. Commands and messages

| command | outcome | message |
| --- | --- | --- |
| `/pi-remote` | bound | `serving H:P as <cwd> <name>` |
| `/pi-remote` | already owner | `already serving H:P` |
| `/pi-remote` | took over, bind blocked | `could not bind H:P after 5s · held by pid N (<comm>)` |
| `/pi-remote` | took over, loopback fallback | `serving 127.0.0.1:P · H is unavailable, the phone cannot reach this` |
| `/remote` | serving | `serving H:P as <session> · N device tokens · audit <path>` |
| `/remote` | waiting | `idle · another pi session holds H:P (pid N, <cwd>)` |
| `/remote` | blocked | `idle · H:P is held by pid N (<comm>), not a pi session` |
| `/remote release` | always | `released H:P, not serving` |
| `/remote rotate <label>` | always | `new token for "<label>": <token>` |
| `/remote revoke <label>` | unknown label | `no device labelled "<label>"; devices: ...` |
| `/pair` | nobody serving | takes over, binds, mints a code for the bound address |
| `/pair` | another session serving | takes over, binds, mints, having said `took the bridge over from pid N` |
| `/pair` | foreign holder | `H:P is held by pid N (<comm>), not a pi session` |
| `/pair` | only loopback bound | refuses: `the phone cannot reach 127.0.0.1` |

`/pair` runs the same takeover as `/pi-remote` before minting a code, so pairing
from a second pane does not fail with "run /pair there".

## 7. Bridge API

- `GET /api/state` gains `boundHost`, `boundPort`, and `ownerPid` (the pid that
  holds the bind, not the last writer). It keeps `sessionId`, `sessionName` and
  `cwd`.
- `POST /api/prompt`, `/api/abort` and `/api/answer` accept an optional
  `sessionId`. When present and it does not match the serving session, the bridge
  answers 409 and acts on nothing.
- Rollout order: bridge first, tolerating a missing field, because every
  installed phone omits it. The app starts sending it in the next release, and
  only then can the field become required.

## 8. App changes

Landed. The app follows the bridge rather than holding a screen and asking.

- `SettingsStore` persists the pinned session id and name, so a restart keeps
  the pin instead of adopting whatever is serving.
- On connect, and on every `state` frame, the app adopts whatever the bridge
  serves: it writes the pin, clears the transcript and the pending question,
  loads that session's history from `/api/history`, and shows one line naming
  the session it moved to. That line goes when the transcript arrives.
- The foreign-session bar is gone. `data/SessionNotice`, `ui/Screens.kt`'s
  `ForeignSessionBar` and `ui/ChatViewModel.kt`'s `otherSession` gating were
  removed with it.
- The composer is never blocked because of a session change; a stale send is
  caught by the 409, which puts the prompt back in the composer rather than
  letting it vanish with the transcript that is about to go.
- On 409 the app keeps the work, re-reads state, and reports in one line which
  session the bridge moved to.
- The SSE stream reconnects with 250 ms, 500 ms, 1 s, then 3 s, so a handover
  appears at once. The three second poll stays as the safety net.
- `prompt`, `answer` and `abort` send the pinned session id
  (`net/PiRemoteClient.kt`), which is what arms the bridge's 409 in section 7.

## 9. Failure semantics

| event | behaviour |
| --- | --- |
| owner dies, `kill -9` | the lease goes stale within 30 s, or a successor takes over at once because `kill(pid, 0)` throws. No command needed. |
| owner exits cleanly | `session_shutdown` removes its lease (`:1123-1132`). The next `/pi-remote` or tick binds. |
| foreign process holds the address | `blocked`, reported with pid and command name. `/pair` refuses. |
| tailnet address unavailable | bind loopback, report it as the bound address, refuse to pair. |
| two panes on purpose | different `PI_REMOTE_PORT`. Leases and takeover records are per address. |
| takeover while the phone is typing | the bridge answers 409, the draft stays in the composer. |
| a session still running the old revision | it reads and writes `owner.json`, which the new code ignores, so it stays `idle`. It can still grab a free address. Restart it. |
| pid recycled onto an unrelated process | only the 30 s stale window protects this. Optional `flock` removes it. |

## 10. Tests

Neither half has tests. `app/src` holds only `main`, so the app's history
parsing and session adoption are uncovered too. The extension's rules above are
decisions about two records and
one bind, so the parts worth unit testing are small: lease freshness, whether a
lease may be refreshed by this pid, takeover record validity, and the state
transitions. That needs a runner decision (`node --test` with a TypeScript loader
is the cheapest) before any of it is written.

Manual checks, with two panes and `PI_REMOTE_ALLOW_NON_TUI=1` for the harness:

1. `/pi-remote` in the second pane. Expect a handover under a second, the first
   pane saying it stopped, and the phone showing the new session with its
   history.
2. Widen the revert window with `PI_REMOTE_HEARTBEAT_MS=1000` and repeat. S1 must
   not reproduce.
3. `kill -9` the owner. Expect a successor within one tick with no command run.
4. Bind a foreign listener on the tailnet address, then `/pi-remote` and `/pair`.
   Expect `blocked` naming the pid, and a refusal.
5. `/pi-remote` in a pane with a different `PI_REMOTE_PORT`. The first bridge must
   keep serving.
6. Restart the phone app while another session serves. Expect the transcript to
   follow it and a line saying so, not silence.
7. Type into the composer, run `/pi-remote` in another pane before sending, then
   send. Expect a 409 and the draft kept.

## 11. Open questions

- Should several sessions be reachable at once, with a picker in the app? That is
  the one-port-per-session path in ADR 0001, and it changes the app more than
  anything here.
- Is `flock` on the lease worth adding to kill the 30 s stale window and the
  pid-recycling case, or is bind plus the pid check enough?
