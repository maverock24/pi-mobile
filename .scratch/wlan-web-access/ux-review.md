# WLAN web access: experience review

Read `design.md`, `analysis.md`, `.scratch/car-mode.md`, `.scratch/files-library.md`,
the Compose screens under `app/src/main/java/com/maverock24/pimobile/ui/`, and
`bridge/extensions/pi-remote.ts`. Review only. Nothing changed except this file.

The architecture is sound and the security half is more careful than most of this repo.
The gap is the human's half. The design tells you where bytes go and who may send them,
and almost never says what the person at the other computer sees while it happens. The
findings below are ordered by how much that hurts a real user.

## 1. The browser cannot send the token to the stream or to the audio. The core loop and the audio both 401. (critical)

`design.md:216` streams with an `EventSource`. `design.md:221` plays with an `<audio>`
element. `design.md:308-309` says the credential is returned in the response body, is
deliberately not a cookie, and that every request sends `Authorization: Bearer
<web session>`.

A native `EventSource` cannot set request headers. Neither can an `<audio>` element's
`src`. So the two calls the first slice is built around, `/api/events` and
`/api/files/<name>` (`design.md:250-251`), arrive at the relay with no credential and
are refused. The page loads, the send button works because a `fetch` can set the header,
and then the answer never streams and the audio never plays. This is not a small edge;
it is the first request after the first prompt.

The same wall is why the bridge already has a query-string exception for browsers: the
one-off talk link exists because "a browser cannot send an Authorization header"
(`analysis.md` section 1, `handleTalkLink`). The design knows the pattern and did not
reuse it.

Fix: pick one and write it down. Either drive SSE with `fetch` and a `ReadableStream`
reader instead of `EventSource`, or allow a `SameSite=Strict` session cookie for the
stream and media paths and keep the existing `Origin` / `Sec-Fetch-Site` checks as the
cross-site guard. For the audio `src`, mint a short-lived, single-use, scoped media
token in the query string, exactly like the talk link, rather than putting the session
token there.

## 2. A prompt that opens a question blocks pi and the browser shows nothing. This is the car-mode bug again. (critical)

`design.md:225` puts question widgets out of the first slice. `design.md:248` still
exposes `POST /api/answer`, and `design.md:248` plus the Files list exposes
`GET /api/question`. So the relay can answer a question that the page cannot draw.

pi is blocked until a question is answered (`ui/Questions.kt`, the class comment). The
app treats this as a first-class state everywhere: the chat puts `QuestionCard` in the
transcript (`ui/Screens.kt:738`, `:843`), and car mode had to grow a `QuestionCard` for
exactly this reason. The comment at `ui/CarScreen.kt:141-147` says it in full: "Without
this the questionnaire never appeared here and the session was stuck with no way to
answer it."

A person at the computer sends "make me a chart of the options", the agent opens a
questionnaire, and the browser shows a spinner forever. The phone does get a
notification, but the person is at the computer, which is the whole point of the
feature.

Fix: do not ship a browser that can start a run it cannot finish. Either render the
question widget, or show a blocking line that names the question and says "answer this
on your phone". The second is cheap and honest, because the event already carries it
(`pi-remote.ts:1645` handles the `question` type, and `/api/events` sends the pending
question on connect at `pi-remote.ts:1463`).

## 3. A dropped stream silently loses the answer, and history is out of scope. (major)

`design.md:216` renders only the live frames. `design.md:225` puts history out of the
first slice.

The bridge's SSE does not replay. `/api/events` sends `state` and `question` on connect
(`pi-remote.ts:1460-1465`) and nothing about a message already in flight. The app needs
the three second poll for exactly this reason, and the comment at
`ui/ChatViewModel.kt:881-887` spells it out: the stream "dies whenever the phone sleeps
or switches networks, and a missed question event used to leave the app with no way to
answer at all". The poll at `:886` is the safety net, and a history reload is paired
with it.

The browser has no poll, no history, and no mid-run catch-up. A reload, a laptop that
sleeps for a second, or a WLAN blip leaves a blank page and no way to read the answer
that just finished. A person who opens the page after the run to read the result sees
nothing at all.

Fix: on every SSE open and reconnect, fetch `/api/state` and `/api/history` and render
the latest assistant answer, and keep a slow poll like the app does. `GET /api/history`
is already planned (`design.md:250`); the slice should use it. Render a reconnecting
state rather than a silent gap.

## 4. Getting in needs the phone in hand, and an address change strands the open tab. (major)

The URL is shown on the phone. `design.md:139` re-reads the Wi-Fi IPv4 on
`onLinkPropertiesChanged` and updates the URL shown in the app. `design.md:303-306`
requires the human to open the URL and then tap Allow on the phone. `design.md` section
8 rejects mDNS.

So the person at the computer has no way to learn the address except by reading the
phone, and no way to learn a changed address except by reading the phone again. A DHCP
lease change kills every open tab with no redirect and no signal. The approval step
needs a tap on the phone too, and the design's fallback is a code that is also only on
the phone, so there is no path into the feature that does not require picking up the
phone. That is a defensible security choice, but the design never says it, so a reader
expects to sit at the computer and drive.

The waiting side is also unspecified. The page "shows a short request code"
(`design.md:303`), the phone shows Allow and Deny (`design.md:305`), and the design does
not say what the browser shows while it waits, what it shows after Deny, or whether the
request times out. A person who does not notice the phone prompt is left on a dead page.

Fix: state plainly that web access cannot be started or joined without touching the
phone, and show the URL and a QR in the app beside the toggle. On the browser side, add
a named waiting state ("waiting for approval, check the phone"), a timeout that says
what happened, and a recovery path for an address change.

## 5. The web UI has no defined truth for empty versus unreachable, so the "blamed the network" bug can return. (major)

`design.md:33-35` says only that if the phone cannot reach the bridge, "the web UI shows
that and refuses". That is one sentence for three different failures: browser cannot
reach the phone, phone is up but the bridge is down, and the bridge is up but the
library is empty.

This repo already paid for getting that wrong. `net/Diagnostics.kt:99-105` carries the
comment that every `SocketException` used to be reported as "no TCP route", which "told
the human the network was down when the request had failed for another reason". The chat
screen distinguishes Not paired, Not connected with a Retry, and no results yet
(`ui/Screens.kt:496-534`), and the Files view keeps "Loading files", a fetch error, and
"No files yet" apart (`ui/FilesScreen.kt` around lines 100-140).

The web slice, as written, has none of that. The likeliest failure, a reachable phone
with a stopped bridge, will read to the user as a network problem.

Fix: give the web UI the same states as the app, reuse `Diagnostics` wording so the
failing hop is named, and add a visible connection indicator instead of leaving the
answer area blank. Never print "network" unless the network is the failed hop.

## 6. Browser audio loses the phone's guarantees, and a talk that is still rendering looks stale. (major)

On the phone, audio plays through a foreground media service with a notification, which
is what makes it survive the screen going off (`player/TalkPlayer.kt` class comment,
`player/TalkPlaybackService`). The design's browser path is an `<audio>` element over the
WLAN (`design.md:221`). It has no media session, no notification, and it stops when the
tab closes or the phone's relay drops. Cleartext and a WLAN drop make that more likely
than a local file ever was.

The app also reacts to the `talk` event: a ready talk is fetched and played without a tap
(`ui/ChatViewModel.kt` `handleEvent` `"talk"` branch). The web slice consumes no `talk`
event, so a talk that finishes rendering after the page loaded is invisible until the
person reloads.

Fix: say in the design that browser playback is tab-bound and dies with the WLAN, so the
limit is a choice. Have the Files view refresh on `talk` / `talk_removed` and show a
rendering state rather than a stale row, and do not offer a tap on a file that is not
ready. `car-mode.md` already chose to leave unfinished renders out of the list for the
same reason.

## 7. The layout is a phone column, and markdown is dropped. (minor)

`design.md:218` says "a plain text area that grows is enough", and `design.md:218-219`
drops the Compose rendering (`AnswerView`, `AnswerBlocks`). `ui/AnswerBlocks.kt` renders
headings, lists, fenced code, diffs and tables. On a desktop, a coding answer arrives as
raw triple-backtick fences in a single narrow column, which is a downgrade on a bigger
screen than the phone has.

Nothing in the design mentions viewport, width, or two panes. By omission it describes a
phone layout stretched across a monitor.

Fix: state the desktop layout. Cap the reading measure, render at least fenced code in a
monospace block, and use the width, for example the file list or the session identity
beside the transcript. If markdown is deferred, say so as a known loss.

## 8. The Files list can show rows the browser cannot open. (minor)

`GET /api/files` returns audio, video, image, text and other (`files-library.md`,
decision 2). The first slice only reaches audio (`design.md:221`). A person who sees a
text or image row and taps gets nothing. The app deliberately avoids that: "offers no tap
that does nothing" (`files-library.md`, decision 7), and the Files view says "This file
cannot be shown here yet".

Fix: for v1 list only audio, or render the other kinds as non-interactive with a reason.
Do not draw a row that looks tappable and is not.

## 9. The relay's own bridge connection makes the laptop footer say two phones. (minor)

`design.md:391` recommends the relay keep its own SSE connection to the bridge. The
footer counts `clients.size` and prints "N phones connected" (`pi-remote.ts:587-593`).
One phone plus the relay reads as "2 phones connected" on the laptop. The design names
this a "visible cost" but leaves it, and it is confusing at the exact moment the human is
trying to understand who is connected.

Fix: label the relay connection and leave it out of the count, or say in the design that
the footer will read two and why. The bridge already has a client label
(`clientLabel`, `pi-remote.ts:965`), so the information is there.

## What the browser version is not

The scope list in `design.md:225` is honest at the document level: no question widgets,
history, search, pins, commands, dictation, car mode, theming or updates. The risk is
that the browser itself is not told to say any of that. A person will open a wide screen,
reach for search and history, and find neither, with no explanation. The two omissions
that bite hardest are the question widget (finding 2) and history (finding 3), because
both leave a run that cannot be finished or read. The rest are reasonable to defer, as
long as the page says what is missing instead of rendering an empty control.
