package com.f2l.downloader

import android.content.Context
import android.os.Build
import android.os.Environment
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
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
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance BitTorrent & magnet engine backed by org.libtorrent4j.
 * Uses native non-blocking libtorrent sessions with peer discovery, metadata fetching,
 * tracker injection, and continuous live metric reporting.
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

    data class TorrentCallbacks(
        val onNameKnown: (String) -> Unit,
        val onProgress: (Progress) -> Unit,
        val onDone: () -> Unit,
        val onError: (String) -> Unit
    )

    internal class TorrentTask(
        val id: Long,
        @Volatile var targetHash: String?,
        @Volatile var handle: TorrentHandle?,
        @Volatile var callbacks: TorrentCallbacks?,
        val isPaused: AtomicBoolean = AtomicBoolean(false),
        val isFinished: AtomicBoolean = AtomicBoolean(false),
        @Volatile var lastDownloadedBytes: Long = 0L,
        @Volatile var lastProgressTime: Long = 0L,
        @Volatile var lastResolvedName: String? = null
    )

    private val tasks = ConcurrentHashMap<Long, TorrentTask>()

    // Comprehensive high-performance Tier 1 UDP & HTTP public trackers
    val PUBLIC_TRACKERS = listOf(
        // High-speed UDP Trackers
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://p4p.arenabg.com:1337/announce",
        "udp://tracker.cyberia.is:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://retracker.lanta-net.ru:2710/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://bt2.archive.org:6969/announce",
        "udp://bt1.archive.org:6969/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker-udp.gbitt.info:80/announce",
        "udp://movies.zsw.ca:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://ipv4.tracker.harry.lu:80/announce",
        "udp://tracker.coppersurfer.tk:6969/announce",
        "udp://9.rarbg.to:2710/announce",
        "udp://9.rarbg.me:2710/announce",
        "udp://tracker.zerobytes.xyz:1337/announce",
        "udp://tracker.altrosky.nl:6969/announce",
        "udp://tracker.t-rb.org:6969/announce",
        "udp://tracker1.bt.moack.co.kr:80/announce",
        "udp://tracker.dump.cl:6969/announce",
        // HTTP / HTTPS Trackers
        "http://tracker.openbittorrent.com:80/announce",
        "http://tracker.opentrackr.org:1337/announce",
        "https://tracker.tamersunion.org:443/announce",
        "http://tracker.dler.org:6969/announce",
        "https://tracker.nanoha.org:443/announce",
        "http://tracker.renfei.net:8080/announce"
    )

    fun injectTrackers(handle: TorrentHandle) {
        runCatching {
            for (tr in PUBLIC_TRACKERS) {
                runCatching { handle.addTracker(AnnounceEntry(tr)) }
            }
            runCatching { handle.forceReannounce() }
            runCatching { handle.forceDhtAnnounce() }
        }
    }

    private val globalAlertListener = object : AlertListener {
        override fun types(): IntArray? = null

        override fun alert(alert: Alert<*>) {
            when (alert.type()) {
                AlertType.ADD_TORRENT -> {
                    val addAlert = alert as? AddTorrentAlert ?: return
                    val handle = addAlert.handle()
                    val hash = runCatching { handle.infoHash().toHex().lowercase() }.getOrNull()

                    val task = if (!hash.isNullOrBlank()) {
                        tasks.values.firstOrNull { it.targetHash.equals(hash, ignoreCase = true) }
                            ?: tasks.values.firstOrNull { it.handle == null && it.targetHash == null }
                    } else {
                        tasks.values.firstOrNull { it.handle == null }
                    }

                    if (task != null) {
                        task.handle = handle
                        if (task.targetHash.isNullOrBlank() && !hash.isNullOrBlank()) {
                            task.targetHash = hash
                        }
                        if (task.isPaused.get()) {
                            runCatching { handle.pause() }
                        } else {
                            runCatching { handle.resume() }
                            injectTrackers(handle)
                        }
                        ensurePolling()
                    }
                }

                AlertType.METADATA_RECEIVED -> {
                    val metaAlert = alert as? MetadataReceivedAlert ?: return
                    val handle = metaAlert.handle()
                    val hash = runCatching { handle.infoHash().toHex().lowercase() }.getOrNull()
                    val task = tasks.values.firstOrNull {
                        it.handle == handle || (!hash.isNullOrBlank() && it.targetHash.equals(hash, ignoreCase = true))
                    }
                    if (task != null) {
                        task.handle = handle
                        injectTrackers(handle)
                        val tf = runCatching { handle.torrentFile() }.getOrNull()
                        val name = tf?.name()?.takeIf { it.isNotBlank() }
                            ?: runCatching { handle.status().name() }.getOrNull()?.takeIf { it.isNotBlank() }
                        if (!name.isNullOrBlank() && !name.startsWith("magnet:")) {
                            task.lastResolvedName = name
                            task.callbacks?.onNameKnown?.invoke(name)
                        }
                    }
                }

                AlertType.TORRENT_FINISHED -> {
                    val finishAlert = alert as? TorrentFinishedAlert ?: return
                    val handle = finishAlert.handle()
                    val hash = runCatching { handle.infoHash().toHex().lowercase() }.getOrNull()
                    val task = tasks.values.firstOrNull {
                        it.handle == handle || (!hash.isNullOrBlank() && it.targetHash.equals(hash, ignoreCase = true))
                    }
                    if (task != null && task.isFinished.compareAndSet(false, true)) {
                        task.callbacks?.onProgress?.invoke(
                            Progress(
                                percent = 100,
                                speedBytesPerSec = 0L,
                                seeders = runCatching { handle.status().numSeeds() }.getOrDefault(0),
                                peers = runCatching { handle.status().numPeers() }.getOrDefault(0),
                                downloadedBytes = runCatching { handle.status().totalDone() }.getOrDefault(0L),
                                totalBytes = runCatching { handle.status().totalWanted() }.getOrDefault(-1L),
                                hasMetadata = true,
                                resolvedName = task.lastResolvedName
                            )
                        )
                        task.callbacks?.onDone?.invoke()
                        tasks.remove(task.id)
                    }
                }

                AlertType.TORRENT_ERROR -> {
                    val errAlert = alert as? TorrentErrorAlert ?: return
                    val handle = errAlert.handle()
                    val hash = runCatching { handle.infoHash().toHex().lowercase() }.getOrNull()
                    val task = tasks.values.firstOrNull {
                        it.handle == handle || (!hash.isNullOrBlank() && it.targetHash.equals(hash, ignoreCase = true))
                    }
                    if (task != null && task.isFinished.compareAndSet(false, true)) {
                        val msg = errAlert.message() ?: "BitTorrent error occurred"
                        task.callbacks?.onError?.invoke(msg)
                        tasks.remove(task.id)
                    }
                }

                else -> {}
            }
        }
    }

    private val session: SessionManager by lazy {
        SessionManager().also { sm ->
            runCatching {
                val sp = SettingsPack()
                runCatching { sp.setEnableDht(true) }
                runCatching {
                    sp.setString(
                        settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                        "router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.libtorrent.org:25401,dht.aelitis.com:6881"
                    )
                }
                sm.applySettings(sp)
            }
            sm.addListener(globalAlertListener)
            sm.start()
        }
    }

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

                val callbacks = TorrentCallbacks(onNameKnown, onProgress, onDone, onError)
                val task = tasks.computeIfAbsent(id) {
                    TorrentTask(id, targetHash, null, callbacks)
                }
                task.callbacks = callbacks
                task.targetHash = targetHash
                task.isPaused.set(false)
                task.isFinished.set(false)
                if (!initialName.isNullOrBlank()) {
                    task.lastResolvedName = initialName
                }

                // Check if already in session
                val existing = if (!targetHash.isNullOrBlank()) findHandle(targetHash) else null
                if (existing != null && existing.isValid) {
                    task.handle = existing
                    existing.resume()
                    injectTrackers(existing)
                    ensurePolling()
                    return@Thread
                }

                session.download(enriched, dir, torrent_flags_t())
                ensurePolling()
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
                saveTorrentFile(context, id, torrentBytes)

                val info = TorrentInfo(torrentBytes)
                val targetHash = info.infoHash().toHex().lowercase()
                val dir = saveDir(context)
                val name = info.name() ?: "torrent"
                onNameKnown(name)

                val callbacks = TorrentCallbacks(onNameKnown, onProgress, onDone, onError)
                val task = tasks.computeIfAbsent(id) {
                    TorrentTask(id, targetHash, null, callbacks)
                }
                task.callbacks = callbacks
                task.targetHash = targetHash
                task.isPaused.set(false)
                task.isFinished.set(false)
                task.lastResolvedName = name

                val existing = findHandle(targetHash)
                if (existing != null && existing.isValid) {
                    task.handle = existing
                    existing.resume()
                    injectTrackers(existing)
                    ensurePolling()
                    return@Thread
                }

                session.download(info, dir)
                ensurePolling()
            } catch (e: Throwable) {
                onError(e.message ?: "Failed to start torrent file (${e.javaClass.simpleName})")
            }
        }.start()
    }

    fun hasHandle(id: Long): Boolean = tasks[id]?.handle?.isValid == true

    /**
     * Pauses the torrent without removing it.
     */
    fun pause(id: Long) {
        val task = tasks[id]
        if (task != null) {
            task.isPaused.set(true)
            task.handle?.let { runCatching { it.pause() } }
        }
    }

    /**
     * Resumes a paused torrent.
     */
    fun resume(
        id: Long,
        onNameKnown: (String) -> Unit,
        onProgress: (Progress) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val task = tasks[id]
        if (task != null) {
            task.callbacks = TorrentCallbacks(onNameKnown, onProgress, onDone, onError)
            task.isPaused.set(false)
            task.lastProgressTime = System.currentTimeMillis()
            val handle = task.handle
            if (handle != null && handle.isValid) {
                runCatching { handle.resume() }
                injectTrackers(handle)
            } else if (!task.targetHash.isNullOrBlank()) {
                val found = findHandle(task.targetHash!!)
                if (found != null && found.isValid) {
                    task.handle = found
                    runCatching { found.resume() }
                    injectTrackers(found)
                }
            }
            ensurePolling()
            return true
        }
        return false
    }

    /**
     * Completely removes a torrent from the session.
     */
    fun remove(id: Long) {
        val task = tasks.remove(id)
        if (task != null) {
            task.isFinished.set(true)
            task.isPaused.set(true)
            val handle = task.handle
            if (handle != null && handle.isValid) {
                runCatching {
                    handle.pause()
                    session.remove(handle)
                }
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

    private val pollingLock = Any()
    @Volatile private var pollingThread: Thread? = null

    private fun ensurePolling() {
        synchronized(pollingLock) {
            if (pollingThread?.isAlive == true) return
            pollingThread = Thread({
                var idleSeconds = 0
                while (true) {
                    val activeTasks = tasks.values.filter { !it.isFinished.get() && !it.isPaused.get() }
                    if (activeTasks.isEmpty()) {
                        idleSeconds++
                        if (idleSeconds > 10) {
                            break
                        }
                    } else {
                        idleSeconds = 0
                    }

                    for (task in tasks.values) {
                        if (task.isFinished.get() || task.isPaused.get()) continue
                        val handle = task.handle
                        if (handle == null || !handle.isValid) continue

                        try {
                            val status = handle.status()
                            val progressFloat = status.progress()
                            val percent = (progressFloat * 100).toInt().coerceIn(0, 100)
                            val seeders = status.numSeeds().coerceAtLeast(0)
                            val peers = status.numPeers().coerceAtLeast(0)
                            val downloaded = status.totalDone().coerceAtLeast(0L)
                            val total = if (status.totalWanted() > 0) status.totalWanted() else -1L
                            val hasMeta = status.hasMetadata()

                            val now = System.currentTimeMillis()
                            val dt = if (task.lastProgressTime > 0) (now - task.lastProgressTime) / 1000.0 else 1.0
                            val delta = downloaded - task.lastDownloadedBytes
                            val calcSpeed = if (dt > 0.3 && delta > 0) (delta / dt).toLong() else 0L
                            val nativeSpeed = maxOf(status.downloadPayloadRate(), status.downloadRate()).toLong().coerceAtLeast(0L)
                            val speed = maxOf(calcSpeed, nativeSpeed)

                            task.lastDownloadedBytes = downloaded
                            task.lastProgressTime = now

                            var newName: String? = null
                            if (hasMeta) {
                                val tf = runCatching { handle.torrentFile() }.getOrNull()
                                val n = tf?.name()?.takeIf { it.isNotBlank() }
                                    ?: runCatching { status.name() }.getOrNull()?.takeIf { it.isNotBlank() }
                                if (!n.isNullOrBlank() && n != task.lastResolvedName && !n.startsWith("magnet:")) {
                                    task.lastResolvedName = n
                                    newName = n
                                    task.callbacks?.onNameKnown?.invoke(n)
                                }
                            }

                            task.callbacks?.onProgress?.invoke(
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
                                if (task.isFinished.compareAndSet(false, true)) {
                                    task.callbacks?.onProgress?.invoke(
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
                                    task.callbacks?.onDone?.invoke()
                                    tasks.remove(task.id)
                                }
                            }
                        } catch (_: Exception) {
                            // Temporary handle state transitions
                        }
                    }

                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "TorrentEngine-Polling").apply {
                isDaemon = true
                start()
            }
        }
    }
}
