package com.f2l.downloader

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

    companion object {
        const val WAIT_WIFI = "Waiting for Wi-Fi — resumes automatically"
    }

    // Auto-resume downloads that were waiting for Wi-Fi as soon as Wi-Fi comes back
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch {
                _items.value
                    .filter { it.status == DownloadItem.Status.PAUSED && it.error == WAIT_WIFI }
                    .forEach { start(it, skipWifiCheck = true) }
            }
        }
    }

    init {
        runCatching {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                wifiCallback
            )
        }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(wifiCallback) }
        super.onCleared()
    }

    fun add(
        url: String,
        folder: Uri,
        fileName: String? = null,
        connections: Int = 8,
        autoStart: Boolean = true,
        userAgent: String? = null,
        referer: String? = null,
        customHeaders: String? = null,
        forceRedownload: Boolean = false
    ) {
        val clean = url.trim()
        val isMagnet = DownloadEngine.isMagnet(clean)
        if (!isMagnet && !clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            return
        }

        // Prevent duplicate downloads for the exact same URL
        if (!forceRedownload) {
            val existing = _items.value.find { it.url.equals(clean, ignoreCase = true) }
            if (existing != null) {
                when (existing.status) {
                    DownloadItem.Status.DOWNLOADING, DownloadItem.Status.QUEUED -> {
                        // Already active - do not create a duplicate download
                        return
                    }
                    DownloadItem.Status.PAUSED, DownloadItem.Status.FAILED -> {
                        // Resume or retry the existing download instead of creating a duplicate
                        if (autoStart) start(existing)
                        return
                    }
                    DownloadItem.Status.COMPLETED -> {
                        // Check if file still exists on disk
                        val tree = runCatching {
                            androidx.documentfile.provider.DocumentFile.fromTreeUri(getApplication(), Uri.parse(existing.folderUri))
                        }.getOrNull()
                        val file = tree?.findFile(existing.fileName)
                        if (file != null && file.exists() && (existing.totalBytes <= 0 || file.length() == existing.totalBytes)) {
                            // File already completely downloaded! Do not re-download
                            return
                        }
                    }
                    else -> {}
                }
            }
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

        val tree = runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(getApplication(), folder)
        }.getOrNull()
        val uniqueName = if (tree != null) getUniqueFileName(tree, defaultName, clean) else defaultName

        val item = DownloadItem(
            id = id,
            url = clean,
            fileName = uniqueName,
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
                var targetName = resolved ?: current.fileName
                if (tree != null) {
                    targetName = getUniqueFileName(tree, targetName, clean)
                }
                if (targetName != current.fileName) {
                    update(id) { it.copy(fileName = targetName) }
                }
                if (autoStart) start(_items.value.first { it.id == id })
            }
        } else if (autoStart) {
            start(item)
        }
    }

    private fun getUniqueFileName(
        tree: androidx.documentfile.provider.DocumentFile,
        baseName: String,
        url: String
    ): String {
        val existingFile = tree.findFile(baseName) ?: return baseName
        val existingItem = _items.value.find { it.url.equals(url, ignoreCase = true) && it.fileName == baseName }
        if (existingItem != null) return baseName

        val dot = baseName.lastIndexOf('.')
        val namePart = if (dot != -1) baseName.substring(0, dot) else baseName
        val extPart = if (dot != -1) baseName.substring(dot) else ""
        var counter = 1
        while (true) {
            val candidate = "$namePart ($counter)$extPart"
            if (tree.findFile(candidate) == null) return candidate
            counter++
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
        val uniqueUrls = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        uniqueUrls.forEach { clean ->
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

    fun extractUrls(text: String): List<String> {
        val regex = Regex("""(https?://[^\s<>"'{}|\\^`]+|magnet:[^\s<>"'{}|\\^`]+)""", RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }

    fun start(item: DownloadItem, skipWifiCheck: Boolean = false) {
        if (!skipWifiCheck && settings.wifiOnly && !isOnWifi()) {
            update(item.id) { it.copy(status = DownloadItem.Status.PAUSED, error = WAIT_WIFI, speedBytesPerSec = 0) }
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
        try {
            ContextCompat.startForegroundService(getApplication(), i)
        } catch (e: Exception) {
            // Android blocks starting a foreground service while the app is in background
            update(item.id) { it.copy(status = DownloadItem.Status.PAUSED, error = "Tap Resume to continue", speedBytesPerSec = 0) }
        }
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
        if (speed < 0) { // finalizing: joining parts into the final file
            update(id) { it.copy(downloadedBytes = downloaded, totalBytes = if (total > 0) total else it.totalBytes, speedBytesPerSec = -1, etaSeconds = -1) }
            return
        }
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
