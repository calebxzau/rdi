package calebxzau.rdi.server.service.hostrollback

import calebxzau.rdi.common.model.HostRollbackCreateDto
import calebxzau.rdi.common.model.HostRollbackPointKind
import calebxzau.rdi.common.model.HostRollbackRequestState
import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupIds
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupKind
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupPart
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupStore
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackFiles
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackState
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.model.Role
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.bson.types.ObjectId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostRollbackServiceTest {
    private val hostsRoot: Path = Files.createTempDirectory("host-rollback")
    private val owner = ObjectId()
    private val token = "0199c2a1-7f3e-7c4b-9a10-3b6e2d4f8a11"
    private val access = FakeAccess()
    private val service = HostRollbackService(access) { 1_700_000_000_000L }
    private var stops = 0

    @AfterTest
    fun cleanUp() {
        hostsRoot.toFile().deleteRecursively()
    }

    @Test
    fun hostWithoutPointsListsNothing() = runTest {
        val status = service.status(access.addHost())
        assertEquals(emptyList(), status.points)
        assertNull(status.pending)
        assertNull(status.lastResult)
        assertFalse(status.hostRunning)
        assertFailsWith<RequestError> { service.request(access.addHost(), owner, HostRollbackCreateDto("x"), ::stop) }
    }

    @Test
    fun requestOnARunningHostStopsItAndIsListed() = runTest {
        val host = access.addHost(stopped = false)
        val (older, newer) = takePoints(host)

        val created = service.request(host, owner, HostRollbackCreateDto(older), ::stop)
        assertTrue(created.stoppedHost)
        assertEquals(1, stops)
        // The fake host stays running until the real stop would have finished.
        assertTrue(service.status(host).hostRunning)

        val request = assertNotNull(SyncChunkRollbackFiles.readRequest(root(host)).getOrThrow())
        assertEquals(older, request.pointId)
        assertEquals(token, request.worldToken)
        assertEquals(owner.toHexString(), request.requestedBy)
        assertEquals(SyncChunkRollbackState.Requested, request.state)

        val status = service.status(host)
        assertEquals(listOf(newer, older), status.points.map { it.id })
        assertEquals(HostRollbackPointKind.Auto, status.points.first().kind)
        assertEquals(1, status.points.first().changedChunks)
        assertEquals(older, status.pending!!.pointId)
        assertEquals(HostRollbackRequestState.Requested, status.pending!!.state)
    }

    @Test
    fun stoppedHostIsNotStoppedAgainAndANewPointReplacesThePendingOne() = runTest {
        val host = access.addHost()
        val (older, newer) = takePoints(host)
        assertFalse(service.request(host, owner, HostRollbackCreateDto(older), ::stop).stoppedHost)
        service.request(host, owner, HostRollbackCreateDto(newer), ::stop)
        assertEquals(0, stops)
        assertEquals(newer, SyncChunkRollbackFiles.readRequest(root(host)).getOrThrow()!!.pointId)
    }

    @Test
    fun invalidRequestsAreRejected() = runTest {
        val host = access.addHost()
        val (older, _) = takePoints(host)
        assertFailsWith<RequestError> { service.request(host, owner, HostRollbackCreateDto("missing"), ::stop) }
        assertFailsWith<RequestError> { service.request(host, ObjectId(), HostRollbackCreateDto(older), ::stop) }
        access.importing += host._id
        assertFailsWith<RequestError> { service.request(host, owner, HostRollbackCreateDto(older), ::stop) }
        access.importing -= host._id

        service.request(host, owner, HostRollbackCreateDto(older), ::stop)
        access.stopped -= host._id
        assertFailsWith<RequestError> { service.request(host, owner, HostRollbackCreateDto(older), ::stop) }
    }

    @Test
    fun startedRollbackCannotBeReplaced() = runTest {
        val host = access.addHost()
        val (older, newer) = takePoints(host)
        service.request(host, owner, HostRollbackCreateDto(older), ::stop)
        markApplying(host, before = newer)
        assertFailsWith<RequestError> { service.request(host, owner, HostRollbackCreateDto(newer), ::stop) }
    }

    @Test
    fun cancelRemovesAWaitingRequest() = runTest {
        val host = access.addHost()
        val (older, _) = takePoints(host)
        assertFailsWith<RequestError> { service.cancel(host) }
        service.request(host, owner, HostRollbackCreateDto(older), ::stop)
        access.stopped -= host._id
        assertFailsWith<RequestError> { service.cancel(host) }
        access.stopped += host._id
        service.cancel(host)
        assertNull(SyncChunkRollbackFiles.readRequest(root(host)).getOrThrow())
    }

    @Test
    fun cancellingAStartedRollbackRestoresTheBeforePoint() = runTest {
        val host = access.addHost()
        val (older, newer) = takePoints(host)
        service.request(host, owner, HostRollbackCreateDto(older), ::stop)
        markApplying(host, before = newer)

        service.cancel(host)
        val request = SyncChunkRollbackFiles.readRequest(root(host)).getOrThrow()!!
        assertEquals(newer, request.pointId)
        assertTrue(request.abandoned)
        assertEquals(SyncChunkRollbackState.Applying, request.state)
        assertTrue(service.status(host).pending!!.abandoned)
        assertFailsWith<RequestError> { service.cancel(host) }
    }

    @Test
    fun replacedWorldDropsThePendingRequest() = runTest {
        val host = access.addHost()
        val (older, _) = takePoints(host)
        service.request(host, owner, HostRollbackCreateDto(older), ::stop)
        HostRollbackService.discardForReplacedWorld(access.hostDir(host))
        assertNull(SyncChunkRollbackFiles.readRequest(root(host)).getOrThrow())
    }

    @Test
    fun unreadableRequestIsReplaced() = runTest {
        val host = access.addHost()
        val (older, _) = takePoints(host)
        root(host).resolve(SyncChunkRollbackFiles.REQUEST).writeText("{")
        assertFailsWith<RequestError> { service.status(host) }
        service.request(host, owner, HostRollbackCreateDto(older), ::stop)
        assertEquals(older, SyncChunkRollbackFiles.readRequest(root(host)).getOrThrow()!!.pointId)
    }

    private suspend fun stop() {
        stops++
    }

    private fun root(host: Host): Path = SyncChunkBackupStore.rootOf(access.hostDir(host))

    /** Two points the way the host mod takes them; returns their ids, older first. */
    private fun takePoints(host: Host): Pair<String, String> {
        var clock = 1_699_999_000_000L
        val store = SyncChunkBackupStore.open(root(host), token, now = clock).getOrThrow().first
        val key = SyncChunkKey("minecraft:overworld", 0, 0)
        val ids = listOf("stone", "water").map { block ->
            clock += 300_000L
            val id = SyncChunkBackupIds.newUuidV7(clock).toString()
            val session = store.begin(id, SyncChunkBackupKind.Auto, clock, 0)
            val raw = block.toByteArray()
            session.putChunk(key, SyncChunkBackupPart.Present(SyncChunkBackupIds.sha1Hex(raw), raw), SyncChunkBackupPart.Absent, SyncChunkBackupPart.Absent).getOrThrow()
            assertNotNull(session.commit(clock).getOrThrow())
            id
        }
        return ids[0] to ids[1]
    }

    private fun markApplying(host: Host, before: String) {
        val request = SyncChunkRollbackFiles.readRequest(root(host)).getOrThrow()!!
        SyncChunkRollbackFiles.writeRequest(root(host), request.copy(state = SyncChunkRollbackState.Applying, beforePointId = before)).getOrThrow()
    }

    private inner class FakeAccess : HostRollbackAccess {
        val hosts = HashMap<ObjectId, Host>()
        val stopped = HashSet<ObjectId>()
        val importing = HashSet<ObjectId>()
        private val lock = Mutex()

        fun addHost(stopped: Boolean = true): Host {
            val host = Host(
                name = "房间", ownerId = owner, modpackId = ObjectId(), port = 50000, difficulty = 2, gameMode = 0,
                levelType = "normal", gameRules = mutableMapOf(), members = listOf(Host.Member(owner, Role.OWNER)), version = 2,
            )
            hosts[host._id] = host
            if (stopped) this.stopped += host._id
            Files.createDirectories(hostDir(host))
            return host
        }

        override suspend fun host(hostId: ObjectId): Host? = hosts[hostId]
        override fun hostDir(host: Host): Path = hostsRoot.resolve(host._id.toHexString())
        override fun isStopped(host: Host): Boolean = host._id in stopped
        override fun checkNotImporting(hostId: ObjectId) {
            if (hostId in importing) throw RequestError("存档导入中")
        }
        override suspend fun <T> withLifecycleLock(hostId: ObjectId, block: suspend () -> T): T = lock.withLock { block() }
    }
}
