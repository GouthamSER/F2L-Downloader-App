package com.f2l.downloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min

class DownloadEngine(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    data class Progress(val downloaded: Long, val total: Long, val speed: Long)

    suspend fun download(
        url: String,
        fileName: String,
        treeUri: Uri,
        connections: Int = 8,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Download folder is unavailable")

        val head = client.newCall(Request.Builder().url(url).head().build()).execute()
        if (!head.isSuccessful && head.code != 405) error("HTTP ${head.code}")

        val total = head.header("Content-Length")?.toLongOrNull() ?: -1L
        val ranges = head.header("Accept-Ranges")?.contains("bytes", true) == true

        if (total > 0 && ranges && connections > 1) {
            segmented(url, fileName, tree, total, min(connections, 16), onProgress)
        } else {
            single(url, fileName, tree, total, onProgress)
        }
    }

    private suspend fun single(
        url: String,
        fileName: String,
        tree: DocumentFile,
        total: Long,
        onProgress: (Progress) -> Unit
    ) {
        val partName = "$fileName.f2l.part"
        val part = tree.findFile(partName) ?: tree.createFile("application/octet-stream", partName)
        ?: error("Cannot create temporary file")

        val existing = part.length().coerceAtLeast(0L)
        val request = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) error("HTTP ${response.code}")
            val start = if (response.code == 206) existing else 0L
            if (response.code != 206 && existing > 0) {
                context.contentResolver.openFileDescriptor(part.uri, "wt")?.use {}
            }
            response.body?.byteStream()?.use { input ->
                val output = context.contentResolver.openOutputStream(part.uri, "wa")
                    ?: error("Cannot open output")
                output.use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = if (response.code == 206) existing else 0L
                    var last = downloaded
                    var lastT = System.nanoTime()
                    while (true) {
                        ensureActive()
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
            if (!part.renameTo(fileName)) error("Cannot finalize file")
        }
    }

    private suspend fun segmented(
        url: String,
        fileName: String,
        tree: DocumentFile,
        total: Long,
        count: Int,
        onProgress: (Progress) -> Unit
    ) = coroutineScope {
        val chunk = (total + count - 1) / count
        val jobs = (0 until count).map { index ->
            launch(Dispatchers.IO) {
                val start = index * chunk
                val end = min(total - 1, start + chunk - 1)
                val partName = "$fileName.f2l.part$index"
                val part = tree.findFile(partName)
                    ?: tree.createFile("application/octet-stream", partName)
                    ?: error("Cannot create segment $index")

                var existing = part.length().coerceAtLeast(0L)
                val expected = end - start + 1
                if (existing > expected) {
                    part.delete()
                    val fresh = tree.createFile("application/octet-stream", partName)
                        ?: error("Cannot recreate segment")
                    existing = 0L
                    downloadRange(url, fresh, start, end, existing, onProgress, tree, fileName, count)
                } else if (existing < expected) {
                    downloadRange(url, part, start, end, existing, onProgress, tree, fileName, count)
                }
            }
        }
        jobs.joinAll()

        val final = tree.findFile(fileName)
        final?.delete()
        val output = tree.createFile("application/octet-stream", fileName)
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
        onProgress: (Progress) -> Unit,
        tree: DocumentFile,
        fileName: String,
        count: Int
    ) {
        val from = start + existing
        val request = Request.Builder().url(url)
            .header("Range", "bytes=$from-$end")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code != 206) error("Server refused range request (HTTP ${response.code})")
            response.body?.byteStream()?.use { input ->
                val out = context.contentResolver.openOutputStream(part.uri, "wa")
                    ?: error("Cannot open segment output")
                out.use { sink ->
                    val buffer = ByteArray(128 * 1024)
                    var got = existing
                    while (got < end - start + 1) {
                        ensureActive()
                        val wanted = min(buffer.size.toLong(), end - start + 1 - got).toInt()
                        val n = input.read(buffer, 0, wanted)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        got += n
                        onProgress(Progress(estimateTotal(tree, fileName, count), -1L, 0))
                    }
                }
            }
        }
    }

    private fun estimateTotal(tree: DocumentFile, fileName: String, count: Int): Long =
        (0 until count).sumOf { tree.findFile("$fileName.f2l.part$it")?.length() ?: 0L }
}
