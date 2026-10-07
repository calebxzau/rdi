package calebxzau.rdi.server.service.baseworld

import calebxzhou.rdi.common.model.RAccount
import calebxzau.rdi.common.model.BaseWorld
import calebxzau.rdi.common.model.BaseWorldUploadSessionCreateDto
import calebxzau.rdi.common.model.BaseWorldUploadStatus
import calebxzau.rdi.common.util.uuid7j
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.util.toUUID
import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.master.infra.postgres.DatabaseProvider
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BaseWorldUploadServiceTest {
    @Test
    fun `complete extracts exact files replaces old world and records extracted size`() = runTest {
        val root = Files.createTempDirectory("base-world-service-success").toFile()
        try {
            val initial = world(size = 91)
            val files = uploadedFiles()
            val fixture = fixture(root, initial)
            val destination = fixture.destination(initial)
            destination.mkdirs()
            destination.resolve("old.txt").writeText("old")
            destination.resolve("level.dat").writeText("old-level")

            val archive = archive(root, files)
            val completed = uploadArchive(fixture, initial, archive)

            assertEquals(files.values.sumOf { it.size.toLong() }, completed.size)
            assertEquals(completed, fixture.worlds[initial.id])
            assertFalse(destination.resolve("old.txt").exists())
            assertTrue(destination.resolve("world.tar.zst").isFile)
            assertEquals(archive.length(), destination.resolve("world.tar.zst").length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `published world rejects a second upload`() = runTest {
        val root = Files.createTempDirectory("base-world-service-immutable").toFile()
        try {
            val initial = world(size = 1)
            val fixture = fixture(root, initial)
            uploadArchive(fixture, initial, archive(root, uploadedFiles()))

            assertTrue(
                fixture.service.createUpload(
                    initial.ownerId,
                    initial.id,
                    BaseWorldUploadSessionCreateDto(1, "0".repeat(40)),
                ).isFailure
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `database size failure restores old world and leaves session retryable`() = runTest {
        val root = Files.createTempDirectory("base-world-service-rollback").toFile()
        try {
            val initial = world(size = 17)
            var failUpdate = true
            val fixture = fixture(root, initial, beforeUpdate = {
                if (failUpdate) throw IllegalStateException("database unavailable")
            })
            val destination = fixture.destination(initial)
            destination.mkdirs()
            val oldLevel = byteArrayOf(8, 7, 6)
            destination.resolve("level.dat").writeBytes(oldLevel)
            destination.resolve("old.txt").writeText("keep")

            val archive = archive(root, uploadedFiles())
            val session = startAndUpload(fixture.service, initial, archive)
            val queued = fixture.service.completeUpload(initial.ownerId, initial.id, session.id).getOrThrow()
            fixture.runPending()

            assertEquals(BaseWorldUploadStatus.Failed, fixture.service.uploadStatus(initial.ownerId, initial.id, session.id).getOrThrow().status)
            assertContentEquals(oldLevel, destination.resolve("level.dat").readBytes())
            assertEquals("keep", destination.resolve("old.txt").readText())
            assertEquals(initial, fixture.worlds[initial.id])
            assertEquals((0 until session.partCount).toList(), fixture.service.uploadStatus(initial.ownerId, initial.id, session.id).getOrThrow().uploadedParts)

            assertEquals(BaseWorldUploadStatus.Failed, queued.status.let { fixture.service.uploadStatus(initial.ownerId, initial.id, session.id).getOrThrow().status })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `delete removes world files session and metadata after database success`() = runTest {
        val root = Files.createTempDirectory("base-world-service-delete").toFile()
        try {
            val initial = world(size = 12)
            val fixture = fixture(root, initial)
            val destination = fixture.destination(initial)
            destination.mkdirs()
            destination.resolve("level.dat").writeText("world")
            val archive = archive(root, uploadedFiles())
            val session = fixture.service.createUpload(
                initial.ownerId,
                initial.id,
                BaseWorldUploadSessionCreateDto(archive.length(), sha1(archive.readBytes())),
            ).getOrThrow()

            assertTrue(fixture.service.delete(initial.ownerId, initial.id).getOrThrow())
            assertFalse(destination.exists())
            assertFalse(fixture.worlds.containsKey(initial.id))
            assertFalse(root.resolve(".uploads").resolve(initial.id.toString()).exists())
            assertTrue(fixture.service.uploadStatus(initial.ownerId, initial.id, session.id).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `delete database failure restores files and metadata`() = runTest {
        val root = Files.createTempDirectory("base-world-service-delete-rollback").toFile()
        try {
            val initial = world(size = 23)
            val fixture = fixture(
                root,
                initial,
                beforeDelete = { throw IllegalStateException("database unavailable") },
            )
            val destination = fixture.destination(initial)
            destination.mkdirs()
            val oldLevel = byteArrayOf(1, 3, 5, 7)
            destination.resolve("level.dat").writeBytes(oldLevel)
            destination.resolve("old.txt").writeText("preserve")
            val archive = archive(root, uploadedFiles())
            val session = fixture.service.createUpload(
                initial.ownerId,
                initial.id,
                BaseWorldUploadSessionCreateDto(archive.length(), sha1(archive.readBytes())),
            ).getOrThrow()

            assertTrue(fixture.service.delete(initial.ownerId, initial.id).isFailure)
            assertContentEquals(oldLevel, destination.resolve("level.dat").readBytes())
            assertEquals("preserve", destination.resolve("old.txt").readText())
            assertEquals(initial, fixture.worlds[initial.id])
            assertNotNull(fixture.service.uploadStatus(initial.ownerId, initial.id, session.id).getOrNull())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `other owner cannot create upload mutate upload delete or complete world`() = runTest {
        val root = Files.createTempDirectory("base-world-service-owner").toFile()
        try {
            val initial = world(size = 31)
            val otherOwner = uuid7j()
            val fixture = fixture(root, initial)
            val destination = fixture.destination(initial)
            destination.mkdirs()
            val oldLevel = byteArrayOf(2, 4, 6)
            destination.resolve("level.dat").writeBytes(oldLevel)
            val archive = archive(root, uploadedFiles())
            val bytes = archive.readBytes()
            val session = fixture.service.createUpload(
                initial.ownerId,
                initial.id,
                BaseWorldUploadSessionCreateDto(bytes.size.toLong(), sha1(bytes)),
            ).getOrThrow()

            assertTrue(
                fixture.service.createUpload(
                    otherOwner,
                    initial.id,
                    BaseWorldUploadSessionCreateDto(bytes.size.toLong(), sha1(bytes)),
                ).isFailure
            )
            val part = bytes.copyOfRange(0, minOf(session.partSize, bytes.size))
            assertTrue(
                fixture.service.uploadPart(
                    otherOwner,
                    initial.id,
                    session.id,
                    0,
                    part.size.toLong(),
                    sha1(part),
                    ByteReadChannel(part),
                ).isFailure
            )
            assertTrue(fixture.service.completeUpload(otherOwner, initial.id, session.id).isFailure)
            assertTrue(fixture.service.cancelUpload(otherOwner, initial.id, session.id).isFailure)
            assertFalse(fixture.service.delete(otherOwner, initial.id).getOrThrow())
            assertContentEquals(oldLevel, destination.resolve("level.dat").readBytes())
            assertEquals(initial, fixture.worlds[initial.id])
            assertEquals(session.id, fixture.service.uploadStatus(initial.ownerId, initial.id, session.id).getOrThrow().id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `completion and delete are serialized while publication updates metadata`() = runTest {
        val root = Files.createTempDirectory("base-world-service-concurrency").toFile()
        try {
            val initial = world(size = 41)
            val enteredUpdate = CompletableDeferred<Unit>()
            val releaseUpdate = CompletableDeferred<Unit>()
            val fixture = fixture(root, initial, beforeUpdate = {
                enteredUpdate.complete(Unit)
                runBlocking { releaseUpdate.await() }
            })
            val destination = fixture.destination(initial)
            destination.mkdirs()
            destination.resolve("old.txt").writeText("old")
            val archive = archive(root, uploadedFiles())
            val session = startAndUpload(fixture.service, initial, archive)

            val completion = async(start = CoroutineStart.UNDISPATCHED) {
                val result = fixture.service.completeUpload(initial.ownerId, initial.id, session.id)
                fixture.runPending()
                result
            }
            enteredUpdate.await()
            val deletion = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.service.delete(initial.ownerId, initial.id)
            }

            assertFalse(deletion.isCompleted)
            try {
                assertTrue(destination.resolve("world.tar.zst").exists())
            } finally {
                releaseUpdate.complete(Unit)
            }

            assertTrue(completion.await().isSuccess)
            assertTrue(deletion.await().getOrThrow())
            assertFalse(destination.exists())
            assertFalse(fixture.worlds.containsKey(initial.id))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `different parts receive concurrently and commit without losing metadata`() = runBlocking {
        val root = Files.createTempDirectory("base-world-service-parallel-parts").toFile()
        try {
            val initial = world(size = 0)
            val fixture = fixture(root, initial, partSize = 2)
            val bytes = byteArrayOf(1, 2, 3, 4)
            val session = fixture.service.createUpload(
                initial.ownerId,
                initial.id,
                BaseWorldUploadSessionCreateDto(bytes.size.toLong(), sha1(bytes)),
            ).getOrThrow()
            val firstChannel = ByteChannel(autoFlush = true)
            val receiving = CompletableDeferred<Unit>()
            val firstInput = object : io.ktor.utils.io.ByteReadChannel by firstChannel {
                override suspend fun awaitContent(min: Int): Boolean {
                    receiving.complete(Unit)
                    return firstChannel.awaitContent(min)
                }
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.service.uploadPart(
                    initial.ownerId,
                    initial.id,
                    session.id,
                    0,
                    2,
                    sha1(bytes.copyOfRange(0, 2)),
                    firstInput,
                )
            }
            withTimeout(5_000) { receiving.await() }

            val second = fixture.service.uploadPart(
                initial.ownerId,
                initial.id,
                session.id,
                1,
                2,
                sha1(bytes.copyOfRange(2, 4)),
                ByteReadChannel(bytes.copyOfRange(2, 4)),
            )
            assertTrue(second.isSuccess)
            assertFalse(first.isCompleted)

            firstChannel.writeFully(bytes.copyOfRange(0, 2))
            firstChannel.close()
            assertTrue(first.await().isSuccess)
            assertEquals(listOf(0, 1), fixture.service.uploadStatus(initial.ownerId, initial.id, session.id).getOrThrow().uploadedParts)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancel while receiving prevents late commit and cleans request temporary`() = runBlocking {
        val root = Files.createTempDirectory("base-world-service-cancel-part").toFile()
        try {
            val initial = world(size = 0)
            val fixture = fixture(root, initial, partSize = 2)
            val bytes = byteArrayOf(1, 2)
            val session = fixture.service.createUpload(
                initial.ownerId,
                initial.id,
                BaseWorldUploadSessionCreateDto(bytes.size.toLong(), sha1(bytes)),
            ).getOrThrow()
            val channel = ByteChannel(autoFlush = true)
            val receiving = CompletableDeferred<Unit>()
            val input = object : io.ktor.utils.io.ByteReadChannel by channel {
                override suspend fun awaitContent(min: Int): Boolean {
                    receiving.complete(Unit)
                    return channel.awaitContent(min)
                }
            }
            val upload = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.service.uploadPart(
                    initial.ownerId,
                    initial.id,
                    session.id,
                    0,
                    2,
                    sha1(bytes),
                    input,
                )
            }
            withTimeout(5_000) { receiving.await() }

            assertTrue(fixture.service.cancelUpload(initial.ownerId, initial.id, session.id).isSuccess)
            channel.writeFully(bytes)
            channel.close()
            assertTrue(upload.await().isFailure)
            assertFalse(root.listFiles()?.any { it.name.startsWith(".base-world-part-") } == true)
            assertTrue(fixture.service.uploadStatus(initial.ownerId, initial.id, session.id).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `at most eight parts receive at once and capacity is released after completion`() = runBlocking {
        val root = Files.createTempDirectory("base-world-service-part-cap").toFile()
        try {
            val initial = world(size = 0)
            val fixture = fixture(root, initial, partSize = 1)
            val bytes = ByteArray(9) { it.toByte() }
            val session = fixture.service.createUpload(
                initial.ownerId,
                initial.id,
                BaseWorldUploadSessionCreateDto(bytes.size.toLong(), sha1(bytes)),
            ).getOrThrow()
            val channels = List(8) { ByteChannel(autoFlush = true) }
            val receiving = List(8) { CompletableDeferred<Unit>() }
            val inputs = channels.indices.map { index ->
                object : io.ktor.utils.io.ByteReadChannel by channels[index] {
                    override suspend fun awaitContent(min: Int): Boolean {
                        receiving[index].complete(Unit)
                        return channels[index].awaitContent(min)
                    }
                }
            }
            val uploads = channels.indices.map { index ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.service.uploadPart(
                        initial.ownerId,
                        initial.id,
                        session.id,
                        index,
                        1,
                        sha1(byteArrayOf(bytes[index])),
                        inputs[index],
                    )
                }
            }
            receiving.forEach { withTimeout(5_000) { it.await() } }

            val rejected = fixture.service.uploadPart(
                initial.ownerId,
                initial.id,
                session.id,
                8,
                1,
                sha1(byteArrayOf(bytes[8])),
                ByteReadChannel(byteArrayOf(bytes[8])),
            )
            assertTrue(rejected.isFailure)
            assertEquals(8, root.listFiles()?.count { it.name.startsWith(".base-world-part-") } ?: 0)

            uploads[0].cancel()
            uploads[0].join()
            channels[0].close()
            assertTrue(
                fixture.service.uploadPart(
                    initial.ownerId,
                    initial.id,
                    session.id,
                    8,
                    1,
                    sha1(byteArrayOf(bytes[8])),
                    ByteReadChannel(byteArrayOf(bytes[8])),
                ).isSuccess,
            )

            channels.drop(1).forEachIndexed { offset, channel ->
                val index = offset + 1
                channel.writeFully(byteArrayOf(bytes[index]))
                channel.close()
            }
            assertTrue(uploads.drop(1).all { it.await().isSuccess })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `same index duplicate is idempotent or rejects a conflicting late commit`() = runBlocking {
        val root = Files.createTempDirectory("base-world-service-duplicate-part").toFile()
        try {
            listOf(false, true).forEach { conflicting ->
                val initial = world(size = 0)
                val fixture = fixture(root, initial, partSize = 2)
                val originalBytes = byteArrayOf(1, 2)
                val duplicateBytes = if (conflicting) byteArrayOf(9, 9) else originalBytes
                val session = fixture.service.createUpload(
                    initial.ownerId,
                    initial.id,
                    BaseWorldUploadSessionCreateDto(2L, sha1(originalBytes)),
                ).getOrThrow()
                val channel = ByteChannel(autoFlush = true)
                val receiving = CompletableDeferred<Unit>()
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.service.uploadPart(
                        initial.ownerId,
                        initial.id,
                        session.id,
                        0,
                        2,
                        sha1(originalBytes),
                        blockedChannel(channel, receiving),
                    )
                }
                withTimeout(5_000) { receiving.await() }
                assertTrue(
                    fixture.service.uploadPart(
                        initial.ownerId,
                        initial.id,
                        session.id,
                        0,
                        2,
                        sha1(duplicateBytes),
                        ByteReadChannel(duplicateBytes),
                    ).isSuccess,
                )
                channel.writeFully(originalBytes)
                channel.close()
                val firstResult = first.await()
                if (conflicting) assertTrue(firstResult.isFailure) else assertTrue(firstResult.isSuccess)
                assertContentEquals(
                    duplicateBytes,
                    root.resolve(".uploads/${initial.id}/${session.id}/part-0").readBytes(),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `deleting a world during receive prevents late commit and session resurrection`() = runBlocking {
        val root = Files.createTempDirectory("base-world-service-delete-part").toFile()
        try {
            val initial = world(size = 0)
            val fixture = fixture(root, initial, partSize = 2)
            val bytes = byteArrayOf(1, 2)
            val session = fixture.service.createUpload(
                initial.ownerId,
                initial.id,
                BaseWorldUploadSessionCreateDto(2L, sha1(bytes)),
            ).getOrThrow()
            val channel = ByteChannel(autoFlush = true)
            val receiving = CompletableDeferred<Unit>()
            val upload = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.service.uploadPart(
                    initial.ownerId,
                    initial.id,
                    session.id,
                    0,
                    2,
                    sha1(bytes),
                    blockedChannel(channel, receiving),
                )
            }
            withTimeout(5_000) { receiving.await() }
            assertTrue(fixture.service.delete(initial.ownerId, initial.id).getOrThrow())
            channel.writeFully(bytes)
            channel.close()
            assertTrue(upload.await().isFailure)
            assertFalse(root.resolve(".uploads/${initial.id}").exists())
            assertFalse(root.listFiles()?.any { it.name.startsWith(".base-world-part-") } == true)
            assertTrue(fixture.service.uploadStatus(initial.ownerId, initial.id, session.id).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `completion rejects missing part and late admitted duplicate cannot mutate queued upload`() = runBlocking {
        val root = Files.createTempDirectory("base-world-service-completion-race").toFile()
        try {
            val initial = world(size = 0)
            val archive = archive(root, uploadedFiles())
            val archiveBytes = archive.readBytes()
            val partSize = (archiveBytes.size / 2 + 1).coerceAtLeast(1)
            val fixture = fixture(root, initial, partSize = partSize)
            val session = fixture.service.createUpload(
                initial.ownerId,
                initial.id,
                BaseWorldUploadSessionCreateDto(archiveBytes.size.toLong(), sha1(archiveBytes)),
            ).getOrThrow()
            assertEquals(2, session.partCount)
            val firstChannel = ByteChannel(autoFlush = true)
            val firstReceiving = CompletableDeferred<Unit>()
            val duplicateChannel = ByteChannel(autoFlush = true)
            val duplicateReceiving = CompletableDeferred<Unit>()
            val firstPart = archiveBytes.copyOfRange(0, partSize)
            val secondPart = archiveBytes.copyOfRange(partSize, archiveBytes.size)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.service.uploadPart(
                    initial.ownerId,
                    initial.id,
                    session.id,
                    0,
                    firstPart.size.toLong(),
                    sha1(firstPart),
                    blockedChannel(firstChannel, firstReceiving),
                )
            }
            withTimeout(5_000) { firstReceiving.await() }
            val duplicate = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.service.uploadPart(
                    initial.ownerId,
                    initial.id,
                    session.id,
                    0,
                    firstPart.size.toLong(),
                    sha1(firstPart),
                    blockedChannel(duplicateChannel, duplicateReceiving),
                )
            }
            withTimeout(5_000) { duplicateReceiving.await() }
            assertTrue(fixture.service.completeUpload(initial.ownerId, initial.id, session.id).isFailure)

            firstChannel.writeFully(firstPart)
            firstChannel.close()
            assertTrue(first.await().isSuccess)
            assertTrue(
                fixture.service.uploadPart(
                    initial.ownerId,
                    initial.id,
                    session.id,
                    1,
                    secondPart.size.toLong(),
                    sha1(secondPart),
                    ByteReadChannel(secondPart),
                ).isSuccess,
            )
            assertEquals(
                BaseWorldUploadStatus.Queued,
                fixture.service.completeUpload(initial.ownerId, initial.id, session.id).getOrThrow().status,
            )

            duplicateChannel.writeFully(firstPart)
            duplicateChannel.close()
            assertTrue(duplicate.await().isFailure)
            fixture.runPending()
            assertContentEquals(archiveBytes, fixture.destination(initial).resolve("world.tar.zst").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `create accepts zero and max sizes and rejects out of range before mutation`() = runTest {
        val root = Files.createTempDirectory("base-world-service-size").toFile()
        try {
            val initial = world(size = 0)
            val fixture = fixture(root, initial)
            fixture.worlds.clear()

            val zero = fixture.service.create(initial.ownerId, "zero", "normal", null, 0, owner = RAccount.DEFAULT).getOrThrow()
            val maximum = fixture.service.create(initial.ownerId, "maximum", "normal", null, BaseWorld.MaxSize, owner = RAccount.DEFAULT).getOrThrow()
            assertEquals(0, zero.size)
            assertEquals(BaseWorld.MaxSize, maximum.size)
            assertEquals(setOf(0L, BaseWorld.MaxSize), fixture.worlds.values.map { it.size }.toSet())

            assertTrue(fixture.service.create(initial.ownerId, "negative", "normal", null, -1, owner = RAccount.DEFAULT).isFailure)
            assertTrue(
                fixture.service.create(
                    initial.ownerId,
                    "large",
                    "normal",
                    null,
                    BaseWorld.MaxSize + 1, owner = RAccount.DEFAULT,
                ).isFailure
            )
            assertEquals(2, fixture.worlds.size)
            assertEquals(setOf("zero", "maximum"), fixture.worlds.values.map { it.name }.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun uploadArchive(fixture: Fixture, world: BaseWorld, archive: File): BaseWorld {
        val session = startAndUpload(fixture.service, world, archive)
        fixture.service.completeUpload(world.ownerId, world.id, session.id).getOrThrow()
        fixture.runPending()
        return fixture.service.findById(world.ownerId, world.id).getOrThrow()!!
    }

    private suspend fun startAndUpload(
        service: BaseWorldService,
        world: BaseWorld,
        archive: File,
    ) = service.createUpload(
        world.ownerId,
        world.id,
        BaseWorldUploadSessionCreateDto(archive.length(), sha1(archive.readBytes())),
    ).getOrThrow().also { session ->
        val bytes = archive.readBytes()
        for (index in 0 until session.partCount) {
            val start = index * session.partSize
            val part = bytes.copyOfRange(start, minOf(bytes.size, start + session.partSize))
            service.uploadPart(
                world.ownerId,
                world.id,
                session.id,
                index,
                part.size.toLong(),
                sha1(part),
                ByteReadChannel(part),
            ).getOrThrow()
        }
    }

    private fun fixture(
        root: File,
        initial: BaseWorld,
        beforeUpdate: (BaseWorld) -> Unit = {},
        beforeDelete: (BaseWorld) -> Unit = {},
        partSize: Int = 11,
    ): Fixture {
        val worlds = linkedMapOf(initial.id to initial)
        val pendingTasks = mutableListOf<Task2>()
        val repository = mockk<PgBaseWorldRepo>()
        every { repository.findById(any(), any()) } answers {
            val owner = firstArg<UUID>()
            val id = secondArg<UUID>()
            worlds[id]?.takeIf { it.ownerId == owner }
        }
        val accounts = mockk<calebxzau.rdi.server.account.PgAccountRepo>()
        every { accounts.lock(any()) } returns true
        every { repository.countByOwner(any()) } answers {
            val owner = firstArg<UUID>()
            worlds.values.count { it.ownerId == owner }.toLong()
        }
        every { repository.create(any(), any(), any(), any(), any()) } answers {
            val created = BaseWorld(
                id = uuid7j(),
                ownerId = firstArg(),
                name = secondArg(),
                levelType = thirdArg(),
                generatorSettings = invocation.args[3] as String?,
                size = invocation.args[4] as Long,
            )
            worlds[created.id] = created
            created
        }
        every { repository.updateSize(any(), any(), any()) } answers {
            val owner = firstArg<UUID>()
            val id = secondArg<UUID>()
            val size = thirdArg<Long>()
            val existing = worlds[id]?.takeIf { it.ownerId == owner } ?: return@answers null
            beforeUpdate(existing)
            existing.copy(size = size).also { worlds[id] = it }
        }
        every { repository.delete(any(), any()) } answers {
            val owner = firstArg<UUID>()
            val id = secondArg<UUID>()
            val existing = worlds[id]?.takeIf { it.ownerId == owner } ?: return@answers false
            beforeDelete(existing)
            worlds.remove(id) != null
        }

        val database = mockk<DatabaseProvider>()
        coEvery { database.transaction<Any?>(any()) } coAnswers {
            firstArg<JdbcTransaction.() -> Any?>().invoke(mockk(relaxed = true))
        }
        val uploads = BaseWorldUploadStore(root, partSize = partSize)
        val service = BaseWorldService(
            database = database,
            repository = repository,
            uploads = uploads,
            worldDestination = { root.resolve(it.id.toString()) },
            taskSubmitter = { task, _ ->
                pendingTasks += task
                "test-${pendingTasks.size}"
            },
            taskStarter = {},
            notifier = { _, _, _ -> },
            accounts = accounts,
        )
        return Fixture(service, worlds, pendingTasks) { world -> root.resolve(world.id.toString()) }
    }

    private data class Fixture(
        val service: BaseWorldService,
        val worlds: MutableMap<UUID, BaseWorld>,
        val pendingTasks: MutableList<Task2>,
        val destination: (BaseWorld) -> File,
    ) {
        suspend fun runPending() {
            while (pendingTasks.isNotEmpty()) {
                val task = pendingTasks.removeAt(0) as Task2.Leaf
                runCatching { task.action(Task2Context(emitProgress = {})) }
            }
        }
    }

    private fun world(size: Long): BaseWorld = BaseWorld(
        id = uuid7j(),
        ownerId = ObjectId().toUUID(),
        name = "Test world",
        levelType = "normal",
        generatorSettings = null,
        size = size,
    )

    private fun uploadedFiles(): Map<String, ByteArray> = linkedMapOf(
        "level.dat" to byteArrayOf(1, 2, 3),
        "region/r.0.0.mca" to byteArrayOf(4, 5, 6, 7),
        "data/raids.dat" to byteArrayOf(8, 9),
    )

    private fun archive(root: File, files: Map<String, ByteArray>): File {
        val archive = root.resolve("upload-${uuid7j()}.tar.zst")
        TarZstArchiveWriter(archive).use { writer ->
            writer.addDirectory("region")
            writer.addDirectory("data")
            files.forEach { (path, bytes) -> writer.addFile(path, bytes) }
        }
        return archive
    }

    private fun filesOnDisk(destination: File): Long = destination.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    private fun sha1(bytes: ByteArray): String = bytes.sha1

    private fun blockedChannel(
        channel: ByteChannel,
        receiving: CompletableDeferred<Unit>,
    ): io.ktor.utils.io.ByteReadChannel = object : io.ktor.utils.io.ByteReadChannel by channel {
        override suspend fun awaitContent(min: Int): Boolean {
            receiving.complete(Unit)
            return channel.awaitContent(min)
        }
    }
}
