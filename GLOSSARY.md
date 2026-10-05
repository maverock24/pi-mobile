# Pi Remote

One pi session serving a phone over Tailscale, and the app that talks to it. The
vocabulary below is the one both halves use; the code and the phone should not
invent their own.

## Language

**Bridge**:
The HTTP and SSE server that one pi session runs so a phone can read it and prompt
it.
_Avoid_: gateway, backend, server

**Owner**:
The pi session whose successful bind holds a bridge address. Holding the address
makes a session the owner, not a record in a file.
_Avoid_: holder, leader, master, primary

**Lease**:
The file that records who owns a bridge address and when that owner last
heartbeat.
_Avoid_: lock file, owner.json

**Takeover**:
The act of one session becoming the owner of an address, started by `/pi-remote`.
Every other session serving that address drops.
_Avoid_: claim, switch, steal

**Handover**:
The moment a bridge passes from one owner to the next.
_Avoid_: switchover, failover

**Release**:
An owner giving up an address and closing its server without anyone asking, on
`/remote release` or session shutdown.
_Avoid_: yield, hand back

**Attached session**:
The session the app is showing. Its prompts, aborts and answers go there.
_Avoid_: current session, active session, target session

**Pinned session**:
The attached session as the app remembers it, so it can say what it moved away
from.
_Avoid_: saved session, last session

**Device token**:
The bearer token one phone uses for the whole bridge API.
_Avoid_: device key, phone token, api key

**Bound address**:
The host and port a bridge is actually listening on, which is not always the
address it was configured to use.
_Avoid_: bind address, listen address, endpoint
