package com.maverock24.pimobile.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One file saved on this phone. It is the copy of the row the bridge published,
 * kept beside the bytes so the library is readable and playable with no bridge
 * in reach. [kind] is the bridge's word for what the file is (audio, video,
 * image, text, other), and [name] is the on-disk name the download route serves.
 * [durationSeconds] is an estimate from a render's word count when
 * [durationEstimated] is true, which is what the bridge said and not a lie about
 * the file.
 */
data class DownloadedFile(
    val id: String,
    val name: String,
    val title: String,
    val kind: String,
    val durationSeconds: Double,
    val durationEstimated: Boolean,
    val voice: String?,
    val createdAt: String,
)

/**
 * The app's own storage for downloaded files. The bytes are saved under the name
 * the bridge served them as, and a sidecar `<name>.json` records the row, so
 * playback and preview need no network and no bridge.
 *
 * The directory keeps the name `talks` from before this was a file library, so a
 * talk downloaded by an older build is still found: a record without a `name`
 * field was a talk, and its audio was `<id>.mp3`.
 *
 * The name comes from the bridge and ends up in a file name, so every path is
 * built through [fileFor], which refuses anything but the bridge's own name
 * shape. That is what stops a hostile or corrupt name walking out of the
 * directory.
 */
class FilesStore(context: Context) {

    private val dir = File(context.filesDir, "talks")

    /** True when [name] is the shape the bridge lists and safe to use as a name. */
    fun isSafeName(name: String): Boolean = NAME_RE.matches(name)

    /** The local copy of [name], or null when the name is not safe to name a file with. */
    fun fileFor(name: String): File? = if (isSafeName(name)) File(dir, name) else null

    fun hasDownload(name: String): Boolean {
        val file = fileFor(name) ?: return false
        return file.isFile && file.length() > 0L
    }

    /** Every file saved here, newest first, skipping any record that will not read. */
    fun list(): List<DownloadedFile> {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return emptyList()
        // One row per file name: a legacy record and a new one for the same talk
        // both resolve to the same name, and the newer record wins.
        val byName = LinkedHashMap<String, DownloadedFile>()
        files.mapNotNull { read(it) }.forEach { byName[it.name] = it }
        return byName.values.sortedByDescending { it.createdAt }
    }

    /** Write (or replace) the record that goes beside a downloaded file. */
    fun save(file: DownloadedFile) {
        if (!isSafeName(file.name)) return
        if (!dir.exists()) dir.mkdirs()
        val json = JSONObject().apply {
            put("id", file.id)
            put("name", file.name)
            put("title", file.title)
            put("kind", file.kind)
            put("durationSeconds", file.durationSeconds)
            put("durationEstimated", file.durationEstimated)
            put("voice", file.voice ?: JSONObject.NULL)
            put("createdAt", file.createdAt)
        }
        runCatching { metaFile(file.name)?.writeText(json.toString()) }
    }

    /** Remove the bytes and the record together. Either half missing is fine. */
    fun delete(name: String) {
        fileFor(name)?.delete()
        metaFile(name)?.delete()
    }

    private fun metaFile(name: String): File? = if (isSafeName(name)) File(dir, "$name.json") else null

    private fun read(file: File): DownloadedFile? = runCatching {
        val json = JSONObject(file.readText())
        // Records written by this build carry the name. A talk saved by the talks
        // build carries only id and kind, and its audio was <id>.mp3, so it is
        // read too rather than making a downloaded talk disappear.
        val name = json.optString("name").takeIf { it.isNotBlank() }
            ?: "${json.optString("id").ifBlank { file.nameWithoutExtension }}.mp3"
        if (!isSafeName(name)) return null
        val artifact = File(dir, name)
        // The bytes are the reason the record exists, so a record whose file is
        // gone is stale and is not shown.
        if (!artifact.isFile || artifact.length() == 0L) return null
        DownloadedFile(
            id = json.optString("id").ifBlank { name.substringBeforeLast('.', name) },
            name = name,
            title = json.optString("title").ifBlank { "File" },
            kind = json.optString("kind").ifBlank { "audio" },
            durationSeconds = json.optDouble("durationSeconds", 0.0),
            durationEstimated = json.optBoolean("durationEstimated", false),
            voice = if (json.isNull("voice")) null else json.optString("voice").takeIf { it.isNotBlank() },
            createdAt = json.optString("createdAt"),
        )
    }.getOrNull()

    private companion object {
        /** The name shape the bridge lists: a leading alphanumeric, bounded length. */
        val NAME_RE = Regex("[A-Za-z0-9][A-Za-z0-9 ._()-]{0,127}")
    }
}
