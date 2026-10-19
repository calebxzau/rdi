package calebxzau.rdi.client.service

import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.ModpackCreateFromUploadDto
import calebxzhou.rdi.common.model.ModpackUploadSessionCreateDto
import calebxzhou.rdi.common.model.ModpackUploadSessionVo
import calebxzhou.rdi.common.model.ModpackVersionCreateFromUploadDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bson.types.ObjectId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModpackChunkedUploaderTest {
    @Test
    fun `new modpack publishes only after chunked session completes`() = runBlocking {
        val bytes = ByteArray(13) { (it * 3).toByte() }
        withTempFile(bytes) { file ->
            val api = RecordingModpackUploadApi(bytes, partSize = 3)
            ModpackChunkedUploader(api, retryDelayMillis = 0).upload(
                file = file.toFile(),
                publish = { uploadId ->
                    api.publishNew(
                        ModpackCreateFromUploadDto(uploadId, createDto()),
                    )
                },
            )

            assertEquals(1, api.completeCount)
            assertEquals(1, api.newPublishCount)
            assertEquals(0, api.versionPublishCount)
            assertEquals(api.session.id, api.newRequest?.uploadId)
            assertContentEquals(bytes, api.reconstruct())
            assertEquals(bytes.sha1(), api.createdRequest?.sha1)
            assertEquals(0, api.cancelCount)
        }
    }

    @Test
    fun `existing modpack version publishes with mods after chunked upload`() = runBlocking {
        val bytes = ByteArray(7) { it.toByte() }
        withTempFile(bytes) { file ->
            val api = RecordingModpackUploadApi(bytes, partSize = 2)
            val modpackId = ObjectId("66a000000000000000000001")
            val mods = listOf(testMod())
            ModpackChunkedUploader(api, retryDelayMillis = 0).upload(
                file = file.toFile(),
                publish = { uploadId ->
                    api.publishVersion(
                        modpackId,
                        "release candidate",
                        ModpackVersionCreateFromUploadDto(uploadId, mods.toMutableList()),
                    )
                },
            )

            assertEquals(modpackId, api.versionPublishId)
            assertEquals("release candidate", api.versionPublishName)
            assertEquals(mods, requireNotNull(api.versionRequest).mods)
            assertEquals(api.session.id, api.versionRequest?.uploadId)
            assertContentEquals(bytes, api.reconstruct())
        }
    }

    @Test
    fun `orchestrator honors eight concurrent part workers`() = runBlocking {
        val bytes = ByteArray(9) { it.toByte() }
        withTempFile(bytes) { file ->
            val api = RecordingModpackUploadApi(bytes, partSize = 1)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            api.partGate = release
            api.onEightEntered = { entered.complete(Unit) }
            val task = async {
                ModpackChunkedUploader(api, retryDelayMillis = 0).upload(
                    file = file.toFile(),
                    publish = { uploadId -> api.publishNew(ModpackCreateFromUploadDto(uploadId, createDto())) },
                )
            }
            try {
                withTimeout(5_000) { entered.await() }
                assertEquals(8, api.maxConcurrent)
            } finally {
                release.complete(Unit)
            }
            task.await()
        }
    }

    @Test
    fun `resumed and ready sessions avoid duplicate parts`() = runBlocking {
        val bytes = ByteArray(5) { (it + 10).toByte() }
        withTempFile(bytes) { file ->
            val api = RecordingModpackUploadApi(bytes, partSize = 2, uploadedParts = listOf(0, 2))
            ModpackChunkedUploader(api, retryDelayMillis = 0).upload(
                file = file.toFile(),
                publish = { uploadId -> api.publishNew(ModpackCreateFromUploadDto(uploadId, createDto())) },
            )
            assertEquals(listOf(1), api.partIndexes)

            val readyApi = RecordingModpackUploadApi(bytes, partSize = 2, ready = true)
            ModpackChunkedUploader(readyApi).upload(
                file = file.toFile(),
                publish = { uploadId -> readyApi.publishNew(ModpackCreateFromUploadDto(uploadId, createDto())) },
            )
            assertTrue(readyApi.partIndexes.isEmpty())
            assertEquals(0, readyApi.completeCount)
        }
    }

    @Test
    fun `retry preserves bytes and nonretryable errors cancel session`() = runBlocking {
        val bytes = ByteArray(4) { it.toByte() }
        withTempFile(bytes) { file ->
            val retryApi = RecordingModpackUploadApi(bytes, partSize = 2, failPartOnce = true)
            ModpackChunkedUploader(retryApi, retryDelayMillis = 0).upload(
                file = file.toFile(),
                publish = { uploadId -> retryApi.publishNew(ModpackCreateFromUploadDto(uploadId, createDto())) },
            )
            assertEquals(2, retryApi.partCalls[0]?.size)
            assertContentEquals(retryApi.partCalls[0]!![0], retryApi.partCalls[0]!![1])

            val failureApi = RecordingModpackUploadApi(bytes, partSize = 2, nonRetryable = true)
            assertFailsWith<RequestError> {
                ModpackChunkedUploader(failureApi, maxPartRetries = 2, retryDelayMillis = 0).upload(
                    file = file.toFile(),
                    publish = { error("must not publish") },
                )
            }
            assertEquals(1, failureApi.cancelCount)
        }
    }

    @Test
    fun `cancellation waits for workers before canceling session`() = runBlocking {
        val bytes = ByteArray(2)
        withTempFile(bytes) { file ->
            val api = RecordingModpackUploadApi(bytes, partSize = 1, failPartIndex = 0)
            val siblingStarted = CompletableDeferred<Unit>()
            val siblingStopped = CompletableDeferred<Unit>()
            api.beforeFailure = { siblingStarted.await() }
            api.partGate = CompletableDeferred()
            api.onPartStarted = { index -> if (index == 1) siblingStarted.complete(Unit) }
            api.onPartStopped = { index -> if (index == 1) siblingStopped.complete(Unit) }
            assertFailsWith<IllegalStateException> {
                ModpackChunkedUploader(api, maxPartRetries = 0, retryDelayMillis = 0, parallelism = 2).upload(
                    file = file.toFile(),
                    publish = { error("must not publish") },
                )
            }
            assertTrue(siblingStopped.isCompleted)
            assertEquals(1, api.cancelCount)
        }
    }

    @Test
    fun `publish transport failure is explicit and does not cancel session`() = runBlocking {
        val bytes = byteArrayOf(1, 2)
        withTempFile(bytes) { file ->
            val api = RecordingModpackUploadApi(bytes, partSize = 1)
            val error = assertFailsWith<ModpackError> {
                ModpackChunkedUploader(api, retryDelayMillis = 0).upload(
                    file = file.toFile(),
                    publish = { throw IOException("connection lost") },
                )
            }
            assertTrue(error.message.orEmpty().contains("发布结果尚未确认"))
            assertEquals(0, api.cancelCount)
        }
    }

    @Test
    fun `publication cancellation is uncertain without canceling completed session`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        withTempFile(bytes) { file ->
            val api = RecordingModpackUploadApi(bytes, partSize = 1)
            val uncertain = mutableListOf<String>()
            var publishAttempts = 0
            assertFailsWith<CancellationException> {
                ModpackChunkedUploader(api, retryDelayMillis = 0).upload(
                    file = file.toFile(),
                    publish = {
                        publishAttempts++
                        throw CancellationException("publication canceled")
                    },
                    onPublicationUncertain = uncertain::add,
                )
            }
            assertEquals(1, uncertain.size)
            assertTrue(uncertain.single().contains("发布结果尚未确认"))
            assertEquals(0, api.cancelCount)
            assertEquals(1, publishAttempts)
        }
    }

    private fun createDto() = Modpack.CreateWithVersionDto(
        name = "测试整合包",
        verName = "1.0",
        mcVer = McVersion.V211,
        modLoader = ModLoader.neoforge,
        iconUrl = null,
        sourceUrl = null,
        info = "测试简介",
        categories = listOf(Modpack.Category.ADVENTURE),
        mods = mutableListOf(),
    )

    private fun testMod() = Mod("mr", "project", "测试Mod", "test.jar", "00".repeat(20))

    private suspend fun withTempFile(bytes: ByteArray, block: suspend (Path) -> Unit) {
        val file = Files.createTempFile("modpack-chunked-", ".zip")
        Files.write(file, bytes)
        try {
            block(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}

private class RecordingModpackUploadApi(
    bytes: ByteArray,
    partSize: Int,
    uploadedParts: List<Int> = emptyList(),
    private val ready: Boolean = false,
    private val failPartOnce: Boolean = false,
    private val nonRetryable: Boolean = false,
    private val failPartIndex: Int? = null,
) : ModpackUploadApi {
    val session = ModpackUploadSessionVo(
        UUID.randomUUID(), "pack.zip", bytes.size.toLong(), bytes.sha1(), partSize,
        (bytes.size + partSize - 1) / partSize, uploadedParts, ready, Long.MAX_VALUE, 8,
    )
    val partCalls = ConcurrentHashMap<Int, MutableList<ByteArray>>()
    val partIndexes = Collections.synchronizedList(mutableListOf<Int>())
    private val uploaded = ConcurrentHashMap<Int, ByteArray>()
    private val active = AtomicInteger()
    var maxConcurrent = 0
    var completeCount = 0
    var cancelCount = 0
    var newPublishCount = 0
    var versionPublishCount = 0
    var newRequest: ModpackCreateFromUploadDto? = null
    var createdRequest: ModpackUploadSessionCreateDto? = null
    var versionRequest: ModpackVersionCreateFromUploadDto? = null
    var versionPublishId: ObjectId? = null
    var versionPublishName: String? = null
    var partGate: CompletableDeferred<Unit>? = null
    var onEightEntered: (() -> Unit)? = null
    var onPartStarted: ((Int) -> Unit)? = null
    var onPartStopped: ((Int) -> Unit)? = null
    var beforeFailure: (suspend () -> Unit)? = null
    private var failed = false

    override suspend fun createSession(request: ModpackUploadSessionCreateDto): ModpackUploadSessionVo {
        createdRequest = request
        return session
    }

    override suspend fun uploadPart(
        uploadId: UUID,
        index: Int,
        bytes: ByteArray,
        sha1: String,
        onBytesSent: (Long) -> Unit,
    ) {
        partIndexes += index
        partCalls.computeIfAbsent(index) { Collections.synchronizedList(mutableListOf()) }.add(bytes.copyOf())
        val current = active.incrementAndGet()
        synchronized(this) { maxConcurrent = maxOf(maxConcurrent, current) }
        onPartStarted?.invoke(index)
        try {
            if (failPartIndex == index) {
                beforeFailure?.invoke()
                throw IllegalStateException("part failed")
            }
            if (active.get() == 8) onEightEntered?.invoke()
            partGate?.await()
            if (nonRetryable) throw RequestError("nonretryable")
            if (failPartOnce && synchronized(this) { if (failed) false else { failed = true; true } }) {
                throw IOException("transient")
            }
            assertEquals(bytes.sha1(), sha1)
            uploaded[index] = bytes.copyOf()
        } finally {
            active.decrementAndGet()
            onPartStopped?.invoke(index)
        }
    }

    override suspend fun completeSession(uploadId: UUID): ModpackUploadSessionVo {
        completeCount++
        return session.copy(ready = true, uploadedParts = (session.uploadedParts + uploaded.keys).distinct().sorted())
    }

    override suspend fun cancelSession(uploadId: UUID) {
        cancelCount++
    }

    override suspend fun publishNew(request: ModpackCreateFromUploadDto) {
        newPublishCount++
        newRequest = request
    }

    override suspend fun publishVersion(modpackId: ObjectId, versionName: String, request: ModpackVersionCreateFromUploadDto) {
        versionPublishCount++
        versionPublishId = modpackId
        versionPublishName = versionName
        versionRequest = request
    }

    override suspend fun listMy(): List<Modpack> = emptyList()

    fun reconstruct(): ByteArray = uploaded.toSortedMap().values.flatMap { it.asIterable() }.toByteArray()
}

private fun ByteArray.sha1(): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(this))
