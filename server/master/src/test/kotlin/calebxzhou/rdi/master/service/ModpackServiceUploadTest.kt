package calebxzhou.rdi.master.service

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.ModpackUploadPreflightDto
import calebxzhou.rdi.common.model.ModpackUploadSessionCreateDto
import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.master.service.ModpackService.createVersion
import calebxzhou.rdi.master.service.ModpackService.createWithVersion
import calebxzhou.rdi.master.service.modpack.ModpackUploadService.preflight
import calebxzhou.rdi.master.service.modpack.ModpackUploadService
import calebxzhou.rdi.common.service.validateIconUrl
import com.mongodb.client.result.InsertOneResult
import com.mongodb.client.result.UpdateResult
import com.mongodb.client.model.InsertOneOptions
import com.mongodb.client.model.DeleteOptions
import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.slot
import com.mongodb.kotlin.client.coroutine.FindFlow
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.runTest
import org.bson.conversions.Bson
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModpackServiceUploadTest {
    private lateinit var collection: MongoCollection<Modpack>

    @BeforeTest
    fun setup() {
        collection = mockk(relaxed = true)
        ModpackService.testDbcl = collection
        mockkStatic("calebxzhou.rdi.common.service.ModpackXtKt")
        coEvery { validateIconUrl(any()) } returns Result.success(Unit)
        coEvery { collection.countDocuments(any<Bson>()) } returns 0L
    }

    @AfterTest
    fun cleanup() {
        ServerTaskManager.testSubmitter = null
        unmockkStatic("calebxzhou.rdi.common.service.ModpackXtKt")
        ModpackService.testDbcl = null
    }

    @Test
    fun `create version accepts tar zst moves archive and queues build`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(player._id)
        stubPack(pack)
        val root = ModpackServiceTestFixtures.tempRoot()
        val upload = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to "ok".encodeToByteArray())
        val extra = Content(
            platform = ContentPlatform.Modrinth,
            type = ContentType.ResPack,
            projectId = "resource-project",
            fileId = "resource-version",
            slug = "resource-pack",
            hash = "a".repeat(40),
            path = "resourcepacks/resource-pack.zip",
            side = ContentSide.Client,
            downloadUrls = listOf("https://example.com/resource-pack.zip"),
        )
        val update = mockk<UpdateResult>()
        every { update.modifiedCount } returns 1L
        val updatePayload = slot<Bson>()
        coEvery { collection.updateOne(any<Bson>(), capture(updatePayload), any()) } returns update
        var submitted = false
        ServerTaskManager.testSubmitter = { _, _, _ -> submitted = true; "test" }
        try {
            ModpackContext(player, pack, null).createVersion(
                "1.0.0",
                upload,
                mutableListOf(ModpackServiceTestFixtures.mod("example")),
                mutableListOf(extra),
            )
            assertTrue(pack.dir.resolve("1.0.0.tar.zst").exists())
            assertFalse(upload.exists())
            assertTrue(submitted)
            assertTrue(updatePayload.captured.toString().contains("clientExtras"))
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `create version rejects server-only client extra before publication and cleans upload temp`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(player._id)
        stubPack(pack)
        val root = ModpackServiceTestFixtures.tempRoot("server-only-extra")
        val upload = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(1, 2, 3))
        val serverOnly = Content(
            platform = ContentPlatform.Modrinth,
            type = ContentType.ShaderPack,
            projectId = "shader-project",
            fileId = "shader-version",
            slug = "shader-pack",
            hash = "b".repeat(40),
            path = "shaderpacks/shader-pack.zip",
            side = ContentSide.Server,
            downloadUrls = listOf("https://example.com/shader-pack.zip"),
        )
        var submitted = false
        ServerTaskManager.testSubmitter = { _, _, _ -> submitted = true; "test" }
        try {
            assertFailsWith<RequestError> {
                ModpackContext(player, pack, null).createVersion(
                    "1.0.0",
                    upload,
                    mutableListOf(),
                    mutableListOf(serverOnly),
                )
            }
            assertFalse(upload.exists())
            assertFalse(pack.dir.resolve("1.0.0.tar.zst").exists())
            assertTrue(pack.versions.isEmpty())
            assertFalse(submitted)
            coVerify(exactly = 0) { collection.updateOne(any<Bson>(), any<Bson>(), any()) }
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `parallel upload feeds existing version publication and consumes session`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(player._id)
        stubPack(pack)
        val root = ModpackServiceTestFixtures.tempRoot("parallel-version-upload")
        val archive = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(1, 2, 3))
        val bytes = archive.readBytes()
        val uploader = ModpackParallelUploadService(root.resolve("sessions"), 1024, partSize = bytes.size)
        val session = uploader.create(
            player._id,
            ModpackUploadSessionCreateDto("pack.tar.zst", bytes.size.toLong(), bytes.sha1),
        ).getOrThrow()
        val update = mockk<UpdateResult>()
        every { update.modifiedCount } returns 1L
        coEvery { collection.updateOne(any<Bson>(), any<Bson>(), any()) } returns update
        ServerTaskManager.testSubmitter = { _, _, _ -> "test" }
        try {
            uploader.uploadPart(
                player._id,
                session.id,
                0,
                bytes.size.toLong(),
                bytes.sha1,
                ByteReadChannel(bytes),
            ).getOrThrow()
            uploader.complete(player._id, session.id).getOrThrow()
            uploader.withReadyUpload(player._id, session.id) { uploadFile ->
                ModpackContext(player, pack, null).createVersion("1.0.0", uploadFile, mutableListOf())
            }.getOrThrow()
            assertEquals(bytes.toList(), pack.dir.resolve("1.0.0.tar.zst").readBytes().toList())
            assertTrue(uploader.status(player._id, session.id).isFailure)
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `parallel upload feeds new pack publication`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val dto = Modpack.CreateWithVersionDto(
            name = "Parallel_New_Pack",
            mcVer = McVersion.V211,
            modLoader = McVersion.V211.loaderVersions.keys.first(),
            verName = "1.0.0",
            info = "valid description with enough characters",
            iconUrl = "https://modrinth.com/icon.png",
            categories = listOf(Modpack.Category.OTHER),
            mods = mutableListOf(),
        )
        val root = ModpackServiceTestFixtures.tempRoot("parallel-new-upload")
        val archive = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(4, 5, 6))
        val bytes = archive.readBytes()
        val uploader = ModpackParallelUploadService(root.resolve("sessions"), 1024, partSize = bytes.size)
        val session = uploader.create(
            player._id,
            ModpackUploadSessionCreateDto("pack.tar.zst", bytes.size.toLong(), bytes.sha1),
        ).getOrThrow()
        val inserted = slot<Modpack>()
        coEvery { collection.insertOne(capture(inserted), any<InsertOneOptions>()) } returns mockk(relaxed = true)
        ServerTaskManager.testSubmitter = { _, _, _ -> "test" }
        try {
            uploader.uploadPart(
                player._id,
                session.id,
                0,
                bytes.size.toLong(),
                bytes.sha1,
                ByteReadChannel(bytes),
            ).getOrThrow()
            uploader.complete(player._id, session.id).getOrThrow()
            uploader.withReadyUpload(player._id, session.id) { uploadFile ->
                dto.createWithVersion(player, uploadFile)
            }.getOrThrow()
            assertTrue(inserted.isCaptured)
            assertEquals(bytes.toList(), inserted.captured.dir.resolve("1.0.0.tar.zst").readBytes().toList())
            assertEquals(player._id, inserted.captured.versions.single().uploaderId)
        } finally {
            if (inserted.isCaptured) inserted.captured.dir.deleteRecursivelyNoSymlink()
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `create version rejects zip and unsupported minecraft versions`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(player._id, mcVersion = McVersion.V071)
        stubPack(pack)
        val root = ModpackServiceTestFixtures.tempRoot()
        val zip = root.resolve("pack.zip").apply { writeBytes(byteArrayOf(80, 75, 3, 4)) }
        try {
            assertFailsWith<Exception> {
                ModpackContext(player, pack, null).createVersion("1.0.0", zip, mutableListOf())
            }
            assertFalse(pack.dir.exists())
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `create pack requires microsoft account and valid metadata`() = runTest {
        val player = ModpackServiceTestFixtures.account().copy(msid = null)
        val dto = Modpack.CreateWithVersionDto(
            name = "New_Pack",
            mcVer = McVersion.V211,
            modLoader = McVersion.V211.loaderVersions.keys.first(),
            verName = "1.0.0",
            info = "valid description with enough characters",
            iconUrl = "https://example.invalid/icon.png",
            categories = listOf(Modpack.Category.OTHER),
            mods = mutableListOf()
        )
        val root = ModpackServiceTestFixtures.tempRoot()
        val archive = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(1))
        try {
            assertFailsWith<Exception> { dto.createWithVersion(player, archive) }
            assertTrue(archive.exists())
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `create preflight rejects invalid name without mutation`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val dto = ModpackUploadPreflightDto(
            name = "bad/name",
            verName = "1.0.0",
            mcVer = McVersion.V211,
            modLoader = McVersion.V211.loaderVersions.keys.first(),
            iconUrl = "https://modrinth.com/icon.png",
            info = "valid description with enough characters",
            categories = listOf(Modpack.Category.OTHER),
        )

        val error = assertFailsWith<RequestError> {
            dto.preflight(player)
        }

        assertEquals("整合包名称只能包含字母、数字、汉字、空格或._-", error.message)
        coVerify(exactly = 0) { collection.insertOne(any<Modpack>(), any()) }
        assertTrue(ServerTaskManager.testSubmitter == null)
    }

    @Test
    fun `valid create preflight does not create pack or enqueue build`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val dto = ModpackUploadPreflightDto(
            name = "Valid Pack",
            verName = "1.0.0",
            mcVer = McVersion.V211,
            modLoader = McVersion.V211.loaderVersions.keys.first(),
            iconUrl = "https://modrinth.com/icon.png",
            info = "valid description with enough characters",
            categories = listOf(Modpack.Category.OTHER),
        )

        dto.preflight(player)

        coVerify(exactly = 0) { collection.insertOne(any<Modpack>(), any()) }
        assertTrue(ServerTaskManager.testSubmitter == null)
    }

    @Test
    fun `update preflight rejects duplicate version`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(player._id)
        pack.versions += ModpackServiceTestFixtures.version(pack, name = "Release")
        stubPack(pack)

        val error = assertFailsWith<RequestError> {
            ModpackUploadPreflightDto(
                modpackId = pack._id,
                name = pack.name,
                verName = "release",
                mcVer = pack.mcVer,
                modLoader = pack.modloader,
            ).preflight(player)
        }

        assertEquals("版本 release 已存在", error.message)
    }

    @Test
    fun `update preflight rejects minecraft loader and author mismatch`() = runTest {
        val owner = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(owner._id, allowUploaderIds = emptyList())
        stubPack(pack)

        val mcError = assertFailsWith<RequestError> {
            ModpackUploadPreflightDto(
                modpackId = pack._id,
                name = pack.name,
                verName = "2.0",
                mcVer = McVersion.V201,
                modLoader = ModLoader.forge,
            ).preflight(owner)
        }
        assertEquals("MC版本与已有整合包不一致", mcError.message)

        val loaderError = assertFailsWith<RequestError> {
            ModpackUploadPreflightDto(
                modpackId = pack._id,
                name = pack.name,
                verName = "2.0",
                mcVer = pack.mcVer,
                modLoader = ModLoader.forge,
            ).preflight(owner)
        }
        assertEquals("Mod加载器与已有整合包不一致", loaderError.message)

        val authorError = assertFailsWith<RequestError> {
            ModpackUploadPreflightDto(
                modpackId = pack._id,
                name = pack.name,
                verName = "2.0",
                mcVer = pack.mcVer,
                modLoader = pack.modloader,
            ).preflight(ModpackServiceTestFixtures.account("other"))
        }
        assertEquals("没有上传新版本的权限", authorError.message)
    }

    @Test
    fun `published pending marker reconciles orphan before retry`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(player._id)
        stubPack(pack)
        val marker = ModpackUploadService.writePendingVersionPublicationForTest(pack._id, "1.0.0")
        val canonical = pack.dir.resolve("1.0.0.tar.zst")
        canonical.writeBytes(byteArrayOf(8, 8, 8))
        val root = ModpackServiceTestFixtures.tempRoot()
        val archive = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(1))
        val update = mockk<UpdateResult>()
        every { update.modifiedCount } returns 1L
        coEvery { collection.updateOne(any<Bson>(), any<Bson>(), any()) } returns update
        ServerTaskManager.testSubmitter = { _, _, _ -> "test" }
        try {
            ModpackContext(player, pack, null).createVersion("1.0.0", archive, mutableListOf())
            assertTrue(canonical.exists())
            assertFalse(marker.exists())
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `pending marker with database version preserves archive and rejects retry`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(player._id)
        pack.versions += ModpackServiceTestFixtures.version(pack, name = "Release")
        stubPack(pack)
        val marker = ModpackUploadService.writePendingVersionPublicationForTest(pack._id, "Release")
        val canonical = pack.dir.resolve("Release.tar.zst")
        val existingBytes = byteArrayOf(7, 7, 7)
        canonical.writeBytes(existingBytes)
        val root = ModpackServiceTestFixtures.tempRoot()
        val archive = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(1))
        try {
            assertFailsWith<RequestError> {
                ModpackContext(player, pack, null).createVersion("release", archive, mutableListOf())
            }
            assertEquals(existingBytes.toList(), canonical.readBytes().toList())
            assertFalse(marker.exists())
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `unmarked canonical archive is never overwritten`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(player._id)
        stubPack(pack)
        val canonical = pack.dir.resolve("1.0.0.tar.zst")
        val existingBytes = byteArrayOf(6, 6, 6)
        canonical.parentFile.mkdirs()
        canonical.writeBytes(existingBytes)
        val root = ModpackServiceTestFixtures.tempRoot()
        val archive = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(1))
        try {
            assertFailsWith<RequestError> {
                ModpackContext(player, pack, null).createVersion("1.0.0", archive, mutableListOf())
            }
            assertEquals(existingBytes.toList(), canonical.readBytes().toList())
            assertTrue(pack.dir.listFiles().orEmpty().none { it.name.startsWith(".upload-") && it.name.endsWith(".pending") })
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `create pack persists processed version and moves tar archive`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val dto = Modpack.CreateWithVersionDto(
            name = "New_Pack",
            mcVer = McVersion.V211,
            modLoader = McVersion.V211.loaderVersions.keys.first(),
            verName = "1.0.0",
            info = "valid description with enough characters",
            iconUrl = "https://modrinth.com/icon.png",
            categories = listOf(Modpack.Category.OTHER),
            mods = mutableListOf(
                ModpackServiceTestFixtures.mod("status-effect-bars-reforged", side = Mod.Side.BOTH),
                ModpackServiceTestFixtures.mod("spark")
            )
        )
        val root = ModpackServiceTestFixtures.tempRoot()
        val archive = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(1))
        val inserted = slot<Modpack>()
        coEvery { collection.insertOne(capture(inserted), any()) } returns mockk(relaxed = true)
        ServerTaskManager.testSubmitter = { _, _, _ -> "test" }
        try {
            dto.createWithVersion(player, archive)
            val stored = inserted.captured
            assertEquals(1, stored.versions.size)
            assertEquals(listOf("status-effect-bars-reforged"), stored.versions.single().mods.map { it.slug })
            assertEquals(Mod.Side.CLIENT, stored.versions.single().mods.single().side)
            assertTrue(stored.dir.resolve("1.0.0.tar.zst").exists())
        } finally {
            if (inserted.isCaptured) inserted.captured.dir.deleteRecursivelyNoSymlink()
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `create pack insertion failure rolls back published archive without deleting a document`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val dto = Modpack.CreateWithVersionDto(
            name = "Rollback_Pack",
            mcVer = McVersion.V211,
            modLoader = McVersion.V211.loaderVersions.keys.first(),
            verName = "1.0.0",
            info = "valid description with enough characters",
            iconUrl = "https://modrinth.com/icon.png",
            categories = listOf(Modpack.Category.OTHER),
            mods = mutableListOf()
        )
        val root = ModpackServiceTestFixtures.tempRoot()
        val archive = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(1))
        coEvery { collection.insertOne(any<Modpack>(), any<InsertOneOptions>()) } throws IllegalStateException("insert failed")
        try {
            assertFailsWith<IllegalStateException> { dto.createWithVersion(player, archive) }
            assertTrue(!archive.exists())
            coVerify(exactly = 0) { collection.deleteOne(any<Bson>(), any<DeleteOptions>()) }
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `create version duplicate modified count rolls back moved archive`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(player._id)
        stubPack(pack)
        val root = ModpackServiceTestFixtures.tempRoot()
        val archive = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(1))
        val update = mockk<UpdateResult>()
        every { update.modifiedCount } returns 0L
        coEvery { collection.updateOne(any<Bson>(), any<Bson>(), any()) } returns update
        try {
            assertFailsWith<Exception> {
                ModpackContext(player, pack, null).createVersion("1.0.0", archive, mutableListOf())
            }
            assertTrue(!pack.dir.resolve("1.0.0.tar.zst").exists())
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `exact canonical target collision does not overwrite archive or reach database`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(player._id)
        stubPack(pack)
        val root = ModpackServiceTestFixtures.tempRoot()
        val archive = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(1))
        val existingArchive = pack.dir.resolve("1.0.0.tar.zst")
        val existingBytes = byteArrayOf(4, 5, 6)
        existingArchive.parentFile.mkdirs()
        existingArchive.writeBytes(existingBytes)
        try {
            assertFailsWith<RequestError> {
                ModpackContext(player, pack, null).createVersion("1.0.0", archive, mutableListOf())
            }
            assertEquals(existingBytes.toList(), existingArchive.readBytes().toList())
            assertTrue(pack.dir.listFiles().orEmpty().none { it.name.startsWith(".upload-") && it.name.endsWith(".tar.zst") })
            coVerify(exactly = 0) { collection.updateOne(any<Bson>(), any<Bson>(), any()) }
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `case variant duplicate does not delete existing archive during atomic rejection`() = runTest {
        val player = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(player._id)
        stubPack(pack)
        val root = ModpackServiceTestFixtures.tempRoot()
        val archive = ModpackServiceTestFixtures.writeTarZst(root, "overrides/config/test.txt" to byteArrayOf(1))
        val existingArchive = pack.dir.resolve("Release.tar.zst")
        val existingBytes = byteArrayOf(9, 8, 7)
        existingArchive.parentFile.mkdirs()
        existingArchive.writeBytes(existingBytes)
        val update = mockk<UpdateResult>()
        every { update.modifiedCount } returns 0L
        coEvery { collection.updateOne(any<Bson>(), any<Bson>(), any()) } returns update
        try {
            assertFailsWith<Exception> {
                ModpackContext(player, pack, null).createVersion("release", archive, mutableListOf())
            }
            assertEquals(existingBytes.toList(), existingArchive.readBytes().toList())
            assertTrue(pack.dir.listFiles().orEmpty().none { it.name.startsWith(".upload-") && it.name.endsWith(".tar.zst") })
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
            root.deleteRecursivelyNoSymlink()
        }
    }

    private fun stubPack(pack: Modpack) {
        val flow = mockk<FindFlow<Modpack>>()
        every { collection.find(any<Bson>()) } returns flow
        coEvery { flow.collect(any()) } coAnswers {
            firstArg<FlowCollector<Modpack>>().emit(pack)
        }
    }
}
