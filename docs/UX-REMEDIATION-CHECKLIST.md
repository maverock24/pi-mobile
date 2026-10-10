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

## fontScale 1.0 and 2.0

Run checks 1 to 11 at the phone's default font scale, then set the display font to its largest
setting and run them again. Record any line that clips, overlaps or is cut off. The three fixed-size
spots this pass is expected to catch (the 24 dp waiting slot, the 20 dp bullet column, the app bar's
two single-line texts) belong to wave 2, so a clip there is recorded rather than fixed here.

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
