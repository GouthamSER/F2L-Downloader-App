package com.f2l.downloader

import android.app.*
import android.content.*
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: DownloadEngine
    private lateinit var repo: DownloadRepository

    companion object {
        private val jobs = ConcurrentHashMap<Long, Job>()
        const val ACTION_START = "START"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_CANCEL = "CANCEL"
        const val ACTION_PROGRESS = "com.f2l.DOWNLOAD_PROGRESS"
        const val ACTION_FINISHED = "com.f2l.DOWNLOAD_FINISHED"
        const val ACTION_FAILED = "com.f2l.DOWNLOAD_FAILED"
    }

    override fun onCreate() {
        super.onCreate()
        engine = DownloadEngine(this)
        repo = DownloadRepository(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /**
     * Writes straight to the same persisted store the app reads on launch. The UI is normally kept
     * in sync live via broadcasts, but if Android kills the whole app process while it's backgrounded
     * (common under memory pressure), broadcasts go nowhere — this makes sure the real outcome
     * (completed/failed/paused/progress) is on disk regardless, so reopening the app shows the truth
     * instead of a stale "still downloading" state.
     */
    private fun persist(id: Long, transform: (DownloadItem) -> DownloadItem) {
        val current = repo.load()
        val updated = current.map { if (it.id == id) transform(it) else it }
        repo.save(updated)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra("id", 0L) ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_START -> {
                val url = intent.getStringExtra("url") ?: return START_NOT_STICKY
                val name = intent.getStringExtra("name") ?: "download.bin"
                val tree = intent.getStringExtra("tree") ?: return START_NOT_STICKY
                val connections = intent.getIntExtra("connections", 8)
                val retryAttempts = intent.getIntExtra("retryAttempts", 3).coerceIn(0, 10)
                val notificationsOn = intent.getBooleanExtra("notifications", true)
                if (jobs.containsKey(id)) return START_NOT_STICKY

                startForeground(1001, notification("F2L Downloader", "Starting $name…"))
                val job = scope.launch {
                    var attempt = 0
                    while (true) {
                        try {
                            var lastNotify = 0L
                            engine.download(url, name, Uri.parse(tree), connections) { p ->
                                sendBroadcast(Intent(ACTION_PROGRESS).apply {
                                    setPackage(packageName)
                                    putExtra("id", id)
                                    putExtra("downloaded", p.downloaded)
                                    putExtra("total", p.total)
                                    putExtra("speed", p.speed)
                                })
                                val now = System.currentTimeMillis()
                                if (now - lastNotify > 800) {
                                    lastNotify = now
                                    persist(id) { it.copy(
                                        status = DownloadItem.Status.DOWNLOADING,
                                        downloadedBytes = p.downloaded,
                                        totalBytes = if (p.total > 0) p.total else it.totalBytes
                                    ) }
                                    if (notificationsOn) {
                                        val percent = if (p.total > 0) (p.downloaded * 100 / p.total).toInt() else 0
                                        val speedText = formatSpeed(p.speed)
                                        val etaText = if (p.total > p.downloaded && p.speed > 0) formatEta((p.total - p.downloaded) / p.speed) else "--:--"
                                        getSystemService(NotificationManager::class.java).notify(
                                            1001,
                                            notification(
                                                name,
                                                "$percent% • $speedText • ETA $etaText",
                                                progressPercent = percent,
                                                indeterminate = p.total <= 0
                                            )
                                        )
                                    }
                                }
                            }
                            persist(id) { it.copy(status = DownloadItem.Status.COMPLETED, downloadedBytes = it.totalBytes.coerceAtLeast(it.downloadedBytes)) }
                            if (notificationsOn) {
                                getSystemService(NotificationManager::class.java)
                                    .notify(1001, notification(name, "Download complete", progressPercent = 100))
                            }
                            sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName).putExtra("id", id))
                            break
                        } catch (e: CancellationException) {
                            persist(id) { it.copy(status = DownloadItem.Status.PAUSED) }
                            sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName)
                                .putExtra("id", id).putExtra("paused", true))
                            break
                        } catch (e: Exception) {
                            if (attempt < retryAttempts) {
                                attempt++
                                delay(2000L * attempt)
                                continue
                            }
                            persist(id) { it.copy(status = DownloadItem.Status.FAILED, error = e.message ?: "Download failed") }
                            sendBroadcast(Intent(ACTION_FAILED).setPackage(packageName)
                                .putExtra("id", id).putExtra("error", e.message ?: "Download failed"))
                            break
                        }
                    }
                    jobs.remove(id)
                    if (jobs.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                jobs[id] = job
            }
            ACTION_PAUSE -> jobs[id]?.cancel()
            ACTION_CANCEL -> {
                jobs[id]?.cancel()
                val tree = intent.getStringExtra("tree")
                val name = intent.getStringExtra("name")
                if (tree != null && name != null) deleteDownloadFiles(tree, name)
            }
        }
        return START_NOT_STICKY
    }

    /** Deletes the finished file plus any leftover .f2l.part segment files for this download. */
    private fun deleteDownloadFiles(treeUri: String, fileName: String) {
        try {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, Uri.parse(treeUri))
            if (tree == null || !tree.canWrite()) {
                android.util.Log.e("F2L", "deleteDownloadFiles: no write access to $treeUri (permission not persisted?)")
                return
            }
            tree.findFile(fileName)?.delete()
            tree.listFiles().forEach { f ->
                val n = f.name ?: return@forEach
                if (n == "$fileName.f2l.part" || n.startsWith("$fileName.f2l.part")) f.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e("F2L", "deleteDownloadFiles failed", e)
        }
    }

    private fun notification(title: String, text: String, progressPercent: Int? = null, indeterminate: Boolean = false): Notification =
        NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(progressPercent == null || progressPercent < 100)
            .apply {
                if (progressPercent != null) setProgress(100, progressPercent, indeterminate)
            }
            .build()

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 B/s"
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var v = bytesPerSec.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }

    private fun formatEta(totalSeconds: Long): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
