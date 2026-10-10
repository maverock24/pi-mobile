# UX review remediation

- **Slug**: ux-remediation
- **Stage**: Questions (next to run)
- **Critical**: yes (provisional; confirmed in the Design round)
- **Done**: nothing yet

## The request

The human asked for an in-depth UI/UX analysis of the Android app, judged against a named Medium
roadmap (*The ultimate UI/UX design roadmap*, Precious Ossai) and the resources that article links.
The analysis is written; the human then chose to act on all of its recommendations, through QRSPI.

## Where the analysis lives

`docs/UX-REVIEW.md`, written against commit `e976063` (v0.0.57). It holds: the framework and the
48-principle rubric with its sources, the measured contrast table, the findings with `file:line`,
the recommendation table R1..R21 with effort and backlog overlap, the "what not to undo" list, and
the limits of the review. Read it before anything else. The two source inventories behind it were
not kept: they are reproducible from the code.

## Settled by the human before this effort began

These are answers, not options. Do not reopen them.

1. **Scope**: all 21 recommendations, prioritised (P0 bugs, P1 structure, P2 polish).
2. **Run status stays answers-only.** No on-screen elapsed time, progress or tool line. R12 is out
   of scope, and that decision makes R13 the only channel that reports a run in progress.
3. **Notifications are prioritised**: a foreground service that keeps the stream alive plus
   notifications for "needs an answer" and "run finished", per
   `docs/PRODUCTION-READINESS.md` Tier 0.2 to 0.4.
4. **The code moves to match the design record.** Add press-scale to the four controls that lack
   it (`Screens.kt` answer action icons and the composer `/` affordance); remove the `CONFIRM`
   haptics from copy and share; update `docs/PRODUCTION-READINESS.md` for what has shipped and fix
   the dead design-lab path in `AnswerStyle.kt`.
5. **Execution**: QRSPI, this effort.

## Not in scope unless a Design decision brings it in

Reversing answers-only. Product backlog items the review did not recommend: session picker (PRD
1.1), model and thinking switching (1.3), diff review (1.5), attachments (0.10), share target
(0.11), offline queue (1.11), Play Store path (2.11), localisation (1.8) except where an
accessibility or text change happens to touch a string.

## Constraints

- The review had no device. Nothing has been checked with TalkBack, at `fontScale` 2.0, in RTL, or
  on Android 15 where the inset problem lives. Any recommendation whose proof needs a device must
  say so, and the human verifies it on the phone.
- CI builds a signed rolling release on every push to `main`, and the phone's updater offers it.
- The QRSPI gate is live for this effort once `.scratch/ACTIVE` names the slug.
- `.scratch/` is not gitignored in this repo. Commit the artifacts with the work, never `ACTIVE`.
