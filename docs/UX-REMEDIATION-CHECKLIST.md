# Pi Remote UX remediation checklist

How to use this: run each numbered check on the phone and write pass or fail beside it. This file is
the verification (D23) — everything in the effort that a person can see or feel is checked here, and
a failure is a fix before the next wave, not a note for later.

The machine checks are the effort's own and are not repeated here: `assembleDebug`,
`:app:lintDebug`, and `python3 scripts/luminance.py` (the last one recomputes every colour ratio
this checklist asks you to judge by eye).

## Wave 1: bugs and notifications

1. **First run, not paired.** Install the build on a device with no saved settings and launch it. On
   Android 13 or newer a notification-permission prompt appears once, and whatever you answer it
   does not come back on the next launch. The chat opens with nothing left over from a previous run.
2. **Pair by QR or by hand.** From Settings, scan the bridge's QR code, or paste a
   `pi-remote://pair?…` link. A `Pairing…` confirmation appears in the message area under the app bar
   and clears itself after about two seconds; `Paired as <device>` replaces it, Settings closes, and
   the chat connects. Then pair again with the manual base URL and token fields: saving returns to
   the chat and the status line reports the connection. A bad link says it is not a pairing code
   rather than doing nothing.
3. **Send a prompt and read the answer.** Type a prompt and press the action button; it reads
   `Send`. While a run is live it still reads `Send`, and pressing it steers the text into the
   running turn rather than starting a second one; the label that reads `Steer` is wave 2 (T17).
   The answer streams into the transcript, and the working accent line on the app bar runs only while
   the run is live.
4. **Answer a question from the phone.** Drive a question from the laptop and answer it from the
   question card. The answer lands as a confirmation in the message area.
5. **Search a session and jump to a hit.** Open search, type a term that matches an older turn, and
   tap a hit: the transcript scrolls to that turn. A hit whose turn is not in the window does not
   open. A one-line hit is still tall enough (48 dp) to tap without catching its neighbour.
6. **Pins: save, rename, delete.** Pin an answer, rename the pin, then delete it. Each action lands
   as a confirmation in the message area and clears itself. Back from an open pin returns to the pin
   list, not out of the app.
7. **View mode and theme switch.** Switch between Transcript, Cards and Pins, then in Settings switch
   between Light, Midnight, Indigo, Amber and Forest. In Midnight and Indigo the muted secondary text
   and the red error text are readable rather than dim.
8. **Settings, the token, updates and the laptop command.** Open Settings: it shows the bridge token
   (and the screen is blocked from screenshots and the recents thumbnail), the laptop command to run,
   and the update status with a button that rechecks it. With an update available, the banner over
   the chat offers two controls: `Install` and `Dismiss`. Tapping `Dismiss` closes the banner and
   does **not** open a package installer; tapping `Install` downloads and offers the installer. In
   light mode both update pills (`available`, `up to date`) are readable against their fill.
9. **Notifications.** With the app in the front, raise a question from the laptop: nothing is posted.
   Send the app to the background, raise a question, and a notification arrives; it carries a single
   `Open` action and tapping it opens the app on the question. Send a prompt, background the app, and
   a notification arrives when the run settles; tapping it opens the app on the answer.
10. **Back, one layer at a time.** With the command palette open, back closes the palette. With
    search open, back closes search and the query is still in the field. With a pin open, back
    returns to the list. In Settings, back returns to the chat. With nothing open, back leaves the
    app.
11. **One message area.** Raise a search error while a connection error is already showing: the two
    do not stack in two bars — the search error takes the one slot. An error stays until you dismiss
    it; a confirmation such as `Copied text added to prompt` clears itself after about two seconds.
    The connection status line and the saved-copy line sit outside this area and stay one line each.

## Wave 2: structure

1. **The composer's two rows.** Open the chat and start a run. The composer now has two button rows
   under the field: the first holds the action button and, while the run is live, `Stop` beside it;
   the second scrolls sideways and holds `Auto-paste`, `Mic` and `Clear`. Scroll the second row:
   neither the action nor `Stop` moves. The action reads `Send` when idle, `Steer` while the run is
   live (and still steers the text into the running turn), and `Search` while search is open.
2. **The pickers are one segmented control, and it says what is selected.** Switch Transcript, Cards
   and Pins with the segmented control above the composer; in Settings, Appearance and Theme use the
   same control. With TalkBack on, each segment is announced as a button and the active one is
   announced as selected.
3. **The answer streams and its headings are jumps.** With TalkBack on, drive a run whose answer
   arrives over several seconds: the answer is read as it streams rather than only after it stops.
   In a long answer that has headings, swipe by heading to move from one heading to the next.
4. **The empty state names its situation.** Launch with nothing saved: the empty chat says
   `Not paired yet` and offers `Open Settings`. Pair to a bridge that cannot be reached: it shows
   the failure text and `Retry`, and `Retry` reconnects. With a working connection and no turns yet,
   it shows the thinking or no-results text rather than a pair or retry prompt.
5. **The connection hint leads and is not cut.** Cause a connection failure (wrong bridge address, or
   the bridge stopped). The chat's status line starts with the fix the app suggests, then the failed
   state, the address and the detail, and it wraps to a second line instead of ellipsising the fix
   away. The same string appears in Settings and wraps there.
6. **The pin, the search and the half-typed prompt survive Settings.** Open a pin, open search and
   type a query, and type half a prompt in the composer. Go to Settings and come back: the pin is
   still open, the query is still in the search field, and the half-typed prompt is still in the
   composer.
7. **Settings folds its prose and copies its commands.** Open each Settings section: its subtitle is
   one line and the explanation appears only once the section is open. Under Pairing, tap the `/pair`
   row; under Connection, tap the `cat ~/.config/pi-remote/token` row. Each tap puts the command on
   the clipboard, shows a `Copied` acknowledgement on the row itself, and pasting the clipboard gives
   exactly that command.

## fontScale 1.0 and 2.0

Run the wave 1 and wave 2 checks at the phone's default font scale, then set the display font to its
largest setting and run them again. Wave 2 fixed two of the three fixed-size spots that used to clip
here: the 24 dp waiting slot and the 20 dp bullet column. The third, the app bar's two lines, is a
known clip rather than a fix: Material3's small `TopAppBar` caps its container at 64 dp and clips, so
`maxLines = 2` renders more lines inside that height and cuts them off. Carrying both lines means
replacing the fixed-height bar, recorded as T21a in wave 3. Beyond that known app-bar clip, nothing
should clip, overlap or be cut off at `fontScale` 2.0; a clip there is a failure to fix, not a line to
record.

Result: _pending, completed with wave 3._

## TalkBack

One pass with TalkBack on, over checks 4, 5, 6, 7 and 10: every clickable row is announced as a
control, the view-mode and theme pickers say which option is selected, and the answer being streamed
is read out as it arrives.

Result: _pending, completed with wave 3._

## Known limitations

- **Notifications arrive only while the app is running.** There is no foreground service (D21), so a
  run that finishes while the phone is in a pocket produces nothing. This is Tier 0.2's job.
- **There is no run-failed notification** (D9). No bridge event carries a failure, so the trigger is
  deferred rather than guessed at.
- **A notification carries only an `Open` action** (D27). There are no question-option buttons and
  no reply from the notification.
- Contrast outside this effort's scope is unchanged: Amber, Forest and Light `error` on `background`
  measure 4.33, 4.24 and 4.10, Amber `onSurfaceVariant` on `surfaceVariant` measures 4.15, and the
  light `release` pill measures 4.07:1. These are recorded, not fixed.
- While a search error is showing, the search panel stops saying "no matches", and a connection error
  is superseded by it.
