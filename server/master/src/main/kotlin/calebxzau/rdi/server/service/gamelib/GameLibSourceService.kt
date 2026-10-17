package calebxzau.rdi.server.service.gamelib

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.net.GameLibMaven
import calebxzhou.rdi.common.net.downloadFailureSummary
import calebxzhou.rdi.common.net.downloadFileFrom
import calebxzhou.rdi.common.util.sha1
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

fun interface GameLibFetcher {
    /** Downloads one of [urls] to [target]; when [sha1] is given the file must match it. */
    suspend fun fetch(urls: List<String>, target: Path, sha1: String?): Result<Path>
}

private val logger = KotlinLogging.logger {}

/**
 * Last-resort source for Maven-layout game libraries. Serves from its own cache, then from
 * libraries already installed for hosts, and otherwise fetches upstream once and caches it.
 */
class GameLibSourceService(
    private val cacheDir: File,
    private val installedLibsRoot: File,
    private val useMirror: () -> Boolean,
    private val fetcher: GameLibFetcher = GameLibFetcher(::fetchFromUpstream),
    // Fetches outlive the request, so a player whose download timed out finds the file cached on retry.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val inflight = ConcurrentHashMap<String, Deferred<Result<File>>>()

    suspend fun resolve(path: String, sha1: String?): Result<File> {
        if (!GameLibMaven.isValidLibraryPath(path)) return Result.failure(RequestError("不支持的运行库路径"))
        val expectedSha1 = sha1?.trim()?.takeIf(String::isNotEmpty)?.lowercase()
        if (expectedSha1 != null && !GameLibMaven.isValidSha1(expectedSha1)) {
            return Result.failure(RequestError("运行库校验值格式错误"))
        }
        val key = "${path}|${expectedSha1.orEmpty()}"
        val created = scope.async(start = CoroutineStart.LAZY) { load(path, expectedSha1) }
        val job = inflight.putIfAbsent(key, created)
        if (job != null) {
            created.cancel()
            return job.await()
        }
        created.invokeOnCompletion { inflight.remove(key, created) }
        created.start()
        return created.await()
    }

    internal fun upstreamUrls(path: String): List<String> {
        val official = GameLibMaven.officialUrlsFor(path)
        return if (useMirror()) listOf("${GameLibMaven.BMCLAPI_BASE}/${path}") + official else official
    }

    private suspend fun load(path: String, sha1: String?): Result<File> = runCatching {
        val cached = cacheDir.resolve(path)
        if (cached.matches(sha1)) return@runCatching cached
        installedCopy(path, sha1)?.let { installed ->
            copyIntoCache(installed, cached)
            return@runCatching cached
        }
        val urls = upstreamUrls(path)
        Files.deleteIfExists(cached.toPath())
        cached.parentFile.mkdirs()
        fetcher.fetch(urls, cached.toPath(), sha1).getOrElse { error ->
            if (error is CancellationException) throw error
            logger.warn(error) { "运行库回源失败: ${path}：${error.downloadFailureSummary()}" }
            throw RequestError("RDI服务器暂时无法提供该运行库")
        }
        check(cached.matches(sha1)) { "运行库回源后校验失败: $path" }
        logger.info { "运行库已缓存: $path ${cached.length()}字节" }
        cached
    }

    private fun installedCopy(path: String, sha1: String?): File? =
        installedLibsRoot.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .map { it.resolve("libraries").resolve(path) }
            .firstOrNull { it.matches(sha1) }

    private fun copyIntoCache(source: File, target: File) {
        target.parentFile.mkdirs()
        val temp = Files.createTempFile(target.parentFile.toPath(), target.name, ".part")
        try {
            Files.copy(source.toPath(), temp, StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun File.matches(sha1: String?): Boolean {
        if (!isFile || length() <= 0L) return false
        return sha1 == null || this.sha1.equals(sha1, ignoreCase = true)
    }
}

private suspend fun fetchFromUpstream(urls: List<String>, target: Path, sha1: String?): Result<Path> =
    target.downloadFileFrom(
        urls = urls,
        validator = { downloaded ->
            if (sha1 == null || downloaded.sha1.equals(sha1, ignoreCase = true)) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("运行库SHA1不符"))
            }
        },
    ) {}
