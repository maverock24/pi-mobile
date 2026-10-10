package com.maverock24.pimobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One entry of the transcript as the bridge reported it, plus the time it was
 * written. This is the shape the cache keeps, which is deliberately not
 * everything the app puts on screen: a prompt echoed here before the bridge
 * confirmed it, a committed answer and a question trace all carry synthetic ids
 * and are not records, so none of them is ever written.
 */
data class TranscriptEntry(
    val id: String,
    val role: String,
    val text: String,
    val timestamp: Long,
    val toolName: String?,
    val answer: String?,
)

/**
 * How many entries the cached transcript may hold. The bridge serves a window
 * of 500; keeping six windows of it means a long session's older turns are
 * still readable after the bridge has moved past them, without the file growing
 * without end. The byte ceiling below is the real bound on disk; this one stops
 * a session of very many tiny entries from making an array that is slow to
 * parse.
 */
private const val MAX_ENTRIES = 3000

/**
 * How many characters of serialised JSON the cache may hold, roughly two
 * megabytes for the mostly-ASCII text a transcript carries. A month-old session
 * is a few hundred kilobytes, so this leaves plenty of history while keeping a
 * session that never ends from filling the phone.
 */
private const val MAX_CHARS = 2_000_000

/**
 * The transcript of the session the app is on, as one JSON document in the
 * app's own files directory, named for that session.
 *
 * A file beside `pins.json` rather than a database, because the document is a
 * list and the app has neither a schema nor a query to run over it. A save goes
 * to a sibling temp file and is renamed over the real one, so a phone killed
 * mid-write leaves the previous document whole rather than half of a new one.
 * Only the session in hand is kept: a save prunes every other session's file,
 * so what sits on disk is one transcript and the temp file being written.
 *
 * Every entry is untrusted text from a laptop and every byte on disk can be
 * truncated by a kill, so nothing here throws: an unreadable file reads as
 * empty and the next successful fetch overwrites it with the merged truth.
 */
class TranscriptStore(context: Context) {

    private val dir = context.filesDir
    private val lock = Any()

    /**
     * The cached transcript of [sessionId], oldest entry first, or empty when
     * the file is missing, blank or corrupt. Individual entries that cannot be
     * read are skipped rather than failing the whole document.
     */
    fun load(sessionId: String): List<TranscriptEntry> {
        synchronized(lock) {
            val text = runCatching { fileFor(sessionId).readText() }.getOrNull()
            if (text.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(text)
                // A repeated id would give two turns one key, which Compose refuses
                // with an exception at layout time, so a repeated id keeps its
                // first entry only.
                val byId = LinkedHashMap<String, TranscriptEntry>()
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val id = json.optString("id").takeIf { it.isNotBlank() } ?: continue
                    if (byId.containsKey(id)) continue
                    byId[id] = TranscriptEntry(
                        id = id,
                        role = json.optString("role"),
                        text = json.optString("text"),
                        timestamp = json.optLong("timestamp"),
                        toolName = json.optText("toolName"),
                        answer = json.optText("answer"),
                    )
                }
                byId.values.toList()
            }.getOrElse { emptyList() }
        }
    }

    /**
     * Move every cached transcript aside so nothing reads them again, without
     * deleting them. Called by a launch that follows one which died while coming
     * up, since a cached transcript is the largest thing the app reads before it
     * can draw. Returns how many files were moved.
     */
    fun quarantineAll(): Int = synchronized(lock) {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return 0
        var moved = 0
        for (candidate in files) {
            // Only the real caches: a half-written `.tmp` is left for the next
            // save to clean up, since nothing reads it anyway.
            if (!isQuarantinable(candidate.name)) continue
            val aside = File(dir, "${candidate.name}.bad")
            if (runCatching { candidate.renameTo(aside) }.getOrDefault(false)) moved += 1
        }
        moved
    }

    /** True when a launch set a transcript aside and any of it is still on disk. */
    fun hasQuarantined(): Boolean = runCatching {
        dir.listFiles()?.any { isQuarantinedTranscript(it.name) } == true
    }.getOrDefault(false)

    /** Delete every transcript, the live ones and any a bad launch set aside. */
    fun clear() = synchronized(lock) {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (candidate in files) {
            if (isCachedTranscript(candidate.name) || isQuarantinedTranscript(candidate.name)) {
                runCatching { candidate.delete() }
            }
        }
    }

    /**
     * Write [entries] as the transcript of [sessionId] and drop every other
     * session's cache. The write is atomic and the prune runs only after it, so
     * a kill leaves either the old file or the new one, never a gap.
     */
    fun save(sessionId: String, entries: List<TranscriptEntry>) {
        synchronized(lock) {
            val file = fileFor(sessionId)
            val json = serialise(bound(entries))
            runCatching {
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(json)
                // The temp file is the real file's sibling, so this rename stays
                // on one filesystem and a reader sees either the old document or
                // the new one, never a half-written one.
                if (!temp.renameTo(file)) {
                    file.writeText(json)
                    temp.delete()
                }
            }
            pruneExcept(file)
        }
    }

    /** The file the app uses for [sessionId], with anything unsafe for a name folded to `_`. */
    private fun fileFor(sessionId: String): File {
        val safe = sessionId.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
        return File(dir, "$PREFIX${safe.joinToString("")}.json")
    }

    /**
     * The newest entries that fit the two bounds, so the oldest go first. The
     * newest entry is always kept even if it alone is over the byte ceiling:
     * losing the turn you are in the middle of is worse than a file a little
     * over the bound, and the next entries will be pruned around it.
     */
    private fun bound(entries: List<TranscriptEntry>): List<TranscriptEntry> {
        if (entries.isEmpty()) return entries
        val whole = serialise(entries)
        if (whole.length <= MAX_CHARS && entries.size <= MAX_ENTRIES) return entries
        val kept = ArrayList<TranscriptEntry>(minOf(entries.size, MAX_ENTRIES))
        var chars = 2 // the two brackets around the array
        for (index in entries.indices.reversed()) {
            val entry = entries[index]
            val cost = serialise(listOf(entry)).length - 1 // its text plus a comma
            if (kept.isNotEmpty() && (kept.size >= MAX_ENTRIES || chars + cost > MAX_CHARS)) break
            kept.add(entry)
            chars += cost
        }
        kept.reverse()
        return kept
    }

    /** The list as JSON text. Written once per save, and read back only by [bound]. */
    private fun serialise(entries: List<TranscriptEntry>): String {
        val array = JSONArray()
        for (entry in entries) {
            val json = JSONObject()
                .put("id", entry.id)
                .put("role", entry.role)
                .put("text", entry.text)
                .put("timestamp", entry.timestamp)
            // Short fields are left out when empty rather than stored as null, so
            // a transcript of plain prompts is mostly text and not mostly keys.
            entry.toolName?.let { json.put("toolName", it) }
            entry.answer?.let { json.put("answer", it) }
            array.put(json)
        }
        return array.toString()
    }

    /**
     * Delete every transcript file that is not [keep]. Called after a save, so
     * moving to another session leaves the directory holding the one transcript
     * the app is on, plus the temp file a save is mid-flight with.
     */
    private fun pruneExcept(keep: File) {
        val keepNames = setOf(keep.name, "${keep.name}.tmp")
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (candidate in files) {
            val name = candidate.name
            if (name in keepNames) continue
            // A quarantined copy is not a cache any more and is kept until the
            // settings screen is asked to clear it.
            if (isCachedTranscript(name)) {
                runCatching { candidate.delete() }
            }
        }
    }

    /** A live transcript cache, as [save] and [load] name it. */
    private fun isCachedTranscript(name: String): Boolean =
        name.startsWith(PREFIX) && (name.endsWith(".json") || name.endsWith(".json.tmp"))

    /** A file [quarantineAll] moves aside: a real cache, not a half-written one. */
    private fun isQuarantinable(name: String): Boolean =
        name.startsWith(PREFIX) && name.endsWith(".json")

    /** A transcript a bad launch moved aside, which nothing reads. */
    private fun isQuarantinedTranscript(name: String): Boolean =
        name.startsWith(PREFIX) && name.endsWith(".json.bad")
}

/** The file name every cache starts with, so a session's file is recognisable. */
private const val PREFIX = "transcript-"

/**
 * A string field, or null when it is missing, null or blank. Kept local so the
 * cache does not depend on the view model's own reader.
 */
private fun JSONObject.optText(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/**
 * The two lists as one, oldest first, with every id kept once. A fetched entry
 * is the bridge's own copy of the id and wins over the stored one, so a turn
 * whose text grew while it streamed is stored as it finally was.
 *
 * Order is by time, and the sort is stable, so entries that share a timestamp
 * keep the order they were handed over in: stored first, then fetched. An entry
 * with no time at all takes the time of the entry before it, which keeps it
 * beside its neighbours instead of jumping to the front of the transcript.
 */
internal fun mergeTranscript(
    stored: List<TranscriptEntry>,
    fetched: List<TranscriptEntry>,
): List<TranscriptEntry> {
    if (stored.isEmpty()) return fillTimes(fetched).sortedBy { it.timestamp }
    if (fetched.isEmpty()) return fillTimes(stored).sortedBy { it.timestamp }
    val byId = LinkedHashMap<String, TranscriptEntry>(stored.size + fetched.size)
    for (entry in stored) byId[entry.id] = entry
    for (entry in fetched) byId[entry.id] = entry
    // sortedBy is a stable sort, so the order above decides entries that share a
    // timestamp rather than leaving their order to chance.
    return fillTimes(byId.values.toList()).sortedBy { it.timestamp }
}

/**
 * Give a missing time the time of the entry before it, and a run of missing
 * times at the front the first time that is known. A list with no known time at
 * all is left as it came.
 */
private fun fillTimes(entries: List<TranscriptEntry>): List<TranscriptEntry> {
    var previous = 0L
    val filled = entries.map { entry ->
        if (entry.timestamp > 0L) {
            previous = entry.timestamp
            entry
        } else {
            entry.copy(timestamp = previous)
        }
    }
    val first = filled.firstOrNull { it.timestamp > 0L }?.timestamp ?: return filled
    return filled.map { if (it.timestamp > 0L) it else it.copy(timestamp = first) }
}
