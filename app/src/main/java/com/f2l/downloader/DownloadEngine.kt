package com.f2l.downloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
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

    data class Progress(val downloaded: Long, val total: Long, val speed: Long)

    companion object {
        fun isMagnet(url: String): Boolean = url.trim().startsWith("magnet:?", ignoreCase = true)

        fun parseMagnetUri(uriString: String): MagnetInfo? {
            val clean = uriString.trim()
            if (!isMagnet(clean)) return null
            return try {
                val uri = Uri.parse(clean)
                val xt = uri.getQueryParameter("xt") // urn:btih:<hash>
                val hash = xt?.substringAfter("urn:btih:", "")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return null
                val dn = uri.getQueryParameter("dn")?.let {
                    runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                }
                val trackers = uri.getQueryParameters("tr")
                MagnetInfo(hash, dn, trackers)
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
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

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
        val magnet = parseMagnetUri(clean)
        if (magnet != null) {
            val name = magnet.displayName?.takeIf { it.isNotBlank() } ?: "torrent-${magnet.infoHash.take(8)}"
            return@withContext if (name.endsWith(".torrent", ignoreCase = true)) name else "$name.torrent"
        }

        val fallback = guessNameFromUrl(clean)
        try {
            val reqBuilder = Request.Builder().url(clean).head()
            applyCustomHeaders(reqBuilder, userAgent, referer, customHeaders)
            val head = client.newCall(reqBuilder.build()).execute()
            val disposition = head.header("Content-Disposition")
            val fromHeader = disposition?.let { parseContentDisposition(it) }
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

        // Magnet link handling
        val magnet = parseMagnetUri(clean)
        if (magnet != null) {
            downloadMagnet(clean, magnet, fileName, tree, onProgress)
            return@withContext
        }

        // Standard HTTP / HTTPS download
        val headReq = Request.Builder().url(clean).head().apply {
            applyCustomHeaders(this, userAgent, referer, customHeaders)
        }.build()

        val head = client.newCall(headReq).execute()
        if (!head.isSuccessful && head.code != 405) {
            error(httpErrorMessage(head.code))
        }

        val total = head.header("Content-Length")?.toLongOrNull() ?: -1L
        val ranges = head.header("Accept-Ranges")?.contains("bytes", true) == true

        if (total > 0 && ranges && connections > 1) {
            try {
                segmented(clean, fileName, tree, total, min(connections, 16), userAgent, referer, customHeaders, onProgress)
            } catch (e: Exception) {
                // Graceful fallback to single-connection if range requests failed midway
                single(clean, fileName, tree, total, userAgent, referer, customHeaders, onProgress)
            }
        } else {
            single(clean, fileName, tree, total, userAgent, referer, customHeaders, onProgress)
        }
    }

    private suspend fun downloadMagnet(
        magnetUrl: String,
        magnet: MagnetInfo,
        fileName: String,
        tree: DocumentFile,
        onProgress: (Progress) -> Unit
    ) {
        onProgress(Progress(10, 100, 0))
        val torrentName = if (fileName.endsWith(".torrent", ignoreCase = true)) fileName else "$fileName.torrent"
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
                val req = Request.Builder().url(cacheUrl).header("User-Agent", "F2LDownloader/3.2.0").build()
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
            val magnetFileName = if (fileName.endsWith(".magnet", ignoreCase = true)) fileName else "$fileName.magnet"
            val magnetFile = tree.findFile(magnetFileName) ?: tree.createFile("text/plain", magnetFileName)
                ?: error("Cannot create magnet file")
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
        val partName = "$fileName.f2l.part"
        val part = tree.findFile(partName) ?: tree.createFile(guessMime(fileName), partName)
        ?: error("Cannot create temporary download file — verify permissions or disk space")

        val existing = part.length().coerceAtLeast(0L)
        val request = Request.Builder().url(url).apply {
            applyCustomHeaders(this, userAgent, referer, customHeaders)
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                error(httpErrorMessage(response.code))
            }
            if (response.code != 206 && existing > 0) {
                context.contentResolver.openFileDescriptor(part.uri, "wt")?.use {}
            }
            response.body?.byteStream()?.use { input ->
                val output = context.contentResolver.openOutputStream(part.uri, "wa")
                    ?: error("Cannot open output stream for writing")
                output.use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = if (response.code == 206) existing else 0L
                    var last = downloaded
                    var lastT = System.nanoTime()
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        downloaded += n
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

        val completed = part.length()
        if (total <= 0L || completed >= total) {
            val final = tree.findFile(fileName)
            final?.delete()
            if (!part.renameTo(fileName)) error("Cannot finalize completed download file")
        }
    }

    private suspend fun segmented(
        url: String,
        fileName: String,
        tree: DocumentFile,
        total: Long,
        count: Int,
        userAgent: String?,
        referer: String?,
        customHeaders: String?,
        onProgress: (Progress) -> Unit
    ) = coroutineScope {
        val chunk = (total + count - 1) / count
        val downloadedTotal = java.util.concurrent.atomic.AtomicLong(0L)

        val reporter = launch {
            var last = 0L
            var lastT = System.nanoTime()
            while (isActive) {
                delay(500)
                val now = System.nanoTime()
                val downloaded = downloadedTotal.get()
                val sec = (now - lastT) / 1e9
                val speed = if (sec > 0) ((downloaded - last) / sec).toLong() else 0L
                onProgress(Progress(downloaded, total, speed))
                last = downloaded
                lastT = now
            }
        }

        data class Segment(val index: Int, val start: Long, val end: Long, val part: DocumentFile, val existing: Long)
        val segments = (0 until count).map { index ->
            val start = index * chunk
            val end = min(total - 1, start + chunk - 1)
            val partName = "$fileName.f2l.part$index"
            var part = tree.findFile(partName)
                ?: tree.createFile("application/octet-stream", partName)
                ?: error("Cannot create segment $index — check storage space")

            var existing = part.length().coerceAtLeast(0L)
            val expected = end - start + 1
            if (existing > expected) {
                part.delete()
                part = tree.createFile("application/octet-stream", partName) ?: error("Cannot recreate segment $index")
                existing = 0L
            }
            if (existing > 0) downloadedTotal.addAndGet(existing)
            Segment(index, start, end, part, existing)
        }

        val jobs = segments.map { seg ->
            launch(Dispatchers.IO) {
                val expected = seg.end - seg.start + 1
                if (seg.existing < expected) {
                    downloadRange(url, seg.part, seg.start, seg.end, seg.existing, downloadedTotal, userAgent, referer, customHeaders)
                }
            }
        }
        jobs.joinAll()
        reporter.cancel()
        onProgress(Progress(downloadedTotal.get(), total, 0))

        val final = tree.findFile(fileName)
        final?.delete()
        val output = tree.createFile(guessMime(fileName), fileName)
            ?: error("Cannot create final file")
        val out = context.contentResolver.openOutputStream(output.uri, "wt")
            ?: error("Cannot open final file")
        out.use { sink ->
            for (i in 0 until count) {
                val part = tree.findFile("$fileName.f2l.part$i") ?: error("Missing segment $i")
                context.contentResolver.openInputStream(part.uri)?.use { input ->
                    input.copyTo(sink, 256 * 1024)
                } ?: error("Cannot read segment $i")
                part.delete()
            }
        }
        onProgress(Progress(total, total, 0))
    }

    private suspend fun downloadRange(
        url: String,
        part: DocumentFile,
        start: Long,
        end: Long,
        existing: Long,
        downloadedTotal: java.util.concurrent.atomic.AtomicLong,
        userAgent: String?,
        referer: String?,
        customHeaders: String?
    ) {
        val from = start + existing
        val request = Request.Builder().url(url).apply {
            applyCustomHeaders(this, userAgent, referer, customHeaders)
            header("Range", "bytes=$from-$end")
        }.build()

        client.newCall(request).execute().use { response ->
            if (response.code != 206) error("Server refused range request (HTTP ${response.code})")
            response.body?.byteStream()?.use { input ->
                val out = context.contentResolver.openOutputStream(part.uri, "wa")
                    ?: error("Cannot open segment output")
                out.use { sink ->
                    val buffer = ByteArray(128 * 1024)
                    var got = existing
                    while (got < end - start + 1) {
                        coroutineContext.ensureActive()
                        val wanted = min(buffer.size.toLong(), end - start + 1 - got).toInt()
                        val n = input.read(buffer, 0, wanted)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        got += n
                        downloadedTotal.addAndGet(n.toLong())
                    }
                }
            }
        }
    }
}
