package com.f2l.downloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

class DownloadEngine(private val context: Context) {
    data class MagnetInfo(
        val infoHash: String,
        val displayName: String?,
        val trackers: List<String>
    )

    /** speed == FINALIZING means: all bytes downloaded, now joining parts / writing final file */
    data class Progress(val downloaded: Long, val total: Long, val speed: Long) {
        companion object { const val FINALIZING = -1L }
    }

    companion object {
        fun isMagnet(url: String): Boolean = url.trim().startsWith("magnet:", ignoreCase = true)

        private fun base32ToHex(base32: String): String? {
            val clean = base32.trim().uppercase()
            if (clean.length != 32) return null
            val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var buffer = 0
            var bitsLeft = 0
            val bytes = ByteArray(20)
            var count = 0
            for (c in clean) {
                val valIndex = base32Chars.indexOf(c)
                if (valIndex < 0) return null
                buffer = (buffer shl 5) or valIndex
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    bytes[count++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                    bitsLeft -= 8
                }
            }
            if (count != 20) return null
            return bytes.joinToString("") { "%02X".format(it) }
        }

        fun parseMagnetUri(uriString: String): MagnetInfo? {
            val clean = uriString.trim()
            if (!isMagnet(clean)) return null
            return try {
                val queryIndex = clean.indexOf('?')
                val queryString = if (queryIndex != -1) clean.substring(queryIndex + 1) else clean.substringAfter("magnet:")
                val params = queryString.split('&')
                var hash: String? = null
                var dn: String? = null
                val trackers = mutableListOf<String>()

                for (param in params) {
                    if (param.isBlank()) continue
                    val eqIdx = param.indexOf('=')
                    val key = if (eqIdx != -1) param.substring(0, eqIdx).trim() else param.trim()
                    val rawVal = if (eqIdx != -1) param.substring(eqIdx + 1).trim() else ""
                    val value = runCatching { java.net.URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)

                    when (key.lowercase()) {
                        "xt" -> {
                            val candidate = when {
                                value.startsWith("urn:btih:", ignoreCase = true) -> value.substring(9).trim()
                                value.startsWith("urn:btmh:", ignoreCase = true) -> value.substring(9).trim()
                                else -> value.substringAfterLast(':', value).trim()
                            }
                            if (candidate.isNotEmpty() && hash == null) {
                                hash = if (candidate.length == 32) base32ToHex(candidate) ?: candidate else candidate
                            }
                        }
                        "dn" -> {
                            if (value.isNotBlank() && dn == null) {
                                dn = value
                            }
                        }
                        "tr" -> {
                            if (value.isNotBlank()) {
                                trackers.add(value)
                            }
                        }
                    }
                }

                if (hash.isNullOrBlank()) return null
                MagnetInfo(hash.uppercase(), dn, trackers)
            } catch (_: Exception) {
                null
            }
        }

        fun httpErrorMessage(code: Int): String = when (code) {
            401 -> "Authentication required (HTTP 401)"
            403 -> "Access denied (HTTP 403) — check custom headers or cookies"
            404 -> "File not found (HTTP 404)"
            410 -> "File no longer available (HTTP 410)"
            416 -> "Range not satisfiable (file may have changed)"
            429 -> "Server busy (HTTP 429) - too many connections. Wait a moment, then retry."
            503 -> "Server busy (HTTP 503). Wait a moment, then retry."
            in 500..599 -> "Server error (HTTP $code)"
            else -> "HTTP $code"
        }
    }

    private fun guessMime(fileName: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // stalled connection -> timeout -> auto retry (was 0 = hang forever)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Run a blocking OkHttp call so that coroutine cancel (pause / app closed) aborts the socket at once.
     * Without this a paused download kept blocking in read() up to 30s, then threw IOException,
     * which the service treated as a network error (retry) and the job never ended -> Resume was ignored.
     */
    private suspend inline fun <T> Call.executeCancellable(block: (Response) -> T): T {
        val call = this
        val watcher = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Default) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            return call.execute().use(block)
        } finally {
            watcher.cancel()
        }
    }

    /** Wait time before retrying a 429/503. Honors Retry-After, else 2s,4s,8s,16s (capped 20s). */
    private fun retryAfterMs(response: Response, tries: Int): Long {
        val header = response.header("Retry-After")?.trim()?.toLongOrNull()
        val base = if (header != null) header * 1000L else 2000L shl min(tries, 3)
        return base.coerceIn(1000L, 20000L)
    }

    private fun isBusy(code: Int) = code == 429 || code == 503

    private fun applyCustomHeaders(
        builder: Request.Builder,
        userAgent: String?,
        referer: String?,
        customHeaders: String?
    ) {
        if (!userAgent.isNullOrBlank()) {
            builder.header("User-Agent", userAgent.trim())
        }
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer.trim())
        }
        if (!customHeaders.isNullOrBlank()) {
            customHeaders.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.contains(':')) {
                    val name = trimmed.substringBefore(':').trim()
                    val value = trimmed.substringAfter(':').trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) {
                        builder.addHeader(name, value)
                    }
                }
            }
        }
    }

    /** Detect the real file name from server headers (Content-Disposition), falling back to the URL path or Magnet info. */
    suspend fun resolveFileName(
        url: String,
        userAgent: String? = null,
        referer: String? = null,
        customHeaders: String? = null
    ): String = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (isMagnet(clean)) {
            val magnet = parseMagnetUri(clean)
            val name = magnet?.displayName?.takeIf { it.isNotBlank() } ?: "torrent-${magnet?.infoHash?.take(8) ?: System.currentTimeMillis()}"
            return@withContext if (name.endsWith(".torrent", ignoreCase = true) || name.endsWith(".magnet", ignoreCase = true)) name else "$name.torrent"
        }

        val fallback = guessNameFromUrl(clean)
        try {
            val reqBuilder = Request.Builder().url(clean).head()
            applyCustomHeaders(reqBuilder, userAgent, referer, customHeaders)
            val fromHeader = client.newCall(reqBuilder.build()).execute().use { head ->
                head.header("Content-Disposition")?.let { parseContentDisposition(it) }
            }
            fromHeader ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun parseContentDisposition(header: String): String? {
        val star = Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(header)
        val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        val raw = star?.groupValues?.get(1) ?: plain?.groupValues?.get(1)
        return raw?.let { runCatching { java.net.URLDecoder.decode(it.trim(), "UTF-8") }.getOrDefault(it.trim()) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun guessNameFromUrl(url: String): String = try {
        val last = java.net.URI(url).path.substringAfterLast('/', "")
        java.net.URLDecoder.decode(last, "UTF-8").ifBlank { "download-${System.currentTimeMillis()}.bin" }
    } catch (_: Exception) { "download-${System.currentTimeMillis()}.bin" }

    suspend fun download(
        url: String,
        fileName: String,
        treeUri: Uri,
        connections: Int = 8,
        userAgent: String? = null,
        referer: String? = null,
        customHeaders: String? = null,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Download folder is unavailable (permission revoked?)")

        if (!tree.canWrite()) {
            error("Cannot write to download folder. Please re-select the folder in Settings.")
        }

        val clean = url.trim()

        // Magnet link handling - MUST NEVER fall through to OkHttp
        if (isMagnet(clean)) {
            val magnet = parseMagnetUri(clean)
            if (magnet != null) {
                downloadMagnet(clean, magnet, fileName, tree, onProgress)
            } else {
                downloadRawMagnet(clean, fileName, tree, onProgress)
            }
            return@withContext
        }

        // Standard HTTP / HTTPS download: ONE connection, ONE file, resumable (no part files).
        val headReq = Request.Builder().url(clean).head().apply {
            applyCustomHeaders(this, userAgent, referer, customHeaders)
        }.build()

        var headCode = 0
        var total = -1L
        var headTries = 0
        while (true) {
            var retryMs = -1L
            client.newCall(headReq).executeCancellable { head ->
                headCode = head.code
                total = head.header("Content-Length")?.toLongOrNull() ?: -1L
                if (isBusy(head.code)) retryMs = retryAfterMs(head, headTries)
            }
            if (retryMs < 0) break
            if (++headTries > 8) error(httpErrorMessage(headCode))
            delay(retryMs)
        }
        if (headCode !in 200..299 && headCode != 405) {
            error(httpErrorMessage(headCode))
        }

        // Old app versions left "<name>.f2l.part*" files. Convert/remove them once, never create new ones.
        migrateLegacyParts(tree, fileName)

        single(clean, fileName, tree, total, userAgent, referer, customHeaders, onProgress)
    }

    private suspend fun downloadMagnet(
        magnetUrl: String,
        magnet: MagnetInfo,
        fileName: String,
        tree: DocumentFile,
        onProgress: (Progress) -> Unit
    ) {
        onProgress(Progress(10, 100, 0))
        val baseName = fileName.removeSuffix(".torrent").removeSuffix(".magnet")
        val torrentName = "$baseName.torrent"
        val existing = tree.findFile(torrentName)
        existing?.delete()

        // Attempt downloading .torrent payload from web torrent caches
        val cacheUrls = listOf(
            "https://itorrents.org/torrent/${magnet.infoHash.uppercase()}.torrent",
            "https://btcache.me/torrent/${magnet.infoHash.uppercase()}"
        )

        var downloadedTorrent = false
        for (cacheUrl in cacheUrls) {
            try {
                val req = Request.Builder().url(cacheUrl).header("User-Agent", "F2LDownloader/3.6.1").build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        res.body?.bytes()?.let { bytes ->
                            if (bytes.isNotEmpty() && bytes.size > 50) {
                                val file = tree.createFile("application/x-bittorrent", torrentName)
                                    ?: error("Cannot create torrent file")
                                context.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
                                    out.write(bytes)
                                }
                                downloadedTorrent = true
                            }
                        }
                    }
                }
                if (downloadedTorrent) break
            } catch (_: Exception) {
                // Next mirror
            }
        }

        if (!downloadedTorrent) {
            // Write standard magnet link file (.magnet) readable by external torrent clients and BitTorrent handlers
            val magnetFileName = "$baseName.magnet"
            val existingMag = tree.findFile(magnetFileName)
            val magnetFile = if (existingMag != null && existingMag.canWrite()) existingMag
                else tree.createFile("text/plain", magnetFileName) ?: error("Cannot create magnet file")
            context.contentResolver.openOutputStream(magnetFile.uri, "wt")?.use { out ->
                val content = buildString {
                    appendLine("[InternetShortcut]")
                    appendLine("URL=$magnetUrl")
                    appendLine()
                    appendLine("# Magnet Details")
                    appendLine("# Hash: ${magnet.infoHash}")
                    if (magnet.displayName != null) appendLine("# Name: ${magnet.displayName}")
                    magnet.trackers.forEach { appendLine("# Tracker: $it") }
                }
                out.write(content.toByteArray(Charsets.UTF_8))
            }
        }

        onProgress(Progress(100, 100, 0))
    }

    private suspend fun downloadRawMagnet(
        magnetUrl: String,
        fileName: String,
        tree: DocumentFile,
        onProgress: (Progress) -> Unit
    ) {
        onProgress(Progress(10, 100, 0))
        val baseName = fileName.removeSuffix(".torrent").removeSuffix(".magnet")
        val magnetFileName = "$baseName.magnet"
        val existingMag = tree.findFile(magnetFileName)
        val magnetFile = if (existingMag != null && existingMag.canWrite()) existingMag
            else tree.createFile("text/plain", magnetFileName) ?: error("Cannot create magnet file")
        context.contentResolver.openOutputStream(magnetFile.uri, "wt")?.use { out ->
            val content = buildString {
                appendLine("[InternetShortcut]")
                appendLine("URL=$magnetUrl")
            }
            out.write(content.toByteArray(Charsets.UTF_8))
        }
        onProgress(Progress(100, 100, 0))
    }

    /**
     * Old versions wrote "<name>.f2l.part" (single) and "<name>.f2l.part0..N" (segments).
     * Single part with data -> becomes the real file (so the old download resumes). Everything else -> deleted.
     */
    private fun migrateLegacyParts(tree: DocumentFile, fileName: String) {
        try {
            val prefix = "$fileName.f2l.part"
            val all = tree.listFiles().filter { it.name?.startsWith(prefix) == true }
            if (all.isEmpty()) return
            val main = all.firstOrNull { it.name == prefix }
            if (main != null && tree.findFile(fileName) == null && main.length() > 0) {
                if (main.renameTo(fileName)) {
                    all.filter { it.name != prefix }.forEach { it.delete() }
                    return
                }
            }
            all.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    private suspend fun single(
        url: String,
        fileName: String,
        tree: DocumentFile,
        total: Long,
        userAgent: String?,
        referer: String?,
        customHeaders: String?,
        onProgress: (Progress) -> Unit
    ) {
        // The download writes straight into the final file and resumes from its length. No temp/part files.
        // octet-stream keeps the exact name (a real mime makes SAF append an extension, then findFile() misses
        // it on resume and a new file is created on every retry).
        var file = tree.findFile(fileName)
        if (file == null) {
            val created = tree.createFile("application/octet-stream", fileName)
                ?: error("Cannot create file - verify permissions or disk space")
            if (created.name != fileName) {
                if (!created.renameTo(fileName)) {
                    created.delete()
                    error("Cannot create file with the exact name - verify permissions")
                }
            }
            file = tree.findFile(fileName) ?: error("Cannot create file - verify permissions or disk space")
        }
        val target: DocumentFile = file

        var existing = target.length().coerceAtLeast(0L)
        if (total > 0L && existing > total) {
            // changed / corrupt file: start over
            context.contentResolver.openFileDescriptor(target.uri, "wt")?.use {}
            existing = 0L
        }

        // Already complete: do NOT send Range (server answers 416)
        if (!(total > 0L && existing == total)) {
            val request = Request.Builder().url(url).apply {
                applyCustomHeaders(this, userAgent, referer, customHeaders)
                if (existing > 0) header("Range", "bytes=$existing-")
            }.build()

            var busyTries = 0
            while (true) {
                var retryMs = -1L
                client.newCall(request).executeCancellable { response ->
                    // 429 / 503: server is full. Wait and retry here, do not count as a failed download.
                    if (isBusy(response.code)) {
                        retryMs = retryAfterMs(response, busyTries)
                        return@executeCancellable
                    }
                    // 416 with data on disk and unknown total = file already holds everything
                    val alreadyDone = response.code == 416 && existing > 0 && total <= 0L
                    if (!alreadyDone && !response.isSuccessful && response.code != 206) {
                        error(httpErrorMessage(response.code))
                    }
                    if (!alreadyDone) {
                        if (response.code != 206 && existing > 0) {
                            // server ignored Range: restart file from 0
                            context.contentResolver.openFileDescriptor(target.uri, "wt")?.use {}
                            existing = 0L
                        }
                        response.body?.byteStream()?.use { input ->
                            val output = context.contentResolver.openOutputStream(target.uri, "wa")
                                ?: error("Cannot open output stream for writing")
                            output.use { out ->
                                val buffer = ByteArray(256 * 1024)
                                var downloaded = existing
                                var last = downloaded
                                var lastT = System.nanoTime()
                                // optional speed cap (Settings). Re-read every second so a change applies live.
                                val limitPrefs = context.getSharedPreferences("f2l_settings", Context.MODE_PRIVATE)
                                var limitBps = limitPrefs.getInt("speed_limit_kbps", 0) * 1024L
                                var limitCheckT = System.nanoTime()
                                var winStart = System.nanoTime()
                                var winBytes = 0L
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    downloaded += n
                                    val tNow = System.nanoTime()
                                    if (tNow - limitCheckT > 1_000_000_000L) {
                                        val newLimit = limitPrefs.getInt("speed_limit_kbps", 0) * 1024L
                                        if (newLimit != limitBps) { limitBps = newLimit; winStart = tNow; winBytes = 0L }
                                        limitCheckT = tNow
                                    }
                                    if (limitBps > 0) {
                                        winBytes += n
                                        val elapsed = tNow - winStart
                                        val needed = winBytes * 1_000_000_000L / limitBps
                                        if (needed > elapsed) delay((needed - elapsed) / 1_000_000L)
                                        if (elapsed > 3_000_000_000L) { winStart = System.nanoTime(); winBytes = 0L }
                                    }
                                    val now = System.nanoTime()
                                    if (now - lastT > 500_000_000L) {
                                        val sec = (now - lastT) / 1e9
                                        onProgress(Progress(downloaded, total, ((downloaded - last) / sec).toLong()))
                                        last = downloaded
                                        lastT = now
                                    }
                                }
                                onProgress(Progress(downloaded, total, 0))
                            }
                        }
                    }
                }
                if (retryMs < 0) break
                if (++busyTries > 10) error(httpErrorMessage(429))
                delay(retryMs)
            }
        }

        val completed = target.length()
        if (total > 0L && completed < total) {
            // Server closed the stream early. Throw so the retry loop resumes instead of marking a half file as done.
            throw java.io.IOException("unexpected end of stream")
        }
        onProgress(Progress(completed, total, 0))
    }
}
