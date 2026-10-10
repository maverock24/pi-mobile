# Research: the design record and the backlog (cluster I)

Cluster I is questions 43 to 46. All line numbers are against `e976063`, the working tree HEAD
(`git rev-parse HEAD` = `e976063990db1365e793e01df965bcb30a3ccb39`). Only `questions.md` was read
from `.scratch/`; the effort ticket was deliberately not read.

## 43. PRODUCTION-READINESS 0.8, 1.7, 1.13 and 2.9 versus the code

The four items sit in the document's open tier tables (`docs/PRODUCTION-READINESS.md`), not in the
"What is already production-grade" table, so the document claims each is still open. Each has code
that ships all or most of its acceptance criteria.

### 0.8 Pairing by QR code

Doc line 49:

> `| 0.8 | Pairing by QR code | Copy-pasting a 64-character token through a chat app is the most fragile step in setup | Bridge prints a QR (host, port, token, optional fingerprint); app scans it with CameraX and fills Settings; manual entry still works | M |`

Code that ships it:

- `bridge/extensions/pi-remote.ts:1857`: `pi.registerCommand("pair", {`
- `bridge/extensions/pi-remote.ts:966`: `if (req.method === "POST" && url.pathname === "/api/pair") {`
- `app/src/main/java/com/maverock24/pimobile/net/Pairing.kt:24`: `object Pairing {`
  - `:44`: `fun parse(link: String): Invite? {`
  - `:61`: `suspend fun exchange(invite: Invite, deviceLabel: String): Paired = withContext(Dispatchers.IO) {`
- `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt:1853`: `val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->`
  - `:1912`: `Text("Scan the pairing QR", style = MaterialTheme.typography.bodyLarge),`
  - `:2501`: `/** ZXing runs the scan in its own activity and hands back the raw QR text. */`
- `app/build.gradle.kts:81`: `implementation("com.journeyapps:zxing-android-embedded:4.3.0")`
- `app/src/main/java/com/maverock24/pimobile/MainActivity.kt:140`: `val invite = Pairing.parse(link)`
- Manual entry, `ui/Screens.kt:1925`: `placeholder = { Text("pi-remote://pair?v=1&u=…&c=…") },`

Two mismatches between the doc's wording and the shipped code: the doc says the app scans "with
CameraX" while the dependency is ZXing (`app/build.gradle.kts:81`), and the doc puts the token in
the QR while `net/Pairing.kt:19` says `* device token. The token is deliberately kept out of the QR, so a screenshot of the`.

### 1.7 History search

Doc line 65:

> `| 1.7 | History search | Answers accumulate quickly and there is no way back | Search across the current session's answers, jump to the match | M |`

Code that ships it:

- `app/src/main/java/com/maverock24/pimobile/ui/ChatViewModel.kt:970`: `fun search(query: String) {`
- `app/src/main/java/com/maverock24/pimobile/net/PiRemoteClient.kt:84`: `suspend fun search(query: String, limit: Int = 40): JSONObject {`
- `bridge/extensions/pi-remote.ts:993`: `if (req.method === "GET" && url.pathname === "/api/search") {`
- Render and jump, `ui/Screens.kt:1231`: `SearchResultRow(` and `:1249`: `private fun SearchResultRow(hit: SearchHit, openable: Boolean, onOpen: () -> Unit) {`

### 1.13 Haptics and motion

Doc line 71:

> `| 1.13 | Haptics and motion | Small feedback is what separates polished apps from utilities | Haptic tick on send, on option tap, and on run completion; animations honour "reduce motion" | S |`

Code that ships most of it:

- `app/src/main/java/com/maverock24/pimobile/ui/Tactile.kt:47`: `object Haptics {`
  - `:50`: `fun press(view: View) {`
  - `:62`: `fun confirm(view: View) {`
  - `:76`: `fun reject(view: View) {`
  - `:108`: `fun Modifier.tactile(`
- Applied on the composer controls, `ui/Screens.kt:1766`, `:1780`, `:1790`, `:1804` (all `tactile(haptics = true, depth = AnswerStyle.keyDepth)`), and on the outcome path `:339`: `if (event.ok) Haptics.confirm(view) else Haptics.reject(view)`.

The "animations honour reduce motion" half is not fully met by the custom sweep:
`app/src/main/java/com/maverock24/pimobile/ui/AnswerView.kt:343`
`internal fun rememberSweep(periodMillis: Int): State<Float> {` advances with
`:350` `withFrameNanos { now -> progress.floatValue = ((now - start) % period) / period.toFloat() }`,
which the KDoc at `:337` describes as `* It is advanced by the frame clock directly rather than by an animation spec.`; a `MotionDurationScale` of zero does not reach it. Compose's own
`animateFloatAsState` in `Tactile.kt:130` does obey it.

### 2.9 Crash and ANR visibility

Doc line 85:

> `| 2.9 | Crash and ANR visibility | A crash on the phone is currently invisible unless the user reports it | Opt-in, self-hosted or privacy-preserving crash reporting, or at minimum a "send diagnostics" action that packages the last error | M |`

Code that ships the "last error" half:

- `app/src/main/java/com/maverock24/pimobile/data/CrashLog.kt:32`: `class CrashLog(context: Context) {`
  - `:84`: `fun recordCrash(error: Throwable) {`
  - `:106`: `fun lastCrash(): String? = runCatching { crashFile.readText().trim() }`
- Installed as the uncaught handler, `PiRemoteApp.kt:20`: `val log = CrashLog(this)` and `:30`: `Thread.setDefaultUncaughtExceptionHandler { thread, error ->`
- Shown in Settings, `ui/Screens.kt:2105`: `lastCrash != null -> "The last launch crashed"`, `:2129`: `lastCrash?.let { trace ->`, `:2140`: `text = "The text selects, so it can be copied out of here.",`

There is no ANR capture. `grep -rin "ANR|ApplicationExitInfo|ExitInfo" app/src/main bridge` returned
one unrelated hit (`update/UpdateChecker.kt:160`, `canRequestPackageInstalls`). There is no dedicated "send diagnostics" button; the trace is selectable, and the only copy is manual.

## 44. The lab path cited by `AnswerStyle.kt` and what is on disk

`app/src/main/java/com/maverock24/pimobile/ui/AnswerStyle.kt:14`:

> `* on 2026-10-04 (pi-remote-design-lab/out/choices-latest.json):`

That file does not exist.

Directory listing of `/home/maverock24/pi-remote-design-lab/out/` (`ls -la`):

```
-rw-r--r-- 1 root       root        570 Oct  5 12:35 choices-tactile-2026-10-05T12-35-38.json
-rw-r--r-- 1 root       root        570 Oct  5 12:35 choices-tactile-latest.json
-rw------- 1 maverock24 maverock24   65 Oct  4 13:47 lab-token
-rw-r--r-- 1 maverock24 maverock24    0 Oct  4 13:47 requests.md
```

There is no `choices-latest.json`, so `AnswerStyle.kt:14` names a file that was never saved or was
removed. The same missing path is named at `docs/UX-REVIEW.md:304` (`- \`AnswerStyle.kt:18-20\` cites \`pi-remote-design-lab/out/choices-latest.json\`, which no longer`) and is still the lab
server's default: `serve.py:16` (`LAB_CHOICES   file in out/ to write (default choices-latest.json)`) and `serve.py:185` (`latest = OUT / os.environ.get("LAB_CHOICES", "choices-latest.json")`).

The cited line range in `docs/UX-REVIEW.md:304` is stale: the path is on `AnswerStyle.kt:14`, not
`:18-20`.

### Every other dead path reference in `docs/`, `README.md`, `GLOSSARY.md`

I extracted every backticked path-like token and every Markdown link from `docs/*.md`,
`docs/adr/*.md`, `README.md` and `GLOSSARY.md`, then tested each on disk. These exist:

- `docs/SESSION-MANAGEMENT.md`, `docs/THREAT-MODEL.md`, `docs/PRODUCTION-READINESS.md`,
  `docs/adr/0001-port-bind-is-the-authority.md`, `docs/adr/0002-bridge-source-in-this-repo.md`,
  `GLOSSARY.md`, `README.md`
- `data/TokenCrypto.kt` (resolves to `app/src/main/java/com/maverock24/pimobile/data/TokenCrypto.kt`),
  `ui/Screens.kt`, `ui/ChatViewModel.kt`, `ui/AnswerStyle.kt`, `ui/AnswerBlocks.kt`,
  `ui/AnswerView.kt`, `ui/Questions.kt`, `ui/Tactile.kt`, `MainActivity.kt`, `net/Diagnostics.kt`,
  `net/PiRemoteClient.kt`, `voice/Dictation.kt`, `AndroidManifest.xml`, `res/values/strings.xml`,
  `res/values/themes.xml`, `network_security_config.xml`
- `~/.pi/agent/extensions/pi-remote.ts`, `~/.pi/agent/lib/remote-question.ts`,
  `~/pi-remote-design-lab/serve.py`, `~/.config/pi-remote/token`, `~/.android-keys/pi-mobile.keystore`,
  `~/.local/share/pi-remote`, `/tmp/pi-remote.ts.bak`, `/root/pi-mobile-keys/keystore-password.txt`
- Runtime artifacts that exist right now: `/tmp/pi-remote-pair.png`

Referenced paths that do not exist on disk:

- `pi-remote-design-lab/out/choices-latest.json` — `ui/AnswerStyle.kt:14`, `docs/UX-REVIEW.md:304`.
- `owner.json` — `README.md:107` (`process that really bound the port claims ownership in \`owner.json\`, so a second pi session`). The lease file is now address-keyed: `bridge/lib/lease.ts:42-43`
  (`export function leaseFileName(host: string, port: number): string {` / `` `return \`owner-${host}-${port}.json\`; ``), and `bridge/extensions/pi-remote.ts:112` says
  `/** One lease per address. The old single owner.json is ignored, never migrated. */`. The live
  directory holds `owner-100.78.153.24-8787.json`, not `owner.json`. `GLOSSARY.md:22` lists
  `owner.json` only under `_Avoid_:` for **Lease**, and `docs/SESSION-MANAGEMENT.md:186` names it
  only for "a session still running the old revision", so those two are not stale claims.
  `docs/adr/0001-port-bind-is-the-authority.md:7` names `~/.config/pi-remote/owner.json` while
  describing the pre-revision design it supersedes.
- `~/.config/pi-remote/owner.json` — absent from the live directory; the bridge writes the
  address-keyed lease instead (same evidence as above).
- Runtime files that are absent but are not repo paths: `docs/THREAT-MODEL.md:184` `latest.json`
  (release manifest, `bridge/extensions/pi-remote.ts:234` reads `release/latest.json`),
  `docs/THREAT-MODEL.md:304` `question-pending.json` (a described attack fixture),
  `docs/THREAT-MODEL.md:382` `tokens.json` (present at `~/.config/pi-remote/tokens.json`).

`GLOSSARY.md` contains no file or path reference other than the `_Avoid_: lock file, owner.json` note.

## 45. What design record exists, and does it define the UI's vocabulary

Record inside the repo:

- `GLOSSARY.md` (repo root). It defines only bridge vocabulary: **Bridge**, **Owner**, **Lease**,
  **Takeover**, **Handover**, **Release**, **Attached session**, **Pinned session**, **Device
  token**, **Bound address** (`GLOSSARY.md:9-58`).
- `docs/adr/0001-port-bind-is-the-authority.md:1` (`# The bind on the port decides who owns the bridge`) and
  `docs/adr/0002-bridge-source-in-this-repo.md:1` (`# The bridge extension lives in this repository`). Both are about the bridge, not the UI.
- `research.md` (repo root) is a UI/UX principle rubric (`# Research: Tick-off UI/UX evaluation rubric (Android app audit)`), not a recorded design decision.
- `/home/maverock24/github/pi-mobile/.scratch/retro-2026-10-10-bridge-diagnosis.md` exists (found by
  `find . -name "*.md"`); not read here, because the task forbids reading anything else under
  `.scratch/`.

Record outside the repo, at `/home/maverock24/pi-remote-design-lab/`:

- `design-lab.html` (`<title>pi remote — design lab</title>`), `tactile-lab.html`
  (`<title>Button feel: tactile feedback for pi remote</title>`), `tactile3d-lab.html`
  (`<title>3D button styles for pi remote</title>`), plus `serve.py`.
- `out/choices-tactile-latest.json` and `out/choices-tactile-2026-10-05T12-35-38.json` (identical,
  saved `2026-10-05T12:35:38+0300`); `out/requests.md` is 0 bytes; `out/lab-token` is a 65-byte
  secret.
- No `choices-latest.json`, so the base `design-lab.html` choices are not recorded on disk (see
  question 44).

Vocabulary: no record defines the UI's own words.

- `grep -ic "deck" GLOSSARY.md` = 0; `grep -ic "deck" README.md` = 0; the labs contain 0 matches for
  `deck`. `grep -ric "deck" docs/` matches only `docs/UX-REVIEW.md` (6).
- `grep -in "appearance|theme|transcript|screen|ui " GLOSSARY.md` = no matches.
- `design-lab.html:309` uses "cards" only as a list style: `{ v: "cards", label: "Card per item", desc: "Each item gets its own surface." }`, not as a screen name.
- The review itself notes the gap at `docs/UX-REVIEW.md:314`: `There is also no place for the UI's own vocabulary: the GLOSSARY covers the bridge (owner, lease,` ... `handover) but not what the screens call things, which is why \`Cards\` and \`deck\` coexist.`

`docs/UX-REVIEW.md:307` says the README calls the deck "the deck", but `README.md` contains no
occurrence of `deck` (`grep -ic "deck" README.md` = 0), so that specific review claim does not hold
at HEAD.

## 46. Do the recorded lab choices corroborate or contradict the two divergences

The full recorded choice set, `out/choices-tactile-latest.json`:

```
"press_motion": "1a",
"tap_haptic": "2a",
"outcome_haptic": "3a",
"surfaces": "4b",
"mechanism": "5a"
```

with `"summary": "press motion: Scale on every tappable control\ntap haptics: Press and release pair\noutcome haptics: Confirm on success, reject on failure\nwhich controls: Only controls that change pi's state\nhaptic mechanism: Platform constants only"`.

### Press-scale missing on four controls

The recorded option, `tactile-lab.html:179-182`:

```
<input type="radio" name="press_motion" value="1a" checked>
<span class="optbody">
  <span class="opthead"><span class="optlabel">Scale on every tappable control</span><span class="badge">recommended</span></span>
```

The code applies `Modifier.tactile()` everywhere except four controls, each quoted:

- copy, `ui/Screens.kt:1418`: `IconButton(` (the block follows at `:1419-1423`, no `.tactile(`)
- share, `ui/Screens.kt:1432`: `IconButton(` (block `:1433-1441`, no `.tactile(`)
- pin, `ui/Screens.kt:1450`: `IconButton(onClick = onPin) {` (no `.tactile(`)
- the composer `/` affordance, `ui/Screens.kt:1731`: `IconButton(onClick = onToggleCommands) {` (no `.tactile(`)

So the code contradicts `press_motion: 1a`. The review states the same at
`docs/UX-REVIEW.md:143-146`: `**Four tappable controls miss the press-scale the project decided on.**` ... `is applied through Modifier.tactile(), which is absent on the three answer action icons (Screens.kt:1418,` `:1432, :1450) and the composer's / affordance (:1731), while every other control has it.`

### `Haptics.confirm` on copy and share

The recorded option, `tactile-lab.html:297-300`:

```
<input type="radio" name="surfaces" value="4b" checked>
<span class="optbody">
  <span class="opthead"><span class="optlabel">Only controls that change pi's state</span><span class="badge">recommended</span></span>
```

and its description at `tactile-lab.html:299`:

> `Send, Stop, mic, questionnaire options, pairing, update, retry; not navigation and not opening a link.`

The code rings the outcome haptic on copy and share:

- `ui/Screens.kt:1421`: `Haptics.confirm(view)` (in the copy `onClick`, after `clipboard.setText(...)`)
- `ui/Screens.kt:1439`: `Haptics.confirm(view)` (in the share `onClick`, after `startActivity(Intent.createChooser(...))`)

Copy and share are neither in the `4b` list nor state changes, so the code contradicts
`surfaces: 4b` by omission. The KDoc above the row does not defer to the record;
`ui/Screens.kt:1400-1402` says `view. All three ring the confirm haptic and raise the chat screen's notice, so` / `a tap always says it did something.`

Neither divergence is itself written into the record. `docs/UX-REVIEW.md:310-311`:
`The two divergences in section 5 (press-scale missing on four controls; confirm haptics on copy` `and share) are the code disagreeing with a recorded decision. Neither is in the record.`
