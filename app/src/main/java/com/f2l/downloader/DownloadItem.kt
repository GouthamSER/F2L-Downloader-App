package com.f2l.downloader

data class DownloadItem(
    val id: Long,
    val url: String,
    val fileName: String,
    val folderUri: String,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val status: Status = Status.QUEUED,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L,
    val error: String? = null,
    val connections: Int = 8,
    val isTorrent: Boolean = false,
    val peers: Int = 0
) {
    enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELED }
}
