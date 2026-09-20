package com.f2l.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TorrentService : Service() {
    private lateinit var repo: DownloadRepository
    private val active = ConcurrentHashMap.newKeySet<Long>()
    private val itemNames = ConcurrentHashMap<Long, String>()

    companion object {
        const val ACTION_START_MAGNET = "TORRENT_START_MAGNET"
        const val ACTION_START_FILE = "TORRENT_START_FILE"
        const val ACTION_PAUSE = "TORRENT_PAUSE"
        const val ACTION_RESUME = "TORRENT_RESUME"
        const val ACTION_REMOVE = "TORRENT_REMOVE"
    }

    override fun onCreate() {
        super.onCreate()
        repo = DownloadRepository(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun persist(id: Long, transform: (DownloadItem) -> DownloadItem) {
        val current = repo.load()
        repo.save(current.map { if (it.id == id) transform(it) else it })
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(title: String, text: String, percent: Int? = null): Notification =
        NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(percent == null || percent < 100)
            .apply {
                if (percent != null && percent in 0..100) {
                    setProgress(100, percent, false)
                } else if (percent == null) {
                    setProgress(100, 0, true)
                }
            }
            .build()

    private fun notify(id: Long, title: String, text: String, percent: Int? = null) {
        getSystemService(NotificationManager::class.java)
            .notify(2000 + id.toInt(), buildNotification(title, text, percent))
    }

    private fun ensureForeground(title: String = "F2L Downloader", text: String = "Connecting to BitTorrent swarm…", percent: Int? = null) {
        val notif = buildNotification(title, text, percent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, 1002, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1002, notif)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 B/s"
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var v = bytesPerSec.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    private fun onFinishedOrError(id: Long) {
        active.remove(id)
        itemNames.remove(id)
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra("id", 0L) ?: return START_NOT_STICKY
        val action = intent.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_PAUSE -> {
                TorrentEngine.pause(id)
                active.remove(id)
                persist(id) { it.copy(status = DownloadItem.Status.PAUSED, speedBytesPerSec = 0) }
                sendBroadcast(Intent(DownloadService.ACTION_PROGRESS).apply {
                    setPackage(packageName)
                    putExtra("id", id)
                    putExtra("paused", true)
                    putExtra("isTorrent", true)
                })
                getSystemService(NotificationManager::class.java).cancel(2000 + id.toInt())
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_REMOVE -> {
                TorrentEngine.remove(id)
                active.remove(id)
                itemNames.remove(id)
                runCatching { File(filesDir, "torrents/$id.torrent").delete() }
                getSystemService(NotificationManager::class.java).cancel(2000 + id.toInt())
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        // For ACTION_START_MAGNET, ACTION_START_FILE, and ACTION_RESUME:
        active.add(id)
        val initialItem = repo.load().find { it.id == id }
        val initialName = initialItem?.fileName ?: "Torrent Download"
        itemNames[id] = initialName

        // ALWAYS call startForeground immediately to avoid ForegroundServiceDidNotStartInTimeException
        ensureForeground("F2L Downloader", "Connecting to BitTorrent swarm: $initialName")

        val onNameKnown: (String) -> Unit = { name ->
            itemNames[id] = name
            persist(id) { it.copy(fileName = name) }
            sendBroadcast(Intent(DownloadService.ACTION_PROGRESS).apply {
                setPackage(packageName)
                putExtra("id", id)
                putExtra("name", name)
                putExtra("isTorrent", true)
            })
        }

        var lastBroadcast = 0L
        val onProgress: (TorrentEngine.Progress) -> Unit = { p ->
            val resolvedName = p.resolvedName ?: itemNames[id] ?: "Torrent"
            itemNames[id] = resolvedName

            val now = System.currentTimeMillis()
            if (now - lastBroadcast > 900) {
                lastBroadcast = now
                persist(id) {
                    it.copy(
                        fileName = resolvedName,
                        status = DownloadItem.Status.DOWNLOADING,
                        downloadedBytes = p.downloadedBytes,
                        totalBytes = if (p.totalBytes > 0) p.totalBytes else it.totalBytes,
                        speedBytesPerSec = p.speedBytesPerSec,
                        seeders = p.seeders,
                        peers = p.peers,
                        etaSeconds = if (p.totalBytes > p.downloadedBytes && p.speedBytesPerSec > 0)
                            (p.totalBytes - p.downloadedBytes) / p.speedBytesPerSec else -1L
                    )
                }

                val notifText = if (p.hasMetadata && p.totalBytes > 0) {
                    "${p.percent}% • ${formatSpeed(p.speedBytesPerSec)} • ${p.seeders}S / ${p.peers}P"
                } else {
                    "Finding peers… (${p.seeders} seeds, ${p.peers} peers)"
                }
                notify(id, resolvedName, notifText, if (p.hasMetadata) p.percent else null)
            }

            sendBroadcast(Intent(DownloadService.ACTION_PROGRESS).apply {
                setPackage(packageName)
                putExtra("id", id)
                putExtra("downloaded", p.downloadedBytes)
                putExtra("total", p.totalBytes)
                putExtra("speed", p.speedBytesPerSec)
                putExtra("seeders", p.seeders)
                putExtra("peers", p.peers)
                putExtra("percent", p.percent)
                putExtra("isTorrent", true)
                if (p.resolvedName != null) putExtra("name", p.resolvedName)
            })
        }

        val onDone: () -> Unit = {
            val finalName = itemNames[id] ?: "Torrent"
            persist(id) {
                it.copy(
                    fileName = finalName,
                    status = DownloadItem.Status.COMPLETED,
                    downloadedBytes = if (it.totalBytes > 0) it.totalBytes else it.downloadedBytes,
                    speedBytesPerSec = 0,
                    etaSeconds = -1
                )
            }
            notify(id, finalName, "Download complete", 100)
            sendBroadcast(Intent(DownloadService.ACTION_FINISHED).apply {
                setPackage(packageName)
                putExtra("id", id)
                putExtra("isTorrent", true)
            })
            onFinishedOrError(id)
        }

        val onError: (String) -> Unit = { message ->
            persist(id) { it.copy(status = DownloadItem.Status.FAILED, error = message, speedBytesPerSec = 0) }
            sendBroadcast(Intent(DownloadService.ACTION_FAILED).apply {
                setPackage(packageName)
                putExtra("id", id)
                putExtra("error", message)
                putExtra("isTorrent", true)
            })
            onFinishedOrError(id)
        }

        when (action) {
            ACTION_START_MAGNET -> {
                val magnet = intent.getStringExtra("magnet") ?: return START_NOT_STICKY
                TorrentEngine.startMagnet(this, id, magnet, onNameKnown, onProgress, onDone, onError)
            }
            ACTION_START_FILE -> {
                val bytes = intent.getByteArrayExtra("torrentBytes")
                    ?: TorrentEngine.getSavedTorrentFile(this, id)?.readBytes()
                    ?: return START_NOT_STICKY
                TorrentEngine.startTorrentFile(this, id, bytes, onNameKnown, onProgress, onDone, onError)
            }
            ACTION_RESUME -> {
                val resumed = TorrentEngine.resume(id, onNameKnown, onProgress, onDone, onError)
                if (!resumed) {
                    val item = repo.load().find { it.id == id }
                    if (item != null) {
                        if (item.url.startsWith("magnet:")) {
                            TorrentEngine.startMagnet(this, id, item.url, onNameKnown, onProgress, onDone, onError)
                        } else {
                            val savedFile = TorrentEngine.getSavedTorrentFile(this, id)
                            if (savedFile != null) {
                                TorrentEngine.startTorrentFile(this, id, savedFile.readBytes(), onNameKnown, onProgress, onDone, onError)
                            } else {
                                onError("Torrent file not found")
                            }
                        }
                    } else {
                        onError("Download item not found")
                    }
                }
                persist(id) { it.copy(status = DownloadItem.Status.DOWNLOADING, error = null) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null
}
