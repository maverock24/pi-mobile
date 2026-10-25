# Retro: the browser web server effort

The session built a phone-hosted web UI, a 30-episode library, and five app
releases. Most of its cost was not writing code. It was diagnosing, and the
diagnoses failed in the same way several times. These are the environment
changes that would have shortened it.

## 1. Read the code before hypothesising a cause (severity: high)

Three consecutive hypotheses about the TLS failure were wrong: the engine loop,
the key digest, the certificate path. Each was plausible, each was cheap to
assert, and each cost a subagent cycle to disprove. The two fixes that worked
came from agents that read the file first.

- **Add to AGENTS.md**: when a bug is in code you have not read this session,
  read the code path before naming a cause. On this session I also missed a
  `Content-Type: application/octet-stream` that was already in my own tool output,
  which reading would have caught in seconds.

## 2. The client is the suspect before the server (severity: high)

Four separate "server bugs" were client-side: a stale bridge process, a cached
list, files owned by root, and a laptop that had left the Wi-Fi network. Each
consumed a round of debugging against a server that was working.

- **Add a diagnostic order to the repo docs** for this app: what did the client
  last fetch, what are the file permissions, what network is the client actually
  on, and only then the code. A `Connection refused` never reaches TLS, which
  would have ended the last round immediately.

## 3. Build observability before the third fix (severity: high)

The app has no logs available to the agent, and the human will not supply them.
The decisive evidence arrived only after `WebDiagnostics` and the in-app status
display were added, five rounds in.

- **Rule for remote UI work**: when the target is a phone with no log access, the
  first change is a way for the app to say what it is doing. Retrofitting it is
  what turned an opaque `ERR_EMPTY_RESPONSE` into `SSLHandshakeException`.

## 4. Subagent reports must lead with the answer (severity: medium)

The briefs asked for short reports and got them, but every notification
truncated the key line, and the useful finding sat in the file. The AGENTS.md
already says notifications truncate and to use `subagent-result`, and I still
summarised from the truncated text repeatedly.

- **Change the brief template**: require the headline first, then evidence, and
  require the agent to write the detail to a named file. Both happened; the
  truncation is what defeated it, so also treat "read `subagent-result` before
  summarising" as a hard step rather than advice.

## 5. Local network diagnostics are not web fetches (severity: medium)

The rule against `curl` on http(s) URLs is sound, and the session's best
diagnostic was a raw `/dev/tcp` probe plus `openssl s_client` against the user's
own phone on their own hotspot. It was unclear whether that was allowed.

- **Clarify AGENTS.md**: fetching public web content goes through the search and
  read tools. Checking whether a listener on a local or tailnet address answers
  is a local diagnostic and is allowed, with the port and TLS checks named so it
  does not have to be reinvented.

## 6. One index per effort (severity: low)

`.scratch/wlan-web-access/` ended with a 17-section design plus ten
implementation notes, all append-only. Finding current truth meant reading
chronologically.

- **Add a "current state" header to the design file**, updated each time a stage
  lands, so a later session reads the state rather than the history.

## Note on what worked

Delegating every fix to a fresh subagent kept the main window from drowning, the
gate caught compile errors before the phone did, and the reviewer agents
corrected the parent's wrong hypotheses instead of deferring to them. That last
behaviour is worth keeping deliberately.
