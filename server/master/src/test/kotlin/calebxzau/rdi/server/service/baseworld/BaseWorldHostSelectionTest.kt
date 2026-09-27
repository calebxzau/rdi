package calebxzau.rdi.server.service.baseworld

import calebxzau.rdi.common.model.BaseWorld
import calebxzau.rdi.common.util.uuid7j
import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.common.model.Task2CancelledException
import calebxzau.rdi.server.account.PgAccountRepo
import calebxzhou.rdi.master.infra.postgres.DatabaseProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaseWorldHostSelectionTest {
    @Test
    fun `generated template publishes marker and is ready without archive`() = runTest {
        val root = Files.createTempDirectory("base-world-generated").toFile()
        try {
            val owner = UUID.randomUUID()
            val account = mockk<PgAccountRepo>()
            val repository = mockk<PgBaseWorldRepo>()
            var created: BaseWorld? = null
            every { account.lock(owner) } returns true
            every { repository.countByOwner(owner) } returns 0L
            every { repository.create(any(), owner, any(), any(), any(), any()) } answers {
                world(owner).copy(id = firstArg()).also { created = it }
            }
            every { repository.findById(owner, any()) } answers { created }
            every { repository.findById(any<UUID>()) } answers { created }
            every { repository.delete(owner, any()) } returns true
            val service = BaseWorldService(
                database = mockk<DatabaseProvider>().also { database ->
                    coEvery { database.transaction<Any?>(any()) } coAnswers {
                        firstArg<JdbcTransaction.() -> Any?>().invoke(mockk(relaxed = true))
                    }
                },
                repository = repository,
                accounts = account,
                worldDestination = { root.resolve(it.id.toString()) },
            )

            val template = service.create(owner, "Generated", "normal", null, 0, generated = true,).getOrThrow()
            assertTrue(root.resolve(template.id.toString()).resolve(".generated").isFile)
            assertTrue(service.requireReadyForHost(template.id).isSuccess)
            assertTrue(service.acquireSnapshotLease(template.id).getOrThrow().generated)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `generated template rejects non zero size`() = runTest {
        val repository = mockk<PgBaseWorldRepo>()
        val account = mockk<PgAccountRepo>()
        val database = mockk<DatabaseProvider>()
        val service = BaseWorldService(database, repository, accounts = account)
        assertTrue(service.create(UUID.randomUUID(), "Generated", "normal", null, 1, generated = true,).isFailure)
    }

    @Test
    fun `generated marker must be a regular file`() = runTest {
        val root = Files.createTempDirectory("base-world-marker").toFile()
        try {
            val owner = UUID.randomUUID()
            val template = world(owner)
            val destination = root.resolve(template.id.toString()).apply { mkdirs() }
            val repository = repository(template)
            val service = service(root, repository)
            val marker = destination.resolve(".generated")
            marker.mkdirs()
            assertTrue(service.requireReadyForHost(template.id).isFailure)
            marker.deleteRecursively()

            val target = root.resolve("marker-target").apply { writeText("") }
            val symlinkCreated = runCatching {
                Files.createSymbolicLink(marker.toPath(), target.toPath())
            }.isSuccess
            if (symlinkCreated) {
                assertTrue(service.requireReadyForHost(template.id).isFailure)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `generated marker is cleaned when metadata insert fails`() = runTest {
        val root = Files.createTempDirectory("base-world-insert-failure").toFile()
        try {
            val owner = UUID.randomUUID()
            val account = mockk<PgAccountRepo>()
            val repository = mockk<PgBaseWorldRepo>()
            every { account.lock(owner) } returns true
            every { repository.countByOwner(owner) } returns 0L
            every { repository.create(any(), owner, any(), any(), any(), any()) } throws IllegalStateException("insert failed")
            val database = mockk<DatabaseProvider>()
            coEvery { database.transaction<Any?>(any()) } coAnswers {
                firstArg<JdbcTransaction.() -> Any?>().invoke(mockk(relaxed = true))
            }
            val service = BaseWorldService(
                database = database,
                repository = repository,
                accounts = account,
                worldDestination = { root.resolve(it.id.toString()) },
            )

            assertTrue(service.create(owner, "Generated", "normal", null, 0, generated = true,).isFailure)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `template names follow modpack rules and owner quota is three`() = runTest {
        val owner = UUID.randomUUID()
        val repository = mockk<PgBaseWorldRepo>()
        val account = mockk<PgAccountRepo>()
        var count = 0L
        every { account.lock(owner) } returns true
        every { repository.countByOwner(owner) } answers { count }
        every { repository.create(owner, any(), any(), any(), any()) } answers {
            count++
            world(owner)
        }
        val database = mockk<DatabaseProvider>()
        coEvery { database.transaction<Any?>(any()) } coAnswers {
            firstArg<JdbcTransaction.() -> Any?>().invoke(mockk(relaxed = true))
        }
        val service = BaseWorldService(database, repository, accounts = account)
        repeat(3) { service.create(owner, "Template$it", "normal", null, 1,).getOrThrow() }
        assertTrue(service.create(owner, "Template4", "normal", null, 1,).isFailure)
        assertTrue(service.create(owner, "bad/name", "normal", null, 1,).isFailure)
    }

    @Test
    fun `released list excludes unpublished templates and includes foreign templates`() = runTest {
        val root = Files.createTempDirectory("base-world-ready-list").toFile()
        try {
            val owner = UUID.randomUUID()
            val foreign = UUID.randomUUID()
            val ready = world(owner)
            val unpublished = world(owner)
            val foreignWorld = world(foreign)
            val all = listOf(ready, unpublished, foreignWorld)
            val repository = mockk<PgBaseWorldRepo>()
            every { repository.listAll() } returns all
            every { repository.findById(any<UUID>()) } answers {
                all.singleOrNull { it.id == firstArg<UUID>() }?.copy(size = 999)
            }
            val service = service(root, repository)
            root.resolve(ready.id.toString()).apply { mkdirs() }
                .resolve("world.tar.zst").writeText("ready")
            root.resolve(foreignWorld.id.toString()).apply { mkdirs() }
                .resolve("world.tar.zst").writeText("foreign")

            assertEquals(setOf(foreignWorld, ready), service.listReleased().getOrThrow().toSet())
            verify(exactly = 0) { repository.findById(any<UUID>()) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `snapshot remains usable after template deletion and is independent`() = runTest {
        val root = Files.createTempDirectory("base-world-snapshot").toFile()
        try {
            val owner = UUID.randomUUID()
            val template = world(owner)
            val repository = repository(template)
            val service = service(root, repository)
            val archive = archive(root, "level.dat" to byteArrayOf(1, 2, 3), "region/a" to byteArrayOf(4))
            val destination = root.resolve(template.id.toString()).apply { mkdirs() }
            archive.copyTo(destination.resolve("world.tar.zst"), overwrite = true)

            val snapshot = service.snapshotForHost(template.id).getOrThrow()
            assertTrue(service.delete(owner, template.id).getOrThrow())
            val hostWorld = root.resolve("host/world")
            hostWorld.parentFile.mkdirs()
            service.extractSnapshotForHost(snapshot, hostWorld).getOrThrow()
            assertContentEquals(byteArrayOf(1, 2, 3), hostWorld.resolve("level.dat").readBytes())
            snapshot.delete()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `snapshot requires a published archive regardless of owner`() = runTest {
        val root = Files.createTempDirectory("base-world-admission").toFile()
        try {
            val owner = UUID.randomUUID()
            val other = UUID.randomUUID()
            val template = world(owner)
            val service = service(root, repository(template))
            assertTrue(service.requireReadyForHost(template.id).isFailure)
            root.resolve(template.id.toString()).apply { mkdirs() }
                .resolve("world.tar.zst").writeText("archive")
            assertEquals(template, service.requireReadyForHost(template.id).getOrThrow())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `valid archive is extracted independently and malformed archives leave no target`() = runTest {
        val root = Files.createTempDirectory("base-world-extract").toFile()
        try {
            val store = BaseWorldUploadStore(root, maxArchiveSize = 1024 * 1024, maxExtractedSize = 1024)
            val valid = archive(root, "level.dat" to byteArrayOf(9, 8), "region/a" to byteArrayOf(7))
            val target = root.resolve("valid")
            store.extractArchiveToDir(valid, target)
            assertContentEquals(byteArrayOf(9, 8), target.resolve("level.dat").readBytes())

            val traversal = archive(root, "../escape" to byteArrayOf(1), "level.dat" to byteArrayOf(1))
            val duplicate = root.resolve("duplicate.tar.zst")
            TarZstArchiveWriter(duplicate).use { writer ->
                writer.addFile("level.dat", byteArrayOf(1))
                writer.addFile("LEVEL.DAT", byteArrayOf(2))
            }
            val missing = archive(root, "region/a" to byteArrayOf(1))
            for ((index, invalid) in listOf(traversal, duplicate, missing).withIndex()) {
                val invalidTarget = root.resolve("invalid-$index")
                assertFailsWith<Exception> { store.extractArchiveToDir(invalid, invalidTarget) }
                assertFalse(invalidTarget.exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancelled extraction removes stage and final world`() = runTest {
        val root = Files.createTempDirectory("base-world-cancel").toFile()
        try {
            val owner = UUID.randomUUID()
            val template = world(owner)
            val repository = repository(template)
            val service = service(root, repository)
            val archive = archive(root, "level.dat" to ByteArray(16 * 1024) { 1 })
            val destination = root.resolve(template.id.toString()).apply { mkdirs() }
            archive.copyTo(destination.resolve("world.tar.zst"), overwrite = true)
            val snapshot = service.snapshotForHost(template.id).getOrThrow()
            val target = root.resolve("host/world")
            target.parentFile.mkdirs()
            assertFailsWith<Task2CancelledException> {
                service.extractSnapshotForHost(snapshot, target) { throw Task2CancelledException() }.getOrThrow()
            }
            assertFalse(target.exists())
            assertTrue(target.parentFile.listFiles()?.none { it.name.startsWith(".world-stage-") } == true)
            snapshot.delete()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `snapshot replacement publishes template and removes old-only content`() = runTest {
        val root = Files.createTempDirectory("base-world-replace").toFile()
        try {
            val owner = UUID.randomUUID()
            val template = world(owner)
            val service = service(root, repository(template))
            val archive = archive(root, "level.dat" to byteArrayOf(1, 2, 3), "region/a" to byteArrayOf(4))
            root.resolve(template.id.toString()).apply { mkdirs() }
                .resolve("world.tar.zst").also { archive.copyTo(it, overwrite = true) }
            val snapshot = service.snapshotForHost(template.id).getOrThrow()
            val target = root.resolve("host/world")
            target.mkdirs()
            target.resolve("old-only.txt").writeText("old")
            target.resolve("level.dat").writeText("old-level")

            service.replaceWorldFromSnapshot(snapshot, target).getOrThrow()

            assertContentEquals(byteArrayOf(1, 2, 3), target.resolve("level.dat").readBytes())
            assertContentEquals(byteArrayOf(4), target.resolve("region/a").readBytes())
            assertFalse(target.resolve("old-only.txt").exists())
            assertTrue(target.parentFile.listFiles()?.none { it.name.startsWith(".world-stage-") || it.name.startsWith(".world.backup-") } == true)
            snapshot.delete()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `corrupt snapshot preserves existing world and cleans replacement stage`() = runTest {
        val root = Files.createTempDirectory("base-world-replace-corrupt").toFile()
        try {
            val owner = UUID.randomUUID()
            val template = world(owner)
            val service = service(root, repository(template))
            val archive = archive(root, "region/a" to byteArrayOf(4))
            root.resolve(template.id.toString()).apply { mkdirs() }
                .resolve("world.tar.zst").also { archive.copyTo(it, overwrite = true) }
            val snapshot = service.snapshotForHost(template.id).getOrThrow()
            val target = root.resolve("host/world")
            target.mkdirs()
            target.resolve("old-only.txt").writeText("old")
            val oldContent = target.resolve("old-only.txt").readBytes()

            assertFailsWith<Exception> { service.replaceWorldFromSnapshot(snapshot, target).getOrThrow() }

            assertContentEquals(oldContent, target.resolve("old-only.txt").readBytes())
            assertTrue(target.parentFile.listFiles()?.none { it.name.startsWith(".world-stage-") || it.name.startsWith(".world.backup-") } == true)
            snapshot.delete()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `snapshot replacement publishes template when existing world is absent`() = runTest {
        val root = Files.createTempDirectory("base-world-replace-empty").toFile()
        try {
            val owner = UUID.randomUUID()
            val template = world(owner)
            val service = service(root, repository(template))
            val archive = archive(root, "level.dat" to byteArrayOf(5, 6))
            root.resolve(template.id.toString()).apply { mkdirs() }
                .resolve("world.tar.zst").also { archive.copyTo(it, overwrite = true) }
            val snapshot = service.snapshotForHost(template.id).getOrThrow()
            val target = root.resolve("host/world")
            target.parentFile.mkdirs()

            service.replaceWorldFromSnapshot(snapshot, target).getOrThrow()

            assertContentEquals(byteArrayOf(5, 6), target.resolve("level.dat").readBytes())
            assertTrue(target.parentFile.listFiles()?.none { it.name.startsWith(".world-stage-") || it.name.startsWith(".world.backup-") } == true)
            snapshot.delete()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `publication failure restores old world and cleans swap residue`() = runTest {
        val root = Files.createTempDirectory("base-world-replace-publish-failure").toFile()
        try {
            val owner = UUID.randomUUID()
            val template = world(owner)
            val archive = archive(root, "level.dat" to byteArrayOf(7, 8))
            root.resolve(template.id.toString()).apply { mkdirs() }
                .resolve("world.tar.zst").also { archive.copyTo(it, overwrite = true) }
            val service = service(root, repository(template)) { _, _ -> throw IOException("injected publication failure") }
            val snapshot = service.snapshotForHost(template.id).getOrThrow()
            val target = root.resolve("host/world")
            target.mkdirs()
            target.resolve("old-only.txt").writeText("old")

            assertFailsWith<IOException> { service.replaceWorldFromSnapshot(snapshot, target).getOrThrow() }

            assertEquals("old", target.resolve("old-only.txt").readText())
            assertTrue(Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertTrue(target.parentFile.listFiles()?.none {
                it.name.startsWith(".world-stage-") || it.name.startsWith(".world.backup-")
            } == true)
            snapshot.delete()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `world symlink is moved as an entry without touching its target`() = runTest {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            calebxzhou.rdi.common.util.canCreateSymlink(),
            "symbolic links are unsupported on this platform",
        )
        val root = Files.createTempDirectory("base-world-replace-symlink").toFile()
        try {
            val owner = UUID.randomUUID()
            val template = world(owner)
            val service = service(root, repository(template))
            val archive = archive(root, "level.dat" to byteArrayOf(9))
            root.resolve(template.id.toString()).apply { mkdirs() }
                .resolve("world.tar.zst").also { archive.copyTo(it, overwrite = true) }
            val snapshot = service.snapshotForHost(template.id).getOrThrow()
            val external = root.resolve("external-world").apply { mkdirs() }
            external.resolve("keep.txt").writeText("keep")
            val target = root.resolve("host/world")
            target.parentFile.mkdirs()
            Files.createSymbolicLink(target.toPath(), external.toPath())

            service.replaceWorldFromSnapshot(snapshot, target).getOrThrow()

            assertTrue(Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isDirectory(external.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertEquals("keep", external.resolve("keep.txt").readText())
            snapshot.delete()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun service(
        root: File,
        repository: PgBaseWorldRepo,
        movePreparedWorld: (Path, Path) -> Unit = { source, target ->
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        },
    ): BaseWorldService {
        val database = mockk<DatabaseProvider>()
        coEvery { database.transaction<Any?>(any()) } coAnswers {
            firstArg<JdbcTransaction.() -> Any?>().invoke(mockk(relaxed = true))
        }
        return BaseWorldService(
            database = database,
            repository = repository,
            uploads = BaseWorldUploadStore(root),
            worldDestination = { root.resolve(it.id.toString()) },
            taskStarter = {},
            notifier = { _, _, _ -> },
            movePreparedWorld = movePreparedWorld,
        )
    }

    private fun repository(vararg worlds: BaseWorld): PgBaseWorldRepo {
        val values = worlds.toList()
        val repository = mockk<PgBaseWorldRepo>()
        every { repository.listByOwner(any()) } answers { values.filter { it.ownerId == firstArg<UUID>() } }
        every { repository.listAll() } returns values
        every { repository.findById(any(), any()) } answers {
            values.singleOrNull { it.ownerId == firstArg<UUID>() && it.id == secondArg<UUID>() }
        }
        every { repository.findById(any<UUID>()) } answers {
            values.singleOrNull { it.id == firstArg<UUID>() }
        }
        every { repository.delete(any(), any()) } answers {
            values.any { it.ownerId == firstArg<UUID>() && it.id == secondArg<UUID>() }
        }
        return repository
    }

    private fun world(owner: UUID) = BaseWorld(uuid7j(), owner, "Template", "normal", null, 1)

    private fun archive(root: File, vararg files: Pair<String, ByteArray>): File {
        val archive = root.resolve("${uuid7j()}.tar.zst")
        TarZstArchiveWriter(archive).use { writer -> files.forEach { writer.addFile(it.first, it.second) } }
        return archive
    }
}
