package com.f2l.downloader

import bt.Bt
import bt.data.Storage
import bt.dht.DHTConfig
import bt.dht.DHTModule
import bt.runtime.BtClient
import bt.runtime.Config
import bt.torrent.TorrentSessionState
import bt.data.file.FileSystemStorage
import bt.metainfo.Torrent
import bt.torrent.fileselector.TorrentFileSelector
import java.io.File
import java.util.function.Consumer

/**
 * Pure-Java BitTorrent client (the "bt" library) — runs in-process, no external
 * aria2c/libtorrent binary required. Handles magnet links and .torrent files.
 *
 * IMPORTANT: bt writes to a plain filesystem File, not a SAF tree Uri, so torrent
 * downloads land in the app's own external files directory (no runtime permission
 * needed) rather than the user-picked SAF download folder that direct HTTP links use.
 */
object TorrentEngine {

    data class TorrentProgress(val piecesComplete: Int, val piecesTotal: Int, val peers: Int, val done: Boolean)

    fun saveDir(context: android.content.Context): File =
        File(context.getExternalFilesDir(null), "Torrents").apply { mkdirs() }

    /** Starts a magnet-link download. Call client.stop() to cancel/pause. */
    fun startMagnet(
        context: android.content.Context,
        magnetUri: String,
        onTorrentFetched: (Torrent) -> Unit,
        onProgress: (TorrentProgress) -> Unit,
        onError: (Throwable) -> Unit
    ): BtClient = startInternal(
        context,
        builder = { storage -> Bt.client().storage(storage).magnet(magnetUri) },
        onTorrentFetched, onProgress, onError
    )

    /** Starts a download from a picked .torrent file's raw bytes. */
    fun startTorrentFile(
        context: android.content.Context,
        torrentBytes: ByteArray,
        onTorrentFetched: (Torrent) -> Unit,
        onProgress: (TorrentProgress) -> Unit,
        onError: (Throwable) -> Unit
    ): BtClient = startInternal(
        context,
        builder = { storage ->
            Bt.client().storage(storage).torrent { java.io.ByteArrayInputStream(torrentBytes) }
        },
        onTorrentFetched, onProgress, onError
    )

    private fun startInternal(
        context: android.content.Context,
        builder: (Storage) -> bt.BtClientBuilder<*>,
        onTorrentFetched: (Torrent) -> Unit,
        onProgress: (TorrentProgress) -> Unit,
        onError: (Throwable) -> Unit
    ): BtClient {
        val storage: Storage = FileSystemStorage(saveDir(context).toPath())
        val config = object : Config() {
            override fun getNumOfHashingThreads(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        }
        val dhtModule = DHTModule(object : DHTConfig() {
            override fun shouldUseRouterBootstrap(): Boolean = true
        })

        val client = try {
            builder(storage)
                .config(config)
                .autoLoadModules()
                .module(dhtModule)
                .afterTorrentFetched(Consumer { t -> onTorrentFetched(t) })
                .stopWhenDownloaded()
                .build()
        } catch (e: Throwable) {
            onError(e)
            throw e
        }

        client.startAsync(Consumer<TorrentSessionState> { state ->
            val total = state.piecesTotal
            val complete = state.piecesComplete
            onProgress(TorrentProgress(complete, total, state.connectedSeeders + state.connectedLeechers, complete >= total && total > 0))
        }, 1000).exceptionally { t -> onError(t); null }

        return client
    }
}
