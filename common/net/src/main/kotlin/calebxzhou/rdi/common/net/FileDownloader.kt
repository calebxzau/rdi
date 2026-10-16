package calebxzhou.rdi.common.net

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.serdesJson
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * calebxzhou @ 2025-12-20 11:49
 * Simplified @ 2026-02-12
 */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Double,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) -1f else (bytesDownloaded / totalBytes.toFloat()).coerceAtMost(1f)
}

private val lgr by Loggers
private const val DEFAULT_DOWNLOAD_ATTEMPTS = 5
private const val MAX_CONCURRENT_DOWNLOADS = 64
private const val HTTP_MAX_REQUESTS = 512
private const val HTTP_MAX_REQUESTS_PER_HOST = 512
private const val HTTP_CONNECTION_POOL_SIZE = 512
private const val MIN_MULTI_PART_DOWNLOAD_BYTES = 4L * 1024 * 1024
private const val WORK_QUEUE_CHUNK_SIZE = 1L * 1024 * 1024  // 1MB per chunk
private const val WORK_QUEUE_WORKERS = 16
private const val READ_TIMEOUT_MILLIS = 60_000L
private const val STALL_RETRY_TIMEOUT_MULTIPLIER = 1.5
private const val ZERO_READ_BACKOFF_MILLIS = 100L
private const val MAX_CONSECUTIVE_ZERO_READS = 100
private const val RETRY_BACKOFF_BASE_MILLIS = 500L
private const val RETRY_BACKOFF_MAX_MILLIS = 10_000L
private const val RETRY_BACKOFF_JITTER_MILLIS = 250L
private const val SOURCE_INITIAL_SPEED_BYTES_PER_SECOND = 1.0 * 1024 * 1024
private const val SOURCE_EWMA_WEIGHT = 0.3
private const val SOURCE_ACTIVE_CHUNK_PENALTY = 0.75
private const val SOURCE_FAILURE_PENALTY = 2.0
private const val SOURCE_FAILURE_COOLDOWN_MILLIS = 5_000L
private const val SOURCE_SPEED_CACHE_TTL_MILLIS = 10 * 60_000L
private const val SOURCE_URL_FAILURE_COOLDOWN_MILLIS = 30_000L
private const val SOURCE_HOST_FAILURE_COOLDOWN_MILLIS = 30_000L
private const val SOURCE_HOST_FAILURE_COOLDOWN_MAX_MILLIS = 5 * 60_000L
// Small files mostly measure latency, not throughput; recording them would make
// a healthy host look slower than an untested one.
private const val SOURCE_SPEED_MIN_SAMPLE_BYTES = 512L * 1024
private const val BMCL_HOST = "bmclapi2.bangbang93.com"
private const val BMCL_MAX_CONCURRENT_REQUESTS = 8
private const val DEFAULT_HOST_MAX_CONCURRENT_REQUESTS = 24
private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
private val targetDownloadMutexes = ConcurrentHashMap<String, TargetDownloadLockEntry>()
private val hostSemaphores = ConcurrentHashMap<String, Semaphore>()
private val hostCooldowns = ConcurrentHashMap<String, HostCooldown>()
private val sourceSpeedCache = ConcurrentHashMap<String, SourceSpeedCacheEntry>()
private val sourceUrlCooldowns = ConcurrentHashMap<String, Long>()
private val sourceHostFailures = ConcurrentHashMap<String, SourceHostFailure>()

private class TargetDownloadLockEntry {
    val mutex = Mutex()
    val users = AtomicInteger(0)
}

val httpFileClient by lazy {
    HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                followRedirects(true)
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(60, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
                dispatcher(Dispatcher().apply {
                    maxRequests = HTTP_MAX_REQUESTS
                    maxRequestsPerHost = HTTP_MAX_REQUESTS_PER_HOST
                })
                connectionPool(ConnectionPool(HTTP_CONNECTION_POOL_SIZE, 5, TimeUnit.MINUTES))
                proxySelector(DynamicProxySelector())
                configureDebugRequestLogging()
                configureDebugTlsForSelfSigned()
            }
        }
        BrowserUserAgent()
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }
}

suspend fun Path.downloadFileFrom(
    url: String,
    headers: Map<String, String> = emptyMap(),
    knownSize: Long = 0L,
    maxAttempts: Int = DEFAULT_DOWNLOAD_ATTEMPTS,
    urlHeadersProvider: (String) -> Map<String, String> = { emptyMap() },
    validator: suspend (Path) -> Result<Unit> = { Result.success(Unit) },
    onProgress: (DownloadProgress) -> Unit
): Result<Path> = downloadFileFrom(
    urls = listOf(url),
    headers = headers,
    knownSize = knownSize,
    maxAttempts = maxAttempts,
    urlHeadersProvider = urlHeadersProvider,
    validator = validator,
    onProgress = onProgress
)

suspend fun Path.downloadFileFrom(
    urls: List<String>,
    headers: Map<String, String> = emptyMap(),
    knownSize: Long = 0L,
    maxAttempts: Int = DEFAULT_DOWNLOAD_ATTEMPTS,
    urlHeadersProvider: (String) -> Map<String, String> = { emptyMap() },
    validator: suspend (Path) -> Result<Unit> = { Result.success(Unit) },
    onProgress: (DownloadProgress) -> Unit
): Result<Path> {
    val normalizedUrls = normalizeDownloadUrls(urls)

    lgr.info {
        "Start download file: urls=${normalizedUrls.joinToString()} -> $this"
    }
    if (normalizedUrls.isEmpty()) {
        return Result.failure(IllegalArgumentException("下载链接为空 无法下载到${this}"))
    }

    val targetPath = this

    return withTargetDownloadLock(targetPath) {
        // Ensure parent dir exists
        targetPath.parent?.let { parent ->
            withContext(Dispatchers.IO) {
                if (!Files.exists(parent)) Files.createDirectories(parent)
            }
        }

        try {
            var lastError: Throwable? = null
            val totalAttempts = maxAttempts.coerceAtLeast(1)
            val tempPath = targetPath.createTempDownloadPath()
            // A deterministic temp name has no durable identity. Never append bytes
            // left by an earlier invocation or process.
            deleteQuietly(tempPath)
            val resumeState = DownloadResumeState()
            var rangeDisabledForInvocation = false
            repeat(totalAttempts) { attemptIndex ->
                val attemptNumber = attemptIndex + 1
                val readTimeoutMillis = attemptReadTimeoutMillis(attemptNumber)
                var usedMultiRange = false
                try {
                    val existingTempBytes = fileSizeOrZero(tempPath)
                    val strategy = resolveDownloadStrategy(
                        urls = normalizedUrls,
                        headers = headers,
                        urlHeadersProvider = urlHeadersProvider,
                        knownSize = knownSize,
                        existingTempBytes = existingTempBytes,
                        allowMultiRange = !rangeDisabledForInvocation,
                        readTimeoutMillis = readTimeoutMillis,
                    )
                    when (strategy) {
                        is DownloadStrategy.Single -> {
                            downloadSingleStreamFromSources(
                                sources = strategy.sources,
                                targetPath = tempPath,
                                onProgress = onProgress,
                                knownSize = strategy.totalBytesHint,
                                validator = validator,
                                resumeState = resumeState,
                                readTimeoutMillis = readTimeoutMillis,
                            )
                        }

                        is DownloadStrategy.MultiRange -> {
                            usedMultiRange = true
                            downloadByRanges(
                                sources = strategy.sources,
                                targetPath = tempPath,
                                totalBytes = strategy.totalBytes,
                                chunks = strategy.chunks,
                                onProgress = onProgress,
                                maxChunkAttempts = totalAttempts,
                            )
                        }
                    }
                    try {
                        if (strategy is DownloadStrategy.MultiRange) {
                            validateDownloadedFile(tempPath, validator)
                        }
                    } catch (validation: DownloadValidationException) {
                        if (!usedMultiRange) throw validation
                        // A mixed range result is not reusable after validation failure.
                        rangeDisabledForInvocation = true
                        deleteQuietly(tempPath)
                        resumeState.clear()
                        val multiRangeStrategy = strategy as DownloadStrategy.MultiRange
                        downloadSingleStreamFromSources(
                            sources = multiRangeStrategy.sequentialSources,
                            targetPath = tempPath,
                            onProgress = onProgress,
                            knownSize = multiRangeStrategy.totalBytes,
                            validator = validator,
                            resumeState = resumeState,
                            readTimeoutMillis = readTimeoutMillis,
                        )
                    }
                    moveDownloadedFile(tempPath, targetPath)
                    return@withTargetDownloadLock Result.success(targetPath)
                } catch (cancel: CancellationException) {
                    deleteQuietly(tempPath)
                    throw cancel
                } catch (t: Throwable) {
                    lastError = t
                    if (usedMultiRange || t is ResumeMismatchException || !t.isRetryableDownloadFailure()) {
                        deleteQuietly(tempPath)
                    }
                }

                val shouldRetry = attemptNumber < totalAttempts && lastError.isRetryableDownloadFailure()
                if (shouldRetry) {
                    val retryDelayMillis = nextRetryDelayMillis(lastError, attemptNumber)
                    lgr.warn {
                        "Download attempt $attemptNumber/$maxAttempts failed for ${normalizedUrls.joinToString()}, retrying in ${retryDelayMillis}ms: ${lastError?.message}"
                    }
                    delay(retryDelayMillis)
                } else {
                    deleteQuietly(tempPath)
                    return@withTargetDownloadLock Result.failure(lastError ?: IOException("下载失败: ${normalizedUrls.joinToString()}"))
                }
            }
            deleteQuietly(tempPath)
            Result.failure(lastError ?: IOException("下载失败: ${normalizedUrls.joinToString()}"))
        } catch (cancel: CancellationException) {
            deleteQuietly(targetPath.createTempDownloadPath())
            throw cancel
        } catch (t: Throwable) {
            deleteQuietly(targetPath.createTempDownloadPath())
            Result.failure(t)
        }
    }
}

private sealed interface DownloadStrategy {
    data class Single(
        val totalBytesHint: Long,
        val supportsResume: Boolean,
        val sources: List<DownloadSource>,
    ) : DownloadStrategy
    data class MultiRange(
        val totalBytes: Long,
        val chunks: List<DownloadChunk>,
        val sources: List<DownloadSource>,
        val sequentialSources: List<DownloadSource>,
    ) : DownloadStrategy
}

private data class DownloadSource(
    val url: String,
    val orderIndex: Int,
    val headers: Map<String, String>,
    val totalBytes: Long,
    val supportsRange: Boolean,
    val latencyMillis: Long,
    val identity: String? = null,
)

private class DownloadResumeState {
    var sourceUrl: String? = null
    var expectedTotalBytes: Long = -1L
    var identity: String? = null

    fun canResume(source: DownloadSource, expectedTotalBytes: Long): Boolean =
        sourceUrl == source.url &&
                this.expectedTotalBytes == expectedTotalBytes &&
                identity != null && identity == source.identity

    fun update(source: DownloadSource, expectedTotalBytes: Long, identity: String?) {
        update(source.url, expectedTotalBytes, identity)
    }

    fun update(url: String, expectedTotalBytes: Long, identity: String?) {
        sourceUrl = url
        this.expectedTotalBytes = expectedTotalBytes
        this.identity = identity
    }

    fun clear() {
        sourceUrl = null
        expectedTotalBytes = -1L
        identity = null
    }
}

private data class DownloadChunk(
    val index: Int,
    val startInclusive: Long,
    val endInclusive: Long,
) {
    val length: Long
        get() = endInclusive - startInclusive + 1
}

private data class ContentRangeInfo(
    val startInclusive: Long,
    val endInclusive: Long,
    val totalBytes: Long?,
)

private class ResumeMismatchException(message: String) : IOException(message)
/** The host answered, but this URL cannot provide the expected file. */
private class SourceRejectedException(message: String) : IOException(message)
private class TooManyRequestsException(
    val retryAfterMillis: Long?,
    message: String
) : IOException(message)
private class DownloadValidationException(
    message: String,
    cause: Throwable
) : IllegalStateException(message, cause)

private data class HostCooldown(
    val untilMillis: Long,
    val reason: String,
)

private data class SourceHostFailure(
    val consecutiveFailures: Int,
    val untilMillis: Long,
)

private data class SourceSpeedCacheEntry(
    val speedBytesPerSecond: Double,
    val updatedAtMillis: Long,
)

private class DynamicRangeSourceScheduler(sources: List<DownloadSource>) {
    private val lock = Any()
    private val stats = sources.map { SourceRuntimeStats(it, cachedSourceSpeed(it)) }

    val sourceCount: Int
        get() = stats.size

    fun acquire(excludedUrls: Set<String>): SourceLease {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val selectable = selectCandidates(now, excludedUrls)
            val selected = selectable.sortedWith(
                compareByDescending<SourceRuntimeStats> { it.score(now) }
                    .thenBy { it.source.orderIndex }
                    .thenBy { it.source.latencyMillis }
                    .thenBy { it.source.url }
            ).first()
            selected.activeChunks++
            return SourceLease(selected)
        }
    }

    fun reportSuccess(lease: SourceLease, bytesDownloaded: Long, elapsedNanos: Long) {
        synchronized(lock) {
            val stat = lease.stat
            stat.activeChunks = (stat.activeChunks - 1).coerceAtLeast(0)
            val measuredSpeed = measuredSpeedBytesPerSecond(bytesDownloaded, elapsedNanos)
            val weight = if (stat.completedChunks == 0) 1.0 else SOURCE_EWMA_WEIGHT
            stat.ewmaBytesPerSecond = stat.ewmaBytesPerSecond * (1.0 - weight) + measuredSpeed * weight
            stat.completedChunks++
            stat.failures = (stat.failures - 1).coerceAtLeast(0)
            rememberSourceSuccess(stat.source)
            if (bytesDownloaded >= SOURCE_SPEED_MIN_SAMPLE_BYTES) {
                rememberSourceSpeed(stat.source, stat.ewmaBytesPerSecond)
            }
        }
    }

    fun reportFailure(lease: SourceLease, error: Throwable) {
        synchronized(lock) {
            val stat = lease.stat
            stat.activeChunks = (stat.activeChunks - 1).coerceAtLeast(0)
            stat.failures++
            val cooldownMillis = (error as? TooManyRequestsException)?.retryAfterMillis
                ?: SOURCE_FAILURE_COOLDOWN_MILLIS
            stat.cooldownUntilMillis = System.currentTimeMillis() + cooldownMillis
            rememberSourceFailure(stat.source, error)
        }
    }

    fun release(lease: SourceLease) {
        synchronized(lock) {
            lease.stat.activeChunks = (lease.stat.activeChunks - 1).coerceAtLeast(0)
        }
    }

    private fun selectCandidates(now: Long, excludedUrls: Set<String>): List<SourceRuntimeStats> {
        val available = stats.filter { it.cooldownUntilMillis <= now && !isSourceCoolingDown(it.source, now) }
        val notTried = available.filter { it.source.url !in excludedUrls }
        return when {
            notTried.isNotEmpty() -> notTried
            available.isNotEmpty() -> available
            else -> stats
        }
    }
}

private class SourceRuntimeStats(
    val source: DownloadSource,
    cachedSpeedBytesPerSecond: Double?,
) {
    var ewmaBytesPerSecond: Double = cachedSpeedBytesPerSecond ?: SOURCE_INITIAL_SPEED_BYTES_PER_SECOND
    var activeChunks: Int = 0
    var failures: Int = 0
    var cooldownUntilMillis: Long = 0L
    var completedChunks: Int = if (cachedSpeedBytesPerSecond != null) 1 else 0

    fun score(now: Long): Double {
        if (cooldownUntilMillis > now) return 0.0
        val activePenalty = 1.0 + activeChunks * SOURCE_ACTIVE_CHUNK_PENALTY
        val failurePenalty = 1.0 + failures * SOURCE_FAILURE_PENALTY
        return ewmaBytesPerSecond.coerceAtLeast(1.0) / activePenalty / failurePenalty
    }
}

private class SourceLease(
    val stat: SourceRuntimeStats,
) {
    val source: DownloadSource
        get() = stat.source
}

private class ProgressReporter(
    private val totalBytes: Long,
    initialBytesDownloaded: Long = 0L,
    private val onProgress: (DownloadProgress) -> Unit,
) {
    private val downloadedBytes = AtomicLong(initialBytesDownloaded.coerceAtLeast(0L))
    private val chunkBytes = HashMap<Int, Long>()
    private val lock = Any()
    private var lastReportTime = System.currentTimeMillis()
    private var lastReportBytes = initialBytesDownloaded.coerceAtLeast(0L)

    fun addBytes(delta: Int) {
        if (delta <= 0) return
        val total = downloadedBytes.addAndGet(delta.toLong())
        maybeEmit(total, force = false)
    }

    fun setChunkBytes(chunkIndex: Int, completedBytes: Long) {
        val total = synchronized(lock) {
            val safeCompleted = completedBytes.coerceAtLeast(0L)
            val previous = chunkBytes.put(chunkIndex, safeCompleted) ?: 0L
            downloadedBytes.addAndGet(safeCompleted - previous)
        }
        maybeEmit(total, force = false)
    }

    fun finish() {
        val total = downloadedBytes.get()
        synchronized(lock) {
            lastReportTime = System.currentTimeMillis()
            lastReportBytes = total
        }
        onProgress(DownloadProgress(total, totalBytes, 0.0))
    }

    private fun maybeEmit(total: Long, force: Boolean) {
        var progress: DownloadProgress? = null
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!force && now - lastReportTime < 500L) return
            val timeDelta = (now - lastReportTime) / 1000.0
            val bytesDelta = total - lastReportBytes
            val speed = if (timeDelta > 0) bytesDelta / timeDelta else 0.0
            progress = DownloadProgress(total, totalBytes, speed)
            lastReportTime = now
            lastReportBytes = total
        }
        progress?.let(onProgress)
    }
}

private suspend fun resolveDownloadStrategy(
    urls: List<String>,
    headers: Map<String, String>,
    urlHeadersProvider: (String) -> Map<String, String>,
    knownSize: Long,
    existingTempBytes: Long,
    allowMultiRange: Boolean = true,
    readTimeoutMillis: Long,
): DownloadStrategy {
    val sources = resolveDownloadSources(
        urls = urls,
        headers = headers,
        urlHeadersProvider = urlHeadersProvider,
        knownSize = knownSize,
        existingTempBytes = existingTempBytes,
        readTimeoutMillis = readTimeoutMillis,
    )
    if (sources.isEmpty()) {
        throw IOException("没有可用下载源: ${urls.joinToString()}")
    }

    val sizeHint = knownSize.takeIf { it > 0L }
        ?: sources.firstNotNullOfOrNull { it.totalBytes.takeIf { bytes -> bytes > 0L } }
        ?: -1L
    val needsResumeProbe = existingTempBytes > 0L && sizeHint > 0L
    if (needsResumeProbe) {
        // A partial file belongs to one source/identity within this invocation;
        // never reinterpret it as a fresh mixed-range destination.
        return DownloadStrategy.Single(
            totalBytesHint = sizeHint,
            supportsResume = true,
            sources = sources,
        )
    }
    val shouldProbeRange = needsResumeProbe || sizeHint >= MIN_MULTI_PART_DOWNLOAD_BYTES
    if (!shouldProbeRange || !allowMultiRange) {
        return DownloadStrategy.Single(
            totalBytesHint = sizeHint,
            supportsResume = false,
            sources = sources
        )
    }

    if (sizeHint < MIN_MULTI_PART_DOWNLOAD_BYTES) {
        return DownloadStrategy.Single(
            totalBytesHint = sizeHint,
            supportsResume = sources.any { it.supportsRange && it.totalBytes > 0L },
            sources = sources
        )
    }

    val rangedSources = selectRangedSources(sources, sizeHint)
    if (rangedSources.isEmpty() || sizeHint <= 0L) {
        return DownloadStrategy.Single(
            totalBytesHint = sizeHint,
            supportsResume = false,
            sources = sources
        )
    }

    return DownloadStrategy.MultiRange(
        totalBytes = sizeHint,
        chunks = buildDownloadChunks(sizeHint),
        sources = rangedSources,
        sequentialSources = sources,
    )
}

private suspend fun resolveDownloadSources(
    urls: List<String>,
    headers: Map<String, String>,
    urlHeadersProvider: (String) -> Map<String, String>,
    knownSize: Long,
    existingTempBytes: Long,
    readTimeoutMillis: Long,
): List<DownloadSource> = coroutineScope {
    val probedSources = urls.mapIndexed { index, url ->
        async {
            probeDownloadSource(
                url = url,
                orderIndex = index,
                headers = headers + urlHeadersProvider(url),
                knownSize = knownSize,
                existingTempBytes = existingTempBytes,
                readTimeoutMillis = readTimeoutMillis,
            )
        }
    }.awaitAll().filterNotNull()

    if (probedSources.isEmpty()) return@coroutineScope emptyList()

    probedSources.sortedWith(
        compareBy<DownloadSource> { it.orderIndex }
            .thenBy { it.latencyMillis }
            .thenBy { it.url }
    )
}

private suspend fun probeDownloadSource(
    url: String,
    orderIndex: Int,
    headers: Map<String, String>,
    knownSize: Long,
    existingTempBytes: Long,
    readTimeoutMillis: Long,
): DownloadSource? {
    val startedAt = System.currentTimeMillis()
    return try {
        val host = extractHost(url)
        val metadata = if (knownSize > 0L && knownSize < MIN_MULTI_PART_DOWNLOAD_BYTES && existingTempBytes <= 0L) {
            null
        } else {
            fetchContentMetadata(url, headers, readTimeoutMillis)
        }
        val sizeHint = knownSize.takeIf { it > 0L }
            ?: metadata?.totalBytes
            ?: -1L
        if (knownSize > 0L && metadata?.totalBytes != null && metadata.totalBytes != knownSize) {
            lgr.warn { "Skip download source due to size mismatch: $url expected=$knownSize actual=${metadata.totalBytes}" }
            return null
        }
        val rangeProbe = if ((sizeHint < MIN_MULTI_PART_DOWNLOAD_BYTES && existingTempBytes <= 0L) || shouldDisableRangeForHost(host)) {
            RangeProbeResult(
                supported = false,
                totalBytes = sizeHint,
                identity = metadata?.identity,
            )
        } else {
            probeRangeSupport(url, headers, sizeHint, readTimeoutMillis)
        }
        val totalBytes = when {
            rangeProbe.totalBytes > 0L -> rangeProbe.totalBytes
            sizeHint > 0L -> sizeHint
            else -> -1L
        }
        if (knownSize > 0L && totalBytes > 0L && totalBytes != knownSize) {
            lgr.warn { "Skip download source due to size mismatch: $url expected=$knownSize actual=$totalBytes" }
            return null
        }
        DownloadSource(
            url = url,
            orderIndex = orderIndex,
            headers = headers,
            totalBytes = totalBytes,
            supportsRange = rangeProbe.supported && totalBytes > 0L,
            latencyMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            identity = rangeProbe.identity ?: metadata?.identity,
        )
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        lgr.debug(t) { "Download source probe failed for $url" }
        null
    }
}

private fun selectRangedSources(
    sources: List<DownloadSource>,
    expectedTotalBytes: Long,
): List<DownloadSource> = sources
    .filter { it.supportsRange && it.totalBytes == expectedTotalBytes }
    .sortedWith(
        compareBy<DownloadSource> { it.orderIndex }
            .thenBy { it.latencyMillis }
            .thenBy { it.url }
    )

private suspend fun fetchContentMetadata(
    url: String,
    headers: Map<String, String>,
    readTimeoutMillis: Long,
): DownloadMetadata? {
    return try {
        val response = executeRequestWithHostLimit(url) {
            httpFileClient.request(url) {
                timeout { socketTimeoutMillis = readTimeoutMillis }
                method = HttpMethod.Head
                headers.forEach { (key, value) -> header(key, value) }
                header(HttpHeaders.AcceptEncoding, "identity")
            }
        }
        if (!response.status.isSuccess()) return null
        DownloadMetadata(
            totalBytes = response.contentLength()?.takeIf { size -> size > 0L },
            identity = responseIdentity(response),
        )
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        lgr.debug(t) { "HEAD probe failed for $url" }
        null
    }
}

private suspend fun probeRangeSupport(
    url: String,
    headers: Map<String, String>,
    sizeHint: Long,
    readTimeoutMillis: Long,
): RangeProbeResult {
    return try {
        executeRequestWithHostLimit(url) {
            httpFileClient.prepareGet(url) {
                timeout { socketTimeoutMillis = readTimeoutMillis }
                headers.forEach { (key, value) -> header(key, value) }
                header(HttpHeaders.AcceptEncoding, "identity")
                header(HttpHeaders.Range, "bytes=0-0")
            }.execute { response ->
                ensureNotRateLimited(url, response)
                if (response.status != HttpStatusCode.PartialContent) {
                    return@execute RangeProbeResult(false, sizeHint)
                }
                val contentRange = parseContentRange(response.headers[HttpHeaders.ContentRange])
                    ?: return@execute RangeProbeResult(false, sizeHint)
                if (contentRange.startInclusive != 0L || contentRange.endInclusive != 0L) {
                    return@execute RangeProbeResult(false, sizeHint)
                }
                val totalBytes = contentRange.totalBytes ?: sizeHint
                RangeProbeResult(totalBytes > 0L, totalBytes, responseIdentity(response))
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        lgr.debug(t) { "Range probe failed for $url" }
        RangeProbeResult(false, sizeHint)
    }
}

private data class RangeProbeResult(
    val supported: Boolean,
    val totalBytes: Long,
    val identity: String? = null,
)

private data class DownloadMetadata(
    val totalBytes: Long?,
    val identity: String?,
)

private fun buildDownloadChunks(totalBytes: Long): List<DownloadChunk> {
    // Work queue模式：切成1MB小chunk，由固定worker从队列消费
    val chunkSize = WORK_QUEUE_CHUNK_SIZE
    return buildList {
        var start = 0L
        var index = 0
        while (start < totalBytes) {
            val end = (start + chunkSize - 1).coerceAtMost(totalBytes - 1)
            add(DownloadChunk(index = index, startInclusive = start, endInclusive = end))
            start = end + 1
            index++
        }
    }
}

private suspend fun downloadSingleStream(
    url: String,
    targetPath: Path,
    onProgress: (DownloadProgress) -> Unit,
    knownSize: Long = -1L,
    headers: Map<String, String>,
    resumeFromBytes: Long = 0L,
    supportsResume: Boolean = false,
    resumeIdentity: String? = null,
    resumeState: DownloadResumeState? = null,
    readTimeoutMillis: Long = READ_TIMEOUT_MILLIS,
): Long {
    val shouldResume = supportsResume && resumeFromBytes > 0L
    return executeRequestWithHostLimit(url) {
        downloadSemaphore.withPermit {
            httpFileClient.prepareGet(url) {
                timeout { socketTimeoutMillis = readTimeoutMillis }
                headers.forEach { (key, value) -> header(key, value) }
                header(HttpHeaders.AcceptEncoding, "identity")
                if (shouldResume) {
                    header(HttpHeaders.Range, "bytes=${resumeFromBytes}-")
                    header(HttpHeaders.IfRange, resumeIdentity!!)
                }
            }.execute { response ->
                ensureNotRateLimited(url, response)
                if (shouldResume && response.status != HttpStatusCode.PartialContent) {
                    throw ResumeMismatchException("Resume download failed: expected 206, got ${response.status} for ${url}")
                }
                rejectJsonDownloadResponse(url, response)
                if (!shouldResume && !response.status.isSuccess()) {
                    throw httpStatusFailure("Download failed: ${response.status} for ${url}", response.status)
                }
                if (!shouldResume && response.status == HttpStatusCode.PartialContent) {
                    throw ResumeMismatchException("Unexpected partial response for fresh download: ${url}")
                }

                val contentRange = if (shouldResume) {
                    parseContentRange(response.headers[HttpHeaders.ContentRange])
                        ?: throw ResumeMismatchException("Missing Content-Range for resumed download: ${url}")
                } else null
                val responseValidator = responseIdentity(response)
                if (shouldResume && responseValidator != resumeIdentity) {
                    throw ResumeMismatchException("Resume identity changed for ${url}")
                }
                if (shouldResume && contentRange?.startInclusive != resumeFromBytes) {
                    throw ResumeMismatchException(
                        "Unexpected resume range for ${url}: expected start $resumeFromBytes, got ${contentRange?.startInclusive}"
                    )
                }

                val totalBytes = contentRange?.totalBytes
                    ?: response.contentLength()
                    ?: knownSize.takeIf { it > 0L }
                    ?: -1L

                val expectedTotalBytes = knownSize.takeIf { it > 0L }
                if (expectedTotalBytes != null && totalBytes > 0L && totalBytes != expectedTotalBytes) {
                    throw SourceRejectedException(
                        "Unexpected total size for ${url}: expected $expectedTotalBytes, got $totalBytes"
                    )
                }
                if (shouldResume && resumeIdentity.isNullOrBlank()) {
                    throw ResumeMismatchException("Missing resume identity for ${url}")
                }
                if (shouldResume) {
                    val expectedEnd = expectedTotalBytes?.minus(1L)
                    if (contentRange!!.endInclusive < contentRange.startInclusive ||
                        (expectedEnd != null && contentRange.endInclusive != expectedEnd) ||
                        (expectedTotalBytes != null && contentRange.totalBytes != expectedTotalBytes)
                    ) {
                        throw ResumeMismatchException("Unexpected resume range for ${url}")
                    }
                }

                if (shouldResume && totalBytes > 0L && resumeFromBytes >= totalBytes) {
                    onProgress(DownloadProgress(totalBytes, totalBytes, 0.0))
                    return@execute totalBytes
                }

                if (!shouldResume) {
                    resumeState?.update(url, totalBytes, responseValidator)
                }

                val initialBytesDownloaded = if (shouldResume) resumeFromBytes else 0L

                val reporter = ProgressReporter(
                    totalBytes = totalBytes,
                    initialBytesDownloaded = initialBytesDownloaded,
                    onProgress = onProgress
                )
                val channel: ByteReadChannel = response.body()
                val buffer = ByteArray(8192)
                var bytesDownloaded = initialBytesDownloaded

                withContext(Dispatchers.IO) {
                    Files.newOutputStream(
                        targetPath,
                        StandardOpenOption.CREATE,
                        if (shouldResume) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING
                    )
                        .use { outputStream ->
                            var consecutiveZeroReads = 0
                            while (!channel.isClosedForRead) {
                                val bytesRead = withTimeoutOrNull(readTimeoutMillis) {
                                    channel.readAvailable(buffer, 0, buffer.size)
                                } ?: throw IOException("Read timeout - connection stalled")

                                if (bytesRead == -1) break

                                if (bytesRead == 0) {
                                    consecutiveZeroReads++
                                    if (consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
                                        throw IOException("Connection stalled - too many zero reads")
                                    }
                                    delay(ZERO_READ_BACKOFF_MILLIS)
                                    continue
                                }
                                consecutiveZeroReads = 0

                                val remaining = if (totalBytes > 0L) totalBytes - bytesDownloaded else Long.MAX_VALUE
                                if (remaining <= 0L) {
                                    throw IOException("Download response exceeded expected size for $url")
                                }
                                val bytesToWrite = bytesRead.toLong().coerceAtMost(remaining).toInt()
                                outputStream.write(buffer, 0, bytesToWrite)
                                bytesDownloaded += bytesToWrite
                                reporter.addBytes(bytesToWrite)
                                if (bytesToWrite != bytesRead) {
                                    throw IOException("Download response exceeded expected size for $url")
                                }
                            }
                        }
                }

                if (totalBytes > 0L && bytesDownloaded != totalBytes) {
                    throw IOException("Download truncated: expected $totalBytes bytes, got $bytesDownloaded for $url")
                }
                reporter.finish()
                bytesDownloaded
            }
        }
    }
}

private suspend fun downloadSingleStreamFromSources(
    sources: List<DownloadSource>,
    targetPath: Path,
    onProgress: (DownloadProgress) -> Unit,
    knownSize: Long = -1L,
    validator: suspend (Path) -> Result<Unit>,
    resumeState: DownloadResumeState,
    readTimeoutMillis: Long = READ_TIMEOUT_MILLIS,
): Long {
    if (sources.isEmpty()) {
        throw IOException("没有可用下载源")
    }

    var lastError: Throwable? = null

    val hasMultipleSources = sources.size > 1
    val initialBytes = fileSizeOrZero(targetPath)
    val resumeOwner = initialBytes.takeIf { it > 0L }?.let { resumeState.sourceUrl }
    for (source in orderedSourcesForDownload(sources, resumeOwner)) {
        val expectedTotal = source.totalBytes.takeIf { it > 0L } ?: knownSize
        var currentBytes = fileSizeOrZero(targetPath)
        var shouldResume = currentBytes > 0L &&
                source.url == resumeState.sourceUrl &&
                expectedTotal > 0L &&
                source.supportsRange &&
                resumeState.canResume(source, expectedTotal)

        if (currentBytes > 0L && !shouldResume) {
            deleteQuietly(targetPath)
            resumeState.clear()
            currentBytes = 0L
        }

        var attemptedCleanAfterMismatch = false
        while (true) {
            val startedAt = System.nanoTime()
            try {
                val bytesDownloaded = downloadSingleStream(
                    url = source.url,
                    targetPath = targetPath,
                    onProgress = onProgress,
                    knownSize = expectedTotal,
                    headers = source.headers,
                    resumeFromBytes = if (shouldResume) currentBytes else 0L,
                    supportsResume = shouldResume,
                    resumeIdentity = if (shouldResume) resumeState.identity else null,
                    resumeState = resumeState,
                    readTimeoutMillis = readTimeoutMillis,
                )
                validateDownloadedFile(targetPath, validator)
                resumeState.clear()
                rememberSourceSuccess(source)
                if (bytesDownloaded >= SOURCE_SPEED_MIN_SAMPLE_BYTES) {
                    rememberSourceSpeed(source, measuredSpeedBytesPerSecond(bytesDownloaded, System.nanoTime() - startedAt))
                }
                return bytesDownloaded
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                lastError = t
                rememberSourceFailure(source, t)
                lgr.warn { "Single-stream source failed for ${source.url}: ${t.message}" }

                if (t is DownloadValidationException) {
                    deleteQuietly(targetPath)
                    resumeState.clear()
                    break
                }
                if (shouldResume && t is ResumeMismatchException && !attemptedCleanAfterMismatch) {
                    deleteQuietly(targetPath)
                    resumeState.clear()
                    currentBytes = 0L
                    shouldResume = false
                    attemptedCleanAfterMismatch = true
                    continue
                }

                // A multi-source call must not carry A's partial bytes into B.
                // With one source, retain an identified partial for the outer
                // retry budget so a safe resume remains possible.
                if (hasMultipleSources || resumeState.identity.isNullOrBlank()) {
                    deleteQuietly(targetPath)
                    resumeState.clear()
                }
                break
            }
        }
    }

    throw (lastError ?: IOException("Single-stream download failed for all sources"))
}

private suspend fun downloadByRanges(
    sources: List<DownloadSource>,
    targetPath: Path,
    totalBytes: Long,
    chunks: List<DownloadChunk>,
    onProgress: (DownloadProgress) -> Unit,
    maxChunkAttempts: Int,
): Long = coroutineScope {
    if (totalBytes <= 0L) {
        throw IOException("Invalid ranged download size: $totalBytes")
    }
    if (sources.isEmpty()) {
        throw IOException("没有支持分段下载的源")
    }

    withContext(Dispatchers.IO) {
        RandomAccessFile(targetPath.toFile(), "rw").use { file ->
            if (file.length() != totalBytes) {
                file.setLength(totalBytes)
            }
        }
    }

    val reporter = ProgressReporter(
        totalBytes = totalBytes,
        onProgress = onProgress
    )

    // Work queue: 所有小chunk放入Channel，固定数量worker消费
    val chunkChannel = Channel<DownloadChunk>(Channel.UNLIMITED)
    chunks.forEach { chunkChannel.trySend(it) }
    chunkChannel.close()

    val workerCount = rangeWorkerLimitForSources(sources)
        .coerceAtMost(WORK_QUEUE_WORKERS)
        .coerceAtMost(chunks.size)
    val sourceScheduler = DynamicRangeSourceScheduler(sources)
    lgr.info { "Starting $workerCount workers for ${chunks.size} chunks (${totalBytes} bytes) from ${sources.joinToString { it.url }}" }

    val workers = (0 until workerCount).map {
        async {
            var workerWritten = 0L
            for (chunk in chunkChannel) {
                val written = downloadRangeChunkWithRetry(
                    sourceScheduler = sourceScheduler,
                    targetPath = targetPath,
                    expectedTotalBytes = totalBytes,
                    chunk = chunk,
                    reporter = reporter,
                    maxAttempts = maxChunkAttempts,
                )
                workerWritten += written
            }
            workerWritten
        }
    }

    val totalWritten = workers.awaitAll().sum()
    if (totalWritten != totalBytes) {
        throw IOException("Download truncated: expected $totalBytes bytes, got $totalWritten")
    }
    reporter.finish()
    totalWritten
}

private suspend fun downloadRangeChunkWithRetry(
    sourceScheduler: DynamicRangeSourceScheduler,
    targetPath: Path,
    expectedTotalBytes: Long,
    chunk: DownloadChunk,
    reporter: ProgressReporter,
    maxAttempts: Int,
): Long {
    var lastError: Throwable? = null
    val sourceCount = sourceScheduler.sourceCount.coerceAtLeast(1)
    val totalAttempts = maxAttempts.coerceAtLeast(sourceCount)
    val attemptedUrls = mutableSetOf<String>()
    repeat(totalAttempts) { attemptIndex ->
        val attemptNumber = attemptIndex + 1
        val lease = sourceScheduler.acquire(attemptedUrls)
        val source = lease.source
        val startedAt = System.nanoTime()
        reporter.setChunkBytes(chunk.index, 0L)
        try {
            val bytesDownloaded = downloadRangeChunk(
                source = source,
                targetPath = targetPath,
                expectedTotalBytes = expectedTotalBytes,
                chunk = chunk,
                reporter = reporter,
                readTimeoutMillis = attemptReadTimeoutMillis(attemptNumber),
            )
            sourceScheduler.reportSuccess(
                lease = lease,
                bytesDownloaded = bytesDownloaded,
                elapsedNanos = System.nanoTime() - startedAt
            )
            return bytesDownloaded
        } catch (cancel: CancellationException) {
            reporter.setChunkBytes(chunk.index, 0L)
            sourceScheduler.release(lease)
            throw cancel
        } catch (t: Throwable) {
            lastError = t
            sourceScheduler.reportFailure(lease, t)
            attemptedUrls += source.url
            if (attemptedUrls.size >= sourceCount) {
                attemptedUrls.clear()
            }
            lgr.warn { "Range chunk ${chunk.index} source failed for ${source.url}: ${t.message}" }
        }

        val shouldRetry = attemptNumber < totalAttempts
        if (shouldRetry) {
            val shouldDelay = lastError is TooManyRequestsException || attemptNumber % sourceCount == 0
            if (shouldDelay) {
                val retryDelayMillis = nextRetryDelayMillis(lastError, attemptNumber)
                lgr.warn {
                    "Range chunk ${chunk.index} retry $attemptNumber/$totalAttempts in ${retryDelayMillis}ms: ${lastError?.message}"
                }
                delay(retryDelayMillis)
            }
        }
    }
    throw (lastError ?: IOException("Range download failed on chunk ${chunk.index}"))
}

private suspend fun downloadRangeChunk(
    source: DownloadSource,
    targetPath: Path,
    expectedTotalBytes: Long,
    chunk: DownloadChunk,
    reporter: ProgressReporter,
    readTimeoutMillis: Long,
): Long {
    return executeRequestWithHostLimit(source.url) {
        downloadSemaphore.withPermit {
            httpFileClient.prepareGet(source.url) {
                timeout { socketTimeoutMillis = readTimeoutMillis }
                source.headers.forEach { (key, value) -> header(key, value) }
                header(HttpHeaders.AcceptEncoding, "identity")
                header(HttpHeaders.Range, "bytes=${chunk.startInclusive}-${chunk.endInclusive}")
            }.execute { response ->
                ensureNotRateLimited(source.url, response)
                if (response.status != HttpStatusCode.PartialContent) {
                    throw httpStatusFailure(
                        "Range download failed: expected 206, got ${response.status} for ${source.url}",
                        response.status
                    )
                }

                val contentRange = parseContentRange(response.headers[HttpHeaders.ContentRange])
                    ?: throw IOException("Missing Content-Range for ranged download: ${source.url}")
                if (contentRange.startInclusive != chunk.startInclusive || contentRange.endInclusive != chunk.endInclusive) {
                    throw IOException(
                        "Unexpected Content-Range for ${source.url}: expected ${chunk.startInclusive}-${chunk.endInclusive}, " +
                                "got ${contentRange.startInclusive}-${contentRange.endInclusive}"
                    )
                }
                if (contentRange.totalBytes != expectedTotalBytes) {
                    throw SourceRejectedException(
                        "Unexpected total size for ${source.url}: expected $expectedTotalBytes, got ${contentRange.totalBytes}"
                    )
                }
                response.contentLength()?.let { contentLength ->
                    if (contentLength != chunk.length) {
                        throw IOException(
                            "Unexpected ranged response length for ${source.url}: expected ${chunk.length}, got $contentLength"
                        )
                    }
                }

                val channel: ByteReadChannel = response.body()
                val buffer = ByteArray(8192)
                var writePosition = chunk.startInclusive
                var bytesDownloaded = 0L
                var consecutiveZeroReads = 0

                withContext(Dispatchers.IO) {
                    FileChannel.open(targetPath, StandardOpenOption.WRITE).use { outputChannel ->
                        while (bytesDownloaded < chunk.length) {
                            val remaining = chunk.length - bytesDownloaded
                            val bytesRead = withTimeoutOrNull(readTimeoutMillis) {
                                channel.readAvailable(buffer, 0, buffer.size.coerceAtMost(remaining.toInt()))
                            } ?: throw IOException("Read timeout - connection stalled")

                            if (bytesRead == -1) break

                            if (bytesRead == 0) {
                                consecutiveZeroReads++
                                if (consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
                                    throw IOException("Connection stalled - too many zero reads")
                                }
                                delay(ZERO_READ_BACKOFF_MILLIS)
                                continue
                            }
                            consecutiveZeroReads = 0

                            writeBufferFully(outputChannel, buffer, bytesRead, writePosition)
                            writePosition += bytesRead
                            bytesDownloaded += bytesRead
                            reporter.setChunkBytes(chunk.index, bytesDownloaded)
                        }
                    }
                }

                if (bytesDownloaded != chunk.length) {
                    throw IOException(
                        "Range download truncated for ${source.url}: expected ${chunk.length} bytes, got $bytesDownloaded on chunk ${chunk.index}"
                    )
                }
                bytesDownloaded
            }
        }
    }
}

private fun normalizeDownloadUrls(urls: List<String>): List<String> = urls.asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .toList()

private fun orderedSourcesForDownload(sources: List<DownloadSource>, resumeOwner: String? = null): List<DownloadSource> {
    val now = System.currentTimeMillis()
    val hasAvailableSource = sources.any { !isSourceCoolingDown(it, now) }
    return sources.sortedWith(
        compareBy<DownloadSource> { if (it.url == resumeOwner) 0 else 1 }
            .thenByDescending { source ->
                sourceSelectionScore(source, now, hasAvailableSource)
            }.thenBy { it.orderIndex }
            .thenBy { it.latencyMillis }
            .thenBy { it.url }
    )
}

private fun sourceSelectionScore(
    source: DownloadSource,
    now: Long,
    hasAvailableSource: Boolean,
): Double {
    if (hasAvailableSource && isSourceCoolingDown(source, now)) return 0.0
    return cachedSourceSpeed(source) ?: SOURCE_INITIAL_SPEED_BYTES_PER_SECOND
}

private fun cachedSourceSpeed(source: DownloadSource): Double? {
    val key = sourceSpeedCacheKey(source)
    val now = System.currentTimeMillis()
    val cached = sourceSpeedCache[key] ?: return null
    if (now - cached.updatedAtMillis > SOURCE_SPEED_CACHE_TTL_MILLIS) {
        sourceSpeedCache.remove(key, cached)
        return null
    }
    return cached.speedBytesPerSecond
}

private fun rememberSourceSpeed(source: DownloadSource, measuredSpeedBytesPerSecond: Double) {
    if (!measuredSpeedBytesPerSecond.isFinite() || measuredSpeedBytesPerSecond <= 0.0) return
    val key = sourceSpeedCacheKey(source)
    val now = System.currentTimeMillis()
    sourceSpeedCache.compute(key) { _, old ->
        val oldSpeed = old?.takeIf { now - it.updatedAtMillis <= SOURCE_SPEED_CACHE_TTL_MILLIS }
            ?.speedBytesPerSecond
        val speed = if (oldSpeed == null) {
            measuredSpeedBytesPerSecond
        } else {
            oldSpeed * (1.0 - SOURCE_EWMA_WEIGHT) + measuredSpeedBytesPerSecond * SOURCE_EWMA_WEIGHT
        }
        SourceSpeedCacheEntry(speed, now)
    }
}

private fun rememberSourceSuccess(source: DownloadSource) {
    sourceUrlCooldowns.remove(source.url)
    sourceHostFailures.remove(extractHost(source.url))
}

private fun rememberSourceFailure(source: DownloadSource, error: Throwable) {
    val now = System.currentTimeMillis()
    val cooldownMillis = (error as? TooManyRequestsException)?.retryAfterMillis
        ?: SOURCE_URL_FAILURE_COOLDOWN_MILLIS
    sourceUrlCooldowns[source.url] = now + cooldownMillis
    if (!error.isHostLevelSourceFailure()) return
    // Every asset/library has a distinct URL, so an unreachable host must be
    // remembered by host or each new file would wait for it to time out again.
    sourceHostFailures.compute(extractHost(source.url)) { _, old ->
        val failures = (old?.consecutiveFailures ?: 0) + 1
        val backoff = (SOURCE_HOST_FAILURE_COOLDOWN_MILLIS shl (failures - 1).coerceAtMost(4))
            .coerceAtMost(SOURCE_HOST_FAILURE_COOLDOWN_MAX_MILLIS)
        val hostCooldownMillis = (error as? TooManyRequestsException)?.retryAfterMillis ?: backoff
        SourceHostFailure(failures, maxOf(now + hostCooldownMillis, old?.untilMillis ?: 0L))
    }
}

private fun Throwable.isHostLevelSourceFailure(): Boolean = when (this) {
    is DownloadValidationException,
    is ResumeMismatchException,
    is SourceRejectedException -> false
    else -> true
}

private fun httpStatusFailure(message: String, status: HttpStatusCode): IOException =
    if (status.value in 400..499) SourceRejectedException(message) else IOException(message)

private fun isSourceCoolingDown(source: DownloadSource, now: Long): Boolean =
    isSourceUrlCoolingDown(source, now) || isSourceHostCoolingDown(source, now)

private fun isSourceHostCoolingDown(source: DownloadSource, now: Long): Boolean {
    val failure = sourceHostFailures[extractHost(source.url)] ?: return false
    // Keep the failure count after expiry so a host that keeps failing backs off longer.
    return failure.untilMillis > now
}

private fun isSourceUrlCoolingDown(source: DownloadSource, now: Long): Boolean {
    val untilMillis = sourceUrlCooldowns[source.url] ?: return false
    if (untilMillis <= now) {
        sourceUrlCooldowns.remove(source.url, untilMillis)
        return false
    }
    return true
}

private fun sourceSpeedCacheKey(source: DownloadSource): String =
    extractHost(source.url).takeIf(String::isNotBlank) ?: source.url

private fun measuredSpeedBytesPerSecond(bytesDownloaded: Long, elapsedNanos: Long): Double {
    val elapsedSeconds = (elapsedNanos / 1_000_000_000.0).coerceAtLeast(0.001)
    return bytesDownloaded / elapsedSeconds
}

private suspend fun <T> withTargetDownloadLock(targetPath: Path, block: suspend () -> T): T {
    val key = targetPath.toAbsolutePath().normalize().toString()
    val entry = targetDownloadMutexes.compute(key) { _, existing ->
        (existing ?: TargetDownloadLockEntry()).also { it.users.incrementAndGet() }
    }!!
    try {
        return entry.mutex.withLock { block() }
    } finally {
        targetDownloadMutexes.computeIfPresent(key) { _, current ->
            if (current !== entry) {
                current
            } else {
                val remaining = entry.users.decrementAndGet()
                if (remaining == 0) null else entry
            }
        }
    }
}

private suspend fun validateDownloadedFile(
    path: Path,
    validator: suspend (Path) -> Result<Unit>,
) {
    try {
        validator(path).getOrElse { error ->
            throw DownloadValidationException(error.message ?: "下载文件校验失败", error)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: DownloadValidationException) {
        throw error
    } catch (error: Throwable) {
        throw DownloadValidationException(error.message ?: "下载文件校验失败", error)
    }
}

private suspend fun <T> executeRequestWithHostLimit(
    url: String,
    block: suspend () -> T
): T {
    val host = extractHost(url)
    waitForHostCooldown(host)
    return hostSemaphore(host).withPermit {
        waitForHostCooldown(host)
        block()
    }
}

private fun hostSemaphore(host: String): Semaphore {
    val maxConcurrent = maxConcurrentRequestsForHost(host)
    return hostSemaphores.computeIfAbsent("$host#$maxConcurrent") { Semaphore(maxConcurrent) }
}

private fun maxConcurrentRequestsForHost(host: String): Int = when {
    host.equals(BMCL_HOST, ignoreCase = true) -> BMCL_MAX_CONCURRENT_REQUESTS
    else -> DEFAULT_HOST_MAX_CONCURRENT_REQUESTS
}

private fun shouldDisableRangeForHost(host: String): Boolean =
    host.equals(BMCL_HOST, ignoreCase = true)

private fun rangeWorkerLimitForSources(sources: List<DownloadSource>): Int {
    val hosts = sources.map { extractHost(it.url) }.distinct()
    if (hosts.isEmpty()) return 1
    if (hosts.size == 1) return maxConcurrentRequestsForHost(hosts.first())
    return hosts.sumOf(::maxConcurrentRequestsForHost)
}

private fun extractHost(url: String): String = runCatching {
    URI(url).host?.lowercase()
        ?.takeIf(String::isNotBlank)
        ?: url.substringAfter("://", url)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .lowercase()
}.getOrElse {
    url
}

private suspend fun waitForHostCooldown(host: String) {
    while (true) {
        val cooldown = hostCooldowns[host] ?: return
        val remainingMillis = cooldown.untilMillis - System.currentTimeMillis()
        if (remainingMillis <= 0L) {
            hostCooldowns.remove(host, cooldown)
            return
        }
        delay(remainingMillis.coerceAtMost(5_000L))
    }
}

private fun ensureNotRateLimited(url: String, response: HttpResponse) {
    if (response.status != HttpStatusCode.TooManyRequests) return
    val retryAfterMillis = parseRetryAfterMillis(response.headers[HttpHeaders.RetryAfter])
    val host = extractHost(url)
    val cooldownMillis = retryAfterMillis ?: defaultHostCooldownMillis(host)
    val newCooldown = HostCooldown(
        untilMillis = System.currentTimeMillis() + cooldownMillis,
        reason = "HTTP 429"
    )
    hostCooldowns.compute(host) { _, existing ->
        if (existing == null || existing.untilMillis < newCooldown.untilMillis) newCooldown else existing
    }
    throw TooManyRequestsException(
        retryAfterMillis = retryAfterMillis,
        message = "Too many requests from $host, retry after ${cooldownMillis}ms"
    )
}

private suspend fun rejectJsonDownloadResponse(url: String, response: HttpResponse) {
    val contentType = response.contentType() ?: return
    if (!contentType.match(ContentType.Application.Json)) return
    val responseText = response.bodyAsText()
    val apiMessage = responseText.apiResponseMessageOrNull()
    throw SourceRejectedException(apiMessage ?: "Download source returned JSON instead of file: ${response.status} for $url")
}

private fun String.apiResponseMessageOrNull(): String? =
    runCatching {
        serdesJson.parseToJsonElement(this)
            .jsonObject["msg"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()


private fun parseRetryAfterMillis(raw: String?): Long? {
    val seconds = raw?.trim()?.toLongOrNull() ?: return null
    if (seconds <= 0L) return null
    return seconds * 1000L
}

private fun defaultHostCooldownMillis(host: String): Long = when {
    host.equals(BMCL_HOST, ignoreCase = true) -> 15_000L
    else -> 5_000L
}

private fun writeBufferFully(
    channel: FileChannel,
    buffer: ByteArray,
    length: Int,
    startPosition: Long,
) {
    var position = startPosition
    val byteBuffer = ByteBuffer.wrap(buffer, 0, length)
    while (byteBuffer.hasRemaining()) {
        val written = channel.write(byteBuffer, position)
        if (written <= 0) {
            throw IOException("Failed to write downloaded bytes to disk")
        }
        position += written
    }
}

private fun parseContentRange(raw: String?): ContentRangeInfo? {
    if (raw.isNullOrBlank()) return null
    val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
        .matchEntire(raw.trim())
        ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3]
        .takeUnless { it == "*" }
        ?.toLongOrNull()
    return ContentRangeInfo(
        startInclusive = start,
        endInclusive = end,
        totalBytes = total,
    )
}

private fun responseIdentity(response: HttpResponse): String? {
    val etag = response.headers[HttpHeaders.ETag]?.trim()
        ?.takeIf { it.isNotBlank() && !it.startsWith("W/", ignoreCase = true) }
    return etag ?: response.headers[HttpHeaders.LastModified]?.trim()?.takeIf { it.isNotBlank() }
}

private fun Path.createTempDownloadPath(): Path {
    val absoluteTarget = toAbsolutePath()
    val parent = absoluteTarget.parent ?: throw IOException("无法确定下载目录: $absoluteTarget")
    val tempName = "${absoluteTarget.fileName}.downloading"
    return parent.resolve(tempName)
}

private suspend fun fileSizeOrZero(path: Path): Long = withContext(Dispatchers.IO) {
    runCatching {
        if (Files.exists(path)) Files.size(path) else 0L
    }.getOrElse { 0L }
}

private fun nextRetryDelayMillis(attemptNumber: Int): Long {
    val exponential = (RETRY_BACKOFF_BASE_MILLIS * (1L shl (attemptNumber - 1).coerceAtLeast(0)))
        .coerceAtMost(RETRY_BACKOFF_MAX_MILLIS)
    val jitter = Random.nextLong(0L, RETRY_BACKOFF_JITTER_MILLIS + 1L)
    return exponential + jitter
}

private fun nextRetryDelayMillis(error: Throwable?, attemptNumber: Int): Long {
    val tooMany = error as? TooManyRequestsException
    val retryAfter = tooMany?.retryAfterMillis
    return retryAfter ?: nextRetryDelayMillis(attemptNumber)
}

private fun attemptReadTimeoutMillis(attemptNumber: Int): Long {
    val multiplier = STALL_RETRY_TIMEOUT_MULTIPLIER.pow((attemptNumber - 1).coerceAtLeast(0))
    return (READ_TIMEOUT_MILLIS * multiplier).toLong().coerceAtMost(180_000L)
}

private fun Double.pow(exponent: Int): Double {
    var result = 1.0
    repeat(exponent) { result *= this }
    return result
}

private suspend fun moveDownloadedFile(source: Path, target: Path) {
    withContext(Dispatchers.IO) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private suspend fun deleteQuietly(path: Path) {
    withContext(NonCancellable + Dispatchers.IO) {
        runCatching { Files.deleteIfExists(path) }
            .onFailure { error ->
                lgr.debug(error) { "Failed to delete temp download file: $path" }
            }
    }
}

private fun Throwable?.isRetryableDownloadFailure(): Boolean {
    val error = this ?: return false
    if (error is CancellationException) return false
    return error is IOException
}
