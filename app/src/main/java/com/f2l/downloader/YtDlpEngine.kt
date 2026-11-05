package com.f2l.downloader

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * YouTube support through yt-dlp (youtubedl-android: bundled python + ffmpeg).
 * yt-dlp cannot write into a SAF folder, so it downloads into app storage (resumable: yt-dlp keeps .part files)
 * and the finished file is copied to the folder the user picked.
 */
object YtDlpEngine {
    data class Option(
        val label: String,          // "1080p", "MP3 320 kbps"
        val isAudio: Boolean,
        val selector: String?,      // yt-dlp -f expression (video) / null (audio)
        val audioExt: String?,      // mp3 / m4a
        val audioQuality: String?,  // 320K / 192K / 128K / null
        val approxBytes: Long
    )

    data class Info(
        val title: String,
        val uploader: String?,
        val durationSec: Long,
        val thumbnail: String?,
        val video: List<Option>,
        val audio: List<Option>
    )

    private val YT_REGEX = Regex("^https?://([a-z0-9-]+\\.)?(youtube\\.com|youtu\\.be|youtube-nocookie\\.com)/", RegexOption.IGNORE_CASE)
    fun isYoutube(url: String): Boolean = YT_REGEX.containsMatchIn(url.trim())

    @Volatile private var ready = false
    private val initLock = Any()

    private fun ensureInit(ctx: Context) {
        if (ready) return
        synchronized(initLock) {
            if (ready) return
            val app = ctx.applicationContext
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            ready = true
        }
    }

    /** yt-dlp breaks when YouTube changes; refresh the bundled extractor (at most every 3 days unless forced). */
    private fun maybeUpdate(ctx: Context, force: Boolean) {
        val prefs = ctx.getSharedPreferences("f2l_settings", Context.MODE_PRIVATE)
        val last = prefs.getLong("yt_last_update", 0L)
        val now = System.currentTimeMillis()
        if (!force && now - last < 3L * 24 * 3600 * 1000) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("yt_last_update", now).apply()
        } catch (_: Exception) { /* offline etc: keep bundled version */ }
    }

    fun tmpDir(ctx: Context, id: Long): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "ytdlp/$id")

    fun deleteTmp(ctx: Context, id: Long) {
        runCatching { tmpDir(ctx, id).deleteRecursively() }
    }

    /** Run [block]; on failure refresh yt-dlp once and retry (most YouTube errors are fixed by a newer yt-dlp). */
    private suspend fun <T> withUpdateRetry(ctx: Context, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            maybeUpdate(ctx, force = true)
            return block()
        }
    }

    suspend fun fetchInfo(ctx: Context, url: String): Info = withContext(Dispatchers.IO) {
        ensureInit(ctx)
        maybeUpdate(ctx, force = false)
        val json = withUpdateRetry(ctx) {
            val req = YoutubeDLRequest(url.trim())
            req.addOption("-J")
            req.addOption("--no-playlist")
            req.addOption("--no-warnings")
            val out = YoutubeDL.getInstance().execute(req, null, null).out
            JSONObject(out.substring(out.indexOf('{')))
        }
        parseInfo(json)
    }

    private fun parseInfo(j: JSONObject): Info {
        val duration = j.optDouble("duration", 0.0).toLong()
        val formats = j.optJSONArray("formats")
        val videoByHeight = sortedMapOf<Int, Long>(compareByDescending { it })
        var bestAudio = 0L
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val v = f.optString("vcodec", "none")
                val a = f.optString("acodec", "none")
                val h = f.optInt("height", 0)
                var size = f.optLong("filesize", 0L)
                if (size <= 0) size = f.optLong("filesize_approx", 0L)
                if (size <= 0 && duration > 0) size = (f.optDouble("tbr", 0.0) * 1000 / 8 * duration).toLong()
                if (v != "none" && h > 0) {
                    videoByHeight[h] = maxOf(videoByHeight[h] ?: 0L, size)
                } else if (v == "none" && a != "none") {
                    bestAudio = maxOf(bestAudio, size)
                }
            }
        }
        val video = videoByHeight.map { (h, size) ->
            Option(
                label = "${h}p",
                isAudio = false,
                selector = "bv*[height<=$h]+ba/b[height<=$h]",
                audioExt = null,
                audioQuality = null,
                approxBytes = if (size > 0) size + bestAudio else 0L
            )
        }.ifEmpty {
            listOf(Option("Best available", false, "bv*+ba/b", null, null, 0L))
        }
        val abase = if (bestAudio > 0) bestAudio else if (duration > 0) duration * 16000 else 0L
        fun mp3(kbps: Int) = Option(
            "MP3 $kbps kbps", true, null, "mp3", "${kbps}K",
            if (duration > 0) duration * kbps * 1000 / 8 else abase
        )
        val audio = listOf(mp3(320), mp3(192), mp3(128), Option("M4A (original, no re-encode)", true, null, "m4a", null, abase))
        return Info(
            title = j.optString("title", "video"),
            uploader = j.optString("uploader", "").ifBlank { null },
            durationSec = duration,
            thumbnail = j.optString("thumbnail", "").ifBlank { null },
            video = video,
            audio = audio
        )
    }

    fun safeName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(100).ifBlank { "youtube" }

    private val OUT_REGEX = Regex("^out\\.[A-Za-z0-9]+$")
    private fun findOutput(dir: File): File? =
        dir.listFiles()?.filter { it.isFile && OUT_REGEX.matches(it.name) && it.length() > 0 }?.maxByOrNull { it.length() }

    private val SPEED_RE = Regex("at\\s+([\\d.]+)\\s*([KMG]?)i?B/s")

    private fun parseSpeed(line: String): Long {
        val m = SPEED_RE.find(line) ?: return 0L
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val mul = when (m.groupValues[2]) { "K" -> 1024.0; "M" -> 1024.0 * 1024; "G" -> 1024.0 * 1024 * 1024; else -> 1.0 }
        return (v * mul).toLong()
    }

    /** Download with yt-dlp into app storage, then copy into the chosen folder as [fileName]. */
    suspend fun download(
        ctx: Context,
        id: Long,
        url: String,
        fileName: String,
        treeUri: android.net.Uri,
        kind: String,
        format: String?,
        audioExt: String?,
        audioQuality: String?,
        estimatedSize: Long,
        onProgress: (DownloadEngine.Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: error("Download folder is unavailable (permission revoked?)")
        if (!tree.canWrite()) error("Cannot write to download folder. Please re-select the folder in Settings.")

        ensureInit(ctx)
        val dir = tmpDir(ctx, id).apply { mkdirs() }
        val total = if (estimatedSize > 0) estimatedSize else 10_000L

        if (findOutput(dir) == null) {
            maybeUpdate(ctx, force = false)
            val streams = if (kind == "video" && (format ?: "").contains("+")) 2 else 1
            var best = 0.0
            withUpdateRetry(ctx) { runYtDlp(id, url, dir, kind, format, audioExt, audioQuality, streams, total, { p -> if (p > best) best = p; best }, onProgress) }
        }

        val produced = findOutput(dir) ?: error("yt-dlp finished but no output file was created")
        currentCoroutineContext().ensureActive()
        onProgress(DownloadEngine.Progress(total, total, DownloadEngine.Progress.FINALIZING))

        val existing = tree.findFile(fileName)
        val target = if (existing != null && existing.canWrite()) existing else {
            existing?.delete()
            val mime = if (fileName.endsWith(".mp3", true)) "audio/mpeg"
                else if (fileName.endsWith(".m4a", true)) "audio/mp4" else "video/mp4"
            tree.createFile(mime, fileName) ?: error("Cannot create output file")
        }
        ctx.contentResolver.openOutputStream(target.uri, "wt")?.use { sink ->
            produced.inputStream().use { it.copyTo(sink, 256 * 1024) }
        } ?: error("Cannot open output file")
        if (target.length() != produced.length()) error("Copy to download folder incomplete")
        // temp data is deleted only after a verified copy (kill during copy -> next run copies again, no re-download)
        runCatching { dir.deleteRecursively() }
        onProgress(DownloadEngine.Progress(total, total, 0))
    }

    private suspend fun runYtDlp(
        id: Long,
        url: String,
        dir: File,
        kind: String,
        format: String?,
        audioExt: String?,
        audioQuality: String?,
        streams: Int,
        total: Long,
        monotonic: (Double) -> Double,
        onProgress: (DownloadEngine.Progress) -> Unit
    ) {
        val processId = "f2l-$id"
        val req = YoutubeDLRequest(url.trim())
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--no-warnings")
        req.addOption("-N", "4")                       // parallel fragments
        req.addOption("-o", File(dir, "out.%(ext)s").absolutePath)
        if (kind == "audio") {
            req.addOption("-f", "bestaudio/best")
            req.addOption("-x")
            req.addOption("--audio-format", audioExt ?: "mp3")
            if (audioQuality != null) req.addOption("--audio-quality", audioQuality)
        } else {
            req.addOption("-f", format ?: "bv*+ba/b")
            req.addOption("-S", "res,vcodec:h264,acodec:aac")   // prefer widely playable codecs at the chosen resolution
            req.addOption("--merge-output-format", "mp4")
        }

        var streamIdx = 0
        var lastEmit = 0L
        // Pause / app closed: kill the yt-dlp process at once (it keeps its .part files -> resume continues)
        val watcher = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Default) {
            try { awaitCancellation() } finally {
                runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            }
        }
        try {
            YoutubeDL.getInstance().execute(req, processId) { progress: Float, _: Long, line: String ->
                if (line.contains("Destination:")) streamIdx++
                if (line.contains("[Merger]") || line.contains("[ExtractAudio]") || line.contains("[VideoConvertor]")) {
                    onProgress(DownloadEngine.Progress(total, total, DownloadEngine.Progress.FINALIZING))
                } else if (progress >= 0f) {
                    val pct = progress.toDouble().coerceIn(0.0, 100.0)
                    val overall = if (streams == 2) {
                        if (streamIdx <= 1) pct * 0.9 else 90.0 + pct * 0.1
                    } else pct
                    val shown = monotonic(overall)
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 400) {
                        lastEmit = now
                        onProgress(DownloadEngine.Progress((total * shown / 100.0).toLong(), total, parseSpeed(line)))
                    }
                }
            }
        } finally {
            watcher.cancel()
        }
    }
}
