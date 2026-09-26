package com.f2l.downloader

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

    private fun persist(id: Long, transform: (DownloadItem) -> DownloadItem) {
        val current = repo.load()
        val updated = current.map { if (it.id == id) transform(it) else it }
        repo.save(updated)
    }

    private fun notifIdFor(id: Long): Int =
        ((id xor (id ushr 16)) and 0x7FFFFFFF).toInt() % 90000 + 1000

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
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
                val userAgent = intent.getStringExtra("userAgent")
                val referer = intent.getStringExtra("referer")
                val customHeaders = intent.getStringExtra("customHeaders")

                if (jobs.containsKey(id)) return START_NOT_STICKY

                val notifId = notifIdFor(id)
                startForeground(notifId, buildNotification(id, name, "Starting $name…", null, false, tree))

                val job = scope.launch {
                    var attempt = 0
                    while (true) {
                        try {
                            var lastNotify = 0L
                            engine.download(
                                url = url,
                                fileName = name,
                                treeUri = Uri.parse(tree),
                                connections = connections,
                                userAgent = userAgent,
                                referer = referer,
                                customHeaders = customHeaders
                            ) { p ->
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
                                    if (notificationsOn && canPostNotifications()) {
                                        val percent = if (p.total > 0) (p.downloaded * 100 / p.total).toInt() else 0
                                        val speedText = formatSpeed(p.speed)
                                        val etaText = if (p.total > p.downloaded && p.speed > 0) formatEta((p.total - p.downloaded) / p.speed) else "--:--"
                                        getSystemService(NotificationManager::class.java).notify(
                                            notifId,
                                            buildNotification(
                                                downloadId = id,
                                                title = name,
                                                text = "$percent% • $speedText • ETA $etaText",
                                                progressPercent = percent,
                                                indeterminate = p.total <= 0,
                                                treeUri = tree
                                            )
                                        )
                                    }
                                }
                            }
                            persist(id) { it.copy(status = DownloadItem.Status.COMPLETED, downloadedBytes = it.totalBytes.coerceAtLeast(it.downloadedBytes)) }
                            if (notificationsOn && canPostNotifications()) {
                                getSystemService(NotificationManager::class.java).notify(
                                    notifId,
                                    NotificationCompat.Builder(this@DownloadService, "downloads")
                                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                                        .setContentTitle(name)
                                        .setContentText("Download complete")
                                        .setAutoCancel(true)
                                        .setContentIntent(mainActivityPendingIntent())
                                        .build()
                                )
                            }
                            sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName).putExtra("id", id))
                            break
                        } catch (e: CancellationException) {
                            persist(id) { it.copy(status = DownloadItem.Status.PAUSED) }
                            sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName)
                                .putExtra("id", id).putExtra("paused", true))
                            if (canPostNotifications()) {
                                getSystemService(NotificationManager::class.java).cancel(notifId)
                            }
                            break
                        } catch (e: Exception) {
                            if (attempt < retryAttempts) {
                                attempt++
                                delay(2000L * attempt)
                                continue
                            }
                            val errorMsg = e.message ?: "Download failed"
                            persist(id) { it.copy(status = DownloadItem.Status.FAILED, error = errorMsg) }
                            sendBroadcast(Intent(ACTION_FAILED).setPackage(packageName)
                                .putExtra("id", id).putExtra("error", errorMsg))
                            if (notificationsOn && canPostNotifications()) {
                                getSystemService(NotificationManager::class.java).notify(
                                    notifId,
                                    NotificationCompat.Builder(this@DownloadService, "downloads")
                                        .setSmallIcon(android.R.drawable.stat_notify_error)
                                        .setContentTitle(name)
                                        .setContentText("Failed: $errorMsg")
                                        .setAutoCancel(true)
                                        .setContentIntent(mainActivityPendingIntent())
                                        .build()
                                )
                            }
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
            ACTION_PAUSE -> {
                jobs[id]?.cancel()
                if (canPostNotifications()) {
                    getSystemService(NotificationManager::class.java).cancel(notifIdFor(id))
                }
            }
            ACTION_CANCEL -> {
                jobs[id]?.cancel()
                if (canPostNotifications()) {
                    getSystemService(NotificationManager::class.java).cancel(notifIdFor(id))
                }
                val tree = intent.getStringExtra("tree")
                val name = intent.getStringExtra("name")
                if (tree != null && name != null) deleteDownloadFiles(tree, name)
            }
        }
        return START_NOT_STICKY
    }

    private fun mainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(
        downloadId: Long,
        title: String,
        text: String,
        progressPercent: Int? = null,
        indeterminate: Boolean = false,
        treeUri: String? = null
    ): Notification {
        val pauseIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_PAUSE
            putExtra("id", downloadId)
        }
        val pausePending = PendingIntent.getService(
            this,
            (downloadId % 50000).toInt() * 10 + 1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra("id", downloadId)
            putExtra("tree", treeUri)
            putExtra("name", title)
        }
        val cancelPending = PendingIntent.getService(
            this,
            (downloadId % 50000).toInt() * 10 + 2,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(progressPercent == null || progressPercent < 100)
            .setContentIntent(mainActivityPendingIntent())
            .addAction(android.R.drawable.ic_media_pause, "Pause", pausePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)

        if (progressPercent != null) {
            builder.setProgress(100, progressPercent, indeterminate)
        }

        return builder.build()
    }

    private fun deleteDownloadFiles(treeUri: String, fileName: String) {
        try {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, Uri.parse(treeUri))
            if (tree == null || !tree.canWrite()) {
                android.util.Log.e("F2L", "deleteDownloadFiles: no write access to $treeUri")
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
