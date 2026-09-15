package com.f2l.downloader

import android.app.*
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

class TorrentService : Service() {
    private lateinit var repo: DownloadRepository
    private val active = ConcurrentHashMap.newKeySet<Long>()

    companion object {
        const val ACTION_START_MAGNET = "TORRENT_START_MAGNET"
        const val ACTION_START_FILE = "TORRENT_START_FILE"
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
        androidx.core.app.NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(percent == null || percent < 100)
            .apply { if (percent != null) setProgress(100, percent, false) }
            .build()

    private fun notify(id: Long, title: String, text: String, percent: Int? = null) {
        getSystemService(NotificationManager::class.java)
            .notify(2000 + id.toInt(), buildNotification(title, text, percent))
    }

    private fun onFinishedOrError(id: Long) {
        active.remove(id)
        if (active.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra("id", 0L) ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_START_MAGNET, ACTION_START_FILE -> {
                active.add(id)
                startForeground(1002, buildNotification("F2L Downloader", "Fetching torrent metadata…"))

                val onNameKnown: (String) -> Unit = { name -> persist(id) { it.copy(fileName = name) } }
                val onProgress: (TorrentEngine.Progress) -> Unit = { p ->
                    persist(id) {
                        it.copy(
                            status = DownloadItem.Status.DOWNLOADING,
                            downloadedBytes = p.percent.toLong(), totalBytes = 100L,
                            speedBytesPerSec = p.speedBytesPerSec.toLong(),
                            seeders = p.seeders, peers = p.peers
                        )
                    }
                    notify(id, "Torrent", "${p.percent}% • ${p.seeders} seeders • ${p.peers} peers", p.percent)
                }
                val onDone: () -> Unit = {
                    persist(id) { it.copy(status = DownloadItem.Status.COMPLETED, downloadedBytes = 100L, totalBytes = 100L, speedBytesPerSec = 0) }
                    notify(id, "Torrent", "Download complete", 100)
                    onFinishedOrError(id)
                }
                val onError: (String) -> Unit = { message ->
                    persist(id) { it.copy(status = DownloadItem.Status.FAILED, error = message) }
                    onFinishedOrError(id)
                }

                if (intent.action == ACTION_START_MAGNET) {
                    val magnet = intent.getStringExtra("magnet") ?: return START_NOT_STICKY
                    TorrentEngine.startMagnet(this, id, magnet, onNameKnown, onProgress, onDone, onError)
                } else {
                    val bytes = intent.getByteArrayExtra("torrentBytes") ?: return START_NOT_STICKY
                    TorrentEngine.startTorrentFile(this, id, bytes, onNameKnown, onProgress, onDone, onError)
                }
            }
            ACTION_REMOVE -> {
                TorrentEngine.remove(id)
                active.remove(id)
                if (active.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null
}
