package calebxzhou.rdi.client.service.content

import calebxzhou.rdi.client.service.ClientDirs
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.common.model.Task2

internal const val DOWNLOAD_CACHE_CLEANUP_DEDUPE_KEY = "client-download-cache-cleanup"

private fun defaultDownloadCacheCleanupService(): DownloadCacheCleanupService =
    DownloadCacheCleanupService(
        cacheRoot = ClientDirs.dlcDir.toPath(),
        versionsRoot = ClientDirs.versionsDir.toPath(),
    )

fun buildDownloadCacheCleanupTask2(
    service: DownloadCacheCleanupService = defaultDownloadCacheCleanupService(),
): Task2 = Task2.Leaf("清除下载缓存") { context ->
    service.cleanup(context).getOrThrow()
}

fun submitDownloadCacheCleanupTask2(
    service: DownloadCacheCleanupService = defaultDownloadCacheCleanupService(),
): String = ClientTaskManager.submit(
    task = buildDownloadCacheCleanupTask2(service),
    dedupeKey = DOWNLOAD_CACHE_CLEANUP_DEDUPE_KEY,
)
