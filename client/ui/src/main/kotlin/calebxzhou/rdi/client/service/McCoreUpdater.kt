package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.client.service.content.ContentDigest
import calebxzhou.rdi.client.service.content.ContentDigestAlgorithm
import calebxzhou.rdi.client.service.content.ContentRequest
import calebxzhou.rdi.client.service.content.ContentSource
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.util.sha1
import io.ktor.http.HttpHeaders
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

data class McCoreUpdateResult(
    val updated: Boolean,
    internal val contentRequest: ContentRequest? = null,
)

object McCoreUpdater {
    fun slug(mcVersion: McVersion, modLoader: ModLoader) =
        "${mcVersion.mcVer}-${modLoader.directorySlug}"

    internal fun contentRequest(
        mcVersion: McVersion,
        modLoader: ModLoader,
        expectedSha1: String,
    ): ContentRequest {
        val slug = slug(mcVersion, modLoader)
        val fixedFileName = "rdi-5-mc-client-$slug.jar"
        return ContentRequest(
            id = "rdi-mc-core:$slug",
            relativePath = fixedFileName,
            digests = listOf(
                ContentDigest(ContentDigestAlgorithm.SHA1, expectedSha1.trim().lowercase())
            ),
            sources = listOf(
                ContentSource(
                    url = "${server.hqUrl}/update/mc/$slug",
                    headers = mapOf(
                        HttpHeaders.Authorization to "Bearer ${loggedAccount.jwt.orEmpty()}"
                    ),
                    name = "rdi-core:$slug"
                )
            ),
            displayName = fixedFileName,
        )
    }

    suspend fun update(
        mcVersion: McVersion,
        modLoader: ModLoader,
        onStatus: (String) -> Unit,
        onDetail: (String) -> Unit
    ): Result<McCoreUpdateResult> = runCatching {
        val slug = slug(mcVersion, modLoader)
        onStatus("检查RDI核心版本...")
        val expectedSha1 = server.makeRequest<String>("update/mc/$slug/hash").data
            ?.trim()
            ?.takeIf { it.matches(Regex("^[0-9a-fA-F]{40}$")) }
            ?: throw RequestError("获取MC核心版本信息失败: $slug")
        onStatus("准备下载rdi-5-mc-client-$slug.jar...")
        // The request is resolved by materializeCore, where the resulting
        // path is consumed before ClientContentStore.use can clean a
        // non-cacheable temporary source.
        McCoreUpdateResult(
            updated = true,
            contentRequest = contentRequest(mcVersion, modLoader, expectedSha1),
        )
    }

    /**
     * Resolves a core through the content cache and atomically installs the
     * fixed filename in one Minecraft instance's mods directory.
     */
    internal suspend fun materializeCore(
        update: McCoreUpdateResult,
        modsDir: File,
        onDetail: (String) -> Unit,
        onProgress: (Task2Progress) -> Unit = {},
        contentStore: ClientContentStore = ClientContentStores.shared,
    ): Result<Boolean> = runCatching {
        val request = update.contentRequest ?: error("RDI核心内容请求缺失")
        val expectedSha1 = request.digests
            .firstOrNull { it.algorithm == ContentDigestAlgorithm.SHA1 }
            ?.normalizedValue
            ?: error("RDI核心SHA-1缺失")
        val target = modsDir.toPath().resolve(request.relativePath)
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) &&
            target.toFile().sha1.equals(expectedSha1, ignoreCase = true)
        ) {
            return@runCatching false
        }

        contentStore.use(
            requests = listOf(request),
            onProgress = { progress ->
                onProgress(progress)
            }
        ) { paths ->
            replaceInstalledCore(
                source = paths.getValue(request.id),
                target = target,
                expectedSha1 = expectedSha1,
            ).getOrThrow()
        }.getOrThrow()
        onDetail("${target.fileName}已同步")
        true
    }

    internal fun replaceInstalledCore(
        source: java.nio.file.Path,
        target: java.nio.file.Path,
        expectedSha1: String,
    ): Result<Unit> = runCatching {
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "核心源文件不存在: $source" }
        check(source.toFile().sha1.equals(expectedSha1, ignoreCase = true)) { "核心源文件校验失败" }
        //check(!Files.isSymbolicLink(target)) { "核心目标不能是符号链接: $target" }
        val parent = target.toAbsolutePath().normalize().parent ?: error("核心目标目录缺失: $target")
        Files.createDirectories(parent)
        val staging = Files.createTempFile(parent, "${target.fileName}.install-", ".tmp")
        try {
            Files.deleteIfExists(staging)
            try {
                Files.createLink(staging, source)
            } catch (_: Throwable) {
                Files.copy(source, staging, StandardCopyOption.COPY_ATTRIBUTES)
            }
            check(staging.toFile().sha1.equals(expectedSha1, ignoreCase = true)) {
                "核心暂存文件校验失败"
            }
            try {
                Files.move(
                    staging,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
            }
            check(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { "核心目标文件不存在" }
            check(target.toFile().sha1.equals(expectedSha1, ignoreCase = true)) {
                "核心文件替换后校验失败"
            }
        } finally {
            Files.deleteIfExists(staging)
        }
    }
}
