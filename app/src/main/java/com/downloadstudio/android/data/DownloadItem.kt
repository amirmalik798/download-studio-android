package com.downloadstudio.android.data

data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val mode: DownloadMode,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val speed: String = "—",
    val eta: String = "—",
    val downloaded: String = "—",
    val totalSize: String = "—",
    val processingStage: String = "",
    val format: String = "",
    val outputPath: String = "",
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,
    val thumbnailUrl: String = "",
    val durationSeconds: Long = 0L,
    val shareReady: Boolean = true,
    val shareState: SharedLinkState? = null
)

enum class DownloadMode { VIDEO, AUDIO }
enum class SharedLinkState { PREVIEWING, AWAITING_ACTION }
enum class DownloadStatus { QUEUED, STARTING, DOWNLOADING, PAUSED, PROCESSING, COMPLETED, FAILED, STOPPED }
