# Phone runs built-in pi commands

Let the phone dispatch the pi slash commands that the interactive TUI handles, not only
the ones a prompt can already expand.

Skills, prompt templates and extension commands already work from the phone: `POST
/api/prompt` passes `expandPromptTemplates: true` and `GET /api/commands` reports
`pi.getCommands()`. What is missing is the built-in set (`/model`, `/thinking`,
`/compact`, `/new`, `/resume`, `/fork`, `/clone`, `/tree`, `/reload`, `/name`,
`/session`, `/settings`, `/login`, `/quit`). Pi resolves those in the TUI only and states
that they "would not execute if sent via `prompt`" (`docs/rpc-commands.md:834`), so they
need their own path through the bridge to the extension API.

## Settled at Q

- **Scope**: the safe subset only. `/model`, `/thinking`, `/compact`, `/new`,
  `/resume`, `/reload`. Every other built-in (including `/fork`, `/clone`, `/tree`,
  `/name`, `/session`, `/settings`, `/login`, `/quit`) is out of this effort.
- **Destructive commands**: the phone confirms nothing extra. Within the scope above,
  `/compact`, `/new`, `/resume` and `/reload` run on the device token alone, on the
  ground that the token is already documented as equivalent to an SSH key.
- **Critical**: yes. No code writes until a human sets `Stage: Implement`.

## Evidence from the field (2026-10-09, before the reload)

Three prompts from the phone reached the bridge that evening, recovered from the audit log by
cracking the digest each line carries (`sha256(text)[:16]`):

| Time (UTC) | chars | text |
|---|---|---|
| 20:34:25 | 6 | `reload` |
| 20:35:02 | 7 | `/reload` |
| 20:35:55 | 15 | `/skill:ask-matt` |

All three hit a bridge running pre-`9c5041a` code, so `/reload` did nothing and
`/skill:ask-matt` arrived as literal text. `ask-matt` is a real skill in `~/.agents/skills`,
which rules out the unknown-name passthrough at `agent-session.js:1637` as the cause.

Two things this settles:

- The first instinct for a stuck bridge was `/reload`, which is the one command the phone
  cannot send today. `/reload` is in scope for operational reasons, not just for parity.
- `digest` in `audit.log` is reversible for short prompts. Useful for forensics, and worth
  knowing it is not a privacy control.

