package com.f2l.downloader

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    fun add(
        url: String,
        folder: Uri,
        fileName: String? = null,
        connections: Int = 8,
        autoStart: Boolean = true,
        userAgent: String? = null,
        referer: String? = null,
        customHeaders: String? = null
    ) {
        val clean = url.trim()
        val isMagnet = DownloadEngine.isMagnet(clean)
        if (!isMagnet && !clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            return
        }

        val requestedName = fileName?.trim().takeUnless { it.isNullOrBlank() }
        val id = System.currentTimeMillis()
        val magnetInfo = if (isMagnet) DownloadEngine.parseMagnetUri(clean) else null

        val defaultName = if (isMagnet) {
            val name = requestedName ?: magnetInfo?.displayName ?: "torrent-${magnetInfo?.infoHash?.take(8) ?: id}"
            if (name.endsWith(".torrent", ignoreCase = true) || name.endsWith(".magnet", ignoreCase = true)) name else "$name.torrent"
        } else {
            requestedName ?: guessName(clean)
        }

        val item = DownloadItem(
            id = id,
            url = clean,
            fileName = defaultName,
            folderUri = folder.toString(),
            connections = if (isMagnet) 1 else connections.coerceIn(1, 16),
            status = if (autoStart) DownloadItem.Status.QUEUED else DownloadItem.Status.PAUSED,
            userAgent = userAgent?.trim()?.takeIf { it.isNotEmpty() },
            referer = referer?.trim()?.takeIf { it.isNotEmpty() },
            customHeaders = customHeaders?.trim()?.takeIf { it.isNotEmpty() },
            isTorrent = isMagnet,
            magnetHash = magnetInfo?.infoHash,
            speedHistory = emptyList()
        )
        setItems(listOf(item) + _items.value)

        if (requestedName == null && !isMagnet) {
            viewModelScope.launch {
                val resolved = runCatching {
                    engine.resolveFileName(clean, item.userAgent, item.referer, item.customHeaders)
                }.getOrNull()
                val current = _items.value.find { it.id == id } ?: return@launch
                if (!resolved.isNullOrBlank() && resolved != current.fileName) {
                    update(id) { it.copy(fileName = resolved) }
                }
                if (autoStart) start(_items.value.first { it.id == id })
            }
        } else if (autoStart) {
            start(item)
        }
    }

    fun addBatch(
        urls: List<String>,
        folder: Uri,
        connections: Int = 8,
        autoStart: Boolean = true,
        userAgent: String? = null,
        referer: String? = null,
        customHeaders: String? = null
    ) {
        urls.forEachIndexed { index, rawUrl ->
            val clean = rawUrl.trim()
            if (clean.isNotBlank()) {
                add(
                    url = clean,
                    folder = folder,
                    fileName = null,
                    connections = connections,
                    autoStart = autoStart,
                    userAgent = userAgent,
                    referer = referer,
                    customHeaders = customHeaders
                )
            }
        }
    }

    fun extractUrls(text: String): List<String> {
        val regex = Regex("""(https?://[^\s<>"'{}|\\^`]+|magnet:\?[^\s<>"'{}|\\^`]+)""", RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }

    fun start(item: DownloadItem) {
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
            putExtra("userAgent", item.userAgent)
            putExtra("referer", item.referer)
            putExtra("customHeaders", item.customHeaders)
        }
        ContextCompat.startForegroundService(getApplication(), i)
    }

    fun pause(item: DownloadItem) {
        getApplication<Application>().startService(
            Intent(getApplication(), DownloadService::class.java)
                .setAction(DownloadService.ACTION_PAUSE)
                .putExtra("id", item.id)
        )
        update(item.id) { it.copy(status = DownloadItem.Status.PAUSED, speedBytesPerSec = 0) }
    }

    fun delete(item: DownloadItem) {
        getApplication<Application>().startService(
            Intent(getApplication(), DownloadService::class.java)
                .setAction(DownloadService.ACTION_CANCEL)
                .putExtra("id", item.id)
                .putExtra("tree", item.folderUri)
                .putExtra("name", item.fileName)
        )
        setItems(_items.value.filterNot { it.id == item.id })
    }

    fun update(id: Long, transform: (DownloadItem) -> DownloadItem) =
        setItems(_items.value.map { if (it.id == id) transform(it) else it })

    fun applyProgress(
        id: Long,
        downloaded: Long,
        total: Long,
        speed: Long,
        resolvedName: String? = null
    ) {
        update(id) {
            val updatedHistory = (it.speedHistory + speed).takeLast(30)
            it.copy(
                fileName = resolvedName ?: it.fileName,
                downloadedBytes = downloaded,
                totalBytes = if (total > 0) total else it.totalBytes,
                speedBytesPerSec = speed,
                speedHistory = updatedHistory,
                etaSeconds = if (total > downloaded && speed > 0) (total - downloaded) / speed else -1
            )
        }
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
