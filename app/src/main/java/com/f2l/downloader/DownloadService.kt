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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
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

                startForeground(1001, notification("Downloading $name"))
                val job = scope.launch {
                    var attempt = 0
                    while (true) {
                        try {
                            engine.download(url, name, Uri.parse(tree), connections) { p ->
                                sendBroadcast(Intent(ACTION_PROGRESS).apply {
                                    setPackage(packageName)
                                    putExtra("id", id)
                                    putExtra("downloaded", p.downloaded)
                                    putExtra("total", p.total)
                                    putExtra("speed", p.speed)
                                })
                                if (notificationsOn) {
                                    val percent = if (p.total > 0) p.downloaded * 100 / p.total else 0
                                    getSystemService(NotificationManager::class.java)
                                        .notify(1001, notification("$name • $percent%"))
                                }
                            }
                            sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName).putExtra("id", id))
                            break
                        } catch (e: CancellationException) {
                            sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName)
                                .putExtra("id", id).putExtra("paused", true))
                            break
                        } catch (e: Exception) {
                            if (attempt < retryAttempts) {
                                attempt++
                                delay(2000L * attempt)
                                continue
                            }
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

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("F2L Downloader")
            .setContentText(text)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
