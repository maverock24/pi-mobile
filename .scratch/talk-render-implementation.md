# Talk render path: implementation detail

Branch `main`, worktree `/home/maverock24/github/pi-mobile-keys`, base commit
`cc5b2b0`. The sibling checkout `/home/maverock24/github/pi-mobile` was not
touched.

## What was built

Three pieces, plus the installer wiring.

### 1. Storage and range serving: `bridge/lib/talks.ts` (new)

A dependency-free module (imports only node builtins) so the range logic can be
run by a plain node script. It owns:

- `talksDir()` = `<PI_AGENT_DIR or ~/.pi/agent>/talks`. Mirrors install.sh.
- One talk = three siblings: `<id>.json` (metadata), `<id>.txt` (the script copy),
  `<id>.mp3` (the audio). `newTalkId()` is 8 random bytes hex.
- `saveTalk` writes through a `.tmp` + rename, so a reader never sees half a
  record. `listTalks()` sorts newest first and skips a corrupt record rather than
  failing the list. `removeTalk()` unlinks all three.
- `estimateDurationSeconds(script)` = words / 150 * 60. 4500 words -> 1800s.
- `parseRangeHeader(header, size)` -> `none` | `invalid` | `{start,end}` (inclusive).
  Only a single range; `bytes=0-99`, `bytes=N-`, `bytes=-N` are handled; a start at
  or past EOF is `invalid`; a suffix of 0 is `invalid`; an end past EOF clamps.
- `serveFile()` writes 200 (whole), 206 (range, with `Content-Range`), 416 (with
  `Content-Range: bytes */size`), 404 (missing); `Accept-Ranges: bytes` on all;
  `HEAD` gets headers with no body. Returns what it answered for auditing.

### 2. Endpoints: `bridge/extensions/pi-remote.ts` (modified, +387)

- `POST /api/talks` (`pi-remote.ts:1251`): body `{title, script|scriptPath, voice?}`.
  Requires bearer auth. Validates the title, resolves the script (text wins over
  path; a path is read with a 2 MB cap), then checks the renderer with
  `edgeTtsProblem()` before promising anything. Creates the id, copies the script
  into the talks dir, writes metadata as `rendering`, `spawn`s edge-tts
  (`--file <script> --voice <voice> --write-media <mp3>`), emits a `talk` SSE
  frame and audits, then returns 200 immediately with the id, state and estimate.
  The render is a child of the bridge process, not of the request, so closing the
  phone does not cancel it.
- `GET /api/talks` (`pi-remote.ts:1244`): `{talks:[...]}` newest first, each row
  `id,title,state,createdAt,bytes,durationSeconds,durationEstimated,voice,error`.
  Rows are trimmed individually and the array is not passed through `shrink()`,
  so the MAX_ARRAY=40 cap cannot truncate the library.
- `GET /api/talks/<id>.mp3` (`pi-remote.ts:1411`): bearer auth, then
  `serveFile(..., "audio/mpeg")` with range support.
- `DELETE /api/talks/<id>` (`pi-remote.ts:1339`): bearer auth, removes the three
  files, drops any outstanding link for that talk.
- `POST /api/talks/<id>/link` (`pi-remote.ts:1378`): bearer auth, mints a 32-byte
  base64url key, stores `{talkId, expiresAt}` in memory for `TALK_LINK_TTL_MS`
  (5 min), returns `{id, url, expiresAt}`. The URL is built with the same host
  choice as pairing (`pairHost()`: MagicDNS when Tailscale answers, else the bound
  host) and the bound port. `GET` of the mp3 with `?key=` is handled *before* the
  bearer gate (`pi-remote.ts:1226`); the key is deleted on first use, so it is
  single-use. The bearer token is never read from a query string, and the key is
  never logged.
- `edgeTtsProblem()` (`pi-remote.ts:1078`): `whichSync` finds the binary, then
  `edge-tts --version` proves the interpreter still imports the module without
  touching the network. Missing -> "edge-tts is not installed. Install it with
  `pipx install edge-tts` ..."; broken -> "edge-tts is installed at <path> but
  could not run (<last stderr line>). Reinstall it with `pipx install --force
  edge-tts` ...". Both are 503, never a traceback. A render that fails after
  starting records the last stderr line only (`startTalkRender`).

### 3. The skill: `bridge/skills/talk/`

- `SKILL.md`: frontmatter `name: talk`; instructs the model to size the script at
  150 wpm (30 min -> 4500 words), write it to a file, run the helper, and report
  the link and length. No confirmation step.
- `render-talk.sh`: reads the first token from `dirname($PI_REMOTE_TOKEN_FILE or
  ~/.config/pi-remote/token)/tokens.json`, POSTs to `/api/talks`, polls
  `/api/talks` until ready/failed (default 1800s wait), then mints a link and
  prints `play: <url>`. Uses python3 (edge-tts needs it anyway) to parse JSON and
  to build the request body without hand-escaping. Base URL defaults to
  `http://127.0.0.1:8787` (the loopback twin); `PI_REMOTE_URL` overrides.
- Edge-tts is only ever invoked as a subprocess by the bridge. Nothing vendors,
  bundles or redistributes it.

### 4. `bridge/install.sh`

Three lines added to the existing `sources` array: `lib/talks.ts`, and the two
skill files. Follows the existing pattern exactly (per-file `src:dst`, sha256,
missing-source check).

## The check

`bridge/check-talks-ranges.ts` (new), run directly (node 22 strips types):
`node bridge/check-talks-ranges.ts`. It starts an HTTP server on a loopback port,
serves a known 1000-byte file through the same `serveFile` the bridge uses, and
asserts: full GET is 200 with the whole file and `Accept-Ranges`; `bytes=0-99` is
206 with exactly 100 bytes and `Content-Range: bytes 0-99/1000`; `bytes=1000-` is
416 with `Content-Range: bytes */1000`.

Mutation check: changed `const length = end - start + 1` to `end - start`,
re-ran, got two FAILs ("99 bytes, want 100" and "body differs from head"),
restored, sha256 matched `c7fcf43979e4...`, test passed again.

## Verification run (temporary, not committed)

Two throwaway harnesses in `/tmp` booted the real extension with a stubbed
`ExtensionAPI` on `127.0.0.1`, with a fake `edge-tts` on PATH.

- `/tmp/check-talks-api.ts`: 27 checks, all pass. Covers missing edge-tts -> 503
  with `pipx install edge-tts` and no traceback; POST returns `rendering`
  immediately with an estimate; the background render reaches `ready` with
  `bytes: 500`; full GET 200 `audio/mpeg`; range 0-99 -> 206, 100 bytes,
  `bytes 0-99/500`; range `500-` -> 416 `bytes */500`; link mint returns a `key=`
  URL with no bearer; the bare link fetch works once and is 401 on the second
  use; DELETE 200 and the file 404s after.
- `/tmp/check-skill.ts`: runs `render-talk.sh` against the live bridge. Passes,
  and prints `render-talk ready: "Roman roads", about 30 minutes` with a play
  link, from a 4500-word script.

## Uncertainties and deviations

- Duration is an **estimate** from the word count (150 wpm), marked
  `durationEstimated: true`. edge-tts can emit `--write-subtitles` with real
  timings; that was not wired up. The skill's "about N minutes" is that estimate.
- Single-use links and HTTP range seek pull in opposite directions: once the link
  is spent, a player cannot resume or seek via that URL, only the whole first
  download. This is the brief's literal ask, and the intended flow (tap, download
  once, play locally) is unaffected; 30-minute seeking is the app's job with the
  bearer. Recorded as a known trade-off, not a defect.
- `POST /api/talks` with a `script` body cannot exceed `readBody`'s 256 KB cap
  before the 2 MB script check is reached; the check matters for `scriptPath`.
- This environment's edge-tts is broken independently of this change: the pipx
  venv at `~/.local/share/pipx/venvs/edge-tts` pins python3.12 in its
  `pyvenv.cfg`, but `/usr/bin/python3` is now 3.14, so `import edge_tts` fails
  (`ModuleNotFoundError`). `edgeTtsProblem()` turns that into the actionable
  reinstall message rather than a traceback. Not fixed here; it is the host's.
- The integration harnesses are not committed, per the brief's one-script test
  ask. Only `check-talks-ranges.ts` is durable in the repo.
