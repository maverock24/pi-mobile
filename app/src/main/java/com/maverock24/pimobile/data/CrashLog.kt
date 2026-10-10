package com.maverock24.pimobile.data

import android.content.Context
import com.maverock24.pimobile.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * What the last launch did, and what went wrong when it died.
 *
 * A phone can be left holding something the app chokes on, and then it fails
 * every launch with no way back except Android's own Clear storage. Remembering
 * on disk whether the app ever came up is what lets the next launch notice and
 * start without the state the last one died on.
 *
 * Two files beside the app's own data:
 *
 *  - `launch.txt` holds one word. `starting` while the app is coming up,
 *    `running` once it has stayed up, and `crashed:starting` or `crashed:running`
 *    when [recordCrash] wrote it on the way out. A process the system or the user
 *    killed leaves `starting` behind, which is deliberately not a crash.
 *  - `last-crash.txt` holds the readable trace of the most recent crash, for the
 *    troubleshooting section of the settings screen.
 *  - `startup-crashes.txt` counts launches that died in a row before coming up,
 *    so the second one can drop more state than the first.
 *
 * Nothing here throws. It runs inside a dying process, where an exception would
 * take the trace down with it.
 */
class CrashLog(context: Context) {

    private val dir = context.filesDir
    private val launchFile = File(dir, "launch.txt")
    private val crashFile = File(dir, "last-crash.txt")
    private val startupCrashFile = File(dir, "startup-crashes.txt")

    /**
     * True when the previous launch recorded a crash before it had stayed up.
     * That is what puts the next launch in safe mode.
     */
    fun previousLaunchCrashedDuringStartup(): Boolean = runCatching {
        launchFile.readText().trim() == "$CRASHED:$STARTING"
    }.getOrDefault(false)

    /** Note that this launch is coming up. Called once, before any screen exists. */
    fun recordStart() {
        runCatching { launchFile.writeText(STARTING) }
    }

    /** Note that this launch came up, so a crash from here is not a startup one. */
    fun markRunning() {
        runCatching { launchFile.writeText(RUNNING) }
        // A launch that comes up is the end of any run of startup failures.
        clearStartupCrashes()
    }

    /**
     * Note another launch that died while coming up, and answer how many in a row
     * that makes. The count lets a launch that setting the transcript and the
     * pins aside did not save drop the bridge attachment as well, rather than
     * circling forever.
     */
    fun bumpStartupCrashes(): Int {
        val count = startupCrashes() + 1
        runCatching { startupCrashFile.writeText(count.toString()) }
        return count
    }

    /** How many launches in a row have died while coming up. */
    fun startupCrashes(): Int =
        runCatching { startupCrashFile.readText().trim().toInt() }.getOrDefault(0)

    /** Forget any run of startup crashes, since this launch followed a good one. */
    fun clearStartupCrashes() {
        runCatching { startupCrashFile.delete() }
    }

    /**
     * Append the trace of a crash, and remember which phase it happened in so
     * that the next launch can tell a startup failure from one during use.
     */
    fun recordCrash(error: Throwable) {
        val phase = runCatching { launchFile.readText().trim() }.getOrDefault(STARTING)
        val wasUp = phase == RUNNING
        runCatching { launchFile.writeText("$CRASHED:" + if (wasUp) RUNNING else STARTING) }
        // A crash before the app came up is one of a run; a crash after it is not.
        if (!wasUp) bumpStartupCrashes()
        runCatching {
            val trace = StringWriter()
            val printer = PrintWriter(trace)
            error.printStackTrace(printer)
            printer.flush()
            val header = "Pi Remote ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                Instant.ofEpochMilli(System.currentTimeMillis()).toString()
            // Newest first, so the crash that just happened is the one on top and
            // the cap cuts the older trace rather than the new one.
            val previous = crashFile.takeIf { it.exists() }?.readText().orEmpty()
            val body = "$header\n$trace\n\n$previous"
            crashFile.writeText(body.take(MAX_CRASH_CHARS))
        }
    }

    /** The most recent trace, or null when there is none. */
    fun lastCrash(): String? = runCatching { crashFile.readText().trim() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

    /** Forget the trace, so the next crash is the only one on record. */
    fun clearLastCrash() {
        runCatching { crashFile.delete() }
    }
}

private const val STARTING = "starting"
private const val RUNNING = "running"
private const val CRASHED = "crashed"

/** Long enough to name a cause, short enough not to fill the phone. */
private const val MAX_CRASH_CHARS = 16_000
