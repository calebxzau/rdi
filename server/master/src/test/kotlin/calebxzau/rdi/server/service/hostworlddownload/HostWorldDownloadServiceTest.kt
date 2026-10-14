package calebxzau.rdi.server.service.hostworlddownload

import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupStore
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackFiles
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackRequest
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bson.types.ObjectId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostWorldDownloadServiceTest {
    private val hostsRoot: Path = Files.createTempDirectory("host-world-download")
    private val owner = ObjectId()
    private val member = ObjectId()
    private val access = FakeAccess()

    /** 2026-10-13 12:00 in Asia/Shanghai. */
    private var now = 1_791_864_000_000L
    private var failBuild = false
    private val service = HostWorldDownloadService(
        hosts = access,
        clock = { now },
        // Jobs run inline, so a build has finished when start returns.
        scope = CoroutineScope(Dispatchers.Unconfined),
        builder = { world, target, work, progress ->
            if (failBuild) Result.failure(RequestError("坏了")) else HostWorldDownloadBuilder.build(world, target, work, progress)
        },
    )

    @AfterTest
    fun cleanUp() {
        hostsRoot.toFile().deleteRecursively()
    }

    @Test
    fun buildCountsAndTheArchiveCanBeDownloaded() = runBlocking {
        val host = access.addHost()
        val started = service.start(host, member)
        assertFalse(started.reused)

        val ready = assertIs<HostWorldDownloadJobState.Ready>(service.job(host, started.buildId)!!.value).ready
        assertEquals(3, ready.chunkCount)
        val file = service.archiveFile(host, started.buildId)!!
        assertEquals(ready.bytes, Files.size(file))

        val status = service.status(host)
        assertEquals(2, status.remaining)
        assertEquals(listOf(member.toHexString()), status.today.map { it.playerId })
        assertEquals("玩家", status.today.single().playerName)
        assertTrue(status.reusable)
        assertNull(status.blockedReason)
    }

    @Test
    fun unchangedWorldIsReusedWithoutCounting() = runBlocking {
        val host = access.addHost()
        val first = service.start(host, member)
        val second = service.start(host, owner)
        assertTrue(second.reused)
        assertEquals(first.buildId, second.buildId)
        assertEquals(2, service.status(host).remaining)
    }

    @Test
    fun fourthBuildOfTheDayIsRefusedAndTheNextDayStartsOver() = runBlocking {
        val host = access.addHost()
        repeat(3) { index ->
            touchWorld(host, index)
            assertFalse(service.start(host, owner).reused)
        }
        touchWorld(host, 3)
        assertEquals("今天的3次下载已经用完，明天再来", assertFailsWith<RequestError> { service.start(host, owner) }.message)
        assertEquals(0, service.status(host).remaining)

        now += 24 * 3600 * 1000L
        assertFalse(service.start(host, owner).reused)
        assertEquals(2, service.status(host).remaining)
    }

    @Test
    fun failedBuildDoesNotCountAndLeavesNoArchive() = runBlocking {
        val host = access.addHost()
        failBuild = true
        val started = service.start(host, member)
        assertEquals("坏了", assertIs<HostWorldDownloadJobState.Failed>(service.job(host, started.buildId)!!.value).message)
        assertNull(service.archiveFile(host, started.buildId))
        val status = service.status(host)
        assertEquals(3, status.remaining)
        assertFalse(status.reusable)
    }

    @Test
    fun startingTheHostDropsThePackedSave() = runBlocking {
        val host = access.addHost()
        val started = service.start(host, member)
        HostWorldDownloadService.discardForStart(access.hostDir(host))
        assertNull(service.archiveFile(host, started.buildId))
        assertNull(service.job(host, started.buildId), "a finished build is only known while its archive is kept")
        assertTrue(Files.exists(HostWorldDownloadFiles.rootOf(access.hostDir(host)).resolve(HostWorldDownloadFiles.RECORDS)), "records stay")
    }

    @Test
    fun blockedHostsCannotStart() = runBlocking {
        val running = access.addHost(stopped = false)
        assertEquals("请先停止房间再下载存档", service.status(running).blockedReason)
        assertFailsWith<RequestError> { service.start(running, member) }

        val oldHost = access.addHost(version = 1)
        assertEquals("仅2026.9.5以后创建的房间支持下载存档", service.status(oldHost).blockedReason)

        val importing = access.addHost()
        access.importing += importing._id
        assertEquals("存档导入中", service.status(importing).blockedReason)

        val rollback = access.addHost()
        SyncChunkRollbackFiles.writeRequest(
            SyncChunkBackupStore.rootOf(access.hostDir(rollback)),
            SyncChunkRollbackRequest(id = "r", pointId = "p", worldToken = "t", requestedBy = owner.toHexString(), requestedAt = now),
        ).getOrThrow()
        assertEquals("有等待中的回档，请先启动房间完成回档或取消回档", service.status(rollback).blockedReason)

        val noWorld = access.addHost(world = false)
        assertEquals("房间还没有存档，请先启动一次房间", service.status(noWorld).blockedReason)
    }

    @Test
    fun damagedRecordsAreSetAside() = runBlocking {
        val host = access.addHost()
        val root = HostWorldDownloadFiles.rootOf(access.hostDir(host))
        Files.createDirectories(root)
        Files.writeString(root.resolve(HostWorldDownloadFiles.RECORDS), "{")
        assertEquals(3, service.status(host).remaining)
        assertTrue(Files.exists(root.resolve("${HostWorldDownloadFiles.RECORDS}.broken-${now}")))
    }

    /** level.dat changes on every save, so the next start packs again. */
    private fun touchWorld(host: Host, index: Int) {
        Files.writeString(access.hostDir(host).resolve("world/level.dat"), "level ${index}".repeat(index + 1))
    }

    private inner class FakeAccess : HostWorldDownloadAccess {
        val hosts = HashMap<ObjectId, Host>()
        val stopped = HashSet<ObjectId>()
        val importing = HashSet<ObjectId>()
        private val lock = Mutex()

        fun addHost(stopped: Boolean = true, version: Int = 2, world: Boolean = true): Host {
            val host = Host(
                name = "房间", ownerId = owner, modpackId = ObjectId(), port = 50000, difficulty = 2, gameMode = 0,
                levelType = "normal", gameRules = mutableMapOf(), members = listOf(Host.Member(owner, Role.OWNER)), version = version,
            )
            hosts[host._id] = host
            if (stopped) this.stopped += host._id
            Files.createDirectories(hostDir(host))
            if (world) HostWorldTestWorld.standard(hostDir(host).resolve("world"))
            return host
        }

        override suspend fun host(hostId: ObjectId): Host? = hosts[hostId]
        override fun hostDir(host: Host): Path = hostsRoot.resolve(host._id.toHexString())
        override fun isStopped(host: Host): Boolean = host._id in stopped
        override fun checkNotImporting(hostId: ObjectId) {
            if (hostId in importing) throw RequestError("存档导入中")
        }
        override suspend fun <T> withLifecycleLock(hostId: ObjectId, block: suspend () -> T): T = lock.withLock { block() }
        override suspend fun playerNames(ids: Collection<ObjectId>): Map<ObjectId, String> = ids.associateWith { "玩家" }
    }
}
