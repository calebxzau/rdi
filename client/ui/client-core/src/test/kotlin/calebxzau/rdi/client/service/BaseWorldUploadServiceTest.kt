package calebxzau.rdi.client.service

import calebxzau.rdi.common.model.BaseWorld
import calebxzau.rdi.common.model.BaseWorldUploadSessionCreateDto
import calebxzau.rdi.common.model.BaseWorldUploadSessionVo
import calebxzau.rdi.common.model.BaseWorldUploadStatus
import calebxzhou.rdi.common.archive.forEachTarZstEntryStreaming
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.client.service.ClientTaskManager
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking

class BaseWorldUploadServiceTest {
    @Test
    fun `generated task key deduplicates only identical immutable inputs`() {
        val task = calebxzhou.rdi.common.model.Task2.Leaf("generated") { }
        val firstKey = baseWorldGeneratedTaskKey("owner", "Name", "minecraft:normal", "{\"seed\":1}")
        val sameKey = baseWorldGeneratedTaskKey("owner", "Name", "minecraft:normal", " {\"seed\":1} ")
        val changedKey = baseWorldGeneratedTaskKey("owner", "Name", "minecraft:flat", "{\"seed\":1}")
        val first = ClientTaskManager.submit(task, firstKey, autoStart = false)
        val duplicate = ClientTaskManager.submit(task, sameKey, autoStart = false)
        val changed = ClientTaskManager.submit(task, changedKey, autoStart = false)
        try {
            assertEquals(first, duplicate)
            assertNotEquals(first, changed)
        } finally {
            ClientTaskManager.remove(first)
            ClientTaskManager.remove(changed)
        }
    }

    @Test
    fun `missing directory creates generated template without upload operations`() = runBlocking {
        val root = createTempDirectory("baseworld-generated-")
        val api = RecordingApi()
        try {
            BaseWorldUploadService(api, root.resolve("temporary").toFile())
                .uploadTask(BaseWorldUploadRequest(null, "Generated", "minecraft:normal", null))
                .run(Task2Context { })

            assertEquals(1, api.createCount)
            assertTrue(api.createdDto?.generated == true)
            assertEquals(0L, api.createdDto?.size)
            assertTrue(api.parts.isEmpty())
            assertEquals(0, api.completeCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `base world name follows modpack naming rules`() {
        listOf("", " ab", "ab ", "ab", "地图/模板", "地图模板".repeat(17)).forEach { value ->
            assertTrue(validateBaseWorldName(value).isFailure, value)
        }
        listOf("Test World", "地图 World", "模板.v1_2-测试").forEach { value ->
            assertTrue(validateBaseWorldName(value).isSuccess, value)
        }
    }

    @Test
    fun `invalid base world name is rejected before filesystem or remote work`() = runBlocking {
        val root = createTempDirectory("baseworld-invalid-name-")
        val api = RecordingApi()
        try {
            val error = assertFailsWith<RequestError> {
                BaseWorldUploadService(api, root.resolve("temporary").toFile())
                    .uploadTask(BaseWorldUploadRequest(root.resolve("missing-world").toFile(), " World", "minecraft:normal", null))
            }
            assertEquals("地图模板名称只能包含字母、数字、汉字、空格或._-", error.message)
            assertEquals(0, api.createCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed level type is rejected before filesystem or remote work`() = runBlocking {
        val root = createTempDirectory("baseworld-invalid-level-type-")
        val api = RecordingApi()
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                BaseWorldUploadService(api, root.resolve("temporary").toFile())
                    .uploadTask(
                        BaseWorldUploadRequest(
                            root.resolve("missing-world").toFile(),
                            "World",
                            "normal",
                            null,
                        ),
                    )
                    .run(Task2Context { })
            }
            assertEquals("地形类型必须是foo:bar格式", error.message)
            assertEquals(0, api.createCount)
            assertEquals(0, api.completeCount)
            assertTrue(api.parts.isEmpty())
            assertTrue(api.partCalls.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `upload archives world root and completes all parts`() = runBlocking {
        val root = createWorld()
        val api = RecordingApi()
        val progress = mutableListOf<calebxzhou.rdi.common.model.Task2Progress>()
        try {
            BaseWorldUploadService(api, root.resolveSibling("baseworld-success-tmp").toFile()).uploadTask(
                BaseWorldUploadRequest(root.toFile(), "Test World", "minecraft:normal", "  "),
            ).run(Task2Context(emitProgress = progress::add))

            assertEquals("Test World", api.createdDto?.name)
            assertEquals(10L, api.createdDto?.size)
            assertEquals(1, api.createCount)
            assertEquals(1, api.completeCount)
            assertTrue(api.completeSawAllParts)
            assertEquals("上传已提交，校验结果将通过邮件通知", progress.last().message)
            assertTrue(api.parts.isNotEmpty())
            val archive = Files.createTempFile("baseworld-test-", ".tar.zst").toFile()
            try {
                archive.writeBytes(api.parts.toSortedMap().values.flatMap { it.asIterable() }.toByteArray())
                val entries = linkedMapOf<String, ByteArray>()
                forEachTarZstEntryStreaming(archive) { entry, input ->
                    if (!entry.isDirectory) entries[entry.path] = input.readBytes()
                }
                assertEquals("level.dat", entries.keys.first())
                assertEquals("level", entries["level.dat"]?.decodeToString())
                assertEquals("region/chunk.mca", entries.keys.last())
                assertTrue(entries.keys.contains("region/chunk.mca"))
            } finally {
                archive.delete()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `source exceeding limit never creates remote world`() = runBlocking {
        val root = createWorld(levelBytes = "level".toByteArray(), extraBytes = ByteArray(2_000))
        val api = RecordingApi()
        val maxSize = 2_004L
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                BaseWorldUploadService(api, root.resolveSibling("baseworld-limit-tmp").toFile(), maxSize = maxSize)
                    .uploadTask(BaseWorldUploadRequest(root.toFile(), "World", "minecraft:normal", null))
                    .run(Task2Context { })
            }
            assertTrue(error.message.orEmpty().contains(maxSize.humanFileSize))
            assertEquals(0, api.createCount)
            assertTrue(root.resolveSibling("baseworld-limit-tmp").toFile().let { !it.exists() || it.listFiles().orEmpty().isEmpty() })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing level dat and symlinks fail before remote create`() = runBlocking {
        val missing = createTempDirectory("baseworld-missing-")
        val missingApi = RecordingApi()
        try {
            missing.resolve("region").toFile().mkdirs()
            assertFails {
                BaseWorldUploadService(missingApi, missing.resolveSibling("baseworld-missing-tmp").toFile())
                    .uploadTask(BaseWorldUploadRequest(missing.toFile(), "World", "minecraft:normal", null))
                    .run(Task2Context { })
            }
            assertEquals(0, missingApi.createCount)
        } finally {
            missing.toFile().deleteRecursively()
        }

        val symlinkRoot = createWorld()
        val target = symlinkRoot.resolve("target").also { Files.write(it, byteArrayOf(1)) }
        val link = symlinkRoot.resolve("link")
        try {
            runCatching { Files.createSymbolicLink(link, target.fileName) }.getOrElse {
                return@runBlocking
            }
            val symlinkApi = RecordingApi()
            assertFails {
                BaseWorldUploadService(symlinkApi, symlinkRoot.resolveSibling("baseworld-link-tmp").toFile())
                    .uploadTask(BaseWorldUploadRequest(symlinkRoot.toFile(), "World", "minecraft:normal", null))
                    .run(Task2Context { })
            }
            assertEquals(0, symlinkApi.createCount)
        } finally {
            symlinkRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed part retries identical bytes and pre-complete failure deletes world`() = runBlocking {
        val root = createWorld()
        val api = RecordingApi(failPartOnce = true)
        try {
            BaseWorldUploadService(
                api,
                root.resolveSibling("baseworld-retry-tmp").toFile(),
                config = BaseWorldUploadTaskConfig(maxPartRetries = 1, retryDelayMillis = 0),
            ).uploadTask(BaseWorldUploadRequest(root.toFile(), "World", "minecraft:normal", null))
                .run(Task2Context { })
            assertEquals(2, api.partCalls[0]?.size)
            val calls = requireNotNull(api.partCalls[0])
            assertContentEquals(calls.first(), calls.last())
            assertEquals(0, api.deleteCount)
            assertEquals(1, api.completeCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `exhausted part failure deletes world before completion`() = runBlocking {
        val root = createWorld()
        val api = RecordingApi(failPartAlways = true)
        try {
            assertFails {
                BaseWorldUploadService(
                    api,
                    root.resolveSibling("baseworld-fail-tmp").toFile(),
                    config = BaseWorldUploadTaskConfig(maxPartRetries = 1, retryDelayMillis = 0),
                ).uploadTask(BaseWorldUploadRequest(root.toFile(), "World", "minecraft:normal", null))
                    .run(Task2Context { })
            }
            assertEquals(1, api.deleteCount)
            assertEquals(0, api.completeCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed upload waits for blocked sibling before deleting world`() = runBlocking {
        val root = createWorld()
        val failureGate = CompletableDeferred<Unit>()
        val blockStarted = CompletableDeferred<Unit>()
        val blockGate = CompletableDeferred<Unit>()
        val blockCancelled = CompletableDeferred<Unit>()
        val api = RecordingApi(
            partSize = 1,
            failPartIndex = 0,
            failAfterPartStarted = failureGate,
            blockPartIndex = 1,
            blockStarted = blockStarted,
            blockGate = blockGate,
            blockCancelled = blockCancelled,
        )
        try {
            assertFails {
                BaseWorldUploadService(
                    api,
                    root.resolveSibling("baseworld-blocked-fail-tmp").toFile(),
                    config = BaseWorldUploadTaskConfig(maxPartRetries = 0, retryDelayMillis = 0, parallelism = 2),
                ).uploadTask(BaseWorldUploadRequest(root.toFile(), "World", "minecraft:normal", null))
                    .run(Task2Context { })
            }
            assertTrue(blockStarted.isCompleted)
            assertTrue(blockCancelled.isCompleted)
            assertEquals(1, api.deleteCount)
            assertEquals(0, api.completeCount)
        } finally {
            blockGate.complete(Unit)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `completion failure does not delete potentially published world`() = runBlocking {
        val root = createWorld()
        val api = RecordingApi(failComplete = true)
        try {
            assertFails {
                BaseWorldUploadService(api, root.resolveSibling("baseworld-complete-tmp").toFile())
                    .uploadTask(BaseWorldUploadRequest(root.toFile(), "World", "minecraft:normal", null))
                    .run(Task2Context { })
            }
            assertEquals(0, api.deleteCount)
            assertEquals(1, api.completeCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `completion status is reflected without deleting the uploaded world`() = runBlocking {
        val expectations = listOf(
            BaseWorldUploadStatus.Queued to "上传已提交，校验结果将通过邮件通知",
            BaseWorldUploadStatus.Processing to "上传已提交，校验结果将通过邮件通知",
            BaseWorldUploadStatus.Ready to "地图模板已准备好，请查看邮件",
        )
        for ((status, message) in expectations) {
            val root = createWorld()
            val api = RecordingApi(completionStatus = status)
            val progress = mutableListOf<calebxzhou.rdi.common.model.Task2Progress>()
            try {
                BaseWorldUploadService(api, root.resolveSibling("baseworld-status-${status.name}-tmp").toFile())
                    .uploadTask(BaseWorldUploadRequest(root.toFile(), "World", "minecraft:normal", null))
                    .run(Task2Context(emitProgress = progress::add))
                assertEquals(message, progress.last().message)
                assertEquals(0, api.deleteCount)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        val failedRoot = createWorld()
        val failedApi = RecordingApi(
            completionStatus = BaseWorldUploadStatus.Failed,
            completionError = "level.dat损坏",
        )
        try {
            val error = assertFailsWith<RequestError> {
                BaseWorldUploadService(failedApi, failedRoot.resolveSibling("baseworld-status-failed-tmp").toFile())
                    .uploadTask(BaseWorldUploadRequest(failedRoot.toFile(), "World", "minecraft:normal", null))
                    .run(Task2Context { })
            }
            assertEquals("level.dat损坏", error.message)
            assertEquals(0, failedApi.deleteCount)
        } finally {
            failedRoot.toFile().deleteRecursively()
        }

        val uploadingRoot = createWorld()
        val uploadingApi = RecordingApi(completionStatus = BaseWorldUploadStatus.Uploading)
        try {
            val error = assertFailsWith<RequestError> {
                BaseWorldUploadService(
                    uploadingApi,
                    uploadingRoot.resolveSibling("baseworld-status-uploading-tmp").toFile(),
                ).uploadTask(BaseWorldUploadRequest(uploadingRoot.toFile(), "World", "minecraft:normal", null))
                    .run(Task2Context { })
            }
            assertEquals("服务器未接受地图模板上传完成请求", error.message)
            assertEquals(0, uploadingApi.deleteCount)
        } finally {
            uploadingRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cancellation cleans local archive and does not create remote world`() = runBlocking {
        val root = createWorld()
        val temp = root.resolveSibling("baseworld-task-temp-${UUID.randomUUID()}")
        val api = RecordingApi()
        try {
            assertFails {
                BaseWorldUploadService(api, temp.toFile())
                    .uploadTask(BaseWorldUploadRequest(root.toFile(), "World", "minecraft:normal", null))
                    .run(Task2Context(isCancelled = { true }, emitProgress = {}))
            }
            assertEquals(0, api.createCount)
            assertFalse(temp.toFile().exists())
        } finally {
            root.toFile().deleteRecursively()
            temp.toFile().deleteRecursively()
        }
    }

    private fun createWorld(
        levelBytes: ByteArray = "level".toByteArray(),
        extraBytes: ByteArray = "chunk".toByteArray(),
    ): java.nio.file.Path {
        val root = createTempDirectory("baseworld-source-")
        Files.write(root.resolve("level.dat"), levelBytes)
        Files.createDirectories(root.resolve("region"))
        Files.write(root.resolve("region/chunk.mca"), extraBytes)
        return root
    }
}

private class RecordingApi(
    private val failPartOnce: Boolean = false,
    private val failPartAlways: Boolean = false,
    private val failComplete: Boolean = false,
    private val completionStatus: BaseWorldUploadStatus = BaseWorldUploadStatus.Queued,
    private val completionError: String? = null,
    private val partSize: Int = 512,
    private val failPartIndex: Int? = null,
    private val failAfterPartStarted: CompletableDeferred<Unit>? = null,
    private val blockPartIndex: Int? = null,
    private val blockStarted: CompletableDeferred<Unit>? = null,
    private val blockGate: CompletableDeferred<Unit>? = null,
    private val blockCancelled: CompletableDeferred<Unit>? = null,
) : BaseWorldApi {
    private val worldId = UUID.randomUUID()
    private val uploadId = UUID.randomUUID()
    var createCount = 0
    var completeCount = 0
    var deleteCount = 0
    var createdDto: BaseWorld.CreateDto? = null
    val parts = ConcurrentHashMap<Int, ByteArray>()
    val partCalls = ConcurrentHashMap<Int, MutableList<ByteArray>>()
    private val partStateLock = Any()
    private var failedPart = false
    private var expectedPartCount = 0
    var completeSawAllParts = false
        private set

    override suspend fun list(): List<BaseWorld> = emptyList()

    override suspend fun create(dto: BaseWorld.CreateDto): BaseWorld {
        createCount++
        createdDto = dto
        return BaseWorld(worldId, UUID.randomUUID(), dto.name, dto.levelType, dto.generatorSettings, dto.size)
    }

    override suspend fun createUpload(worldId: UUID, dto: BaseWorldUploadSessionCreateDto): BaseWorldUploadSessionVo {
        expectedPartCount = ((dto.size - 1) / partSize + 1).toInt()
        return BaseWorldUploadSessionVo(
            uploadId,
            dto.size,
            partSize,
            expectedPartCount,
            emptyList(),
            Long.MAX_VALUE,
        )
    }

    override suspend fun uploadStatus(worldId: UUID, uploadId: UUID): BaseWorldUploadSessionVo = error("unused")

    override suspend fun uploadPart(
        worldId: UUID,
        uploadId: UUID,
        index: Int,
        bytes: ByteArray,
        sha1: String,
        onBytesSent: (Long) -> Unit,
    ) {
        val shouldFail = synchronized(partStateLock) {
            partCalls.getOrPut(index) { mutableListOf() } += bytes.copyOf()
            val shouldFail = failPartAlways ||
                (failPartIndex == index) ||
                (failPartOnce && !failedPart)
            if (shouldFail) {
                failedPart = true
            }
            shouldFail
        }
        if (index == blockPartIndex) {
            blockStarted?.complete(Unit)
            failAfterPartStarted?.complete(Unit)
            try {
                blockGate?.await()
            } finally {
                blockCancelled?.complete(Unit)
            }
        }
        if (shouldFail) {
            failAfterPartStarted?.await()
            throw IOException("transient")
        }
        assertEquals(sha1(bytes), sha1)
        parts[index] = bytes.copyOf()
    }

    override suspend fun completeUpload(worldId: UUID, uploadId: UUID): BaseWorldUploadSessionVo {
        completeCount++
        completeSawAllParts = parts.size == expectedPartCount
        if (failComplete) throw IOException("response lost")
        return BaseWorldUploadSessionVo(
            uploadId,
            1,
            512,
            1,
            listOf(0),
            Long.MAX_VALUE,
            completionStatus,
            completionError,
        )
    }

    override suspend fun cancelUpload(worldId: UUID, uploadId: UUID) = Unit

    override suspend fun delete(worldId: UUID) {
        deleteCount++
    }

    private fun sha1(bytes: ByteArray): String = bytes.sha1
}

private fun calebxzhou.rdi.common.model.Task2.run(context: Task2Context) = runBlocking {
    (this@run as calebxzhou.rdi.common.model.Task2.Leaf).action(context)
}
