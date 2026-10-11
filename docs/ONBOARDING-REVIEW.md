# Onboarding review

Audited the working tree at `ae16414` (`qrspi/ux-remediation-w3`). `origin/main`
(`d093ad4`, what GitHub serves) is six commits ahead and fixes two small items below;
every blocker survives there, and where a line moved, main's number is given.

## The path a stranger walks

1. GitHub shows `README.md` first. It opens with a description and a network diagram
   (`README.md:3-17`). No prerequisites, no licence, no screenshot. The first
   instruction arrives before anything says what must already exist.

2. "Laptop side" step 1 says "Copy the extension into place (already installed on the
   reference machine)" and shows one destination path (`README.md:21-24`). No source, no
   `cp`, no mention of `bridge/install.sh`, which exists to copy all three files
   (`bridge/install.sh:20-24`). A stranger who copies only `pi-remote.ts` gets an
   extension that cannot load, because it imports `../lib/remote-question.ts` and
   `../lib/lease.ts` (`bridge/extensions/pi-remote.ts:64-70`). First stop.

3. Step 2 says run `/reload` or restart pi (`README.md:27-28`). This assumes pi is
   installed and a TUI session is running. The README never names a pi install or links
   to one. Second stop.

4. Step 3 says read the token (`README.md:30-35`). In this checkout it points at
   `~/.config/pi-remote/token`, a file the bridge deletes when it migrates to
   `tokens.json` (`bridge/extensions/pi-remote.ts:155-189`). `origin/main` already fixed
   this to `tokens.json`. The step is unnecessary on the pairing path anyway, where
   `/pair` mints the token (`bridge/extensions/pi-remote.ts:837-840`).

5. Start the bridge. Nothing sets `PI_REMOTE_HOST`, so the default is the author's
   tailnet address (`bridge/extensions/pi-remote.ts:77,105`). It does not exist on the
   stranger's machine, so the bind falls back to `127.0.0.1`
   (`bridge/extensions/pi-remote.ts:1317-1319`) and the phone cannot reach it. The QR
   can still name the MagicDNS host, so pairing looks fine and times out
   (`bridge/extensions/pi-remote.ts:1917`). Third stop. On main the loopback fallback is
   at least announced (`main bridge/extensions/pi-remote.ts:1917-1920`).

6. "Phone side" says install the APK from the latest release (`README.md:57`). No URL,
   but the Releases page is public and the APK is there, so this works.

7. Pairing is the best section (`README.md:77-117`). `/pair` draws a QR and opens it;
   the app scans it. But `/pair` shells out to `qrencode`
   (`bridge/extensions/pi-remote.ts:1920,1926`) and the README never lists it; without
   it the command warns and no QR is drawn (`bridge/extensions/pi-remote.ts:1960`).
   Fourth stop. On main `install.sh` reports the missing binary, but the README never
   sends you to `install.sh`.

8. The real prerequisites are never gathered in one place: pi, Node, Tailscale on the
   laptop and the phone on the same tailnet with MagicDNS enabled, `qrencode`, and an
   image viewer (`bridge/extensions/pi-remote.ts:760-762`).
   `docs/PAIRING-REVIEW.md:12-15` lists them, but that is a review, and the README links
   only `docs/THREAT-MODEL.md` (`README.md:114`).

## Building

`./gradlew assembleDebug` (`README.md:159`) needs JDK 17 or newer and an Android SDK. A
clone has no `local.properties` (it is gitignored, `.gitignore:4`), so Gradle needs
`ANDROID_HOME`; neither the JDK version nor the SDK path is stated. Release signing is
configured only when four env vars exist (`app/build.gradle.kts:10-16,30-33`), so a local
`assembleRelease` yields an unsigned APK; the real keystore lives only in CI secrets
(`README.md:141-155`, `.github/workflows/android-release.yml:39-45`). A stranger can
build debug or download the release, not produce an installable signed build. The
no-secrets debug workflow is `workflow_dispatch` only
(`.github/workflows/android-debug.yml:4`) and is undocumented.

## Missing

Blockers:

1. No prerequisites section, and pi is never named or linked. Fix: a short "What you
   need" list at the top.
2. The bridge install is undocumented. Fix: replace `README.md:21-24` with
   `git clone ... && bridge/install.sh`.
3. The laptop address is hardcoded. Fix: document `PI_REMOTE_HOST=<tailnet IP>`.
4. Tailscale and MagicDNS are not stated as requirements. Fix: add them to the
   prerequisites.
5. No licence. Fix: add one.

Nice to have: a screenshot; `CONTRIBUTING.md`; the 23 internal files under `.scratch/`
are published; no stable tagged release, `latest-build` churns each push
(`README.md:120-121`); `README.md:107` still names `owner.json`, which is unused
(`bridge/extensions/pi-remote.ts:112`); no tests.

## Verdict

No. A stranger can download the APK and follow the pairing walkthrough, but cannot get
the bridge running from the README alone: the three-file install, pi itself, the laptop
address, and qrencode are all undiscoverable from the front page. The single highest-value
fix is to put the install command and a prerequisites list into `README.md`.
