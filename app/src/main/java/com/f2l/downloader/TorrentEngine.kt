package com.f2l.downloader

import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.alerts.AddTorrentAlert
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.alerts.TorrentAlert
import com.frostwire.jlibtorrent.alerts.TorrentErrorAlert
import java.io.File

/**
 * BitTorrent engine backed by frostwire-jlibtorrent — real native libtorrent bindings with
 * prebuilt Android .so binaries (used in production by FrostWire itself), not a pure-Java
 * reflection-heavy library. An earlier attempt used "bt" (bt-core), which depends on Guice;
 * Guice's method interception calls Class.getAnnotatedSuperclass(), which Android's ART
 * runtime doesn't actually implement despite declaring it — a hard, unfixable-from-app-code
 * crash. libtorrent is C++ with a thin JNI layer, so it doesn't hit that class of problem.
 *
 * Saves to the app's own external files directory (plain filesystem path), not the SAF
 * folder used for direct HTTP downloads — libtorrent writes real files, not SAF tree Uris.
 */
object TorrentEngine {

    // One session for the whole app process — starting a session (DHT bootstrap etc.) is
    // relatively expensive, so it's created once, lazily, and kept running.
    private val session: SessionManager by lazy { SessionManager().also { it.start() } }
    private val handles = java.util.concurrent.ConcurrentHashMap<Long, TorrentHandle>()

    fun saveDir(context: android.content.Context): File =
        File(context.getExternalFilesDir(null), "Torrents").apply { mkdirs() }

    /** Starts a magnet-link download. */
    fun startMagnet(
        context: android.content.Context,
        id: Long,
        magnetUri: String,
        onNameKnown: (String) -> Unit,
        onProgress: (percent: Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        attachListenerAndStart(id, onNameKnown, onProgress, onDone, onError)
        session.download(magnetUri, saveDir(context))
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
        attachListenerAndStart(id, onNameKnown, onProgress, onDone, onError)
        val info = TorrentInfo(torrentBytes)
        onNameKnown(info.name() ?: "torrent")
        session.download(info, saveDir(context))
    }

    /** Pauses/cancels a running torrent by the id it was started with. */
    fun cancel(id: Long) {
        handles.remove(id)?.let { runCatching { it.pause() } }
    }

    private fun attachListenerAndStart(
        id: Long,
        onNameKnown: (String) -> Unit,
        onProgress: (percent: Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        var myHandle: TorrentHandle? = null

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
                            runCatching { onNameKnown(handle.name() ?: "torrent") }
                        }
                    }
                    AlertType.PIECE_FINISHED -> {
                        val handle = (alert as? TorrentAlert<*>)?.handle()
                        if (handle != null && handle == myHandle) {
                            val progress = (handle.status().progress() * 100).toInt()
                            onProgress(progress)
                        }
                    }
                    AlertType.TORRENT_FINISHED -> {
                        val handle = (alert as? TorrentAlert<*>)?.handle()
                        if (handle != null && handle == myHandle) {
                            onProgress(100)
                            onDone()
                            handles.remove(id)
                            session.removeListener(this)
                        }
                    }
                    AlertType.TORRENT_ERROR -> {
                        val handle = (alert as? TorrentErrorAlert)?.handle()
                        if (handle == null || handle == myHandle) {
                            onError((alert as? TorrentErrorAlert)?.error()?.message() ?: "Torrent error")
                            handles.remove(id)
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
