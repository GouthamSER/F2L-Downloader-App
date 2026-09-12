package com.f2l.downloader

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLDecoder

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DownloadRepository(app)
    private val _items = MutableStateFlow(repo.load())
    val items = _items.asStateFlow()

    fun add(url: String, folder: Uri, fileName: String? = null, connections: Int = 8, autoStart: Boolean = true) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return
        val item = DownloadItem(
            id = System.currentTimeMillis(),
            url = clean,
            fileName = fileName?.trim().takeUnless { it.isNullOrBlank() } ?: guessName(clean),
            folderUri = folder.toString(),
            connections = connections.coerceIn(1, 16),
            status = if (autoStart) DownloadItem.Status.QUEUED else DownloadItem.Status.PAUSED
        )
        setItems(listOf(item) + _items.value)
        if (autoStart) start(item)
    }

    fun start(item: DownloadItem) {
        update(item.id) { it.copy(status = DownloadItem.Status.DOWNLOADING, error = null) }
        val i = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra("id", item.id)
            putExtra("url", item.url)
            putExtra("name", item.fileName)
            putExtra("tree", item.folderUri)
            putExtra("connections", item.connections)
        }
        ContextCompat.startForegroundService(getApplication(), i)
    }

    fun pause(item: DownloadItem) {
        getApplication<Application>().startService(
            Intent(getApplication(), DownloadService::class.java)
                .setAction(DownloadService.ACTION_PAUSE)
                .putExtra("id", item.id)
        )
        update(item.id) { it.copy(status = DownloadItem.Status.PAUSED) }
    }

    fun delete(item: DownloadItem) {
        getApplication<Application>().startService(
            Intent(getApplication(), DownloadService::class.java)
                .setAction(DownloadService.ACTION_CANCEL)
                .putExtra("id", item.id)
        )
        setItems(_items.value.filterNot { it.id == item.id })
    }

    fun update(id: Long, transform: (DownloadItem) -> DownloadItem) =
        setItems(_items.value.map { if (it.id == id) transform(it) else it })

    fun applyProgress(id: Long, downloaded: Long, total: Long, speed: Long) {
        update(id) { it.copy(
            downloadedBytes = downloaded,
            totalBytes = if (total > 0) total else it.totalBytes,
            speedBytesPerSec = speed,
            etaSeconds = if (total > downloaded && speed > 0) (total - downloaded) / speed else -1
        ) }
    }

    private fun setItems(value: List<DownloadItem>) {
        _items.value = value
        repo.save(value)
    }

    private fun guessName(url: String): String = try {
        val last = java.net.URI(url).path.substringAfterLast('/', "")
        URLDecoder.decode(last, "UTF-8").ifBlank { "download-${System.currentTimeMillis()}.bin" }
    } catch (_: Exception) { "download-${System.currentTimeMillis()}.bin" }
}
