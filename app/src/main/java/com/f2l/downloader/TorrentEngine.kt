package com.f2l.downloader

import android.os.Environment
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import java.io.File

/**
 * BitTorrent engine backed by org.libtorrent4j — real native libtorrent bindings with
 * prebuilt Android .so binaries. (Earlier attempts: "bt"/bt-core hits a Guice/ART
 * incompatibility not fixable from app code; com.frostwire:jlibtorrent's own maven host
 * is dead. org.libtorrent4j is the same author's actively-maintained republish on real
 * Maven Central.)
 */
object TorrentEngine {

    data class Progress(val percent: Int, val speedBytesPerSec: Int, val seeders: Int, val peers: Int)

    // One session for the whole app process — starting a session (DHT bootstrap etc.) is
    // relatively expensive, so it's created once, lazily, and kept running.
    private val session: SessionManager by lazy { SessionManager().also { it.start() } }
    private val handles = java.util.concurrent.ConcurrentHashMap<Long, TorrentHandle>()
    private val stopFlags = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicBoolean>()

    /**
     * Real public Downloads folder (same one other download managers use), not the app's
     * own sandboxed folder — needs the "All files access" permission on Android 11+
     * (MANAGE_EXTERNAL_STORAGE) since libtorrent writes to a plain filesystem path, not
     * through SAF or MediaStore. Falls back to the app's own external files dir if that
     * permission isn't granted, so torrents still work, just not in the shared folder.
     */
    fun saveDir(context: android.content.Context): File {
        val hasAllFilesAccess = android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
        val hasLegacyWrite = android.os.Build.VERSION.SDK_INT < 30 // WRITE_EXTERNAL_STORAGE checked at request time
        return if (hasAllFilesAccess || hasLegacyWrite) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        } else {
            File(context.getExternalFilesDir(null), "Torrents").apply { mkdirs() }
        }
    }

    fun hasStorageAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    /** Starts a magnet-link download. fetchMagnet() blocks waiting on DHT, so this runs on its own thread. */
    fun startMagnet(
        context: android.content.Context,
        id: Long,
        magnetUri: String,
        onNameKnown: (String) -> Unit,
        onProgress: (Progress) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val dir = saveDir(context)
                val data = session.fetchMagnet(magnetUri, 30, dir)
                if (data == null) {
                    onError("Could not fetch torrent metadata (timed out)")
                    return@Thread
                }
                val info = TorrentInfo(data)
                onNameKnown(info.name() ?: "torrent")
                attachListenerAndStart(id, info, onProgress, onDone, onError)
                session.download(info, dir)
            } catch (e: Exception) {
                onError(e.message ?: "Magnet fetch failed")
            }
        }.start()
    }

    /** Starts a download from a picked .torrent file's raw bytes. */
    fun startTorrentFile(
        context: android.content.Context,
        id: Long,
        torrentBytes: ByteArray,
        onNameKnown: (String) -> Unit,
        onProgress: (Progress) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val info = TorrentInfo(torrentBytes)
            onNameKnown(info.name() ?: "torrent")
            attachListenerAndStart(id, info, onProgress, onDone, onError)
            session.download(info, saveDir(context))
        } catch (e: Exception) {
            onError(e.message ?: "Invalid torrent file")
        }
    }

    /** Pauses without losing the torrent — resume(id) picks it back up. */
    fun pause(id: Long) {
        handles[id]?.let { runCatching { it.pause() } }
    }

    /** Resumes a paused torrent. */
    fun resume(id: Long) {
        handles[id]?.let { runCatching { it.resume() } }
    }

    /** Fully stops and forgets a torrent (used on delete). */
    fun remove(id: Long) {
        stopFlags.remove(id)?.set(true)
        handles.remove(id)?.let { runCatching { it.pause() } }
    }

    private fun attachListenerAndStart(
        id: Long,
        info: TorrentInfo,
        onProgress: (Progress) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Every download's listener is registered on the SAME shared session and therefore
        // receives EVERY torrent's alerts, not just its own — addListener() is global, not
        // per-download. Matching on the torrent's own info-hash (known before download() is
        // even called) makes each listener only ever react to its own torrent, regardless of
        // whether another download is starting around the same time.
        val expectedHash = info.infoHash()
        var myHandle: TorrentHandle? = null
        var finished = false
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        stopFlags[id] = stopped

        fun startPolling(handle: TorrentHandle) {
            Thread {
                while (!finished && !stopped.get()) {
                    try {
                        val status = handle.status()
                        val percent = (status.progress() * 100).toInt()
                        onProgress(Progress(percent, status.downloadPayloadRate(), status.numSeeds(), status.numPeers()))
                        if (percent >= 100) {
                            finished = true
                            onDone()
                            handles.remove(id)
                            stopFlags.remove(id)
                        }
                    } catch (_: Exception) { /* handle may have been removed/invalidated */ }
                    Thread.sleep(1000)
                }
            }.also { it.isDaemon = true; it.start() }
        }

        val listener = object : AlertListener {
            override fun types(): IntArray? = null // all alert types; we filter in alert()

            override fun alert(alert: Alert<*>) {
                when (alert.type()) {
                    AlertType.ADD_TORRENT -> {
                        if (myHandle == null) {
                            val handle = (alert as AddTorrentAlert).handle()
                            if (handle.infoHash() == expectedHash) {
                                myHandle = handle
                                handles[id] = handle
                                handle.resume()
                                startPolling(handle)
                            }
                        }
                    }
                    AlertType.TORRENT_FINISHED -> {
                        val handle = (alert as? TorrentAlert<*>)?.handle()
                        if (handle != null && handle.infoHash() == expectedHash && !finished) {
                            finished = true
                            onProgress(Progress(100, 0, 0, 0))
                            onDone()
                            handles.remove(id)
                            stopFlags.remove(id)
                            session.removeListener(this)
                        }
                    }
                    AlertType.TORRENT_ERROR -> {
                        val handle = (alert as? TorrentErrorAlert)?.handle()
                        if (handle != null && handle.infoHash() == expectedHash && !finished) {
                            finished = true
                            val message = (alert as? TorrentErrorAlert)?.message() ?: "Torrent error"
                            onError(message)
                            handles.remove(id)
                            stopFlags.remove(id)
                            session.removeListener(this)
                        }
                    }
                    else -> {}
                }
            }
        }
        session.addListener(listener)
    }
}
