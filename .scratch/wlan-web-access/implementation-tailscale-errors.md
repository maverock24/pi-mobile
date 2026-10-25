# Tailscale from the app, and short errors that unfold

Effort: QRSPI `wlan-web-access`, worktree `/home/maverock24/github/pi-mobile-keys`,
branch `main`. Written 2026-10-11.

## 1. Can an ordinary Android app start Tailscale?

No. Three separate routes were checked, and all three are closed or unreliable.

### 1.1 An embeddable Tailscale library or SDK

Tailscale's own embedding product is **tsnet**, and it is a Go library:
"tsnet is a library that lets you embed Tailscale inside a Go program"
(<https://tailscale.com/docs/features/tsnet>). It runs a userspace TCP/IP stack
inside that Go program, so it needs no `VpnService`, but it is not an Android SDK
and there is no AAR, no Maven artifact and no Kotlin API.

The Android app's bindings, `libtailscale`
(<https://pkg.go.dev/github.com/tailscale/tailscale-android/libtailscale>), are a
gomobile package inside the `tailscale-android` module, published on pkg.go.dev
with "Imported by: 0". They exist so Tailscale can build its own app, not so a
third-party app can link them.

Community work exists (`github.com/leondgarse/tailscale_mobile`, and TailSocks,
<https://tailscale.com/community/community-projects/tailsocks>) and proves the
idea: embed the Go library in process, expose a localhost TCP proxy, no
`VpnService` and no Tailscale app on the device. Both are third-party, and both
pull a large native toolchain into a Compose app whose only network need is one
HTTP client. Not taken. This is the one dependency found that would genuinely
remove a human step, and it is a gomobile build, a vendored Go module and a
second Tailscale node per install, which is a bigger change than the effort.

The pairing review reached the same conclusion from the other side: "I could not
establish any supported way for a third-party Android app to read Tailscale's
local state" (`docs/PAIRING-REVIEW.md:112`).

### 1.2 `VpnService` to bring up the Tailscale app's tunnel

Impossible by design. `VpnService.prepare(Context)` grants the **calling** app its
own tunnel: "Only one application can be granted at the same time. The right is
revoked when another application is granted" and "The former deals with user
action and stops the VPN connection created by another application"
(<https://developer.android.com/reference/android/net/VpnService>). Calling it
from this app would ask the user to disconnect Tailscale and hand the VPN slot to
Pi Remote, which is the opposite of the goal.

Tailscale's own service is also unreachable: in its manifest `IPNService` is
`android:exported="false"` with `android.permission.BIND_VPN_SERVICE`
(<https://github.com/tailscale/tailscale-android/blob/main/android/src/main/AndroidManifest.xml>),
so no other app can bind it.

### 1.3 What the Tailscale Android app does offer

- **Package name:** `com.tailscale.ipn`, stated in Tailscale's own docs, "The
  application ID of our Android app is com.tailscale.ipn"
  (<https://tailscale.com/docs/integrations/mdm/android>).
- **Broadcast actions to connect and disconnect:** `IPNReceiver` is
  `android:exported="true"` and handles `com.tailscale.ipn.CONNECT_VPN`,
  `com.tailscale.ipn.DISCONNECT_VPN` and `com.tailscale.ipn.USE_EXIT_NODE`, with
  the comment "IPNReceiver allows external applications to start the VPN"
  (`.../android/src/main/java/com/tailscale/ipn/IPNReceiver.java`). Sending the
  action is easy; `Intent(PACKAGE)` plus the action needs no permission.
  Not taken, for three reasons: it is not documented for third parties (no
  Tailscale KB page, only the source), it is fire and forget so the sender cannot
  tell whether the tunnel came up, and it is reported unreliable after the phone
  sleeps (`tailscale/tailscale` issues 10831 and 14148, both still open, and the
  reporter of 10831 is another app's developer doing exactly this). A button whose
  effect the app cannot verify would let it claim a connection it does not have.
- **Quick Settings tile:** `QuickToggleService`, exported, with the
  `TOGGLEABLE_TILE` metadata. A third-party app cannot toggle another app's tile;
  the human adds it in the shade.
- **Deep link:** `MainActivity` accepts `VIEW` for `tailscale://navigate`, and has
  a normal `LAUNCHER` intent. Opening the app needs none of that: the platform's
  `PackageManager.getLaunchIntentForPackage` returns the launcher intent, and
  package visibility for the target is granted by a `<queries>` entry
  (<https://developer.android.com/training/package-visibility>).
- **VPN state:** `ConnectivityManager` with `TRANSPORT_VPN` tells an app that some
  VPN is up. It cannot tell which one: `NetworkCapabilities.getOwnerUid()` is
  "only populated" when the caller is the network owner
  (<https://developer.android.com/reference/android/net/NetworkCapabilities>), so
  a third-party app always reads `INVALID_UID`. There is no supported read of
  Tailscale's own connected state.

So the honest app can say "no VPN is on" or "a VPN is on", open Tailscale, and
say whether Tailscale is installed. It cannot say "connected", and it must never
claim it.

## 2. What was built, Part 1

`app/src/main/java/com/maverock24/pimobile/net/Tailscale.kt`
- `PACKAGE = "com.tailscale.ipn"`, cited from Tailscale's MDM docs.
- `state(context): VpnState` reads `TRANSPORT_VPN` on the active network.
- `installed(context)`, `open(context)` (launcher intent, `FLAG_ACTIVITY_NEW_TASK`),
  `install(context)` (Play Store listing, https, so it resolves to the store app
  or a browser).
- The KDoc states what is impossible and why, so the next reader does not try the
  broadcast action again.

`app/src/main/AndroidManifest.xml:33-38` adds `<queries><package
android:name="com.tailscale.ipn" /></queries>`. Without it, Android 11 and later
hide the app package and both `installed` and `open` silently see nothing.

`app/src/main/java/com/maverock24/pimobile/ui/ProblemText.kt:122-148` draws the
state line and the chip: "Open Tailscale" when installed, "Get Tailscale" when
not, and the line "No VPN is on." or "A VPN is on. Android does not say which app
runs it." Tapping it opens another app. Nothing in the app says the tunnel came
up.

## 3. What was built, Part 2

One type, one builder, one composable.

- `net/Problems.kt`: `Problem(summary, detail, tailnet)` and `Problems.of(error,
  baseUrl, subject)`. `summary()` maps each exception class to one short sentence
  plus what to do; `detail()` keeps the class, the raw message, the address and a
  plain sentence on what that class usually means. `tailnetLikely()` decides
  whether the Tailscale action applies; a `ConnectException` is excluded because a
  refused port means the link works.
- `ui/ProblemText.kt`: `ProblemFold(problem, ...)` draws the summary, a chip row
  (Details / Hide details, Open Tailscale, the notice's own action, Dismiss) and
  the detail in monospace behind a `SelectionContainer` when unfolded. Collapsed
  by default, state keyed on the strings so a new failure folds the old one back.
- Surfaces converted, so there is one style:
  - connection status: `ChatViewModel.statusProblem`, drawn at `Screens.kt:455-466`
  - every notice: `Notice.detail` and `Notice.tailnet`, drawn by `NoticeBar`
    (`Screens.kt:1657-1693`)
  - the Settings Test result: `ChatViewModel.testConnection` and `Screens.kt:2119-2132`
  - the file library: `ChatViewModel.filesError` is a `Problem`, drawn twice in
    `FilesScreen.kt` (above the rows, and in the empty box)
  - web access: `WebAccessStatus.Failed(problem)` (`web/WebAccess.kt:35`), set at
    `web/WebAccessService.kt:139`, drawn in `ui/WebAccessSection.kt:61,146`
  - the listener readout: `web/WebDiagnostics.kt:98` adds `diagnosticsHeadline`
    (one line) while `diagnosticsSummary` becomes the folded detail, so the full
    `SSLHandshakeException` reason is one tap away instead of inline
    (`ui/WebAccessSection.kt:126-144`)
  - update check and install: `UpdateStatus.Failed(problem)` (`Screens.kt:2431`)
  - pairing: `MainActivity:245` routes the failure through `Problems`
  - the last-resort `safeLaunch` catch: `ChatViewModel:1240`
- Car mode draws `problem.summary` only (`CarScreen.kt:412`, `CarNotices`). No
  Details chip, no exception text. The detail is still reachable in the phone UI.

Removed: `Diagnostics.hint` and `Diagnostics.describe`, which were the two long
inline strings. `Diagnostics` keeps `networkSummary` and `probe`, which the Test
action still reads.

## 4. Deliberately left alone

- `FileRow.error` and the talk row's `detail` are the bridge's own short strings
  (`readableTalkError` in `bridge/extensions/pi-remote.ts:1056`), not exception
  text, so they stay as they are.
- The Troubleshooting section's crash trace (`Screens.kt:2346`) is the artifact
  itself and sits in a section the human opens on purpose.

## 5. What only the human can confirm on the phone

- That "Open Tailscale" lands on the Tailscale app (package visibility on the
  device, not just in the manifest).
- That "Get Tailscale" opens the Play Store listing on a phone without it.
- That the VPN line matches reality with Tailscale on, off and with another VPN
  active.
- That the folded detail is readable and selectable in both themes, and that the
  chip row does not wrap badly on a narrow screen.
