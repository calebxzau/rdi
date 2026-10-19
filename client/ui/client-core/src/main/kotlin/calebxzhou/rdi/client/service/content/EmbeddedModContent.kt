package calebxzhou.rdi.client.service.content

import calebxzau.rdi.client.service.ClientContentStores

import calebxzau.rdi.client.packproc.EmbeddedModSource
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private fun EmbeddedModSource.toContentRequest(): ContentRequest {
    val stagedPath = stagedFile.toPath()
    val size = stagedFile.length()
    return mod.toClientContentRequest(
        size = size,
    ).copy(
        allowNetwork = false,
        sources = listOf(
            ContentSource(
                knownSize = size,
                name = originalFileName,
                localOnly = true,
                downloader = { target, _ ->
                    runCatching {
                        check(Files.isRegularFile(stagedPath)) {
                            "暂存内嵌mod不存在: $stagedPath"
                        }
                        Files.copy(stagedPath, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            )
        )
    )
}

suspend fun commitEmbeddedModSources(sources: List<EmbeddedModSource>): Result<Unit> {
    if (sources.isEmpty()) return Result.success(Unit)
    return try {
        ClientContentStores.shared.use(
            requests = sources.map(EmbeddedModSource::toContentRequest)
        ) { }
    } finally {
        sources.map { it.stagedFile }.distinct().forEach { staged ->
            runCatching {
                Files.deleteIfExists(staged.toPath())
                staged.parentFile?.takeIf { it.isDirectory && it.listFiles().isNullOrEmpty() }?.delete()
            }
        }
    }
}
