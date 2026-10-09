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
    private val lock = Any()

    /** Every pin, in the order it was written. A corrupt or missing file is empty. */
    fun load(): List<Pin> {
        synchronized(lock) {
            val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
            if (text.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { index ->
                    val json = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = json.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Pin(
                        id = id,
                        title = json.optString("title"),
                        prompt = json.optString("prompt"),
                        answer = json.optString("answer"),
                    )
                }
            }.getOrElse { emptyList() }
        }
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
