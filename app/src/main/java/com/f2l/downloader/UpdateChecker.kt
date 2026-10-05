package com.f2l.downloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Checks GitHub Releases for a newer version, downloads the APK and hands it to the system installer. */
object UpdateChecker {
    /** owner/repo that publishes the signed release APKs */
    const val REPO = "GouthamSER/F2L-Downloader-App"

    data class UpdateInfo(
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val apkSize: Long,
        val pageUrl: String
    )

    sealed class Result {
        data class Available(val info: UpdateInfo) : Result()
        object UpToDate : Result()
        data class Error(val message: String) : Result()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun check(current: String = BuildConfig.VERSION_NAME): Result = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "F2LDownloader/$current")
                .build()
            client.newCall(req).execute().use { res ->
                when {
                    res.code == 404 -> Result.Error("No release published yet")
                    !res.isSuccessful -> Result.Error("Update server error (HTTP ${res.code})")
                    else -> {
                        val json = JSONObject(res.body?.string().orEmpty())
                        val tag = json.optString("tag_name")
                        if (tag.isBlank() || !isNewer(tag, current)) {
                            Result.UpToDate
                        } else {
                            var apkUrl: String? = null
                            var apkSize = 0L
                            val assets = json.optJSONArray("assets")
                            if (assets != null) {
                                for (i in 0 until assets.length()) {
                                    val a = assets.getJSONObject(i)
                                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                                        apkUrl = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                                        apkSize = a.optLong("size", 0L)
                                        break
                                    }
                                }
                            }
                            Result.Available(
                                UpdateInfo(
                                    version = tag.trim().removePrefix("v").removePrefix("V"),
                                    notes = json.optString("body").trim(),
                                    apkUrl = apkUrl,
                                    apkSize = apkSize,
                                    pageUrl = json.optString("html_url", "https://github.com/$REPO/releases")
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Result.Error(ErrorMessages.friendly(e))
        }
    }

    /** "v3.4.0" > "3.3.0" — compares numeric parts, missing parts count as 0. */
    fun isNewer(remote: String, current: String): Boolean {
        fun parts(s: String) = Regex("\\d+").findAll(s).map { it.value.toIntOrNull() ?: 0 }.toList()
        val r = parts(remote)
        val c = parts(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val url = info.apkUrl ?: error("No APK attached to this release")
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // drop old update files
        val out = File(dir, "F2L-Downloader-${info.version}.apk")
        val tmp = File(dir, out.name + ".tmp")

        val req = Request.Builder().url(url).header("User-Agent", "F2LDownloader/${BuildConfig.VERSION_NAME}").build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Download failed (HTTP ${res.code})")
            val body = res.body ?: error("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
            body.byteStream().use { input ->
                tmp.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 200) { lastReport = now; onProgress(done, total) }
                    }
                    onProgress(done, if (total > 0) total else done)
                }
            }
        }
        if (!tmp.renameTo(out)) error("Cannot save update file")
        out
    }

    /** @return false when the user must first allow "install unknown apps" for F2L (settings page opened). */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
