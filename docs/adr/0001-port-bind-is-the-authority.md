# The bind on the port decides who owns the bridge

Status: proposed. Revised 2026-10-05: the claim/takeover dance is gone, one
command switches the bridge and the reaction to it is a file watch, not a tick.

The bridge decides who serves it from one shared file,
`~/.config/pi-remote/owner.json`, which any pi process may write and which the
serving process's heartbeat rewrites whole every ten seconds
(`pi-remote.ts:947`). The only thing that really serialises access is the bind on
`host:port`, so the file and the truth disagree often enough to matter: a
`/remote claim` is written before the claimant binds (`:1199`) and can be erased
by the old owner's heartbeat, the file is not keyed by address so a claim in one
pane can stop a bridge serving another port, and a handover leaves the address
unserved for up to two tick intervals.

We decided the successful bind is the only authority, and that taking the bridge
over is one command, `/pi-remote`, run in the session that should serve. The
record becomes a lease keyed by address and written only after a bind succeeds. A
heartbeat refreshes only a lease it owns, so it can never revert anything. The
command writes a takeover record instead of the lease, every session watches the
state directory, and any session serving that address closes its server as soon
as it sees a takeover naming someone else. The new owner retries the bind every
50 ms until the address is free, so the switch is immediate rather than one tick
away. The phone follows the switch on its own and loads the new session's
history. Details are in [../SESSION-MANAGEMENT.md](../SESSION-MANAGEMENT.md).

## Considered options

- Fix only the heartbeat revert. Cheapest, leaves the address keying bug and the
  unserved window.
- Claim plus acknowledgement handshake. This was the first design. Rejected once
  the reaction path became a watch: the handshake existed to cover a reaction
  that could take ten seconds, and with a watch it is machinery nobody needs.
- `flock` on a per-address lock file. Released automatically when the owner dies,
  which removes the stale-pid window, but says nothing about which session holds
  it, and both `/pair` and the phone need that. Kept as optional hardening.
- A supervisor daemon that owns the socket and forwards to the active session.
  Rejected for now: a new process to package and run, duplicating the SSE path
  that works. This is the shape to revisit if several sessions must be reachable
  at once.
- One port per pi session plus a picker in the app. Deferred. The lease keyed by
  address is what makes it possible later.

## Consequences

- A handover is instant, and the losing session stops serving without being
  asked twice.
- The phone follows the bridge automatically, so the manual attach bar in the app
  goes away. A prompt typed during the switch is rejected by the bridge with 409
  rather than delivered to the wrong session.
- `/remote` and `/pair` can report the truth: serving, idle, waiting, address
  held by a foreign process, or bound to the loopback fallback.
- Two panes may serve two addresses on purpose, and a takeover in one does not
  touch the other.
- The 30 second staleness window stays as the fallback for an owner that died
  without cleaning up.
