package com.f2l.downloader

import android.content.Context
import android.os.Build
import android.os.Environment
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance BitTorrent & magnet engine backed by org.libtorrent4j.
 * Uses native non-blocking libtorrent sessions with peer discovery, metadata fetching,
 * tracker injection, and live metric reporting.
 */
object TorrentEngine {

    data class Progress(
        val percent: Int,
        val speedBytesPerSec: Long,
        val seeders: Int,
        val peers: Int,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val hasMetadata: Boolean = false,
        val resolvedName: String? = null
    )

    private val session: SessionManager by lazy {
        SessionManager().also { sm ->
            sm.start()
        }
    }

    private val handles = ConcurrentHashMap<Long, TorrentHandle>()
    private val stopFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    // Tier 1 reliable public trackers to accelerate swarm discovery
    val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "http://tracker.openbittorrent.com:80/announce"
    )

    /**
     * Appends public trackers to a magnet URI if not already present.
     */
    fun enrichMagnetUri(magnetUri: String): String {
        val sb = StringBuilder(magnetUri)
        for (tr in PUBLIC_TRACKERS) {
            val encoded = try { URLEncoder.encode(tr, "UTF-8") } catch (_: Exception) { tr }
            if (!magnetUri.contains(encoded) && !magnetUri.contains(tr)) {
                sb.append("&tr=").append(encoded)
            }
        }
        return sb.toString()
    }

    /**
     * Extracts display name (dn parameter) from a magnet URI if present.
     */
    fun extractDisplayName(magnetUri: String): String? {
        return try {
            val match = Regex("""[?&]dn=([^&]+)""").find(magnetUri)
            match?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts the torrent info-hash from a magnet URI.
     */
    fun parseInfoHash(magnetUri: String): String? {
        return try {
            val ec = error_code()
            val p = libtorrent.parse_magnet_uri(magnetUri, ec)
            if (ec.value() == 0) {
                p.info_hashes.get_best().to_hex()
            } else {
                val match = Regex("""xt=urn:btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})""", RegexOption.IGNORE_CASE).find(magnetUri)
                match?.groupValues?.get(1)
            }
        } catch (_: Throwable) {
            val match = Regex("""xt=urn:btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})""", RegexOption.IGNORE_CASE).find(magnetUri)
            match?.groupValues?.get(1)
        }
    }

    /**
     * Inspects a raw .torrent byte array and returns its internal name.
     */
    fun getTorrentName(bytes: ByteArray): String? {
        return try {
            val info = TorrentInfo(bytes)
            info.name()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Real public Downloads folder (if storage permission granted) or app sandboxed Torrents folder.
     */
    fun saveDir(context: Context): File {
        val hasAllFilesAccess = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
        val hasLegacyWrite = Build.VERSION.SDK_INT < 30
        return if (hasAllFilesAccess || hasLegacyWrite) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        } else {
            File(context.getExternalFilesDir(null), "Torrents").apply { mkdirs() }
        }
    }

    fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    fun torrentCacheDir(context: Context): File =
        File(context.filesDir, "torrents").apply { mkdirs() }

    fun saveTorrentFile(context: Context, id: Long, bytes: ByteArray): File {
        val file = File(torrentCacheDir(context), "$id.torrent")
        file.writeBytes(bytes)
        return file
    }

    fun getSavedTorrentFile(context: Context, id: Long): File? {
        val file = File(torrentCacheDir(context), "$id.torrent")
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Starts or resumes a magnet download.
     * Uses libtorrent's native async magnet processing — starts searching peers immediately,
     * pulls metadata via DHT and trackers, and streams file data without blocking.
     */
    fun startMagnet(
        context: Context,
        id: Long,
        magnetUri: String,
        onNameKnown: (String) -> Unit,
        onProgress: (Progress) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val enriched = enrichMagnetUri(magnetUri.trim())
                val initialName = extractDisplayName(enriched)
                if (!initialName.isNullOrBlank()) {
                    onNameKnown(initialName)
                }

                val targetHash = parseInfoHash(enriched)?.lowercase()
                val dir = saveDir(context)

                // Check if already in session (e.g. after pause/resume)
                if (!targetHash.isNullOrBlank()) {
                    val existing = findHandle(targetHash)
                    if (existing != null && existing.isValid) {
                        handles[id] = existing
                        existing.resume()
                        attachAlertsAndPolling(id, targetHash, existing, onNameKnown, onProgress, onDone, onError)
                        return@Thread
                    }
                }

                attachAlertsAndPolling(id, targetHash, null, onNameKnown, onProgress, onDone, onError)
                session.download(enriched, dir, torrent_flags_t())
            } catch (e: Throwable) {
                onError(e.message ?: "Failed to start magnet download (${e.javaClass.simpleName})")
            }
        }.start()
    }

    /**
     * Starts or resumes a download from a .torrent file.
     */
    fun startTorrentFile(
        context: Context,
        id: Long,
        torrentBytes: ByteArray,
        onNameKnown: (String) -> Unit,
        onProgress: (Progress) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                // Save torrent bytes to internal cache for persistence across app restarts
                saveTorrentFile(context, id, torrentBytes)

                val info = TorrentInfo(torrentBytes)
                val targetHash = info.infoHash().toHex().lowercase()
                val dir = saveDir(context)
                val name = info.name() ?: "torrent"
                onNameKnown(name)

                // Check if already in session
                val existing = findHandle(targetHash)
                if (existing != null && existing.isValid) {
                    handles[id] = existing
                    existing.resume()
                    attachAlertsAndPolling(id, targetHash, existing, onNameKnown, onProgress, onDone, onError)
                    return@Thread
                }

                attachAlertsAndPolling(id, targetHash, null, onNameKnown, onProgress, onDone, onError)
                session.download(info, dir)
            } catch (e: Throwable) {
                onError(e.message ?: "Failed to start torrent file (${e.javaClass.simpleName})")
            }
        }.start()
    }

    fun hasHandle(id: Long): Boolean = handles[id]?.isValid == true

    /**
     * Pauses the torrent without removing it.
     */
    fun pause(id: Long) {
        stopFlags[id]?.set(true)
        handles[id]?.let { runCatching { it.pause() } }
    }

    /**
     * Resumes a paused torrent.
     */
    fun resume(id: Long) {
        stopFlags[id]?.set(false)
        handles[id]?.let { runCatching { it.resume() } }
    }

    /**
     * Completely removes a torrent from the session.
     */
    fun remove(id: Long) {
        stopFlags.remove(id)?.set(true)
        val handle = handles.remove(id)
        if (handle != null && handle.isValid) {
            runCatching {
                handle.pause()
                session.remove(handle)
            }
        }
    }

    private fun findHandle(hashHex: String): TorrentHandle? {
        return try {
            val sha1 = Sha1Hash.parseHex(hashHex)
            session.find(sha1)?.takeIf { it.isValid }
        } catch (_: Throwable) {
            null
        }
    }

    private fun attachAlertsAndPolling(
        id: Long,
        targetHash: String?,
        initialHandle: TorrentHandle?,
        onNameKnown: (String) -> Unit,
        onProgress: (Progress) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        var myHandle: TorrentHandle? = initialHandle
        val finished = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        stopFlags[id] = stopped

        fun startPolling(handle: TorrentHandle) {
            Thread {
                var lastResolvedName = ""
                while (!finished.get() && !stopped.get()) {
                    try {
                        if (!handle.isValid) break
                        val status = handle.status()
                        val progressFloat = status.progress()
                        val percent = (progressFloat * 100).toInt().coerceIn(0, 100)
                        val speed = status.downloadPayloadRate().toLong().coerceAtLeast(0L)
                        val seeders = status.numSeeds().coerceAtLeast(0)
                        val peers = status.numPeers().coerceAtLeast(0)
                        val downloaded = status.totalDone().coerceAtLeast(0L)
                        val total = if (status.totalWanted() > 0) status.totalWanted() else -1L
                        val hasMeta = status.hasMetadata()

                        var newName: String? = null
                        if (hasMeta) {
                            val tf = runCatching { handle.torrentFile() }.getOrNull()
                            val n = tf?.name()?.takeIf { it.isNotBlank() }
                                ?: runCatching { status.name() }.getOrNull()?.takeIf { it.isNotBlank() }
                            if (!n.isNullOrBlank() && n != lastResolvedName && !n.startsWith("magnet:")) {
                                lastResolvedName = n
                                newName = n
                                onNameKnown(n)
                            }
                        }

                        onProgress(
                            Progress(
                                percent = percent,
                                speedBytesPerSec = speed,
                                seeders = seeders,
                                peers = peers,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                hasMetadata = hasMeta,
                                resolvedName = newName
                            )
                        )

                        val state = status.state()
                        if (percent >= 100 || state == TorrentStatus.State.FINISHED || state == TorrentStatus.State.SEEDING) {
                            if (finished.compareAndSet(false, true)) {
                                onProgress(
                                    Progress(
                                        percent = 100,
                                        speedBytesPerSec = 0L,
                                        seeders = seeders,
                                        peers = peers,
                                        downloadedBytes = if (total > 0) total else downloaded,
                                        totalBytes = total,
                                        hasMetadata = true,
                                        resolvedName = newName
                                    )
                                )
                                onDone()
                                handles.remove(id)
                                stopFlags.remove(id)
                            }
                            break
                        }
                    } catch (_: Exception) {
                        // Handle may become temporarily invalid during state transitions
                    }
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        }

        if (myHandle != null) {
            handles[id] = myHandle
            startPolling(myHandle)
        }

        val listener = object : AlertListener {
            override fun types(): IntArray? = null

            override fun alert(alert: Alert<*>) {
                if (finished.get() || stopped.get()) return

                when (alert.type()) {
                    AlertType.ADD_TORRENT -> {
                        val addAlert = alert as? AddTorrentAlert ?: return
                        val handle = addAlert.handle()
                        val handleHash = runCatching { handle.infoHash().toHex().lowercase() }.getOrNull()

                        val matches = if (!targetHash.isNullOrBlank()) {
                            targetHash.equals(handleHash, ignoreCase = true)
                        } else {
                            myHandle == null
                        }

                        if (matches && myHandle == null) {
                            myHandle = handle
                            handles[id] = handle
                            handle.resume()
                            startPolling(handle)
                        }
                    }
                    AlertType.METADATA_RECEIVED -> {
                        val metaAlert = alert as? MetadataReceivedAlert
                        val handle = metaAlert?.handle()
                        val handleHash = runCatching { handle?.infoHash()?.toHex()?.lowercase() }.getOrNull()
                        if (handle != null && (targetHash.isNullOrBlank() || targetHash.equals(handleHash, ignoreCase = true))) {
                            val tf = runCatching { handle.torrentFile() }.getOrNull()
                            val name = tf?.name()?.takeIf { it.isNotBlank() }
                                ?: runCatching { handle.status().name() }.getOrNull()?.takeIf { it.isNotBlank() }
                            if (!name.isNullOrBlank() && !name.startsWith("magnet:")) {
                                onNameKnown(name)
                            }
                        }
                    }
                    AlertType.TORRENT_FINISHED -> {
                        val torrentAlert = alert as? TorrentFinishedAlert
                        val handle = torrentAlert?.handle()
                        val handleHash = runCatching { handle?.infoHash()?.toHex()?.lowercase() }.getOrNull()
                        if (handle != null && (targetHash.isNullOrBlank() || targetHash.equals(handleHash, ignoreCase = true))) {
                            if (finished.compareAndSet(false, true)) {
                                onDone()
                                handles.remove(id)
                                stopFlags.remove(id)
                                session.removeListener(this)
                            }
                        }
                    }
                    AlertType.TORRENT_ERROR -> {
                        val errAlert = alert as? TorrentErrorAlert
                        val handle = errAlert?.handle()
                        val handleHash = runCatching { handle?.infoHash()?.toHex()?.lowercase() }.getOrNull()
                        if (handle != null && (targetHash.isNullOrBlank() || targetHash.equals(handleHash, ignoreCase = true))) {
                            if (finished.compareAndSet(false, true)) {
                                val errorMsg = errAlert.message() ?: "Torrent error occurred"
                                onError(errorMsg)
                                handles.remove(id)
                                stopFlags.remove(id)
                                session.removeListener(this)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        session.addListener(listener)
    }
}
