# Car mode episode list, and clearing the phone's copies in Settings

Worktree `/home/maverock24/github/pi-mobile-keys`, branch `main`, base HEAD
`d039e8b` (QRSPI: wlan-web-access keep the library current and let a message
play an episode). The sibling checkout `/home/maverock24/github/pi-mobile` was
not written to.

## What was asked

Two changes. Car mode has to list every episode and start one from a row while
staying usable at a glance, using the player and service that already exist, and
without touching the voice path or the current play/pause. Settings has to be
able to delete the copies this app keeps on the phone, saying how much that
frees and asking first, and without ever reaching the bridge's library or
`~/.pi/agent/talks`.

## Change 1: the episode list in car mode

`CarListenPanel` (`ui/CarScreen.kt:326`) keeps the "Now playing" card exactly as
it was, including its 96 dp play/pause and its read-only progress readout, and
replaces the old three-row short list below it with a `LazyColumn` of every
audio and video row the library holds (`:420`). The card does not scroll with
the list, so the control that was there before is still on the panel without a
scroll.

Why a list after all, when `car-mode.md` argued for three rows: the human asked
for the episodes, and three rows cannot express thirty. What the design was
protecting is kept, not dropped. Each row is a `Surface` with
`heightIn(min = CarStyle.tap)` (72 dp), the title in `titleLarge` and one line
under it in `titleMedium` that carries the category and what a tap will do, so a
row is read once rather than compared with the row above it. There is no search,
no slider, no delete and no preview here; browsing is a scroll and a tap.

**The category comes from the title, not from a new field.** The bridge's
`fileSummary` (`bridge/lib/files.ts:250`) does not forward the `category` the
episode records carry, so `carCategory` (`ui/CarScreen.kt:470`) reads the words
before the colon: every episode title reads "Biology: How antibiotics actually
stop bacteria", so the prefix is the subject. A title with no colon, which is
every non-episode audio file, reads as "Other" rather than being given a group
it does not have. This needs no bridge change, which matters because the bridge
on the laptop is the thing that must keep working untouched, and an older bridge
answering `/api/files` without a category field would make an app-side field
useless anyway.

**What a row says and whether it is tappable** is one pure decision,
`carEpisodeState` (`ui/CarScreen.kt:439`), so the four ways a row can be
unplayable are covered by unit tests without a player or a bridge:

| row state | line under the title | tappable |
|---|---|---|
| failed render | "the render failed, so there is nothing to play: <error>" | no |
| still rendering | "still rendering, so it cannot play yet" | no |
| download in flight | "downloading 42%" | no |
| playing now | "on this phone, tap to play" becomes "playing now, tap to pause" | yes, pauses |
| on the phone | "on this phone, tap to play" | yes |
| not on the phone, no bridge paired | "not on this phone, and no bridge is paired to fetch it" | no |
| not on the phone, bridge paired | "not on this phone, tap to download and play" | yes |

A tappable row follows the rule the old short list had (`ui/CarScreen.kt:493`):
a file already on the phone goes through `playOrPause`, which is the same
`TalkPlayer` and `TalkPlaybackService` the Files screen drives; a file that is
not is passed to `downloadFile(autoPlay = true)`, which downloads and then plays
through that same player. There is no second player, no second service and no
new dependency.

The voice path is untouched: `playFromSpeech`, `playKeywords` and
`spokenFileMatches` were not edited, and a spoken "play chemistry" still calls
the same `openFile`/`downloadFile` path it did before. The play/pause card above
the list was not edited.

An empty list distinguishes the three cases it can be empty for, which is the
rule section 13 of the design calls out: "Loading episodes…", "No episodes here.
Pair the bridge in Settings to see them." when nothing is paired, and the last
fetch error in the error colour when the bridge could not be reached
(`ui/CarScreen.kt:401`). A failed fetch never reads as "No episodes yet."

### What was deliberately not done here

- **No category headers.** Every row carries its category, so a scrolled list
  still says which subject a row belongs to; a header would be off screen by the
  time the driver is reading row twelve.
- **No refresh button.** The library is read when car mode is entered and again
  when the app comes back to the foreground (`filesShown`), which is the same
  rule the Files view uses. A refresh control would be a third thing to aim at
  while driving for a case the resume already covers.
- **Text, image and "other" files are not listed.** The car screen plays
  episodes; opening a recipe is not a driving task, and those rows stay in the
  Files view.
- **No new icons or dependencies.** The row is a `Surface` with text, from the
  same Compose Material 3 set the screen already imports.

## Change 2: clearing the phone's copies in Settings

A new **Storage** section sits between App Updates and Troubleshooting
(`ui/Screens.kt:2217`), presented the way every other section is: a collapsed
statement, expanded on a tap, with the action inside. Its subtitle is the
footprint ("12.4 MB of downloads and cache", "Nothing stored on this phone", or
"A download is running"), the body states what clearing does and what it does
not touch, and the delete button is disabled when there is nothing to clear.

- **The size is measured before it is offered.** `localCopiesBytes`
  (`ui/ChatViewModel.kt:486`) is read by `measureLocalCopies` (`:2412`) on
  `Dispatchers.IO` when Settings opens (`ui/Screens.kt:1945`). It is the sum of
  the download directory and the cache directory, so what the section offers to
  free is exactly what the clear will walk.
- **It asks first and says it cannot be undone** (`ui/Screens.kt:2257`), in the
  same two-button shape the existing "Clear the saved data and the trace" uses:
  a sentence naming the bytes and the fact there is no undo, then "Delete them"
  or "Cancel". The confirmation is not a dialog because the rest of Settings
  does not use one.
- **A download in flight defers the clear.** `clearLocalCopies`
  (`ui/ChatViewModel.kt:2433`) refuses while `downloadProgress` is not empty and
  says so; the button is disabled for the same reason
  (`clearingBlocked`, `MainActivity.kt:318`). Without that, a download would
  finish writing into a file the clear had just unlinked, and its row would
  claim bytes that are gone.
- **Audio that is playing is stopped first, deliberately, and said out loud.**
  If the player holds a file (`player.playingId != null`) the clear stops it
  before deleting, and the confirmation reads "Cleared 38.2 MB. Playback
  stopped." A stop the human asked for reads differently from audio that
  disappears mid-sentence.
- **The library still lists what the bridge lists.** Rows are not rebuilt from
  the store after a clear, which would erase the bridge's rows; only rows that
  are not in `lastRemoteNames` (`ui/ChatViewModel.kt:494`, set on every
  successful fetch at `:2168`) are dropped, because those are the phone-only
  rows whose bytes just went. `downloadedIds` is rebuilt by reading the store
  back rather than cleared, so a download that landed during the clear is
  believed and one whose bytes went is not. A cleared episode then shows "tap to
  download and play" in car mode and "Play" in Files, which is the existing
  download-then-play path.
- **`formatSize` (`ui/FilesScreen.kt:452`) went from `private` to `internal`**
  so the section, the notice and the library rows all format bytes the same way.
  That is the only edit to an existing file screen.

### How the clear is kept off the laptop

Three separate reasons, each checkable:

1. **The code takes a directory, not a name.** `clearDirectory`
   (`data/LocalCopies.kt:30`) is the only multi-file delete in the app. It lists
   the directory it is given and deletes the `File` objects `listFiles()`
   returned, so no path is built from a name the bridge sent, and it does not
   recurse, so a directory inside one is not walked out of. The tests prove it
   leaves a subdirectory (empty and non-empty) and a sibling directory standing.
2. **The two directories it is given are the app's own.**
   `FilesStore.clearAll` (`data/FilesStore.kt:97`) passes the store's own
   `File(context.filesDir, "talks")`, and the view model passes
   `app.cacheDir`. Those are the app's private storage areas; nothing else on
   the phone is named anywhere in the clear path.
3. **There is no path from the app to the laptop's library at all.** The bridge
   is reached over HTTP by `PiRemoteClient`; the app never mounts a share, never
   receives a path from the bridge, and no source file in `app/src/main/java`
   names `~/.pi/agent` or `/home`. Downloads land where the app wrote them, so
   deleting them cannot reach the source.

The other deletions in the app were checked too, and all of them are inside the
same two private areas or inside `filesDir`: `TranscriptStore` (`:124`, `:146`,
`:214`), `PinsStore` (`:85`, `:86`, `:111`), `CrashLog` (`:81`, `:116`),
`FilesStore.delete` (`:85`, `:86`), a failed download's partial file
(`ChatViewModel.kt:2227`) and the update APK (`UpdateChecker.kt:91`, `:95`).

### What the clear does not do

- It does not touch the transcript cache, the pins, the token or the certificate.
  Those live in `filesDir` outside the download directory, and the existing
  Troubleshooting section is the control that clears them.
- It does not remove a downloaded update APK from the cache and keep it back.
  The cache is cleared whole. That APK is re-downloadable from App Updates, and
  an install that dies because the file went is retried by downloading it again;
  the alternative, a cache clear that quietly keeps files, would make the size
  offered and the size freed disagree.
- It does not cancel a download. There is no cancel in the client today; asking
  the human to clear again when it finishes is the honest version of that.

## The browser, and drift (design section 13)

Nothing in the browser changes, and it does not fall behind:

- The car episode list is car mode, and car mode does not exist in the browser.
  The browser's own Settings already names it as phone-only, in the "Only on the
  phone" note at `app/src/main/assets/web/screens/settings.js:131`: "Search,
  slash commands, dictation and car mode are phone features and are not in the
  browser."
- The browser's Files screen lists the same bridge library, and is unchanged by
  this: it plays over the relay from the bridge, never from the phone's
  downloaded copies, so clearing those copies changes nothing it can see.
- The browser's Settings already names this action as phone work, in the same
  note: "Troubleshooting and clearing the saved data act on the phone's
  storage." Adding a browser button that deletes files on the phone would give a
  remote session a destructive capability the security review never approved,
  which is a different decision from the one asked for here.

## Evidence

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin
  --offline` succeeds.
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest
  --offline` passes: `LocalCopiesTest` 5 tests, `CarEpisodeTest` 9 tests, and the
  rest of the suite unchanged.
- Each new test was shown able to fail by breaking the code on purpose and
  restoring it byte for byte (sha256 compared): deleting a subdirectory
  recursively failed `clearLeavesADirectoryInsideTheDirectoryAlone`; removing
  the "files only" guard failed the same test on the freed byte count; making an
  unpaired row tappable failed
  `aFileWithNoBridgeToFetchItSaysSoAndCannotBeTapped`; dropping the category
  fallback failed `aTitleWithNoCategoryReadsAsOther`.

## What only a real phone can confirm

- The list at a large font scale, and in landscape where the Now playing card
  and the list share a short screen. The row uses `heightIn(min = ...)` and two
  `maxLines`, which is what the rest of the app does for the same reason, but
  nothing here has run on a device or an emulator.
- That a cleared episode really downloads again and plays from the bridge, with
  the bridge reachable and then unreachable.
- That a clear during playback stops the audio and says so, and that the media
  notification disappears with it.
- The size the section reports against what the system reports for the app's
  storage. The cache directory is counted whole, including the SQLite and
  WebView scratch Android keeps there, so the number will be slightly larger
  than the episodes alone.
