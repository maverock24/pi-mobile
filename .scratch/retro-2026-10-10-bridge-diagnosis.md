# Retro: diagnosing the phone that could not dispatch skills

Session: `2026-10-09T19-35-31-181Z_01a12229` (pi-mobile). Retro written 2026-10-10.

What the session had to find out: why a phone paired to the bridge could not run skills, when
the bridge code to support it had landed the day before.

Cost of the session: 99 tool calls, 103 results, 184 KB of tool output (~46k tokens).

## The answer it eventually reached

The bridge holding port 8787 was `pid 636839`, started Oct 8 20:08. `expandPromptTemplates`
and `GET /api/commands` first appear in commit `9c5041a`, dated Oct 9 20:52. The process had
the old module in memory, so the phone saw a 404 on `/api/commands` and `/skill:...` arrived
as literal text. Fix: `/reload` in that pane.

`docs/PRODUCTION-READINESS.md:81` already lists the reload dependency as a known hazard.

## Candidates, worst first

### 1. The agent cannot probe the local bridge (information access)

`curl`/`wget` on http(s) is blocked in code, and `web_url_read` cannot set an `Authorization`
header. A single `GET /api/commands` with the bearer token would have proven the 404 in one
call. Instead the session reasoned from file timestamps, asserted a cause as "airtight",
and had to be corrected by the human (call 88). One human round-trip spent on a fact a request
settles.

Fix: permit read-only HTTP to loopback and the tailnet address, or add a `probe-bridge` helper.

### 2. The lease and audit files are documented where nothing points (navigation)

`docs/SESSION-MANAGEMENT.md:66-79` documents `owner-<host>-<port>.json` field by field,
including `pid`, `sessionId`, `cwd`, `claimedAt`, `heartbeatAt`. `docs/THREAT-MODEL.md:340`
has `ls -l ~/.config/pi-remote/ ~/.local/share/pi-remote`; `:382` names `audit.log`.

`README.md:114` is the only pointer into `docs/` from the entry point, and it names
THREAT-MODEL.md alone. The session rediscovered both paths from scratch across calls 57-63,
75-80 and 86.

Fix, two edits:

- `bridge/extensions/pi-remote.ts`, in the header beside the endpoint list (after the endpoint
  block, before the herdr badge paragraph), add where runtime state lands:
  `~/.config/pi-remote/` holds `token`, `tokens.json` and the lease `owner-<host>-<port>.json`
  (`pid`, `sessionId`, `cwd`, `claimedAt`, `heartbeatAt`); `~/.local/share/pi-remote/audit.log`
  holds prompts, aborts, pairing and auth failures.
- `README.md`: add a `## Docs` line pointing at `docs/SESSION-MANAGEMENT.md`,
  `docs/THREAT-MODEL.md` and `docs/PRODUCTION-READINESS.md`.

### 3. No guardrail, and one check that catches this exactly (automated checks)

- No repo-level `AGENTS.md` or `CLAUDE.md`.
- Zero tests: no `app/src/**/test` sources, no test dependency in `app/build.gradle.kts`.
- No CI job runs a test, lint or typecheck: `android-debug.yml` is dispatch plus
  `assembleDebug`; `android-release.yml` builds and publishes.

The check that would have caught this bug, with no runtime change:

```
ps -o lstart= -p "$(jq .pid ~/.config/pi-remote/owner-*.json)"   # when the process started
stat -c %y ~/.pi/agent/extensions/pi-remote.ts                    # when the code landed
```

An older process than the deployed file means the bridge is serving stale code. Ship as
`bridge/check-freshness.sh`: read the lease, compare, print both times, exit non-zero on
stale, exit 0 with a clear message when no lease exists. Honour `PI_REMOTE_TOKEN_FILE`, since
the state dir is `dirname(tokenPath)` (`pi-remote.ts:100`).

### 4. `ps ... cmd` is pathological on this machine (tool economy)

Calls 57 and 59 emitted 17.6 KB (~4.4k tokens) for "which pi processes, since when", because
each pi line carries a ~900-character `sudo env HOME=... PATH=...` prefix. Call 59 alone was
16.3 KB, 9% of all tool output.

Fix: read the lease instead (candidate 2 removes the need for `ps`), and select explicit
columns when `ps` is unavoidable: `ps -o pid,lstart,etime,comm`.

### 5. Nothing for a coding-standards file

The cache defect found late (`ui/ChatViewModel.kt:975` early-returns on an unchanged session
id; `:1016-1019` pins `commandsLoaded = true` on a 404) is a judgement-call bug and is already
`questions.md` Q15. No reviewer agent is configured for this repo, and the mechanical form of
the rule is the version handshake Q15 asks for. Prose would buy nothing.

The global `AGENTS.md` was swept for no-ops and none were worth cutting. It is 3.1 KB and
states the positive target, which is the right shape.

## Status

Candidates 2 and 3 are the changes the user picked. Both are blocked: `.scratch/ACTIVE` names
`phone-builtin-commands`, which is `Critical: yes` at `Stage: Research`, so the QRSPI gate
refuses repo writes outside `.scratch/`. Apply them once that effort reaches Implement, or
after the human deletes `.scratch/ACTIVE`.
