package com.maverock24.pimobile.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One talk saved on this phone. It is the copy of the row the bridge published,
 * kept beside the audio so the library is readable and playable with no bridge
 * in reach. [durationSeconds] is an estimate from the render's word count when
 * [durationEstimated] is true, which is what the bridge said and not a lie about
 * the file.
 */
data class DownloadedTalk(
    val id: String,
    val title: String,
    val durationSeconds: Double,
    val durationEstimated: Boolean,
    val voice: String?,
    val createdAt: String,
)

/**
 * The app's own storage for downloaded talks. One talk is two siblings in
 * `files/talks`: `<id>.mp3` is the audio and `<id>.json` the small record that
 * names it. Playback reads the audio file directly, so a downloaded talk needs
 * no network and no bridge.
 *
 * The id comes from the bridge and ends up in a file name, so every path is
 * built through [fileFor], which refuses anything but the bridge's own id shape.
 * That is what stops a hostile or corrupt id walking out of the directory.
 */
class TalksStore(context: Context) {

    private val dir = File(context.filesDir, "talks")

    /** True when [id] is the shape the bridge mints and safe to use as a name. */
    fun isSafeId(id: String): Boolean = ID_RE.matches(id)

    fun hasDownload(id: String): Boolean {
        val file = audioFile(id) ?: return false
        return file.isFile && file.length() > 0L
    }

    /** The audio for [id], or null when the id is not safe to name a file with. */
    fun audioFile(id: String): File? = if (isSafeId(id)) File(dir, "$id.mp3") else null

    /** Every talk saved here, newest first, skipping any record that will not read. */
    fun list(): List<DownloadedTalk> {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { read(it) }.sortedByDescending { it.createdAt }
    }

    /** Write (or replace) the record that goes beside a downloaded talk. */
    fun save(talk: DownloadedTalk) {
        if (!isSafeId(talk.id)) return
        if (!dir.exists()) dir.mkdirs()
        val json = JSONObject().apply {
            put("id", talk.id)
            put("title", talk.title)
            put("durationSeconds", talk.durationSeconds)
            put("durationEstimated", talk.durationEstimated)
            put("voice", talk.voice ?: JSONObject.NULL)
            put("createdAt", talk.createdAt)
        }
        runCatching { metaFile(talk.id)?.writeText(json.toString()) }
    }

    /** Remove the audio and the record together. Either half missing is fine. */
    fun delete(id: String) {
        audioFile(id)?.delete()
        metaFile(id)?.delete()
    }

    private fun metaFile(id: String): File? = if (isSafeId(id)) File(dir, "$id.json") else null

    private fun read(file: File): DownloadedTalk? = runCatching {
        val json = JSONObject(file.readText())
        val id = json.optString("id").ifBlank { file.nameWithoutExtension }
        if (!isSafeId(id)) return null
        // The audio is the reason the record exists, so a record whose audio is
        // gone is stale and is not shown as a playable talk.
        if (!hasDownload(id)) return null
        DownloadedTalk(
            id = id,
            title = json.optString("title").ifBlank { "Talk" },
            durationSeconds = json.optDouble("durationSeconds", 0.0),
            durationEstimated = json.optBoolean("durationEstimated", false),
            voice = if (json.isNull("voice")) null else json.optString("voice").takeIf { it.isNotBlank() },
            createdAt = json.optString("createdAt"),
        )
    }.getOrNull()

    private companion object {
        /** The same id shape the bridge mints: base64url-ish, bounded in length. */
        val ID_RE = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
