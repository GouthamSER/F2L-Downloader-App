package com.f2l.downloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
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

    /** speed == FINALIZING means: all bytes downloaded, now joining parts / writing final file */
    data class Progress(val downloaded: Long, val total: Long, val speed: Long) {
        companion object { const val FINALIZING = -1L }
    }

    private class RangeRefusedException(msg: String) : Exception(msg)

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

        // Standard HTTP / HTTPS download
        val headReq = Request.Builder().url(clean).head().apply {
            applyCustomHeaders(this, userAgent, referer, customHeaders)
        }.build()

        var headCode = 0
        var total = -1L
        var ranges = false
        client.newCall(headReq).execute().use { head ->
            headCode = head.code
            total = head.header("Content-Length")?.toLongOrNull() ?: -1L
            ranges = head.header("Accept-Ranges")?.contains("bytes", true) == true
        }
        if (headCode !in 200..299 && headCode != 405) {
            error(httpErrorMessage(headCode))
        }

        // Remove orphan temp files left by older pause/resume runs (e.g. "name.f2l.part.mp4", "name.f2l.part (1)")
        removeStrayParts(tree, fileName)

        // Check if the destination file is already completely downloaded
        val existingFinal = tree.findFile(fileName)
        if (existingFinal != null && existingFinal.exists()) {
            val existingLen = existingFinal.length()
            if (total > 0 && existingLen == total) {
                // File is already fully downloaded! Clean up any leftover segments and complete
                cleanupPartFiles(tree, fileName)
                onProgress(Progress(total, total, 0))
                return@withContext
            }
        }

        // A single-connection temp file with data means an earlier run already fell back to 1 connection: keep resuming it
        val singlePartLen = tree.findFile("$fileName.f2l.part")?.length() ?: 0L
        if (total > 0 && ranges && connections > 1 && singlePartLen <= 0L) {
            try {
                segmented(clean, fileName, tree, total, min(connections, 16), userAgent, referer, customHeaders, onProgress)
            } catch (e: CancellationException) {
                // Pause / cancel: keep segment files for resume, never fall back
                throw e
            } catch (e: Exception) {
                // Server refuses parallel ranges, or segments barely started: use 1 connection and drop the empty segment files.
                // Otherwise (real progress made, e.g. network blip) rethrow so the retry loop resumes the segments.
                val got = segmentBytes(tree, fileName)
                if (e is RangeRefusedException || got < total / 20) {
                    removeSegmentParts(tree, fileName)
                    single(clean, fileName, tree, total, userAgent, referer, customHeaders, onProgress)
                } else {
                    throw e
                }
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

    private fun segmentRegex(fileName: String) = Regex("^" + Regex.escape("$fileName.f2l.part") + "\\d{1,2}$")

    private fun segmentBytes(tree: DocumentFile, fileName: String): Long = try {
        val re = segmentRegex(fileName)
        tree.listFiles().filter { f -> f.name?.let { re.matches(it) } == true }.sumOf { it.length().coerceAtLeast(0L) }
    } catch (_: Exception) { 0L }

    private fun removeSegmentParts(tree: DocumentFile, fileName: String) {
        try {
            val re = segmentRegex(fileName)
            tree.listFiles().forEach { f -> if (f.name?.let { re.matches(it) } == true) f.delete() }
        } catch (_: Exception) {}
    }

    /** Delete temp files that start with "<name>.f2l.part" but are not one of our exact part names. */
    private fun removeStrayParts(tree: DocumentFile, fileName: String) {
        try {
            val prefix = "$fileName.f2l.part"
            val valid = Regex("^" + Regex.escape(prefix) + "(\\d{1,2})?$")
            tree.listFiles().forEach { f ->
                val n = f.name ?: return@forEach
                if (n.startsWith(prefix) && !valid.matches(n)) f.delete()
            }
        } catch (_: Exception) {}
    }

    private fun cleanupPartFiles(tree: DocumentFile, fileName: String) {
        try {
            tree.listFiles().forEach { f ->
                val n = f.name ?: return@forEach
                if (n == "$fileName.f2l.part" || (n.startsWith("$fileName.f2l.part") && n != fileName)) {
                    f.delete()
                }
            }
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
        val partName = "$fileName.f2l.part"
        // octet-stream keeps the exact name; a real mime makes SAF append an extension (name.f2l.part.mp4)
        // so findFile() missed it on resume and a new temp file was created every time.
        val part = tree.findFile(partName) ?: tree.createFile("application/octet-stream", partName)
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
        if (total > 0L && completed < total) {
            // Server closed the stream early. Throw so the retry loop resumes instead of marking a half file as done.
            throw java.io.IOException("unexpected end of stream")
        }
        if (total <= 0L || completed >= total) {
            onProgress(Progress(completed, total, Progress.FINALIZING))
            val final = tree.findFile(fileName)
            if (final != null && final.canWrite()) {
                context.contentResolver.openOutputStream(final.uri, "wt")?.use { sink ->
                    context.contentResolver.openInputStream(part.uri)?.use { input ->
                        input.copyTo(sink, 256 * 1024)
                    }
                }
                part.delete()
            } else {
                final?.delete()
                if (!part.renameTo(fileName)) {
                    val newFile = tree.createFile(guessMime(fileName), fileName)
                        ?: error("Cannot finalize completed download file")
                    context.contentResolver.openOutputStream(newFile.uri, "wt")?.use { sink ->
                        context.contentResolver.openInputStream(part.uri)?.use { input ->
                            input.copyTo(sink, 256 * 1024)
                        }
                    }
                    part.delete()
                }
            }
            cleanupPartFiles(tree, fileName)
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
        onProgress(Progress(downloadedTotal.get(), total, Progress.FINALIZING))

        val existingFinal = tree.findFile(fileName)
        val output = if (existingFinal != null && existingFinal.canWrite()) {
            existingFinal
        } else {
            existingFinal?.delete()
            tree.createFile(guessMime(fileName), fileName)
                ?: error("Cannot create final file")
        }
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
        cleanupPartFiles(tree, fileName)
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
            if (response.code != 206) throw RangeRefusedException("Server refused range request (HTTP ${response.code})")
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
                    if (got < end - start + 1) throw java.io.IOException("unexpected end of stream")
                }
            }
        }
    }
}
