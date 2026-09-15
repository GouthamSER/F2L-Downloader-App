package com.f2l.downloader

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
 * prebuilt Android .so binaries. Two earlier attempts hit real walls:
 *  - "bt" (bt-core): Guice-based, hits Class.getAnnotatedSuperclass() which Android's ART
 *    doesn't implement — not fixable from app code.
 *  - com.frostwire:jlibtorrent: same underlying library, but its distribution host
 *    (dl.frostwire.com/maven) is dead.
 * org.libtorrent4j is the same author's actively-maintained republish on real Maven Central,
 * but its actual method signatures have drifted from the old frostwire fork — notably there's
 * no download(String magnetUri, File) shortcut anymore. Magnet links go through
 * fetchMagnet() (blocking, hence run off the main thread) -> TorrentInfo -> download(TorrentInfo, File).
 *
 * Saves to the app's own external files directory (plain filesystem path), not the SAF
 * folder used for direct HTTP downloads — libtorrent writes real files, not SAF tree Uris.
 */
object TorrentEngine {

    // One session for the whole app process — starting a session (DHT bootstrap etc.) is
    // relatively expensive, so it's created once, lazily, and kept running.
    private val session: SessionManager by lazy { SessionManager().also { it.start() } }
    private val handles = java.util.concurrent.ConcurrentHashMap<Long, TorrentHandle>()
    private val stopFlags = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicBoolean>()

    fun saveDir(context: android.content.Context): File =
        File(context.getExternalFilesDir(null), "Torrents").apply { mkdirs() }

    /** Starts a magnet-link download. fetchMagnet() blocks waiting on DHT, so this runs on its own thread. */
    fun startMagnet(
        context: android.content.Context,
        id: Long,
        magnetUri: String,
        onNameKnown: (String) -> Unit,
        onProgress: (percent: Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val data = session.fetchMagnet(magnetUri, 30, saveDir(context))
                if (data == null) {
                    onError("Could not fetch torrent metadata (timed out)")
                    return@Thread
                }
                val info = TorrentInfo(data)
                onNameKnown(info.name() ?: "torrent")
                attachListenerAndStart(id, onProgress, onDone, onError)
                session.download(info, saveDir(context))
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
        onProgress: (percent: Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val info = TorrentInfo(torrentBytes)
            onNameKnown(info.name() ?: "torrent")
            attachListenerAndStart(id, onProgress, onDone, onError)
            session.download(info, saveDir(context))
        } catch (e: Exception) {
            onError(e.message ?: "Invalid torrent file")
        }
    }

    /** Pauses/cancels a running torrent by the id it was started with. */
    fun cancel(id: Long) {
        stopFlags.remove(id)?.set(true)
        handles.remove(id)?.let { runCatching { it.pause() } }
    }

    private fun attachListenerAndStart(
        id: Long,
        onProgress: (percent: Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        var myHandle: TorrentHandle? = null
        var finished = false
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        stopFlags[id] = stopped

        fun startPolling(handle: TorrentHandle) {
            Thread {
                while (!finished && !stopped.get()) {
                    try {
                        val progress = (handle.status().progress() * 100).toInt()
                        onProgress(progress)
                        if (progress >= 100) {
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
                        // The very next ADD_TORRENT after we call download() is ours —
                        // lock onto it and make sure it actually starts.
                        if (myHandle == null) {
                            val handle = (alert as AddTorrentAlert).handle()
                            myHandle = handle
                            handles[id] = handle
                            handle.resume()
                            // Poll status directly instead of relying on PIECE_FINISHED alerts —
                            // those need an explicit alert-mask opt-in on the session's settings
                            // to fire at all, and without it progress silently never updates even
                            // though the download is actually happening ("stuck at peers" symptom).
                            startPolling(handle)
                        }
                    }
                    AlertType.TORRENT_FINISHED -> {
                        val handle = (alert as? TorrentAlert<*>)?.handle()
                        if (handle != null && handle == myHandle && !finished) {
                            finished = true
                            onProgress(100)
                            onDone()
                            handles.remove(id)
                            stopFlags.remove(id)
                            session.removeListener(this)
                        }
                    }
                    AlertType.TORRENT_ERROR -> {
                        val handle = (alert as? TorrentErrorAlert)?.handle()
                        if ((handle == null || handle == myHandle) && !finished) {
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
