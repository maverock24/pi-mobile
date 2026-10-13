# Files library: design decisions

Worktree `/home/maverock24/github/pi-mobile-keys`, branch `main`, base HEAD
`34b8832` (QRSPI: kokoro-tts keep the composer's controls on one row). The
sibling checkout `/home/maverock24/github/pi-mobile` was read only (`state.md`)
and not touched.

## What the human asked for

Rename the Talks tab to Files, list any file a pi session produced (audio, video,
image, text, other), do the obvious thing per kind on tap, and keep the TTS path
exactly as it is. The rendered talk must still appear.

## Decisions

1. **There is no new directory and no database.** The library is a `readdir` of
   the directory that already exists, `<agent dir>/talks`, the same one the
   renderer writes and the skill drafts into. A session publishes a file by
   putting it there; a talk publishes itself through `POST /api/talks` as before.
   `bridge/lib/files.ts` scans the top level only: a subdirectory (`scripts/`) is
   a workspace, not the library.

2. **Kind comes from the extension.** `KIND_BY_EXT` maps to audio, video, image
   or text; anything else lists as `other`, because hiding a produced file is
   worse than saying it cannot be drawn. The bridge only names the kind; the app
   decides what a kind does.

3. **One row per file, and a talk is its audio row.** A talk's `<id>.mp3`,
   `<id>.txt` and `<id>.json` are one entry, the mp3. Its script and metadata are
   the talk's internals, not separate library items. `bees.mp3` (an MP3 with no
   record) is an orphan talk and appears as an audio row, which is why the
   existing file still shows.

4. **The bridge serves a file by its name, not by an id.** `GET /api/files` is
   the whole library; `GET /api/files/<name>` serves one file with the existing
   byte-range code. `isSafeFileName` (leading alphanumeric, no `/` or `\`,
   bounded length) is the one shape that can be listed and the one that can be
   served, so every listed row is downloadable and a name cannot walk out of the
   directory. `serveLibraryFile` is the whole download path, so the check script
   and the route run the same code.

5. **`id` stays a talk's hex id; `name` is the on-disk name.** The existing SSE
   `talk` event still carries only an id, so the app derives `<id>.mp3` from it;
   a files row carries both. The app keys rows and its local store by `name`,
   which cannot collide, rather than by an extensionless id.

6. **The old endpoints are untouched.** `POST /api/talks`, `GET /api/talks`,
   `GET /api/talks/<id>.mp3`, `DELETE /api/talks/<id>` and
   `POST /api/talks/<id>/link` are byte-for-byte the same behaviour, so the skill
   and any older app are unaffected. The app switched to `/api/files`; the talk
   routes remain for the skill and for `bees` compatibility.

7. **The app does the obvious thing per kind.** Audio and video download then
   play through the existing media3 player and `MediaSessionService`. Text is
   downloaded and shown selectable; images are downloaded and decoded
   (downsampled to 2048px) and shown. `other` says "This file cannot be shown
   here yet" and offers no tap that does nothing. A missing piece (an image that
   will not decode) says so on the preview screen.

8. **The shrink() rule is honoured.** `filePayload()` trims each row itself and
   the route sends it whole, exactly like `talksPayload`/`commandsPayload`, so
   `shrink()` never cuts the list at 40.

## What I chose not to do

- **No video surface.** Rendering video needs `media3-ui` (`PlayerView`), which is
  a new dependency the task rules out, so video plays its audio through the same
  player with no picture. This is the literal "video plays the same way" and it
  is recorded here as the limit rather than hidden.
- **No recursive scan.** Subdirectories are not listed. The skill's drafts under
  `talks/scripts` stay drafts.
- **No bridge `DELETE /api/files/<name>`.** Delete in the app removes the phone's
  copy only, as it already did for talks; nothing in the request asked to remove
  a producer's file.
- **No local-cache migration.** The phone store now writes `<name>.json`
  sidecars; a talk downloaded by the old build (`<hex>.json`) is read by the same
  reader through a one-branch fallback, so an already-downloaded talk still plays
  offline. See the fallback in `FilesStore.read`.

## Uncertain

- Video playback without a surface is unverified on a device: ExoPlayer should
  fall back to audio-only with no surface set, but that is not tested here (no
  emulator in this environment).
- The app calls `/api/files`; a bridge older than this commit answers 404 and the
  Files view shows the fetch error. Only this repo's bridge is in scope.

## Evidence

- Bridge types: `tsc -p /tmp/pibridge-tsconfig.json` passes.
- `node bridge/check-files.ts` binds a loopback port, fetches `/api/files` and
  `/api/files/bees.mp3`, and passes; it can fail (a mutated content type failed
  the `audio/mpeg` check, then the file was restored byte-identical).
- `node bridge/check-talks-ranges.ts` still passes, so talks serving is intact.
- App: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew
  :app:compileDebugKotlin --offline` succeeds.
