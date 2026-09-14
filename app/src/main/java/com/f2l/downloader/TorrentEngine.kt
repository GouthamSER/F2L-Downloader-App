package com.f2l.downloader

import bt.Bt
import bt.data.Storage
import bt.data.file.FileSystemStorage
import bt.dht.DHTConfig
import bt.dht.DHTModule
import bt.metainfo.MetadataService
import bt.metainfo.Torrent
import bt.runtime.BtClient
import bt.runtime.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.function.Consumer

/**
 * Pure-Java BitTorrent client (the "bt" library) — runs in-process, no external
 * aria2c/libtorrent binary required. Handles magnet links and .torrent files.
 *
 * IMPORTANT: bt writes to a plain filesystem File, not a SAF tree Uri, so torrent
 * downloads land in the app's own external files directory (no runtime permission
 * needed) rather than the user-picked SAF download folder that direct HTTP links use.
 *
 * Progress reporting intentionally sticks to only bt-core's confirmed, documented API
 * (BtClient#startAsync(Consumer<TorrentSessionState>, long) and
 * TorrentSessionState#getPiecesRemaining()) rather than guessing at less-documented
 * getters, to keep this buildable against the real 1.10 jar.
 */
object TorrentEngine {

    fun saveDir(context: android.content.Context): File =
        File(context.getExternalFilesDir(null), "Torrents").apply { mkdirs() }

    private fun config() = object : Config() {
        override fun getNumOfHashingThreads(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }

    private fun dhtModule() = DHTModule(object : DHTConfig() {
        override fun shouldUseRouterBootstrap(): Boolean = true
    })

    /** Starts a magnet-link download. Call client.stop() to cancel/pause. */
    fun startMagnet(
        context: android.content.Context,
        magnetUri: String,
        onTorrentFetched: (Torrent) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit
    ): BtClient {
        val storage: Storage = FileSystemStorage(saveDir(context).toPath())
        val client = try {
            Bt.client()
                .config(config())
                .storage(storage)
                .magnet(magnetUri)
                .autoLoadModules()
                .module(dhtModule())
                .afterTorrentFetched(Consumer { t -> onTorrentFetched(t) })
                .stopWhenDownloaded()
                .build()
        } catch (e: Throwable) {
            onError(e)
            throw e
        }
        client.startAsync(Consumer { state ->
            if (state.piecesRemaining == 0) onDone()
        }, 1000)
        return client
    }

    /** Starts a download from a picked .torrent file's raw bytes. */
    fun startTorrentFile(
        context: android.content.Context,
        torrentBytes: ByteArray,
        onTorrentFetched: (Torrent) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit
    ): BtClient {
        val storage: Storage = FileSystemStorage(saveDir(context).toPath())
        val client = try {
            Bt.client()
                .config(config())
                .storage(storage)
                .torrent { ByteArrayInputStream(torrentBytes).use { MetadataService().fromInputStream(it) } }
                .autoLoadModules()
                .module(dhtModule())
                .afterTorrentFetched(Consumer { t -> onTorrentFetched(t) })
                .stopWhenDownloaded()
                .build()
        } catch (e: Throwable) {
            onError(e)
            throw e
        }
        client.startAsync(Consumer { state ->
            if (state.piecesRemaining == 0) onDone()
        }, 1000)
        return client
    }
}
