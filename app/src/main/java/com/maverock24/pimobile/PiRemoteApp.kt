package com.maverock24.pimobile

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.maverock24.pimobile.data.CrashLog

/**
 * Wires in the two things that have to exist before any screen does: the record
 * of how the last launch ended, and the handler that records the next crash.
 *
 * The handler writes the trace and the phase the app was in, then hands the
 * thread to the platform's own handler, so the process still dies the way
 * Android expects it to.
 */
class PiRemoteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val log = CrashLog(this)
        // Read first, then overwrite: reading the phase is what consumes it.
        val crashedBefore = log.previousLaunchCrashedDuringStartup()
        startupSafeMode = crashedBefore
        startupCrashCount = if (crashedBefore) log.startupCrashes() else 0
        // A run of startup crashes has ended unless the last launch was one.
        if (!crashedBefore) log.clearStartupCrashes()
        log.recordStart()

        val platform = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log.recordCrash(error)
            platform?.uncaughtException(thread, error)
        }

        // A launch that is still up seconds later is not a launch that failed to
        // come up, so a crash from here must not put the next one in safe mode.
        Handler(Looper.getMainLooper()).postDelayed({ log.markRunning() }, STAYED_UP_MS)
    }

    companion object {
        /** How long a launch has to survive before it counts as having come up. */
        private const val STAYED_UP_MS = 5_000L

        /**
         * True when the previous launch crashed before it came up. Set once in
         * [onCreate] and read by the view model, which then leaves the state the
         * last launch died on unread instead of walking back into it.
         */
        var startupSafeMode: Boolean = false
            private set

        /**
         * How many launches in a row have now died while coming up. A second one
         * is what tells the view model that setting the transcript and the pins
         * aside was not enough, so more has to go.
         */
        var startupCrashCount: Int = 0
            private set
    }
}
