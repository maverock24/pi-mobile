# Questions

Each one is a fact about what exists, answerable with a `file:line`. No question here is
about what to build.

## A. What pi exposes

1. **Which built-in commands have a programmatic equivalent?** Enumerate every command
   listed in `docs/slash-commands.md` and pair it with a member of `ExtensionContext`
   (`dist/core/extensions/types.d.ts:213`) or `ExtensionAPI` (`:1149`) that performs the
   same thing. The prompt-name table in the ticket is partial; the left-out rows are the
   answer that matters. Which built-ins have no equivalent at all?

2. **Is a TUI-only built-in reachable any other way?** For any command with no API
   equivalent (`/settings`, `/login`, `/hotkeys`), does pi expose a non-TUI route: an
   RPC command (`docs/rpc-commands.md`), something on `ctx.ui` that also works with
   `hasUI` in RPC mode (`docs/extensions.md:` "UI and modes"), or nothing?

3. **Can these methods be called from an HTTP request handler?** `docs/extensions.md:`
   states "commands and idle session events often have no operation signal". Find the
   lifecycle contract for `ctx.newSession()`, `ctx.switchSession()`, `ctx.fork()`,
   `ctx.navigateTree()`, `ctx.reload()`, `ctx.compact()`, `ctx.shutdown()` and
   `pi.setModel()`. Which of them await idle, which abort in-flight work, and does
   anything forbid calling them outside a command handler?

4. **What happens if the agent is busy?** For each control method in Q3, what does pi do
   when `ctx.isIdle()` is false. Does it queue, abort, throw, or no-op? The bridge
   already distinguishes busy from idle for `/api/prompt` (`pi-remote.ts:1045-1059`), so
   the same distinction has to be known here.

5. **What does `ctx.mode` / `ctx.hasUI` report in the pane the bridge serves?**
   `pi-remote.ts` can serve from a non-TUI session (it has `PI_REMOTE_ALLOW_NON_TUI`).
   What do those two fields hold there, and which control methods require interactive
   mode?

## B. What the bridge already has

6. **Which existing endpoint conventions must a new write endpoint follow?** Find the
   reuse sites for: bearer-token check, `wrongSession()` guard, `readBody`, `sendJson`,
   `audit()`, and any rate limiting. Is there a destructive-endpoint rule in
   `docs/THREAT-MODEL.md` or `docs/PRODUCTION-READINESS.md`?

7. **What does `shrink()` do to a longer `/api/commands` payload?** Read `shrink`
   (`pi-remote.ts:259`) and the array cap it applies. How many rows can be added before
   the tail is silently dropped, and does anything already guard against that?

8. **Which SSE events exist and which of them would a command emit?** Enumerate the
   `emit(...)` call sites and the event `type` values the app consumes in
   `ui/ChatViewModel.kt`. Would a model change, a compaction, or a session switch reach
   the phone through the existing stream, or does the bridge need a new event?

9. **How does the bridge detect a session handover, and what does it return to a stale
   phone?** Read the `sessionId()` / `wrongSession()` logic and the app's reaction to a
   409.

## C. What the phone already has

10. **How does the app model and render a command today?** Read `SessionCommand`
    (`ui/ChatViewModel.kt:165`), `loadCommands()` (`:973`) and the palette in
    `ui/Screens.kt`. What does each row show, what does tapping one do, and what happens
    for an unknown `source` value?

11. **How does the app show and change model and thinking level?** Are they on screen
    already from `/api/state` (`pi-remote.ts:522`), and is there any selector to reuse,
    or would one be built from scratch?

12. **How does the app treat a bridge too old for an endpoint?** Read the `commandsNote`
    404 path (`ui/ChatViewModel.kt:1016`) and the equivalent in `ui/Screens.kt`. Is this
    the pattern a built-in endpoint should follow, and does the search endpoint use the
    same one?

13. **What is the app's Android/Compose baseline?** Read `app/build.gradle.kts` for
    `minSdk`, `compileSdk` and Compose version, and find any existing confirm dialog or
    argument-entry surface to reuse for a command that needs a value.

## D. Precedent

14. **Has any code in this repo already called a control method on `ctx`?** `pi-remote.ts`
    registers `/remote`, `/pi-remote` and `/pair` (`:1695`, `:1805`, `:1857`) and calls
    `ctx.ui.notify`. Find every `ctx.<control>()` call in the repo and say whether it runs
    in a command handler or in a callback.

15. **Can the phone tell it is talking to stale bridge code?** Found while diagnosing a
    404 on `/api/commands` from a process started before the endpoint existed. Read
    `loadCommands()` (`ui/ChatViewModel.kt:973`) and its 404 branch (`:1016-1019`), plus
    `forgetSession()` and its only two callers (`:442`, `:1046`). Does anything clear the
    command cache when the session id does not change, and is there a version or
    capability field anywhere in `/api/state` or `/api/health` that a phone could compare
    against? If not, say so plainly: a new endpoint has the same invisibility, and a
    bridge-side change would look identical to a broken one.

