package com.maverock24.pimobile.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.maverock24.pimobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Self-update from the rolling release published on every push to main, served by
 * the bridge on this tailnet at /api/release/latest.json and /api/release/apk, so
 * the repository itself stays private and no public URL is involved.
 *
 * The bearer token is required for both requests, and the APK url may be relative
 * to the bridge.
 *
 * Two checks guard the installer, and neither relies on the other:
 *  1. the manifest must carry a valid sha256 and an APK url inside this repo's
 *     release assets, checked before anything is downloaded;
 *  2. the downloaded APK must be signed with the same certificate as the
 *     installed app, re-checked in [install] right before the installer starts.
 *
 * The second check is the important one. The manifest hash only proves the
 * download was not corrupted; it cannot prove the manifest itself is honest.
 * The signing certificate can, because it is pinned on the device, not fetched.
 */
object UpdateChecker {

    /** Kept so a manifest that still points at the old public location is handled. */
    private const val LEGACY_APK_URL_PREFIX =
        "https://github.com/maverock24/pi-mobile/releases/"

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    private val http = OkHttpClient()

    private fun manifestUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/') + "/api/release/latest.json"

    /** A manifest may name the APK by relative path on the bridge. */
    private fun resolveApkUrl(baseUrl: String, url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url
        else baseUrl.trim().trimEnd('/') + "/" + url.trimStart('/')

    private fun headers(builder: Request.Builder, token: String) = builder
        .header("Authorization", "Bearer $token")
        .header("X-Pi-Client", "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}")

    /** Returns release info only when it is newer and verifiable. */
    suspend fun check(baseUrl: String, token: String, currentVersionCode: Int): Info? =
        withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || token.isBlank()) return@withContext null
        val request = headers(
            Request.Builder()
                .url("${manifestUrl(baseUrl)}?ts=${System.currentTimeMillis()}")
                .header("Accept", "application/json"),
            token,
        ).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@withContext null
            val json = JSONObject(body)
            val code = json.optInt("versionCode", 0)
            if (code <= currentVersionCode) return@withContext null
            val url = resolveApkUrl(baseUrl, json.optString("url"))
            val sha256 = json.optString("sha256")
            // Fail closed: never offer an update that cannot be verified.
            if (!isTrustedApkUrl(baseUrl, url) || !SHA256.matches(sha256)) return@withContext null
            Info(
                versionCode = code,
                versionName = json.optString("versionName", "v$code"),
                url = url,
                sha256 = sha256,
                sizeBytes = json.optLong("sizeBytes", 0L),
            )
        }
    }

    suspend fun download(context: Context, baseUrl: String, token: String, info: Info): File =
        withContext(Dispatchers.IO) {
        require(isTrustedApkUrl(baseUrl, info.url)) { "release manifest points outside the bridge" }
        require(SHA256.matches(info.sha256)) { "release manifest has no valid sha256" }
        val target = File(context.cacheDir, "update-${info.versionCode}.apk")
        val request = headers(Request.Builder().url(info.url), token).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("download failed: HTTP ${response.code}")
            val body = response.body ?: error("download failed: empty body")
            body.byteStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val actual = sha256(target)
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            target.delete()
            error("checksum mismatch: expected ${info.sha256.take(12)}…, got ${actual.take(12)}…")
        }
        if (!isSignedByThisApp(context, target)) {
            target.delete()
            error("downloaded APK is not signed by this app's key")
        }
        target
    }

    /** The APK must live on the configured bridge, or at the old public location. */
    private fun isTrustedApkUrl(baseUrl: String, url: String): Boolean {
        val bridge = baseUrl.trim().trimEnd('/')
        if (bridge.isNotEmpty() && url.startsWith("$bridge/")) return true
        return url.startsWith(LEGACY_APK_URL_PREFIX)
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

    /**
     * True when [apk] carries the same signing certificate as the installed app.
     * This is what stops a forged manifest from delivering a foreign APK: the
     * package installer will reject an update signed with a different key, and
     * we refuse to hand it over in the first place.
     */
    private fun isSignedByThisApp(context: Context, apk: File): Boolean = runCatching {
        val expected = installedSignerCerts(context)
        val actual = apkSignerCerts(context, apk)
        expected.isNotEmpty() && actual.isNotEmpty() &&
            expected.all { want -> actual.any { got -> want.contentEquals(got) } }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun installedSignerCerts(context: Context): List<ByteArray> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.map { it.toByteArray() }.orEmpty()
        }
    }

    @Suppress("DEPRECATION")
    private fun apkSignerCerts(context: Context, apk: File): List<ByteArray> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
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
    fun install(context: Context, apk: File): String? {
        if (!isSignedByThisApp(context, apk)) {
            return "refusing to install: APK is not signed by this app's key"
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.exceptionOrNull()?.let { "could not start installer (${it.javaClass.simpleName})" }
    }
}
