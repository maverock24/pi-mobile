package com.maverock24.pimobile.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Self-update from the rolling GitHub release published on every push to main.
 * The manifest carries versionCode, the APK URL and its sha256.
 */
object UpdateChecker {

    const val MANIFEST_URL =
        "https://github.com/maverock24/pi-mobile/releases/latest/download/latest.json"

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    private val http = OkHttpClient()

    /** Returns release info only when it is newer than [currentVersionCode]. */
    suspend fun check(currentVersionCode: Int): Info? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$MANIFEST_URL?ts=${System.currentTimeMillis()}")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@withContext null
            val json = JSONObject(body)
            val code = json.optInt("versionCode", 0)
            if (code <= currentVersionCode) return@withContext null
            Info(
                versionCode = code,
                versionName = json.optString("versionName", "v$code"),
                url = json.optString("url"),
                sha256 = json.optString("sha256"),
                sizeBytes = json.optLong("sizeBytes", 0L),
            )
        }
    }

    suspend fun download(context: Context, info: Info): File = withContext(Dispatchers.IO) {
        require(info.url.isNotBlank()) { "release manifest has no APK url" }
        val target = File(context.cacheDir, "update-${info.versionCode}.apk")
        val request = Request.Builder().url(info.url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("download failed: HTTP ${response.code}")
            val body = response.body ?: error("download failed: empty body")
            body.byteStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (info.sha256.isNotBlank()) {
            val actual = sha256(target)
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                target.delete()
                error("checksum mismatch: expected ${info.sha256.take(12)}…, got ${actual.take(12)}…")
            }
        }
        target
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** True when the OS still needs to allow installs from this app. */
    fun needsInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Returns null on success, or a message describing why the installer could not start. */
    fun install(context: Context, apk: File): String? = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.exceptionOrNull()?.let { "could not start installer (${it.javaClass.simpleName})" }
}
