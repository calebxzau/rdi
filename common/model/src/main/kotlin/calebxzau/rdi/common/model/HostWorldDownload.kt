package calebxzau.rdi.common.model

import kotlinx.serialization.Serializable

/** Steps of building a host save download (下载房间存档). */
@Serializable
enum class HostWorldDownloadStage {
    /** Copying the sync chunks out of the region files; counted in sync chunks. */
    SyncChunks,

    /** Adding player data and the other world files; counted in files. */
    Files,

    /** Finishing the zstd archive and its SHA-1. */
    Compress,
}

/** One download of the host's save that used up one of the day's downloads. */
@Serializable
data class HostWorldDownloadRecordVo(
    val playerId: String,
    val playerName: String?,
    val finishedAt: Long,
    val bytes: Long,
)

@Serializable
data class HostWorldDownloadStatusVo(
    val limit: Int,
    val remaining: Int,
    /** Downloads that used up today's downloads, oldest first. */
    val today: List<HostWorldDownloadRecordVo>,
    /** The current save is already packed; downloading it again does not use a download. */
    val reusable: Boolean,
    /** Why a download cannot start now, or null when it can. */
    val blockedReason: String?,
)

@Serializable
data class HostWorldDownloadStartVo(
    val buildId: String,
    /** The packed save is reused; no download is used up and no build runs. */
    val reused: Boolean,
)

/** SSE `progress` event. */
@Serializable
data class HostWorldDownloadProgressVo(
    val stage: HostWorldDownloadStage,
    val done: Int,
    val total: Int,
)

/** SSE `ready` event: the archive can be downloaded. */
@Serializable
data class HostWorldDownloadReadyVo(
    val buildId: String,
    val bytes: Long,
    val sha1: String,
    val chunkCount: Int,
)
