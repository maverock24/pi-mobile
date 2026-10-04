# Pi Remote — production readiness

Audit date: 2026-10-04. Scope: `maverock24/pi-mobile` (Android client) plus the laptop
bridge (`~/.pi/agent/extensions/pi-remote.ts`, `~/.pi/agent/lib/remote-question.ts`).

How this list was built: an audit of the current code, compared against what other
agent clients on phones actually ship — [Happy Coder](https://github.com/slopus/happy)
(Android client for Claude Code and Codex), [VibeTunnel](https://github.com/amantus-ai/vibetunnel),
[Claude Code on the web and mobile](https://www.anthropic.com/news/claude-code-on-the-web),
[Omnara](https://docs.omnara.com/), [Termux](https://github.com/termux/termux-app),
[Crush](https://github.com/charmbracelet/crush) and [opencode](https://opencode.ai/docs/).

## What is already production-grade

| Area | State |
| --- | --- |
| Transport | Tailscale only, sshd bound to the tailnet address, key-only auth, `mosh` for roaming |
| Bridge auth | 32-byte bearer token, 0600, timing-safe comparison, axios-free node builtins |
| Token at rest | Encrypted with a keystore key (`data/TokenCrypto.kt`) |
| Session ownership | Heartbeat plus claim/release so exactly one pi session serves the port |
| Question widgets | Answered from the phone, verified end to end |
| Answer rendering | Markdown block parser, design tokens, tappable links |
| Release pipeline | Push to main builds a signed APK into a rolling GitHub release with a sha256 manifest |
| Self-update | Verified download, checksum check, FileProvider install |

## Verified gaps (audit findings)

- `AndroidManifest.xml` declares no `android:icon`, so the launcher shows the default Android icon.
- No notification code anywhere: no `POST_NOTIFICATIONS`, no service, no `WorkManager`, no wake lock.
- `app/src` contains only `main`: zero unit tests, zero instrumentation tests.
- `res/values/strings.xml` holds one entry; `ui/Screens.kt` hardcodes 18 user-facing strings.
- `targetSdk = 35` with no `enableEdgeToEdge()` and no inset handling, so Android 15 draws under the system bars.
- `isMinifyEnabled = false`, no shrinker, no baseline profile.
- CI runs `assembleRelease` / `assembleDebug` only: no test or lint job.

---

## Tier 0 — blocking for daily use

| # | Item | Why | Acceptance criteria | Effort |
| --- | --- | --- | --- | --- |
| 0.1 | App icon and splash | The launcher shows a generic robot; looks unfinished and is hard to find | Adaptive icon (foreground/background/monochrome) plus a splash theme; icon visible in launcher, recents, and notifications | S |
| 0.2 | Foreground service while a run is active | Android suspends the SSE socket when the app is backgrounded, so a long run silently disconnects | Service with a persistent notification ("pi working · 4m"), started on `agent_start`, stopped on `agent_settled`; screen-off run keeps receiving events | M |
| 0.3 | Notifications for "needs you" and "done" | This is Happy Coder's headline feature: you get told when the agent needs permission, errors, or finishes | Notification channel plus three triggers: question pending, run finished, run failed. Tapping opens the answer or the question card | M |
| 0.4 | Notification actions | On a commute you should not have to unlock and navigate | Question notification carries up to 3 option buttons plus "Open"; result notification carries "Copy answer" and "Reply"; verified from the lock screen | M |
| 0.5 | Reconnect and event replay | The bridge already numbers events (`seq`) but nothing resumes from it, so text streamed while backgrounded is lost | App stores the last `seq`, reconnects with it, bridge replays from a bounded buffer (say 500 events); no duplicated or missing text after a 60s disconnect | M |
| 0.6 | Battery optimisation guidance | Termux documents that Android kills background processes and throttles CPU; the same applies to our socket | First run asks to exempt the app from battery optimisation; `dontkillmyapp.com` linked in Settings; connection survives 30 minutes of screen-off | S |
| 0.7 | Edge-to-edge insets | On Android 15 the app already draws behind bars; the composer can collide with the gesture bar | `enableEdgeToEdge()` plus `WindowInsets.safeDrawing` padding on the scaffold; composer and top bar clear on a gesture-nav device | S |
| 0.8 | Pairing by QR code | Copy-pasting a 64-character token through a chat app is the most fragile step in setup | Bridge prints a QR (host, port, token, optional fingerprint); app scans it with CameraX and fills Settings; manual entry still works | M |
| 0.9 | Prompt idempotency and retry | A network blip mid-POST can double-send, and a failure currently loses the prompt | Request carries a client id; bridge ignores duplicates; failed prompt offers Retry and queues while offline | M |
| 0.10 | Image and file attachments | pi accepts image content blocks, and a phone camera is the reason to use a phone client | Attach from camera, gallery, or clipboard; bridge forwards as a pi image block; verified by asking pi to describe a screenshot | M |
| 0.11 | Share target | The natural gesture is "share this page/photo into pi" from any app | App appears in the Android share sheet; shared text or image arrives as a prompt; verified from Chrome and Photos | S |
| 0.12 | Onboarding | Nothing explains Tailscale, the bridge, or what the token is | Three screens: install Tailscale, run the bridge, pair; ends with a live health check that must pass | S |

## Tier 1 — finishing touches that make it feel complete

| # | Item | Why | Acceptance criteria | Effort |
| --- | --- | --- | --- | --- |
| 1.1 | Session picker | The bridge serves whichever session owns it; VibeTunnel switches sessions with ctrl+1..9 and Omnara treats sessions as first-class | `/api/sessions` lists live pi sessions (cwd, name, last activity, busy); app switches target; ownership claim moves to the chosen session | M |
| 1.2 | Context and cost overflow | The results-only view hides real information a long run needs | Overflow menu shows context %, tokens, cost, model, and elapsed time from `/api/state` | S |
| 1.3 | Model and thinking-level switch | pi supports both; the phone currently cannot change either | Reads `pi.getAllModels()` through the bridge, sets the active model and thinking level | M |
| 1.4 | Session controls | New, resume, fork, compact, abort | Each action reachable from the overflow menu and reflected in `/api/state` | M |
| 1.5 | Diff review and git actions | Claude Code's mobile story ends in a PR summary; ours ends in rendered text | Changed-files list per run, per-file diff, and commit/push from the app with a message | L |
| 1.6 | Per-answer actions | Streamdown-style block actions are the norm in web clients | Copy whole answer, copy one code block, share and export; long-press on a block | S |
| 1.7 | History search | Answers accumulate quickly and there is no way back | Search across the current session's answers, jump to the match | M |
| 1.8 | Localisation | Only `app_name` is a string resource; 18 strings are hardcoded | All user-facing text in `strings.xml`, Finnish and English resource folders, no hardcoded `Text("` in composables | S |
| 1.9 | Accessibility pass | Untested with TalkBack and large fonts | Content descriptions on every control, 48dp touch targets, TalkBack reads the status and question card, layouts survive `fontScale` 2.0 | M |
| 1.10 | Keyboard and IME behaviour | VibeTunnel treats mobile keyboard fidelity as a feature; a terminal-origin agent needs Enter, newline, and Ctrl chords | IME action sends, Shift+Enter inserts a newline, an optional chord bar provides Esc, Ctrl+C, and Tab | M |
| 1.11 | Offline queue | Trains and tunnels drop the network constantly | Prompts typed offline are queued, delivered on reconnect in order, and clearly marked as queued | M |
| 1.12 | Theming options | M3 dynamic colour and the design-lab accents are already tokens | Dynamic colour toggle, accent choice, and text size slider in Settings, all persisted | S |
| 1.13 | Haptics and motion | Small feedback is what separates polished apps from utilities | Haptic tick on send, on option tap, and on run completion; animations honour "reduce motion" | S |

## Tier 2 — engineering and release hardening

| # | Item | Why | Acceptance criteria | Effort |
| --- | --- | --- | --- | --- |
| 2.1 | Unit tests for the pure logic | `AnswerBlocks`, `Questions` parsing, `Diagnostics.hint`, and SSE frame parsing are all pure and untested | JVM tests with a corpus of real answers (fences, tables, diffs, unterminated fences); CI fails under a coverage floor | M |
| 2.2 | CI: test, lint, and build on PRs | Today CI only assembles release on main, so mistakes are caught after merge | Workflow runs `testDebugUnitTest lintDebug assembleDebug` on pull requests and pushes; `assembleRelease` stays main-only | S |
| 2.3 | R8 plus keep rules and a baseline profile | Minification is off, which ships dead code and slows cold start | `isMinifyEnabled = true`, `isShrinkResources = true`, keep rules for org.json and reflection-free code, baseline profile generated; app launches and streams correctly in a release build | M |
| 2.4 | Bridge version handshake | App and bridge evolve together; a mismatch currently fails in confusing ways | `/api/version` returns protocol and app-compat range; the app shows a clear "update the bridge" message when incompatible | S |
| 2.5 | Bridge as a systemd user service | It only runs after a manual `/reload`, so a reboot silently disables remote access | Unit file with restart-on-failure, `WantedBy=default.target`, `After=tailscaled.service`; survives a reboot with no manual step | S |
| 2.6 | Bridge auth hardening | One static token and no lockout | Constant-time compare already; add failure backoff, an audit log of prompts received, and token rotation from the app | M |
| 2.7 | HTTPS instead of cleartext | The app whitelists cleartext for `*.ts.net` and loopback, so the token rides plain HTTP inside the tunnel | `tailscale serve` terminates TLS on the ts.net name; app drops the exception. **Blocked:** `tailscale cert` fails with *this account does not support getting TLS certs* — HTTPS certificates must be enabled in the tailnet console first, otherwise fall back to a self-signed cert with its fingerprint in the pairing QR | S |
| 2.8 | Release notes and channels | The rolling release says only "Automatic build from <sha>" | Notes generated from commits; a `beta` channel tag the app can opt into | S |
| 2.9 | Crash and ANR visibility | A crash on the phone is currently invisible unless the user reports it | Opt-in, self-hosted or privacy-preserving crash reporting, or at minimum a "send diagnostics" action that packages the last error | M |
| 2.10 | Dependency and SDK hygiene | AGP, Kotlin, Compose, OkHttp all move fast | Renovate or Dependabot PRs, a version catalog, and a documented monthly SDK bump | S |
| 2.11 | Play Store path (optional) | `REQUEST_INSTALL_PACKAGES` self-update is not acceptable on Play | Decide: stay sideloaded (current pipeline) or switch to Play Core in-app updates, with data-safety form and privacy policy | M |

## Tier 3 — differentiators worth considering later

| # | Item | Reference | Note |
| --- | --- | --- | --- |
| 3.1 | Push without a backend | [ntfy](https://ntfy.sh) / UnifiedPush | Private topic the bridge publishes to; gives real background notifications without FCM and without a cloud account |
| 3.2 | Session recording and replay | VibeTunnel (asciinema) | Record a run and play it back later, useful for long tasks you only skimmed |
| 3.3 | Device handoff | Happy Coder | Press a key at the desk to take over from the phone, notification says "continue on the laptop" |
| 3.4 | End-to-end encryption of payloads | Happy Coder | Today the token protects the channel and WireGuard carries it; E2E would matter if the bridge is ever exposed beyond the tailnet |
| 3.5 | Quick actions, tile, and widget | Android platform | Launcher shortcut "dictate", QS tile to start/stop a run, widget showing the last answer |
| 3.6 | Multiple machines and profiles | Termius | More than one laptop, each with its own endpoint, token, and session list |
| 3.7 | Voice improvements | — | On-device STT option for privacy, voice activity detection, auto-send on long silence |
| 3.8 | Tablet and foldable layouts | — | List-detail layout for the answer stream and questions |

---

## Do these first

1. **0.2 + 0.3 + 0.4** (foreground service and notifications with actions). This is the single
   biggest difference between a demo and something you rely on daily, and every comparable
   client has it.
2. **0.5** (reconnect and replay). Everything else is undermined by losing text while the
   phone is locked.
3. **0.1 + 0.7** (icon and insets). Ten minutes of work for the first impression and for
   Android 15 correctness.
4. **2.1 + 2.2** (tests and CI gates). The last three pushes to main failed on trivial Kotlin
   errors that a unit-test job plus lint would have caught before merge.
5. **0.8 + 0.12** (QR pairing and onboarding). Setup is currently the most fragile part of the
   whole system.

## What not to do

- Do not expose the bridge outside the tailnet to make notifications easier. Use ntfy or a
  foreground service instead.
- Do not add a cloud account or telemetry. Happy Coder's "no telemetry, no tracking" is a
  selling point and it costs nothing to keep.
- Do not enable Play Store distribution accidentally: self-updating APKs via
  `REQUEST_INSTALL_PACKAGES` violate Play policy, so that path requires replacing 0.x
  self-update with Play Core.

## References

- Happy Coder — https://github.com/slopus/happy
- VibeTunnel — https://github.com/amantus-ai/vibetunnel
- Claude Code on the web and mobile — https://www.anthropic.com/news/claude-code-on-the-web
- Omnara (approvals, questions, streaming events) — https://docs.omnara.com/
- Termux process limits and battery guidance — https://github.com/termux/termux-app
- Crush themes and compact mode — https://github.com/charmbracelet/crush
- opencode docs — https://opencode.ai/docs/
- Android: edge-to-edge enforcement — https://developer.android.com/develop/ui/views/layout/edge-to-edge
- Android: notification actions — https://developer.android.com/develop/ui/views/notifications/notification-actions
