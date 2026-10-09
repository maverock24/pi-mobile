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
                (0 until array.length()).mapNotNull { index ->
                    val json = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = json.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    TranscriptEntry(
                        id = id,
                        role = json.optString("role"),
                        text = json.optString("text"),
                        timestamp = json.optLong("timestamp"),
                        toolName = json.optText("toolName"),
                        answer = json.optText("answer"),
                    )
                }
            }.getOrElse { emptyList() }
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
        return File(dir, "transcript-${safe.joinToString("")}.json")
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
            if (name.startsWith("transcript-") && (name.endsWith(".json") || name.endsWith(".json.tmp"))) {
                runCatching { candidate.delete() }
            }
        }
    }
}

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
