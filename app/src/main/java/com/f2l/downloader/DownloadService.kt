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

        /** True while this process really has a live download thread for [id] (false after a process kill). */
        fun isRunning(id: Long): Boolean = jobs[id]?.isActive == true
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
        repo.update(id, transform)
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

    private data class Params(
        val id: Long,
        val url: String,
        val name: String,
        val tree: String,
        val connections: Int,
        val retryAttempts: Int,
        val notificationsOn: Boolean,
        val userAgent: String?,
        val referer: String?,
        val customHeaders: String?
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Null intent = Android restarted the service after the process was killed (START_STICKY):
        // pick up every download that was still marked DOWNLOADING and continue it from its part files.
        if (intent == null) {
            resumeInterrupted()
            return START_STICKY
        }
        val id = intent.getLongExtra("id", 0L)
        when (intent.action) {
            ACTION_START -> {
                val url = intent.getStringExtra("url") ?: return START_NOT_STICKY
                val tree = intent.getStringExtra("tree") ?: return START_NOT_STICKY
                val p = Params(
                    id = id,
                    url = url,
                    name = intent.getStringExtra("name") ?: "download.bin",
                    tree = tree,
                    connections = intent.getIntExtra("connections", 8),
                    retryAttempts = intent.getIntExtra("retryAttempts", 3).coerceIn(0, 10),
                    notificationsOn = intent.getBooleanExtra("notifications", true),
                    userAgent = intent.getStringExtra("userAgent"),
                    referer = intent.getStringExtra("referer"),
                    customHeaders = intent.getStringExtra("customHeaders")
                )
                val running = jobs[id]
                if (running != null && running.isActive && !running.isCancelled) return START_STICKY
                promoteToForeground(p)
                launchJob(p)
            }
            ACTION_PAUSE -> {
                jobs[id]?.cancel()
                if (canPostNotifications()) {
                    getSystemService(NotificationManager::class.java).cancel(notifIdFor(id))
                }
                stopIfIdle()
            }
            ACTION_CANCEL -> {
                val old = jobs[id]
                old?.cancel()
                if (canPostNotifications()) {
                    getSystemService(NotificationManager::class.java).cancel(notifIdFor(id))
                }
                val tree = intent.getStringExtra("tree")
                val name = intent.getStringExtra("name")
                if (tree != null && name != null) {
                    // wait until the download thread really stopped, else it re-creates the part file after we delete it
                    scope.launch {
                        old?.join()
                        deleteDownloadFiles(tree, name)
                        stopIfIdle()
                    }
                } else stopIfIdle()
            }
        }
        return START_STICKY
    }

    private fun stopIfIdle() {
        if (jobs.values.none { it.isActive }) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun promoteToForeground(p: Params) {
        try {
            startForeground(notifIdFor(p.id), buildNotification(p.id, p.name, "Starting ${p.name}…", null, false, p.tree))
        } catch (e: Exception) {
            android.util.Log.w("F2L", "startForeground failed", e)
        }
    }

    private fun resumeInterrupted() {
        val settings = SettingsRepository(this)
        val pending = repo.load().filter {
            (it.status == DownloadItem.Status.DOWNLOADING || it.status == DownloadItem.Status.QUEUED) &&
                !(jobs[it.id]?.isActive ?: false)
        }
        if (pending.isEmpty() || !settings.autoResume) {
            // nothing to continue: mark stale items so UI does not show a fake "downloading"
            if (pending.isNotEmpty()) pending.forEach { persist(it.id) { d -> d.copy(status = DownloadItem.Status.PAUSED) } }
            stopSelf()
            return
        }
        pending.forEach { d ->
            val p = Params(d.id, d.url, d.fileName, d.folderUri, d.connections, settings.retryAttempts,
                settings.notifications, d.userAgent, d.referer, d.customHeaders)
            promoteToForeground(p)
            launchJob(p)
        }
    }

    private fun launchJob(p: Params) {
        val previous = jobs[p.id]
        val job = scope.launch {
            val self = coroutineContext[Job]
            // Pause then quick Resume: wait for the cancelled run to finish so two runs never write the same part files
            previous?.cancelAndJoin()
            try {
                runDownload(p)
            } finally {
                // remove only OUR entry (a newer run may already be registered under the same id)
                if (self != null) jobs.remove(p.id, self)
                if (jobs.values.none { it.isActive && it !== self }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        jobs[p.id] = job
    }

    private suspend fun runDownload(p: Params) {
        val id = p.id
        val name = p.name
        val notifId = notifIdFor(id)
        val notificationsOn = p.notificationsOn
        val tree = p.tree
        var attempt = 0
        var bestBytes = 0L
        var pendingDelay = 0L

        fun paused() {
            persist(id) { it.copy(status = DownloadItem.Status.PAUSED, speedBytesPerSec = 0) }
            sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName)
                .putExtra("id", id).putExtra("paused", true))
            if (canPostNotifications()) {
                getSystemService(NotificationManager::class.java).cancel(notifId)
            }
        }

        while (true) {
            try {
                if (pendingDelay > 0) { delay(pendingDelay); pendingDelay = 0 }
                var lastNotify = 0L
                engine.download(
                    url = p.url,
                    fileName = name,
                    treeUri = Uri.parse(tree),
                    connections = p.connections,
                    userAgent = p.userAgent,
                    referer = p.referer,
                    customHeaders = p.customHeaders
                ) { prog ->
                    sendBroadcast(Intent(ACTION_PROGRESS).apply {
                        setPackage(packageName)
                        putExtra("id", id)
                        putExtra("downloaded", prog.downloaded)
                        putExtra("total", prog.total)
                        putExtra("speed", prog.speed)
                    })
                    // real progress made -> give the retry budget back (long downloads survive many network blips)
                    if (prog.downloaded > bestBytes + 2_000_000L) { bestBytes = prog.downloaded; attempt = 0 }
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 800) {
                        lastNotify = now
                        persist(id) { it.copy(
                            status = DownloadItem.Status.DOWNLOADING,
                            downloadedBytes = prog.downloaded,
                            totalBytes = if (prog.total > 0) prog.total else it.totalBytes
                        ) }
                        if (notificationsOn && canPostNotifications()) {
                            val percent = if (prog.total > 0) (prog.downloaded * 100 / prog.total).toInt() else 0
                            val speedText = formatSpeed(prog.speed)
                            val etaText = if (prog.total > prog.downloaded && prog.speed > 0) formatEta((prog.total - prog.downloaded) / prog.speed) else "--:--"
                            getSystemService(NotificationManager::class.java).notify(
                                notifId,
                                buildNotification(
                                    downloadId = id,
                                    title = name,
                                    text = if (prog.speed < 0) "Finishing file…" else "$percent% • $speedText • ETA $etaText",
                                    progressPercent = percent,
                                    indeterminate = prog.total <= 0,
                                    treeUri = tree
                                )
                            )
                        }
                    }
                }
                persist(id) { it.copy(status = DownloadItem.Status.COMPLETED, speedBytesPerSec = 0, downloadedBytes = it.totalBytes.coerceAtLeast(it.downloadedBytes)) }
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
                return
            } catch (e: CancellationException) {
                paused()
                return
            } catch (e: Exception) {
                // pause closes the socket -> IOException; that is a pause, not a network error
                if (!currentCoroutineContext().isActive) { paused(); return }
                if (attempt < p.retryAttempts) {
                    attempt++
                    pendingDelay = 2000L * attempt
                    continue
                }
                val errorMsg = ErrorMessages.friendly(e)
                persist(id) { it.copy(status = DownloadItem.Status.FAILED, error = errorMsg, speedBytesPerSec = 0) }
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
                return
            }
        }
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
