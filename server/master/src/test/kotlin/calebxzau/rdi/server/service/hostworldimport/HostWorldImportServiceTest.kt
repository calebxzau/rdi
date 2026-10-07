package calebxzau.rdi.server.service.hostworldimport

import calebxzau.rdi.common.model.HostWorldImportCreateDto
import calebxzau.rdi.common.model.HostWorldImportPrecheckDto
import calebxzau.rdi.common.model.HostWorldImportStatus
import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.model.Role
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bson.types.ObjectId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostWorldImportServiceTest {
    private val root: File = Files.createTempDirectory("host-world-import").toFile()
    private val owner = ObjectId()
    private val friend = ObjectId()
    private val modpack = Modpack(name = "Pack", authorId = ObjectId(), modloader = ModLoader.forge, mcVer = McVersion.V201)
    private val access = FakeHostAccess(root.resolve("hosts").toPath())
    private val store = HostWorldImportStore(root.resolve("imports"), partSize = 4096)
    private val tasks = ArrayList<Task2.Leaf>()
    private val mails = ArrayList<Pair<String, String>>()
    private val service = newService()

    /** A service with its own execution claims, as after a restart; it shares the files and fakes. */
    private fun newService() = HostWorldImportService(
        store = store,
        hosts = access,
        taskSubmitter = { task, _ -> tasks += task as Task2.Leaf; "run-${tasks.size}" },
        taskStarter = {},
        notifier = { _, title, content -> mails += title to content },
    )

    init {
        access.modpacks[modpack._id] = modpack
        access.accounts += listOf(owner, friend)
        HostWorldImportGuard.store = store
    }

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun importReplacesTheWorldAndFinishes() = runBlocking {
        val host = access.addHost(gameRules = mutableMapOf("keepInventory" to "true"))
        access.worldFile(host, "level.dat").writeText("old world")
        val archive = archive(level(), "region/r.0.0.mca" to byteArrayOf(1, 2, 3))

        val session = upload(host, archive, members = listOf(owner, friend))
        assertTrue(store.blockingSession(host._id) != null, "a queued import blocks the host")
        assertFailsWith<RequestError> { HostWorldImportGuard.check(host._id) }
        runTasks()

        val status = service.status(host._id, session, owner)
        assertEquals(HostWorldImportStatus.Ready, status.status)
        assertTrue(access.worldFile(host, "region/r.0.0.mca").exists())
        assertFalse(access.hostDir(host).resolve(".world-import-old-${session}").exists(), "the old world is deleted")
        assertFalse(access.hostDir(host).resolve(".world-import-stage-${session}").exists())
        assertTrue(access.clearedGameRules.contains(host._id))
        assertEquals<List<ObjectId>?>(listOf(friend), access.addedMembers[host._id], "the owner is already a member")
        assertEquals("存档导入完成", mails.single().first)
        assertNull(store.blockingSession(host._id))
        HostWorldImportGuard.check(host._id)
        assertFalse(store.find(host._id, session)!!.archive.exists(), "payloads are cleaned up")
    }

    @Test
    fun precheckRejectsWithoutSideEffects() = runBlocking {
        val host = access.addHost()
        val good = HostWorldImportPrecheckDto("1.20.1", ModLoader.forge, listOf(owner))
        service.precheck(host, owner, good.copy(memberPlayerIds = listOf(friend))).let {
            assertTrue(it.memberWarnings.isEmpty())
            assertEquals(0, it.gameRuleOverrideCount)
        }

        val cases = mapOf(
            "not owner" to suspend { service.precheck(host, friend, good) },
            "version" to suspend { service.precheck(host, owner, good.copy(mcVersionName = "1.21.1")) },
            "loader" to suspend { service.precheck(host, owner, good.copy(loader = ModLoader.neoforge)) },
            "missing account" to suspend { service.precheck(host, owner, good.copy(memberPlayerIds = listOf(ObjectId()))) },
            "too many" to suspend { service.precheck(host, owner, good.copy(memberPlayerIds = List(33) { ObjectId() })) },
            "v1 host" to suspend { service.precheck(access.addHost(version = 1), owner, good) },
            "running" to suspend { service.precheck(access.addHost(stopped = false), owner, good) },
            "neoforge host" to suspend {
                val neo = Modpack(name = "Neo", authorId = ObjectId(), modloader = ModLoader.neoforge, mcVer = McVersion.V211)
                access.modpacks[neo._id] = neo
                service.precheck(access.addHost(modpackId = neo._id), owner, good.copy(mcVersionName = "1.21.1", loader = ModLoader.neoforge))
            },
        )
        cases.forEach { (name, call) -> assertFailsWith<RequestError>(name) { call() } }
        assertTrue(store.allSessions().isEmpty(), "precheck never creates a session")
    }

    @Test
    fun theUploadedSaveIsCheckedAgain() = runBlocking {
        val cases = mapOf(
            "version" to archive(level(version = "1.19.2")),
            "not saved by forge" to archive(level(modIds = null)),
            "hardcore" to archive(level(hardcore = true)),
            "no sync list" to archive(level(), sync = null),
            "empty sync list" to archive(level(), sync = syncList(0)),
            "gzip bomb" to archive(gzip(ByteArray(40 * 1024 * 1024))),
            "huge array" to archive(gzip(byteArrayOf(10, 0, 0, 7, 0, 1, 'a'.code.toByte(), 0x7f, -1, -1, -1, 0))),
        )
        cases.forEach { (name, archive) ->
            val host = access.addHost()
            access.worldFile(host, "level.dat").writeText("old world")
            val session = upload(host, archive)
            assertFailsWith<RequestError>(name) { runTasks() }
            assertEquals(HostWorldImportStatus.Failed, service.status(host._id, session, owner).status, name)
            assertEquals("old world", access.worldFile(host, "level.dat").readText(), name)
            assertFalse(access.hostDir(host).resolve(".world-import-stage-${session}").exists(), name)
            assertNull(store.blockingSession(host._id), name)
        }
        assertEquals(cases.size, mails.count { it.first == "存档导入失败" })
    }

    @Test
    fun ownerChangeDuringUploadFailsBeforeTheSwap() = runBlocking {
        val host = access.addHost()
        access.worldFile(host, "level.dat").writeText("old world")
        upload(host, archive(level()))
        access.hosts[host._id] = host.copy(ownerId = friend)

        assertFailsWith<RequestError> { runTasks() }
        assertEquals("old world", access.worldFile(host, "level.dat").readText())
        assertTrue(mails.single().second.contains("房间拥有者已变更"))
    }

    @Test
    fun oneImportPerHostAndUploadsDoNotBlock() = runBlocking {
        val host = access.addHost()
        val bytes = archive(level())
        val first = service.create(host, owner, HostWorldImportCreateDto(bytes.size.toLong(), bytes.sha1, "1.20.1", ModLoader.forge))
        HostWorldImportGuard.check(host._id)
        val resumed = service.create(host, owner, HostWorldImportCreateDto(bytes.size.toLong(), bytes.sha1, "1.20.1", ModLoader.forge))
        assertEquals(first.id, resumed.id, "the identical upload is resumed")
        assertFailsWith<RequestError> {
            service.create(host, owner, HostWorldImportCreateDto(bytes.size.toLong() + 1, bytes.sha1, "1.20.1", ModLoader.forge))
        }
        assertFailsWith<RequestError> {
            service.create(host, owner, HostWorldImportCreateDto(HostWorldImportStore.MAX_ARCHIVE_SIZE + 1, bytes.sha1, "1.20.1", ModLoader.forge))
        }
        service.cancel(host._id, first.id, owner)
        assertTrue(store.sessions(host._id).isEmpty())
    }

    @Test
    fun recoveryFinishesACommittedImport() = runBlocking {
        val host = access.addHost(gameRules = mutableMapOf("doDaylightCycle" to "false"))
        val session = upload(host, archive(level()), members = listOf(friend))
        // Simulate a crash right after the swap committed, before any post-commit step.
        val paths = WorldSwapPaths.forHost(access.hostDir(host), session)
        Files.createDirectories(paths.world)
        paths.world.resolve("level.dat").writeText("imported")
        store.update(store.find(host._id, session)!!) {
            it.copy(journal = it.journal.copy(status = ImportJournalStatus.Processing, swapPhase = SwapPhase.Committed, hadOldWorld = false))
        }
        tasks.clear()
        val restarted = newService()

        restarted.recoverAll()

        assertEquals(HostWorldImportStatus.Ready, restarted.status(host._id, session, owner).status)
        assertTrue(access.clearedGameRules.contains(host._id))
        assertEquals<List<ObjectId>?>(listOf(friend), access.addedMembers[host._id])
        assertEquals("存档导入完成", mails.single().first)
        restarted.recoverAll()
        assertEquals(1, mails.size, "the mail is sent once")
    }

    @Test
    fun recoveryRequeuesAnImportThatNeverSwapped() = runBlocking {
        val host = access.addHost()
        val session = upload(host, archive(level()))
        store.update(store.find(host._id, session)!!) { it.copy(journal = it.journal.copy(status = ImportJournalStatus.Processing)) }
        tasks.clear()
        val restarted = newService()

        restarted.recoverAll()

        assertEquals(HostWorldImportStatus.Queued, restarted.status(host._id, session, owner).status)
        assertEquals(1, tasks.size)
        restarted.recoverAll()
        assertEquals(1, tasks.size, "a queued (claimed) session is not enqueued twice")
        runTasks()
        assertEquals(HostWorldImportStatus.Ready, service.status(host._id, session, owner).status)
    }

    @Test
    fun recoveryRequiredBlocksUntilAnAdministratorResolves() = runBlocking {
        val host = access.addHost()
        val session = upload(host, archive(level()))
        store.update(store.find(host._id, session)!!) {
            it.copy(journal = it.journal.copy(status = ImportJournalStatus.Failed, recoveryRequired = true, recoveryReason = "test"))
        }
        val blocked = assertFailsWith<RequestError> { HostWorldImportGuard.check(host._id) }
        assertEquals(HostWorldImportStore.RECOVERY_REQUIRED_BLOCK, blocked.message)
        assertFailsWith<RequestError> { service.create(host, owner, HostWorldImportCreateDto(1, "0".repeat(40), "1.20.1", ModLoader.forge)) }
        Files.createDirectories(WorldSwapPaths.forHost(access.hostDir(host), session).world)

        val result = newService().resolve(host._id, session, "davickk", HostWorldImportResolveDto(ImportResolution.KeptImportedWorld))

        assertTrue(result.notDone.isNotEmpty())
        HostWorldImportGuard.check(host._id)
        assertEquals(HostWorldImportStatus.Ready, service.status(host._id, session, owner).status)
    }

    // ---- helpers ----

    private suspend fun upload(host: Host, archive: ByteArray, members: List<ObjectId> = emptyList()): UUID {
        val session = service.create(host, owner, HostWorldImportCreateDto(archive.size.toLong(), archive.sha1, "1.20.1", ModLoader.forge, members))
        for (index in 0 until session.partCount) {
            val part = archive.copyOfRange(index * session.partSize, minOf(archive.size, (index + 1) * session.partSize))
            service.uploadPart(host._id, session.id, owner, index, part.size.toLong(), part.sha1, ByteReadChannel(part))
        }
        assertEquals(HostWorldImportStatus.Queued, service.complete(host._id, session.id, owner).status)
        return session.id
    }

    private suspend fun runTasks() {
        while (tasks.isNotEmpty()) tasks.removeAt(0).action(Task2Context(emitProgress = {}))
    }

    private fun archive(levelDat: ByteArray, vararg files: Pair<String, ByteArray>, sync: ByteArray? = syncList(1)): ByteArray {
        val file = root.resolve("archive-${UUID.randomUUID()}.tar.zst")
        TarZstArchiveWriter(file).use { writer ->
            writer.addFile("level.dat", levelDat)
            sync?.let { writer.addDirectory("data"); writer.addFile("data/rdi_sync_chunks.dat", it) }
            files.forEach { (path, bytes) -> writer.addFile(path, bytes) }
        }
        return file.readBytes()
    }

    private fun level(version: String = "1.20.1", hardcore: Boolean = false, modIds: List<String>? = listOf("minecraft", "forge")): ByteArray =
        gzip(nbt {
            compound("Data") {
                compound("Version") { string("Name", version) }
                byte("hardcore", if (hardcore) 1 else 0)
            }
            if (modIds != null) compound("fml") { compoundList("LoadingModList", modIds.map<String, NbtBuilder.() -> Unit> { id -> { string("ModId", id) } }) }
        })

    private fun syncList(count: Int): ByteArray = gzip(nbt {
        compound("data") {
            compoundList("chunks", List<NbtBuilder.() -> Unit>(count) { index ->
                {
                    string("dimension", "minecraft:overworld")
                    int("x", index)
                    int("z", 0)
                    string("owner", UUID(1, 2).toString())
                }
            })
        }
    })

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    /** A minimal binary NBT writer for the few tags these tests need. */
    private class NbtBuilder(val out: DataOutputStream) {
        fun string(name: String, value: String) = named(8, name).also { out.writeUTF(value) }
        fun int(name: String, value: Int) = named(3, name).also { out.writeInt(value) }
        fun byte(name: String, value: Int) = named(1, name).also { out.writeByte(value) }
        fun compound(name: String, body: NbtBuilder.() -> Unit) {
            named(10, name)
            body()
            out.writeByte(0)
        }
        fun compoundList(name: String, items: List<NbtBuilder.() -> Unit>) {
            named(9, name)
            out.writeByte(10)
            out.writeInt(items.size)
            items.forEach { item -> item(); out.writeByte(0) }
        }
        private fun named(type: Int, name: String) {
            out.writeByte(type)
            out.writeUTF(name)
        }
    }

    private fun nbt(body: NbtBuilder.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(10)
            out.writeUTF("")
            NbtBuilder(out).body()
            out.writeByte(0)
        }
        return bytes.toByteArray()
    }

    private inner class FakeHostAccess(private val hostsRoot: Path) : ImportHostAccess {
        val hosts = ConcurrentHashMap<ObjectId, Host>()
        val modpacks = ConcurrentHashMap<ObjectId, Modpack>()
        val accounts = HashSet<ObjectId>()
        val stopped = HashSet<ObjectId>()
        val clearedGameRules = HashSet<ObjectId>()
        val addedMembers = HashMap<ObjectId, MutableList<ObjectId>>()
        private val locks = ConcurrentHashMap<ObjectId, Mutex>()

        fun addHost(
            version: Int = 2,
            stopped: Boolean = true,
            modpackId: ObjectId = modpack._id,
            gameRules: MutableMap<String, String> = mutableMapOf(),
        ): Host {
            val host = Host(
                name = "房间", ownerId = owner, modpackId = modpackId, port = 50000, difficulty = 2, gameMode = 0,
                levelType = "normal", gameRules = gameRules, members = listOf(Host.Member(owner, Role.OWNER)), version = version,
            )
            hosts[host._id] = host
            if (stopped) this.stopped += host._id
            Files.createDirectories(hostDir(host))
            return host
        }

        fun worldFile(host: Host, path: String): Path =
            hostDir(host).resolve("world").resolve(path).also { Files.createDirectories(it.parent) }

        override suspend fun host(hostId: ObjectId): Host? = hosts[hostId]
        override suspend fun modpack(modpackId: ObjectId): Modpack? = modpacks[modpackId]
        override fun isStopped(host: Host): Boolean = host._id in stopped
        override fun hostDir(host: Host): Path = hostsRoot.resolve(host._id.toHexString())
        override suspend fun accountsExist(ids: List<ObjectId>): Boolean = accounts.containsAll(ids)
        override suspend fun playerNames(ids: List<ObjectId>): Map<ObjectId, String> = ids.associateWith { "玩家" }
        override suspend fun previewMemberAdds(host: Host, ids: List<ObjectId>): List<Pair<ObjectId, String>> = emptyList()
        override suspend fun addMember(hostId: ObjectId, playerId: ObjectId): Result<Unit> {
            if (hosts.getValue(hostId).members.none { it.id == playerId }) addedMembers.getOrPut(hostId) { ArrayList() } += playerId
            return Result.success(Unit)
        }
        override suspend fun clearGameRules(hostId: ObjectId) {
            clearedGameRules += hostId
        }
        override suspend fun <T> withLifecycleLock(hostId: ObjectId, block: suspend () -> T): T =
            locks.computeIfAbsent(hostId) { Mutex() }.withLock { block() }
    }
}
