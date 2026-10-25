# Retro: how the agent loop itself worked over this session

A second retro, on the mechanics rather than the debugging method. The first one
(`retro-2026-10-11-wlan-web-access.md`) covered causes and suspects. This one is
about how the work was handed out and verified, which is where the session spent
most of its tokens.

## 1. Every repeatable brief should be a custom agent, not a paragraph (severity: high)

Roughly thirty subagent spawns each carried the same 120-odd words: the worktree
path, the warning not to touch the sibling checkout, the gate command, the commit
prefix, push to main, keep the report short. That boilerplate was roughly half the
cost of each spawn, and it was retyped every time, which also means it drifted: the
push rule was stated as a standing rule in one place and contradicted in another.

- **Fix**: a project-level agent definition (`.pi/agents/` in the repo, or a global
  one) that bakes in the worktree rule, the gate command, the commit and push
  convention and the reporting style. The prompt then carries only the task.
- The existing global `worker` says "never pushes", which was wrong for this repo's
  standing rule every single time it was overridden. Worth aligning.

## 2. The phone is reachable by USB and nothing used it (severity: high)

Five rounds of TLS debugging happened through the human: push, wait for CI,
install by hand, try the browser, describe the failure. `adb` is already mentioned
in AGENTS.md, so the device was reachable the whole time.

- **Fix**: when the target is a phone on USB, installing the build, launching it and
  reading `logcat` is available to the agent. That would have turned
  `ERR_EMPTY_RESPONSE` into `SSLHandshakeException` with a stack trace in minutes.
  Worth making explicit, because the default assumption was that only the human
  could act on the device.

## 3. An effort's documents must be committed when its first code lands (severity: high)

The design, both reviews and several implementation notes lived untracked for about
twenty commits, discoverable only in one worktree. It was caught late and by luck.

- **Fix**: commit the design and reviews in the same change as stage one. An
  untracked plan is a plan that one `rm -rf` can erase, and it is the artifact the
  next session reads first.

## 4. Confirm the CI actually runs the tests (severity: medium)

Agents added tests repeatedly and ran a test task locally. Whether the CI job that
builds the APK also runs them was never checked, and an unwired test is a comment.

- **Fix**: read the workflow and confirm the lint and test steps run on every push,
  then say so in `.scratch` for the next effort.

## 5. One slug stopped describing the work (severity: low)

Commits carried `QRSPI: kokoro-tts`, then `wlan-web-access`, while the work moved
through talks, a file library, car mode, a learning library and a web server. The
slug is a promise that the tracker explains the commit, and by the end it did not.

- **Fix**: rename the effort, or start a new one, when the work changes shape
  rather than letting the original name cover unrelated changes.

## What to keep

Delegating every fix kept the main window alive through a very long session. The
one-agent-per-fix pattern with a fresh window also produced the two fixes that
worked, after the parent's hypotheses failed three times.
