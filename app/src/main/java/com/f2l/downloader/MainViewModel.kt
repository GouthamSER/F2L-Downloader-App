package com.f2l.downloader

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DownloadRepository(app)
    private val engine = DownloadEngine(app)
    val settings = SettingsRepository(app)
    private val _items = MutableStateFlow(repo.load())
    val items = _items.asStateFlow()
    private val ariaPollers = mutableMapOf<Long, Job>()

    private fun aria() = Aria2Client(settings.aria2Host, settings.aria2Port, settings.aria2Secret.ifBlank { null })

    /** Direct HTTP link, magnet link, or (when aria2 is on) any link — routed to the right engine. */
    fun add(url: String, folder: Uri, fileName: String? = null, connections: Int = 8, autoStart: Boolean = true) {
        val clean = url.trim()
        val isMagnet = clean.startsWith("magnet:")
        if (!isMagnet && !clean.startsWith("http://") && !clean.startsWith("https://")) return

        val useAria = isMagnet || settings.aria2Enabled
        val requestedName = fileName?.trim().takeUnless { it.isNullOrBlank() }
        val id = System.currentTimeMillis()

        val item = DownloadItem(
            id = id,
            url = clean,
            fileName = requestedName ?: (if (isMagnet) "Magnet: fetching metadata…" else guessName(clean)),
            folderUri = folder.toString(),
            connections = connections.coerceIn(1, 16),
            engine = if (useAria) DownloadItem.Engine.ARIA2 else DownloadItem.Engine.HTTP,
            status = if (autoStart) DownloadItem.Status.QUEUED else DownloadItem.Status.PAUSED
        )
        setItems(listOf(item) + _items.value)

        if (!autoStart) return

        if (useAria) {
            startAria(item)
        } else if (requestedName == null) {
            viewModelScope.launch {
                val resolved = runCatching { engine.resolveFileName(clean) }.getOrNull()
                val current = _items.value.find { it.id == id } ?: return@launch
                if (!resolved.isNullOrBlank() && resolved != current.fileName) update(id) { it.copy(fileName = resolved) }
                start(_items.value.first { it.id == id })
            }
        } else {
            start(item)
        }
    }

    /** Add a picked .torrent file. */
    fun addTorrent(bytes: ByteArray, displayName: String) {
        val id = System.currentTimeMillis()
        val item = DownloadItem(
            id = id, url = "torrent:$displayName", fileName = displayName,
            folderUri = settings.aria2SaveDir, engine = DownloadItem.Engine.ARIA2,
            status = DownloadItem.Status.QUEUED
        )
        setItems(listOf(item) + _items.value)
        viewModelScope.launch {
            try {
                val gid = aria().addTorrent(bytes, settings.aria2SaveDir)
                update(id) { it.copy(gid = gid, status = DownloadItem.Status.DOWNLOADING) }
                pollAria(id, gid)
            } catch (e: Exception) {
                update(id) { it.copy(status = DownloadItem.Status.FAILED, error = e.message ?: "aria2 unreachable") }
            }
        }
    }

    private fun startAria(item: DownloadItem) {
        update(item.id) { it.copy(status = DownloadItem.Status.DOWNLOADING, error = null) }
        viewModelScope.launch {
            try {
                val gid = if (item.gid != null) {
                    aria().unpause(item.gid); item.gid
                } else {
                    aria().addUri(item.url, settings.aria2SaveDir, item.fileName.takeUnless { it.contains("fetching metadata") })
                }
                update(item.id) { it.copy(gid = gid) }
                pollAria(item.id, gid)
            } catch (e: Exception) {
                update(item.id) { it.copy(status = DownloadItem.Status.FAILED, error = e.message ?: "aria2 unreachable — check host/port in Settings") }
            }
        }
    }

    private fun pollAria(id: Long, gid: String) {
        ariaPollers[id]?.cancel()
        ariaPollers[id] = viewModelScope.launch {
            while (true) {
                val current = _items.value.find { it.id == id } ?: break
                if (current.status !in listOf(DownloadItem.Status.DOWNLOADING, DownloadItem.Status.QUEUED)) break
                try {
                    val s = aria().tellStatus(gid)
                    when (s.state) {
                        "complete" -> { update(id) { it.copy(status = DownloadItem.Status.COMPLETED, downloadedBytes = s.total, totalBytes = s.total, speedBytesPerSec = 0) }; break }
                        "error" -> { update(id) { it.copy(status = DownloadItem.Status.FAILED, error = s.error ?: "aria2 error") }; break }
                        "paused" -> update(id) { it.copy(status = DownloadItem.Status.PAUSED) }
                        "removed" -> break
                        else -> update(id) {
                            it.copy(
                                downloadedBytes = s.completed, totalBytes = if (s.total > 0) s.total else it.totalBytes,
                                speedBytesPerSec = s.speed, status = DownloadItem.Status.DOWNLOADING,
                                fileName = if (it.fileName.contains("fetching metadata") && s.total > 0) it.fileName else it.fileName
                            )
                        }
                    }
                } catch (_: Exception) { /* transient RPC hiccup, retry next tick */ }
                delay(1500)
            }
            ariaPollers.remove(id)
        }
    }

    fun start(item: DownloadItem) {
        if (item.engine == DownloadItem.Engine.ARIA2) { startAria(item); return }
        if (settings.wifiOnly && !isOnWifi()) {
            update(item.id) { it.copy(status = DownloadItem.Status.FAILED, error = "Waiting for Wi-Fi") }
            return
        }
        update(item.id) { it.copy(status = DownloadItem.Status.DOWNLOADING, error = null) }
        val i = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra("id", item.id)
            putExtra("url", item.url)
            putExtra("name", item.fileName)
            putExtra("tree", item.folderUri)
            putExtra("connections", item.connections)
            putExtra("retryAttempts", settings.retryAttempts)
            putExtra("notifications", settings.notifications)
        }
        ContextCompat.startForegroundService(getApplication(), i)
    }

    fun pause(item: DownloadItem) {
        if (item.engine == DownloadItem.Engine.ARIA2) {
            item.gid?.let { gid -> viewModelScope.launch { runCatching { aria().pause(gid) } } }
            update(item.id) { it.copy(status = DownloadItem.Status.PAUSED) }
            return
        }
        getApplication<Application>().startService(
            Intent(getApplication(), DownloadService::class.java)
                .setAction(DownloadService.ACTION_PAUSE)
                .putExtra("id", item.id)
        )
        update(item.id) { it.copy(status = DownloadItem.Status.PAUSED) }
    }

    fun delete(item: DownloadItem) {
        ariaPollers.remove(item.id)?.cancel()
        if (item.engine == DownloadItem.Engine.ARIA2) {
            item.gid?.let { gid -> viewModelScope.launch { runCatching { aria().remove(gid) } } }
        } else {
            getApplication<Application>().startService(
                Intent(getApplication(), DownloadService::class.java)
                    .setAction(DownloadService.ACTION_CANCEL)
                    .putExtra("id", item.id)
            )
        }
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

    private fun isOnWifi(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
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
