package com.f2l.downloader

import android.app.*
import android.content.Intent
import bt.runtime.BtClient
import java.util.concurrent.ConcurrentHashMap

class TorrentService : Service() {
    private lateinit var repo: DownloadRepository
    private val clients = ConcurrentHashMap<Long, BtClient>()

    companion object {
        const val ACTION_START_MAGNET = "TORRENT_START_MAGNET"
        const val ACTION_START_FILE = "TORRENT_START_FILE"
        const val ACTION_CANCEL = "TORRENT_CANCEL"
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

    private fun notify(id: Long, title: String, text: String, percent: Int?) {
        getSystemService(NotificationManager::class.java)
            .notify(2000 + id.toInt(), buildNotification(title, text, percent))
    }

    private fun onFinishedOrError(id: Long) {
        clients.remove(id)
        if (clients.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra("id", 0L) ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_START_MAGNET, ACTION_START_FILE -> {
                startForeground(1002, buildNotification("F2L Downloader", "Fetching torrent metadata…"))

                val onFetched: (bt.metainfo.Torrent) -> Unit = { torrent ->
                    persist(id) { it.copy(fileName = torrent.name ?: it.fileName) }
                }
                val onProgress: (TorrentEngine.TorrentProgress) -> Unit = { p ->
                    val status = if (p.done) DownloadItem.Status.COMPLETED else DownloadItem.Status.DOWNLOADING
                    persist(id) { it.copy(status = status, downloadedBytes = p.piecesComplete.toLong(), totalBytes = p.piecesTotal.toLong(), peers = p.peers) }
                    val pct = if (p.piecesTotal > 0) p.piecesComplete * 100 / p.piecesTotal else 0
                    notify(id, "Torrent", "$pct% • ${p.peers} peers", pct)
                    if (p.done) onFinishedOrError(id)
                }
                val onError: (Throwable) -> Unit = { e ->
                    persist(id) { it.copy(status = DownloadItem.Status.FAILED, error = e.message ?: "Torrent failed") }
                    onFinishedOrError(id)
                }

                val client = if (intent.action == ACTION_START_MAGNET) {
                    val magnet = intent.getStringExtra("magnet") ?: return START_NOT_STICKY
                    TorrentEngine.startMagnet(this, magnet, onFetched, onProgress, onError)
                } else {
                    val bytes = intent.getByteArrayExtra("torrentBytes") ?: return START_NOT_STICKY
                    TorrentEngine.startTorrentFile(this, bytes, onFetched, onProgress, onError)
                }
                clients[id] = client
            }
            ACTION_CANCEL -> {
                clients.remove(id)?.let { runCatching { it.stop() } }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        clients.values.forEach { runCatching { it.stop() } }
        super.onDestroy()
    }
}
