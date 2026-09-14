package calebxzhou.rdi.client.service.content

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.model.Mod
import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentType
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.validateClientExtra
import calebxzau.rdi.common.model.normalizeClientExtraPath
import calebxzau.rdi.common.model.validateAndMergeClientExtras
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.model.CurseForgeFile
import calebxzhou.rdi.common.net.DownloadProgress
import calebxzhou.rdi.common.net.downloadFileFrom
import calebxzhou.rdi.common.service.ModService
import calebxzhou.rdi.common.service.CurseForgeService
import calebxzhou.rdi.common.util.hardLinkFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale
import kotlin.math.max

private val lgr by Loggers

/**
 * Shared digest coordination for every client content operation.  Downloads
 * and the startup migration deliberately use the same locks and 32-way I/O
 * limits so an old-cache move cannot race a download commit.
 */
internal object ClientContentStoreCoordinator {
    val flightLocks = ConcurrentHashMap<String, Mutex>()
    val progressNetwork = Semaphore(32)
    val progressVerify = Semaphore(32)
    val progressCopy = Semaphore(32)
    private val cacheLeases = ConcurrentHashMap<Path, AtomicInteger>()

    internal class CachePathLease internal constructor(private val path: Path) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            cacheLeases.computeIfPresent(path) { _, count ->
                if (count.decrementAndGet() <= 0) null else count
            }
        }
    }

    fun lease(path: Path): CachePathLease {
        val normalized = path.toAbsolutePath().normalize()
        cacheLeases.compute(normalized) { _, count ->
            (count ?: AtomicInteger()).also { it.incrementAndGet() }
        }
        return CachePathLease(normalized)
    }

    fun isLeased(path: Path): Boolean =
        cacheLeases[path.toAbsolutePath().normalize()]?.get()?.let { it > 0 } == true

    suspend fun <T> withDigestLock(digest: ContentDigest, block: suspend () -> T): T {
        return withDigestLocks(listOf(digest), block)
    }

    suspend fun <T> withDigestLocks(
        digests: Collection<ContentDigest>,
        block: suspend () -> T,
    ): T {
        return withKeys(
            keys = digests
                .map { digest -> "${digest.algorithm.suffix}:${digest.normalizedValue}" },
            block = block,
        )
    }

    suspend fun <T> withKeyLock(key: String, block: suspend () -> T): T =
        withKeys(listOf(key), block)

    private suspend fun <T> withKeys(
        keys: Collection<String>,
        block: suspend () -> T,
    ): T {
        val locks = keys
            .distinct()
            .sorted()
            .map { key -> flightLocks.computeIfAbsent(key) { Mutex() } }

        suspend fun lockAt(index: Int): T {
            if (index == locks.size) return block()
            return locks[index].withLock { lockAt(index + 1) }
        }
        return lockAt(0)
    }
}

enum class ContentDigestAlgorithm(val suffix: String) {
    SHA1("sha1"),
    MURMUR2("murmur2"),
    SHA256("sha256")
}

data class ContentDigest(
    val algorithm: ContentDigestAlgorithm,
    val value: String
) {
    val normalizedValue: String
        get() = value.trim().lowercase(Locale.ROOT)
}

typealias ContentDownloader = suspend (Path, (DownloadProgress) -> Unit) -> Result<Path>

data class ContentSource(
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val knownSize: Long? = null,
    val downloader: ContentDownloader? = null,
    val name: String? = null,
    /** A local downloader is allowed when the request explicitly disables network access. */
    val localOnly: Boolean = false
)

data class ContentRequest(
    val id: String,
    val relativePath: String,
    val size: Long? = null,
    val digests: List<ContentDigest> = emptyList(),
    val sources: List<ContentSource> = emptyList(),
    val allowNetwork: Boolean = true,
    val displayName: String = id,
    /** Set for Mod-backed requests so a batch can prepare CurseForge metadata once. */
    internal val curseForgeMod: Mod? = null
) {
    val targetRelativePath: String
        get() = relativePath
}

private data class ResolvedContent(
    val path: Path,
    val temporary: Boolean,
    val lease: ClientContentStoreCoordinator.CachePathLease? = null,
)

private class ContentResourceCollector {
    private val resources = ConcurrentLinkedQueue<ResolvedContent>()

    fun register(content: ResolvedContent): ResolvedContent {
        resources.add(content)
        return content
    }

    fun close() {
        resources.forEach { content ->
            content.lease?.close()
            if (content.temporary) {
                runCatching { deleteTemporaryPath(content.path) }
                    .onFailure { error -> lgr.warn(error) { "无法清理临时客户端内容: ${content.path}" } }
            }
        }
        resources.clear()
    }

    private fun deleteTemporaryPath(path: Path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(path)
    }
}

private data class ProgressState(
    var completedBytes: Long = 0L,
    var totalBytes: Long? = null,
    var speedBytesPerSecond: Double = 0.0,
    var complete: Boolean = false
)

/**
 * The client-only immutable content cache. The cache names are digest names;
 * callers never need to know how cache entries, temporary files, or aliases are
 * laid out.
 */
open class ClientContentStore(
    private val root: Path,
    private val curseForgeFileInfoFetcher: suspend (List<Int>) -> List<CurseForgeFile> =
        CurseForgeService::getModFilesInfo
) {
    suspend fun <T> use(
        requests: List<ContentRequest>,
        onProgress: (Task2Progress) -> Unit = {},
        block: suspend (Map<String, Path>) -> T
    ): Result<T> {
        val resources = ContentResourceCollector()
        return try {
            val normalized = validateRequests(requests)
            val prepared = prepareBatchSources(normalized)
            val progress = ProgressAggregator(prepared, onProgress)
            val resolved = coroutineScope {
                prepared.map { request ->
                    async {
                        resolve(request, progress, resources)
                    }
                }.awaitAll()
            }
            val paths = prepared.zip(resolved).associate { (request, content) ->
                request.id to content.path
            }
            Result.success(block(paths))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            lgr.error(error) { "客户端内容处理失败" }
            Result.failure(error)
        } finally {
            resources.close()
        }
    }

    /**
     * Reads only already materialized content from the cache. Missing entries
     * are omitted instead of starting downloads or failing the whole batch.
     */
    suspend fun <T> useCached(
        requests: List<ContentRequest>,
        onProgress: (Task2Progress) -> Unit = {},
        block: suspend (Map<String, Path>) -> T,
    ): Result<T> {
        val resources = ContentResourceCollector()
        return try {
            val normalized = validateRequests(requests)
            val progress = ProgressAggregator(normalized, onProgress)
            val hits = coroutineScope {
                normalized.map { request ->
                    async {
                        progress.started(request.id)
                        val verificationStart = System.nanoTime()
                        val hit = findCacheAndLease(request, resources)
                        if (hit != null) {
                            val size = fileSize(hit.first)
                            progress.finished(
                                request.id,
                                size,
                                elapsedSpeed(size, verificationStart),
                                total = size,
                            )
                        } else {
                            progress.finished(
                                request.id,
                                completed = 0L,
                                speed = elapsedSpeed(0L, verificationStart),
                            )
                        }
                        request.id to hit
                    }
                }.awaitAll()
            }.mapNotNull { (id, hit) -> hit?.let { id to it } }.toMap()
            block(hits.mapValues { it.value.first }).let(Result.Companion::success)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            lgr.error(error) { "客户端内容缓存读取失败" }
            Result.failure(error)
        } finally {
            resources.close()
        }
    }

    open suspend fun materialize(
        requests: List<ContentRequest>,
        targetRoot: Path,
        onProgress: (Task2Progress) -> Unit = {}
    ): Result<List<Path>> = try {
        val normalizedRequests = validateRequests(requests)
        // Validate the destination before resolving network content, so a bad
        // target fails without starting any downloads.
        val normalizedRoot = prepareTargetRoot(targetRoot)
        val existingTargets = normalizedRequests.associateWith { request ->
            val target = resolveTarget(normalizedRoot, request.relativePath)
            target.takeIf { targetPath ->
                Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS) &&
                    verify(targetPath, request).isSuccess
            }
        }
        val unresolvedRequests = normalizedRequests.filter { existingTargets.getValue(it) == null }
        if (unresolvedRequests.isEmpty()) {
            Result.success(normalizedRequests.map { existingTargets.getValue(it)!! })
        } else {
            use(unresolvedRequests, onProgress) { paths ->
                normalizedRequests.map { request ->
                    existingTargets.getValue(request)
                        ?: materializeOne(request, paths.getValue(request.id), resolveTarget(normalizedRoot, request.relativePath))
                }
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        lgr.error(error) { "客户端内容落盘失败" }
        Result.failure(error)
    }

    suspend fun materializeClientExtras(
        extras: List<Content>,
        targetRoot: Path,
        onProgress: (Task2Progress) -> Unit = {},
    ): Result<List<Path>> = try {
        val normalizedExtras = extras.validateAndMergeClientExtras()
            .filter { it.side != ContentSide.Server }
        val normalizedRequests = validateRequests(
            normalizedExtras.map { it.toClientContentRequest(it.targetRelativePath) }
        )
        val normalizedRoot = prepareTargetRoot(targetRoot)
        val targetByRequest = normalizedRequests.associateWith { request ->
            resolveTarget(normalizedRoot, request.relativePath)
        }
        val localWinners = normalizedRequests.associateWith { request ->
            val target = targetByRequest.getValue(request)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@associateWith null
            require(!Files.isSymbolicLink(target) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                "客户端额外内容目标不是安全的普通文件: ${request.relativePath}"
            }
            target
        }
        val missing = normalizedRequests.filter { localWinners.getValue(it) == null }
        val materializedMissing: Map<ContentRequest, Path> = if (missing.isEmpty()) emptyMap() else {
            val paths = materialize(missing, normalizedRoot, onProgress).getOrThrow()
            missing.zip(paths).toMap()
        }
        Result.success(normalizedRequests.map { request ->
            localWinners.getValue(request) ?: materializedMissing.getValue(request)
        })
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        lgr.error(error) { "客户端额外内容安装失败" }
        Result.failure(error)
    }

    /** Client task with one visible leaf; individual content files stay internal. */
    fun materializeTask2(
        requests: List<ContentRequest>,
        targetRoot: Path,
        title: String = "下载客户端内容"
    ): Task2 = Task2.Leaf(title) { context ->
        materialize(requests, targetRoot, onProgress = context::emit).getOrThrow()
    }

    /**
     * Shared ingest seam for the startup old-cache migration. The source is
     * moved directly into digest-named cache entries; no copy fallback is
     * allowed. All digest aliases are locked in deterministic order with
     * downloads before collision handling begins.
     */
    internal suspend fun migrateLegacySource(
        source: Path,
        destinations: List<ContentDigest>,
        lockDigests: Collection<ContentDigest> = destinations,
    ): Boolean = withDigestLocks(lockDigests) {
        if (!isRegularFile(source)) return@withDigestLocks false
        withMigrationMovePermit {
            Files.createDirectories(root)
            val canonical = destinations.firstOrNull { it.algorithm == ContentDigestAlgorithm.SHA256 }
            val alias = destinations.firstOrNull { it.algorithm == ContentDigestAlgorithm.SHA1 }
            if (canonical != null && alias != null) {
                migrateGithubSource(source, canonical, alias)
            } else {
                require(destinations.size == 1) { "旧内容摘要目标无效" }
                migrateSingleSource(source, destinations.single())
            }
        }
    }

    private suspend fun migrateSingleSource(source: Path, destination: ContentDigest): Boolean {
        val target = cachePath(destination)
        if (validMigrationDestination(target, destination)) {
            Files.deleteIfExists(source)
            return true
        }
        deleteMigrationPath(target)
        moveMigrationSource(source, target)
        return true
    }

    private suspend fun migrateGithubSource(
        source: Path,
        canonical: ContentDigest,
        alias: ContentDigest,
    ): Boolean {
        val canonicalPath = cachePath(canonical)
        val aliasPath = cachePath(alias)
        if (validMigrationDestination(canonicalPath, canonical)) {
            if (!ensureMigrationAlias(canonicalPath, aliasPath, alias)) return false
            Files.deleteIfExists(source)
            return true
        }

        // Link the alias while the source inode still exists. If either the
        // hard-link or atomic move fails, the source remains available.
        deleteMigrationPath(canonicalPath)
        deleteMigrationPath(aliasPath)
        createMigrationHardLink(aliasPath, source)
        try {
            moveMigrationSource(source, canonicalPath)
        } catch (error: Throwable) {
            deleteMigrationPath(aliasPath)
            throw error
        }
        check(Files.isSameFile(canonicalPath, aliasPath)) { "GitHub摘要别名不是硬链接" }
        return true
    }

    private suspend fun validMigrationDestination(
        path: Path,
        expected: ContentDigest,
    ): Boolean {
        if (!isRegularFile(path)) return false
        return calculateDigestForMigration(path, expected.algorithm) == expected.normalizedValue
    }

    private suspend fun ensureMigrationAlias(
        canonical: Path,
        alias: Path,
        expected: ContentDigest,
    ): Boolean {
        if (Files.exists(alias, LinkOption.NOFOLLOW_LINKS) &&
            validMigrationDestination(alias, expected) &&
            Files.isSameFile(canonical, alias)
        ) return true
        deleteMigrationPath(alias)
        return runCatching {
            createMigrationHardLink(alias, canonical)
            Files.isSameFile(canonical, alias)
        }.onFailure { error ->
            lgr.warn(error) { "无法创建GitHub内容摘要别名: ${alias.fileName}" }
        }.getOrDefault(false)
    }

    private fun deleteMigrationPath(path: Path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(path)
    }

    private fun createMigrationHardLink(alias: Path, source: Path) {
        alias.parent?.let(Files::createDirectories)
        Files.createLink(alias, source)
    }

    private fun moveMigrationSource(source: Path, target: Path) {
        target.parent?.let(Files::createDirectories)
        // ATOMIC_MOVE is required so an old cache hard-link inode is never
        // broken by a cross-volume copy fallback.
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    private suspend fun <T> withMigrationMovePermit(block: suspend () -> T): T =
        ClientContentStoreCoordinator.progressCopy.withPermit { block() }

    /**
     * Prepare source closures after local cache/legacy checks.  This keeps a
     * verified cache hit entirely offline and makes every unresolved CF request
     * share one file-info lookup before the existing 32-way download fan-out.
     */
    private suspend fun prepareBatchSources(requests: List<ContentRequest>): List<ContentRequest> {
        val curseForgeMisses = requests.filter { request ->
            request.allowNetwork &&
                request.curseForgeMod != null &&
                !hasReusableLocalContent(request)
        }
        if (curseForgeMisses.isEmpty()) return requests

        val mods = curseForgeMisses.mapNotNull(ContentRequest::curseForgeMod)
        val fetched = ModService.prepareCurseForgeFileInfos(mods, curseForgeFileInfoFetcher)
        return requests.map { request ->
            val mod = request.curseForgeMod ?: return@map request
            if (request !in curseForgeMisses) return@map request
            val fileInfo = ModService.run {
                mod.trustedCurseForgeFile()
                    ?: fetched[mod.fileId.toIntOrNull()]
                    ?: error("未找到文件信息: ${mod.slug}")
            }
            request.copy(
                sources = request.sources.map { source ->
                    if (source.downloader == null) source else source.copy(
                        downloader = { target, onProgress ->
                            ModService.downloadModToPath(
                                mod = mod,
                                targetPath = target,
                                onProgress = onProgress,
                                preparedCurseForgeFile = fileInfo
                            )
                        }
                    )
                }
            )
        }
    }

    private suspend fun hasReusableLocalContent(request: ContentRequest): Boolean {
        return findCache(request) != null
    }

    /** Request-count seam used by the focused batch regression test. */
    internal suspend fun prepareBatchSourcesForTest(
        requests: List<ContentRequest>
    ): List<ContentRequest> = prepareBatchSources(validateRequests(requests))

    private suspend fun resolve(
        request: ContentRequest,
        progress: ProgressAggregator,
        resources: ContentResourceCollector,
    ): ResolvedContent {
        progress.started(request.id)
        val verificationStart = System.nanoTime()
        val cacheHit = if (request.digests.isEmpty()) {
            null
        } else {
            ClientContentStoreCoordinator.withDigestLocks(request.digests) {
                findCache(request)?.let { path ->
                    resources.register(
                        ResolvedContent(path, temporary = false, lease = ClientContentStoreCoordinator.lease(path))
                    )
                }
            }
        }
        if (cacheHit != null) {
            val cachePath = cacheHit.path
            progress.finished(
                request.id,
                fileSize(cachePath),
                elapsedSpeed(fileSize(cachePath), verificationStart),
                total = fileSize(cachePath)
            )
            return cacheHit
        }

        if (!request.allowNetwork && request.sources.none { it.localOnly && it.downloader != null }) {
            throw IOException("内容缓存不可用且禁止网络下载: ${request.displayName}")
        }
        val resolved = downloadSingleFlight(request, progress, resources)
        progress.finished(request.id, fileSize(resolved.path), 0.0, request.size)
        return resolved
    }

    private suspend fun findCache(request: ContentRequest): Path? {
        if (request.digests.isEmpty()) return null
        for (digest in request.digests) {
            val candidate = cachePath(digest)
            if (!isRegularFile(candidate)) continue
            val valid = verify(candidate, request)
            if (valid.isSuccess) return candidate
            valid.exceptionOrNull()?.let { error ->
                lgr.warn(error) { "删除损坏客户端内容缓存: $candidate" }
            }
            deleteCacheFile(candidate)
        }
        return null
    }

    private suspend fun findCacheAndLease(
        request: ContentRequest,
        resources: ContentResourceCollector,
    ): Pair<Path, ClientContentStoreCoordinator.CachePathLease>? {
        if (request.digests.isEmpty()) return null
        return ClientContentStoreCoordinator.withDigestLocks(request.digests) {
            findCache(request)?.let { path ->
                val lease = ClientContentStoreCoordinator.lease(path)
                resources.register(ResolvedContent(path, temporary = false, lease = lease))
                path to lease
            }
        }
    }

    private suspend fun downloadSingleFlight(
        request: ContentRequest,
        progress: ProgressAggregator,
        resources: ContentResourceCollector,
    ): ResolvedContent {
        val resolveUnderLock: suspend () -> ResolvedContent = resolveUnderLock@{
            findCache(request)?.let {
                return@resolveUnderLock resources.register(ResolvedContent(
                    it,
                    false,
                    ClientContentStoreCoordinator.lease(it),
                ))
            }
            val temporary = createTemporaryFile()
            resources.register(ResolvedContent(temporary, true))
            try {
                download(request, temporary, progress)
                val actual = calculateCommitDigests(temporary, request)
                validateCalculatedDigests(temporary, actual, request)
                val committed = commit(temporary, actual)
                if (committed != null) {
                    resources.register(ResolvedContent(
                        committed,
                        false,
                        ClientContentStoreCoordinator.lease(committed),
                    ))
                } else {
                    lgr.warn { "客户端内容缓存不可写，使用已校验临时文件: ${request.displayName}" }
                    ResolvedContent(temporary, true)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                throw error
            }
        }
        return if (request.digests.isEmpty()) {
            ClientContentStoreCoordinator.withKeyLock("id:${request.id}", resolveUnderLock)
        } else {
            ClientContentStoreCoordinator.withDigestLocks(request.digests, resolveUnderLock)
        }
    }

    private suspend fun download(
        request: ContentRequest,
        temporary: Path,
        progress: ProgressAggregator
    ) {
        val sources = request.sources.filter { request.allowNetwork || it.localOnly }
        if (sources.isEmpty()) {
            throw IOException("没有可用下载来源: ${request.displayName}")
        }
        var lastError: Throwable? = null
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            try {
                ClientContentStoreCoordinator.progressNetwork.withPermit {
                    val callback: (DownloadProgress) -> Unit = { downloadProgress ->
                        val total = downloadProgress.totalBytes.takeIf { it > 0 }
                            ?: source.knownSize?.takeIf { it > 0 }
                            ?: request.size?.takeIf { it > 0 }
                        progress.updated(
                            request.id,
                            downloadProgress.bytesDownloaded.coerceAtLeast(0L),
                            total,
                            downloadProgress.speedBytesPerSecond.coerceAtLeast(0.0)
                        )
                    }
                    val validator: suspend (Path) -> Result<Unit> = { path ->
                        verify(path, request)
                    }
                    when {
                        source.downloader != null -> {
                            val downloaded = source.downloader.invoke(temporary, callback).getOrThrow()
                            if (downloaded.toAbsolutePath().normalize() != temporary.toAbsolutePath().normalize()) {
                                Files.deleteIfExists(temporary)
                                try {
                                    Files.move(downloaded, temporary, StandardCopyOption.REPLACE_EXISTING)
                                } catch (moveError: Throwable) {
                                    Files.copy(downloaded, temporary, StandardCopyOption.REPLACE_EXISTING)
                                }
                            }
                            validator(temporary).getOrThrow()
                        }

                        !source.url.isNullOrBlank() -> {
                            temporary.downloadFileFrom(
                                url = source.url,
                                headers = source.headers,
                                knownSize = source.knownSize ?: request.size ?: 0L,
                                validator = validator,
                                onProgress = callback
                            ).getOrThrow()
                        }

                        else -> error("下载来源缺少URL或下载器: ${request.displayName}")
                    }
                    return
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                lastError = error
                lgr.warn(error) { "内容来源失败，尝试下一个来源: ${source.name ?: source.url}" }
                deleteTemporary(temporary)
            }
        }
        throw lastError ?: IOException("内容下载失败: ${request.displayName}")
    }

    private suspend fun calculateCommitDigests(
        path: Path,
        request: ContentRequest
    ): Map<ContentDigestAlgorithm, String> = withContext(Dispatchers.IO) {
        val algorithms = buildSet {
            add(ContentDigestAlgorithm.SHA1)
            request.digests.forEach { add(it.algorithm) }
        }
        algorithms.associateWith { algorithm -> calculateDigest(path, algorithm) }
    }

    private fun validateCalculatedDigests(
        path: Path,
        actual: Map<ContentDigestAlgorithm, String>,
        request: ContentRequest
    ) {
        request.size?.let { expected ->
            check(Files.size(path) == expected) {
                "内容大小校验失败: ${request.displayName}"
            }
        }
        request.digests.forEach { expected ->
            val actualValue = actual[expected.algorithm]
            check(actualValue.equals(expected.normalizedValue, ignoreCase = true)) {
                "内容${expected.algorithm.suffix}校验失败: ${request.displayName}"
            }
        }
    }

    private suspend fun commit(
        temporary: Path,
        actual: Map<ContentDigestAlgorithm, String>
    ): Path? = try {
        withContext(Dispatchers.IO) {
            Files.createDirectories(root)
            val entries = actual.map { (algorithm, value) ->
                algorithm to root.resolve("${value}.${algorithm.suffix}")
            }
            val primary = entries.firstOrNull { it.first == ContentDigestAlgorithm.SHA256 }
                ?: entries.firstOrNull { it.first == ContentDigestAlgorithm.SHA1 }
                ?: entries.first()
            val primaryPath = primary.second
            if (isRegularFile(primaryPath)) {
                Files.deleteIfExists(temporary)
            } else {
                moveAtomicallyOrReplace(temporary, primaryPath)
            }
            entries.filterNot { it.second == primaryPath }.forEach { (_, alias) ->
                runCatching { createAlias(primaryPath, alias) }
                    .onFailure { error ->
                        lgr.warn(error) { "无法创建客户端内容摘要别名，保留主缓存: $alias" }
                    }
            }
            primaryPath
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        lgr.error(error) { "无法写入客户端内容缓存" }
        null
    }

    private suspend fun materializeOne(request: ContentRequest, source: Path, target: Path): Path {
        ClientContentStoreCoordinator.progressCopy.withPermit {
            checkNoSymlink(target.parent)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target)) {
                    hardLinkFile(source.toFile(), target.toFile()).getOrThrow()
                    return target
                }
                val valid = verify(target, request)
                if (valid.isSuccess) return target
                throw IllegalStateException("目标文件内容不同，拒绝覆盖: $target")
            }
            Files.createDirectories(target.parent)
            try {
                Files.createLink(target, source)
            } catch (linkError: Throwable) {
                lgr.debug(linkError) { "硬链接失败，复制客户端内容: $target" }
                try {
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
                    verify(target, request).getOrThrow()
                } catch (copyError: Throwable) {
                    Files.deleteIfExists(target)
                    throw copyError
                }
            }
            return target
        }
    }

    private fun validateRequests(requests: List<ContentRequest>): List<ContentRequest> {
        val ids = HashSet<String>()
        val paths = HashMap<String, ContentRequest>()
        return requests.map { request ->
            require(request.id.isNotBlank()) { "内容id不能为空" }
            require(ids.add(request.id)) { "内容id重复: ${request.id}" }
            val normalizedPath = normalizeRelativePath(request.relativePath)
            val old = paths[normalizedPath]
            if (old != null) {
                require(sameContentIdentity(old, request)) {
                    "同一目标路径对应不同内容: $normalizedPath"
                }
            }
            val normalized = request.copy(relativePath = normalizedPath)
            paths[normalizedPath] = normalized
            validateDigests(normalized)
            normalized
        }
    }

    private fun sameContentIdentity(first: ContentRequest, second: ContentRequest): Boolean =
        first.digests.map { it.algorithm to it.normalizedValue }.toSet() ==
            second.digests.map { it.algorithm to it.normalizedValue }.toSet() &&
            first.size == second.size

    private fun validateDigests(request: ContentRequest) {
        require(request.size == null || request.size >= 0) { "内容大小不能为负数" }
        require(request.digests.distinctBy(ContentDigest::algorithm).size == request.digests.size) {
            "同一内容摘要算法重复: ${request.id}"
        }
        request.digests.forEach { digest ->
            val value = digest.normalizedValue
            when (digest.algorithm) {
                ContentDigestAlgorithm.SHA1 -> require(value.matches(Regex("[0-9a-f]{40}")))
                ContentDigestAlgorithm.SHA256 -> require(value.matches(Regex("[0-9a-f]{64}")))
                ContentDigestAlgorithm.MURMUR2 -> require(value.toULongOrNull()?.let { it <= UInt.MAX_VALUE.toULong() } == true)
            }
        }
    }

    private fun normalizeRelativePath(raw: String): String {
        val path = raw.replace('\\', '/')
        require(path.isNotBlank()) { "内容目标路径不能为空" }
        val parsed = Paths.get(path)
        require(!parsed.isAbsolute) { "内容目标路径不能是绝对路径: $raw" }
        val segments = path.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "内容目标路径包含非法段: $raw"
        }
        val normalized = parsed.normalize().toString().replace('\\', '/')
        require(normalized.isNotBlank() && normalized != "." && !normalized.startsWith("../")) {
            "内容目标路径越界: $raw"
        }
        return normalized
    }

    private fun prepareTargetRoot(targetRoot: Path): Path {
        val normalized = targetRoot.toAbsolutePath().normalize()
        checkNoSymlink(normalized)
        Files.createDirectories(targetRoot)
        checkNoSymlink(normalized)
        return normalized
    }

    private fun resolveTarget(root: Path, relativePath: String): Path {
        val target = root.resolve(relativePath).normalize()
        require(target.startsWith(root)) { "内容目标路径越界: $relativePath" }
        checkNoSymlink(target.parent)
        return target
    }

    private fun checkNoSymlink(path: Path?) {
        if (path == null) return
        var current = path.toAbsolutePath().normalize()
        val rootPath = current.root ?: return
        val parts = rootPath.relativize(current)
        current = rootPath
        for (part in parts) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "内容路径不能穿过符号链接: $current" }
        }
    }

    private suspend fun verify(path: Path, request: ContentRequest): Result<Unit> =
        ClientContentStoreCoordinator.progressVerify.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    if (!isRegularFile(path)) {
                        Result.failure(IOException("内容文件不存在: $path"))
                    } else {
                        request.size?.let { expected ->
                            if (Files.size(path) != expected) {
                                return@withContext Result.failure(IOException("内容大小不匹配: $path"))
                            }
                        }
                        request.digests.forEach { expected ->
                            val actual = calculateDigest(path, expected.algorithm)
                            check(actual == expected.normalizedValue) {
                                "内容${expected.algorithm.suffix}不匹配: $path"
                            }
                        }
                        Result.success(Unit)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
        }

    private fun cachePath(digest: ContentDigest): Path =
        root.resolve("${digest.normalizedValue}.${digest.algorithm.suffix}")

    private suspend fun calculateDigest(path: Path, algorithm: ContentDigestAlgorithm): String = when (algorithm) {
        ContentDigestAlgorithm.SHA1 -> digest(path, "SHA-1")
        ContentDigestAlgorithm.SHA256 -> digest(path, "SHA-256")
        ContentDigestAlgorithm.MURMUR2 -> murmur2(path)
    }

    /** Digest implementation shared by the old-cache migrator. */
    internal suspend fun calculateDigestForMigration(
        path: Path,
        algorithm: ContentDigestAlgorithm,
    ): String = ClientContentStoreCoordinator.progressVerify.withPermit {
        calculateDigest(path, algorithm)
    }

    /** Digest-level lock shared by the old-cache migrator and downloads. */
    internal suspend fun <T> withDigestLock(
        digest: ContentDigest,
        block: suspend () -> T,
    ): T = ClientContentStoreCoordinator.withDigestLock(digest, block)

    /** All aliases for one request share one deterministic lock order. */
    internal suspend fun <T> withDigestLocks(
        digests: Collection<ContentDigest>,
        block: suspend () -> T,
    ): T = ClientContentStoreCoordinator.withDigestLocks(digests, block)

    private suspend fun digest(path: Path, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun murmur2(path: Path): String {
        val normalizedLength = countNormalizedBytes(path)
        if (normalizedLength == 0u) return "0"

        val multiplex = 1540483477u
        var hash = 1u xor normalizedLength
        var block = 0u
        var shift = 0
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                for (index in 0 until read) {
                    if (buffer[index].isContentWhitespace()) continue
                    val value = (buffer[index].toInt() and 0xFF).toUInt()
                    block = block or (value shl shift)
                    shift += 8
                    if (shift == 32) {
                        val mixed = block * multiplex
                        val remixed = (mixed xor (mixed shr 24)) * multiplex
                        hash = hash * multiplex xor remixed
                        block = 0u
                        shift = 0
                    }
                }
            }
        }
        if (shift > 0) hash = (hash xor block) * multiplex
        var remixed = (hash xor (hash shr 13)) * multiplex
        remixed = remixed xor (remixed shr 15)
        return remixed.toLong().toULong().toString()
    }

    private suspend fun countNormalizedBytes(path: Path): UInt {
        var count = 0u
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                for (index in 0 until read) {
                    if (!buffer[index].isContentWhitespace()) count += 1u
                }
            }
        }
        return count
    }

    private fun Byte.isContentWhitespace(): Boolean = when (toInt() and 0xFF) {
        9, 10, 13, 32 -> true
        else -> false
    }

    private fun isRegularFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)

    private fun fileSize(path: Path): Long =
        runCatching { Files.size(path) }.getOrDefault(0L)

    private fun elapsedSpeed(bytes: Long, startedNanos: Long): Double {
        val elapsed = max(1L, System.nanoTime() - startedNanos) / 1_000_000_000.0
        return bytes / elapsed
    }

    private fun createTemporaryFile(): Path {
        return try {
            Files.createDirectories(root.resolve(".tmp"))
            Files.createTempFile(root.resolve(".tmp"), "content-", ".tmp")
        } catch (error: Throwable) {
            lgr.error(error) { "客户端内容缓存不可写，回退系统临时目录" }
            Files.createTempFile("rdi-content-", ".tmp")
        }
    }

    private fun moveAtomicallyOrReplace(source: Path, target: Path) {
        target.parent?.let { Files.createDirectories(it) }
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun createAlias(source: Path, alias: Path) {
        if (source == alias) return
        alias.parent?.let { Files.createDirectories(it) }
        if (isRegularFile(alias)) {
            Files.deleteIfExists(alias)
        }
        try {
            Files.createLink(alias, source)
        } catch (linkError: Throwable) {
            lgr.debug(linkError) { "别名硬链接失败，复制摘要缓存: $alias" }
            Files.copy(source, alias, StandardCopyOption.REPLACE_EXISTING)
            check(isRegularFile(alias)) { "无法创建内容缓存别名: $alias" }
        }
    }

    private fun deleteCacheFile(path: Path) {
        runCatching { Files.deleteIfExists(path) }
            .onFailure { error -> lgr.warn(error) { "无法删除损坏内容缓存: $path" } }
    }

    private fun deleteTemporary(path: Path) {
        runCatching { Files.deleteIfExists(path) }
            .onFailure { error -> lgr.warn(error) { "无法清理内容临时文件: $path" } }
    }
}

private class ProgressAggregator(
    requests: List<ContentRequest>,
    private val emit: (Task2Progress) -> Unit
) {
    private val lock = Any()
    private val states = requests.associate { it.id to ProgressState() }.toMutableMap()
    private val names = requests.associate { it.id to it.displayName }

    fun started(id: String) = synchronized(lock) {
        states.getValue(id).complete = false
        emitSnapshot(id)
    }

    fun updated(id: String, completed: Long, total: Long?, speed: Double) = synchronized(lock) {
        val state = states.getValue(id)
        state.completedBytes = completed.coerceAtLeast(0L)
        state.totalBytes = total?.takeIf { it > 0 }
        state.speedBytesPerSecond = speed.coerceAtLeast(0.0)
        emitSnapshot(id)
    }

    fun finished(id: String, completed: Long, speed: Double, total: Long? = null) = synchronized(lock) {
        val state = states.getValue(id)
        state.completedBytes = completed.coerceAtLeast(0L)
        if (state.totalBytes == null) {
            state.totalBytes = total?.takeIf { it > 0 }
        } else if (state.totalBytes!! < state.completedBytes) {
            state.totalBytes = state.completedBytes
        }
        state.speedBytesPerSecond = speed.coerceAtLeast(0.0)
        state.complete = true
        emitSnapshot(id)
        state.speedBytesPerSecond = 0.0
    }

    private fun emitSnapshot(currentId: String) {
        val values = states.values
        val totalKnown = values.all { it.totalBytes != null }
        val totalBytes = values.sumOf { it.totalBytes ?: 0L }.takeIf { totalKnown && it > 0 }
        val completedBytes = values.sumOf { it.completedBytes }.takeIf { it > 0 }
        val fraction = if (totalBytes != null) {
            (completedBytes ?: 0L).toDouble().div(totalBytes).toFloat().coerceIn(0f, 1f)
        } else null
        val completedItems = values.count { it.complete }
        emit(
            Task2Progress(
                message = names.getValue(currentId),
                fraction = fraction,
                completedBytes = completedBytes,
                totalBytes = totalBytes,
                bytesPerSecond = values.sumOf { it.speedBytesPerSecond }.takeIf { it > 0.0 },
                completedItems = completedItems,
                totalItems = values.size
            )
        )
    }
}

fun Mod.toClientContentRequest(
    targetRelativePath: String = fileName,
    size: Long? = null,
): ContentRequest = ContentRequest(
    id = "${platform}:${projectId}:${fileId}:${hash}",
    relativePath = targetRelativePath,
    size = size,
    digests = listOf(
        when (platform.lowercase()) {
            "cf" -> ContentDigest(ContentDigestAlgorithm.MURMUR2, hash)
            "mr" -> ContentDigest(ContentDigestAlgorithm.SHA1, hash)
            "github" -> if (ModService.githubUsesSha1(hash)) {
                ContentDigest(ContentDigestAlgorithm.SHA1, hash)
            } else {
                ContentDigest(ContentDigestAlgorithm.SHA256, hash)
            }
            else -> ContentDigest(ContentDigestAlgorithm.SHA1, hash)
        }
    ),
    sources = listOf(
        ContentSource(
            downloader = { target, onProgress ->
                ModService.downloadModToPath(this@toClientContentRequest, target, onProgress)
            },
            name = "${platform}:${slug}"
        )
    ),
    displayName = slug,
    curseForgeMod = takeIf { platform.equals("cf", ignoreCase = true) }
)

/** Builds the shared cache request for a published client-side resource or shader pack. */
fun Content.toClientContentRequest(
    targetRelativePath: String = this.targetRelativePath,
    size: Long? = null,
): ContentRequest {
    val validated = copy(path = targetRelativePath).validateClientExtra()
    val normalizedPath = normalizeClientExtraPath(validated.targetRelativePath)
    val digest = when (platform.name.lowercase()) {
        "curseforge" -> ContentDigest(ContentDigestAlgorithm.MURMUR2, hash)
        "modrinth" -> ContentDigest(ContentDigestAlgorithm.SHA1, hash)
        "github" -> if (ModService.githubUsesSha1(hash)) {
            ContentDigest(ContentDigestAlgorithm.SHA1, hash)
        } else {
            ContentDigest(ContentDigestAlgorithm.SHA256, hash)
        }
        else -> ContentDigest(ContentDigestAlgorithm.SHA1, hash)
    }
    val urls = validated.downloadUrls
    val fallbackMod = Mod(
        platform = when (platform.name.lowercase()) {
            "curseforge" -> "cf"
            "modrinth" -> "mr"
            else -> "github"
        },
        projectId = projectId,
        slug = slug,
        fileId = fileId,
        hash = hash,
        downloadUrls = urls,
    )
    return ContentRequest(
        id = "${platform}:${projectId}:${fileId}:${hash}:$normalizedPath",
        relativePath = normalizedPath,
        size = size,
        digests = listOf(digest),
        sources = if (urls.isNotEmpty()) urls.map { url ->
            ContentSource(url = url, headers = if (platform.name.equals("CurseForge", true)) CurseForgeService.downloadHeadersFor(url) else emptyMap(), name = "${platform.name}:${slug}")
        } else listOf(ContentSource(downloader = { target, onProgress -> ModService.downloadModToPath(fallbackMod, target, onProgress) }, name = "${platform.name}:${slug}")),
        displayName = slug,
    )
}

fun List<Mod>.toClientContentRequests(
    targetRelativePath: (Mod) -> String = { it.fileName },
): List<ContentRequest> = map { mod ->
    mod.toClientContentRequest(targetRelativePath(mod))
}
