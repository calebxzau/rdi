package calebxzhou.rdi.client.service.content

import calebxzau.rdi.client.packproc.EmbeddedClientExtraSource
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.common.util.sha1
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private fun EmbeddedClientExtraSource.toContentRequest(): ContentRequest {
    val stagedPath = stagedFile.toPath()
    val size = stagedFile.length()
    val request = content.toClientContentRequest(
        targetRelativePath = content.targetRelativePath,
        size = size,
    )
    val digests = if (request.digests.any { it.algorithm == ContentDigestAlgorithm.SHA1 }) {
        request.digests
    } else {
        request.digests + ContentDigest(ContentDigestAlgorithm.SHA1, verifiedSha1)
    }
    return request.copy(
        digests = digests,
        allowNetwork = false,
        sources = listOf(
            ContentSource(
                knownSize = size,
                name = sourceRelativePath,
                localOnly = true,
                downloader = { target, _ ->
                    runCatching {
                        check(Files.isRegularFile(stagedPath)) {
                            "暂存客户端额外内容不存在: $stagedPath"
                        }
                        check(stagedPath.toFile().sha1.equals(verifiedSha1, ignoreCase = true)) {
                            "客户端额外内容在缓存提交前发生变化: $sourceRelativePath"
                        }
                        Files.copy(stagedPath, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                },
            ),
        ),
    )
}

/** Imports matched local resource/shader pack bytes into the shared cache. */
suspend fun commitEmbeddedClientExtraSources(
    sources: List<EmbeddedClientExtraSource>,
): Result<Unit> {
    if (sources.isEmpty()) return Result.success(Unit)
    return try {
        sources.forEach { source ->
            check(Files.isRegularFile(source.stagedFile.toPath())) {
                "暂存客户端额外内容不存在: ${source.stagedFile}"
            }
            check(source.stagedFile.sha1.equals(source.verifiedSha1, ignoreCase = true)) {
                "客户端额外内容在缓存提交前发生变化: ${source.sourceRelativePath}"
            }
        }
        ClientContentStores.shared.use(
            requests = sources.map(EmbeddedClientExtraSource::toContentRequest),
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
