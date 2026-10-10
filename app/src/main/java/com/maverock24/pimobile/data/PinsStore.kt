package com.maverock24.pimobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One pinned prompt and the answer it produced, with a title the user can edit.
 *
 * The two texts are the record and are never edited. Nothing names the session
 * they came from, so a pin outlives the session that produced it and can be
 * sent again from any of them.
 */
data class Pin(
    val id: String,
    val title: String,
    val prompt: String,
    val answer: String,
)

/**
 * The pins, as one JSON document in the app's own files directory.
 *
 * A file rather than SharedPreferences because the two texts are long and a
 * preference is not built for a document of this size. A save goes to a
 * sibling temp file and is renamed over the real one, so a phone killed
 * mid-write leaves the previous document whole rather than half of a new one.
 * The lock keeps two saves in this process from interleaving their renames.
 */
class PinsStore(context: Context) {

    private val file = File(context.filesDir, "pins.json")

    /**
     * Where the pins go when a launch died rather than deleting them: a bad
     * start should cost the use of the pins, not the pins themselves.
     */
    private val quarantinedFile = File(context.filesDir, "pins.json.bad")

    private val lock = Any()

    /** Every pin, in the order it was written. A corrupt or missing file is empty. */
    fun load(): List<Pin> {
        synchronized(lock) {
            val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
            if (text.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(text)
                // Two pins with one id would be two list rows with one key, which
                // Compose refuses with an exception at layout time, so a repeated
                // id keeps its first entry only.
                val byId = LinkedHashMap<String, Pin>()
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val id = json.optString("id").takeIf { it.isNotBlank() } ?: continue
                    if (byId.containsKey(id)) continue
                    byId[id] = Pin(
                        id = id,
                        title = json.optString("title"),
                        prompt = json.optString("prompt"),
                        answer = json.optString("answer"),
                    )
                }
                byId.values.toList()
            }.getOrElse { emptyList() }
        }
    }

    /**
     * Move the written pins aside so nothing reads them again, without deleting
     * them. Called by a launch that follows one which died while coming up, so
     * the pins cannot be what the next launch chokes on either. Returns true when
     * a file was moved.
     */
    fun quarantine(): Boolean = synchronized(lock) {
        runCatching { file.exists() && file.renameTo(quarantinedFile) }.getOrDefault(false)
    }

    /** True when a launch set the pins aside and they are still on disk. */
    fun hasQuarantined(): Boolean = runCatching { quarantinedFile.exists() }.getOrDefault(false)

    /** Delete the pins, both the live file and any copy a bad launch set aside. */
    fun clear() = synchronized(lock) {
        runCatching { file.delete() }
        runCatching { quarantinedFile.delete() }
    }

    /** Write the whole list. The write is atomic and never leaves a partial file. */
    fun save(pins: List<Pin>) {
        synchronized(lock) {
            val array = JSONArray()
            pins.forEach { pin ->
                array.put(
                    JSONObject()
                        .put("id", pin.id)
                        .put("title", pin.title)
                        .put("prompt", pin.prompt)
                        .put("answer", pin.answer),
                )
            }
            val json = array.toString()
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
        }
    }
}
