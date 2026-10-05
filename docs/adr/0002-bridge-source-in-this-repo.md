# The bridge extension lives in this repository

Status: proposed

The bridge is a 43 KB pi extension at `~/.pi/agent/extensions/pi-remote.ts`,
owned by root because pi runs under sudo on this host. Git tracks no `.ts` file
at all in this repo, the only other copy is `/tmp/pi-remote.ts.bak`, and there
are no tests. The Android app in this repo cannot work without it, and the two
have already drifted: the pi session serving the bridge while this was written
was running an older revision than the file on disk. A change to one half is
reviewed against nothing.

We decided the source lives in `bridge/` in this repo, next to the app it serves,
and an install step puts it where pi will find it. The installed path stays the
one pi auto-discovers; the repo copy is the thing that gets changed and reviewed.

## Consequences

- Bridge and app changes travel in one commit and one review.
- Installing needs sudo on this host, and needs a restart or `/reload` before the
  running session picks up the new copy. The previous revision must not be left
  behind to serve requests.
- `bridge/` needs its own typecheck in CI, since nothing in the Gradle build
  looks at TypeScript. Until that exists, a syntax error in the bridge is found
  by running pi.
- The pi host and the phone are different machines, so the install step is
  manual. It should print the installed path and the revision it wrote.
