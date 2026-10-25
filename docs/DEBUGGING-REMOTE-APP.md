# Debugging the remote app

Status: guidance written after the WLAN web access effort, October 2026. The
history is in `.scratch/wlan-web-access/` and
`.scratch/retro-2026-10-11-wlan-web-access.md`.

The app runs on a phone and the agent cannot read its logs; the human will not
supply them. A symptom that reads like a server bug is more often a client, a
file permission, or a network. This document sets the order to work in.

## 1. Read the code before naming a cause

When the bug is in code you have not read in this session, open the code path and
follow it before you write a hypothesis down. A plausible cause that costs a
subagent cycle to disprove is more expensive than ten minutes of reading.

The TLS failure in the web access effort took three wrong hypotheses in a row:
the engine loop, the key digest, the certificate path. The two fixes that worked
came from agents that read the files first. One of them
(`implementation-tls-engine.md`) starts by establishing that the hand-written
engine loop the brief named does not exist.

Read your own earlier tool output before reasoning about a symptom. In the same
effort the answer had already been printed: a response carried
`Content-Type: application/octet-stream`, which is why the browser downloaded the
page instead of rendering it. Nobody read it until a later pass.

## 2. The client is the suspect before the server

Work this order and stop at the first answer that explains the symptom.

1. **What did the client last fetch?** Look at the request the client actually
   made, its response, and which process answered it. Confirm the client is
   talking to the server you just changed, not an older process, not a cached
   response, and not the list it fetched before the change.
2. **What are the file permissions?** The server can only serve what its process
   can read. Run `ls -l` on the served path as the user the server runs as. A
   file written by a root-owned helper is unreadable to the app, and the error
   often surfaces as an empty body rather than a permission message.
3. **What network is the client actually on?** Read the client's own address and
   route. A laptop that left the hotspot, or a client on the tailnet trying to
   reach a hotspot-only address, cannot be fixed in the server. Check whether a
   listener answers on the address and port the human was given, not the one you
   believe is bound.
4. **Only now the code.** With the client, the permissions and the network
   confirmed, a server bug is the remaining explanation and the code path is
   worth changing.

The concrete tells from that effort:

- **A stale process.** An old bridge or app instance was still serving the port,
  so the changed code was never exercised.
- **A cached list.** The client showed rows from an earlier fetch, and the
  missing episodes looked like a server route failing.
- **Files owned by root.** A root-run process wrote files the app could not
  read.
- **A client on the wrong network.** The laptop had left the Wi-Fi the phone was
  on, so the address still existed but nothing could route to it.
- **`Connection refused` never reaches TLS.** The TCP stack answered with a
  reset, so the network path worked and no process was listening on that port.
  Certificate, handshake and page code are irrelevant until a listener answers.

## 3. Build observability before the third fix

When the target is a phone with no retrievable logs, the first change is a way
for the app to report what it is doing. Instrument before the third attempt at a
fix, not after it.

In the web access effort the decisive evidence arrived only after
`WebDiagnostics` (`app/src/main/java/com/maverock24/pimobile/web/WebDiagnostics.kt:50`)
and the status line in Settings, Web access
(`app/src/main/java/com/maverock24/pimobile/ui/WebAccessSection.kt:129`) were
added. The opaque browser `ERR_EMPTY_RESPONSE` became an app-reported
`SSLHandshakeException` with a reason and counters, and the guessing stopped. The
same state is served on `GET /api/diagnostics`
(`app/src/main/java/com/maverock24/pimobile/web/WebRelayServer.kt:147`), which
needs no session and is the route to read when the session or TLS is broken.

A useful status line answers three questions without a log: did the listener
start, what address is it bound to, and what was the last failure. Counters are
history; the last close reason is current state, and a success has to clear it.
