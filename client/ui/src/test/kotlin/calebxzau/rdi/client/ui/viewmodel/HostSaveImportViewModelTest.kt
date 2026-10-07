package calebxzau.rdi.client.ui.viewmodel

import calebxzau.rdi.anvilrw.remap.DualIdentityKeep
import calebxzau.rdi.anvilrw.remap.SaveLoader
import calebxzau.rdi.anvilrw.remap.SavePlayer
import calebxzau.rdi.anvilrw.remap.SavePlayerScanner
import calebxzau.rdi.anvilrw.remap.SaveScanResult
import calebxzau.rdi.anvilrw.remap.SaveSyncChunk
import calebxzau.rdi.client.service.SaveImportMarkingRecord
import calebxzau.rdi.client.service.SaveImportMarkingSummary
import calebxzau.rdi.client.service.SaveImportPlan
import calebxzau.rdi.client.service.SaveImportPreparation
import calebxzau.rdi.common.model.HostWorldImportMemberWarning
import calebxzau.rdi.common.model.HostWorldImportPrecheckDto
import calebxzau.rdi.common.model.HostWorldImportPrecheckVo
import calebxzau.rdi.common.model.SavePlayerMsidMatchVo
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.util.toUUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.bson.types.ObjectId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostSaveImportViewModelTest {
    private val me = ObjectId()
    private val friend = ObjectId()
    private val owner = SavePlayerScanner.offlineUuid("Owner")
    private val online = UUID.fromString("1512ef02-2b79-4494-a4e9-e35cd8e03923")
    private val rdiPlayer = ObjectId().toUUID()
    private val save: Path = Files.createTempDirectory("save-import-vm").resolve("新的世界")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        Files.createDirectories(save)
        Files.write(save.resolve("level.dat"), levelDat())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun suggestionsPreferTheMsidMatchThenTheOwnerAsSelf() {
        val gateway = FakeGateway(players = listOf(player(owner, host = true), player(online)), msid = mapOf(online to me))
        val vm = mapped(gateway)

        val rows = vm.uiState.value.rows.associateBy { it.player.uuid }
        assertEquals(me, rows.getValue(online).target?.accountId, "the msid match wins")
        assertEquals(MappingTargetSource.MsidMatch, rows.getValue(online).target?.source)
        assertNull(rows.getValue(owner).target, "the uploader is already used by the msid match")

        val noMatch = mapped(FakeGateway(players = listOf(player(owner, host = true), player(online))))
        assertEquals(me, noMatch.uiState.value.rows.first { it.player.uuid == owner }.target?.accountId)
        assertEquals(MappingTargetSource.Self, noMatch.uiState.value.rows.first { it.player.uuid == owner }.target?.source)
    }

    @Test
    fun targetsAreUniqueAndRdiPlayersAreReadOnly() {
        val gateway = FakeGateway(players = listOf(player(owner, host = true), player(online), player(rdiPlayer)), existingRdi = emptySet())
        val vm = mapped(gateway)

        vm.setTarget(online, MappingTarget(me, "我", MappingTargetSource.Qq))
        assertNotNull(vm.uiState.value.error, "two players cannot share one account")
        assertNull(vm.uiState.value.rows.first { it.player.uuid == online }.target)

        vm.setTarget(rdiPlayer, MappingTarget(friend, "朋友", MappingTargetSource.Qq))
        val rdiRow = vm.uiState.value.rows.first { it.player.uuid == rdiPlayer }
        assertTrue(rdiRow.readOnly)
        assertTrue(rdiRow.accountMissing)
        assertNull(rdiRow.target)
    }

    @Test
    fun dualIdentityDefaultsToLongerPlayTimeAndCanBeChanged() {
        val myUuid = me.toUUID()
        val gateway = FakeGateway(players = listOf(player(owner, host = true, ticks = 900), player(myUuid, ticks = 100)), existingRdi = setOf(myUuid))
        val vm = mapped(gateway)

        vm.setTarget(owner, vm.selfTarget())
        val dual = vm.uiState.value.dualIdentities.single()
        assertEquals(owner, dual.other)
        assertEquals(DualIdentityKeep.Other, vm.uiState.value.dualChoices[owner])
        vm.review()
        assertEquals(mapOf(owner to myUuid, myUuid to owner), vm.uiState.value.confirmation?.plan?.mapping)

        vm.backToMapping()
        vm.setDualKeep(owner, DualIdentityKeep.Rdi)
        vm.review()
        assertEquals(emptyMap(), vm.uiState.value.confirmation?.plan?.mapping)
    }

    @Test
    fun confirmationShowsWarningsAndSubmits() {
        val gateway = FakeGateway(
            players = listOf(player(owner, host = true)),
            hardcore = true,
            brands = listOf("forge", "fabric"),
            precheckVo = HostWorldImportPrecheckVo(listOf(HostWorldImportMemberWarning(me, "该玩家加入的房间已达上限")), gameRuleOverrideCount = 3),
        )
        val vm = mapped(gateway)

        vm.review()

        val messages = vm.uiState.value.confirmation!!.messages
        assertTrue(messages.any { it.contains("3条游戏规则") })
        assertTrue(messages.any { it.contains("其他加载器") })
        assertTrue(messages.any { it.contains("极限模式") })
        assertTrue(messages.any { it.contains("加入的房间已达上限") })
        assertTrue(messages.any { it.contains("1个区块") })
        assertEquals(listOf(me), gateway.lastPrecheck?.memberPlayerIds)
        vm.confirm()
        assertEquals(SaveImportStep.Submitted, vm.uiState.value.step)
        assertEquals("run-1", vm.uiState.value.runId)
        assertEquals(gateway.submitted.single().confirmedWarnings, vm.uiState.value.confirmation!!.precheck.memberWarnings)
    }

    @Test
    fun anImportNeedsAtLeastOneMarkedChunk() {
        val vm = marked(FakeGateway(players = listOf(player(owner, host = true)), marked = emptyList()))

        vm.continueToMapping()

        assertEquals(SaveImportStep.Summary, vm.uiState.value.step)
        assertEquals("请至少标记一个要保留的区块", vm.uiState.value.error)
    }

    @Test
    fun onlyTheOwnerOfAV2HostCanImport() {
        val vm = HostSaveImportViewModel("host", FakeGateway(players = emptyList(), hostOwner = friend), picker = { save.toFile() }, self = { me to "我" }, ioDispatcher = Dispatchers.Unconfined)
        assertEquals("只有房主可以导入存档", vm.uiState.value.error)
        assertNull(vm.uiState.value.host)
    }

    @Test
    fun selectedRoleIsSuggestedAsSelfInsteadOfOriginalOwner() {
        val gateway = FakeGateway(players = listOf(player(owner, host = true), player(online)))
        val vm = HostSaveImportViewModel("host", gateway, picker = { save.toFile() }, self = { me to "我" }, ioDispatcher = Dispatchers.Unconfined)
        vm.pickSave()
        assertEquals(SaveImportStep.SelectPlayer, vm.uiState.value.step)
        assertEquals(owner, vm.uiState.value.selectedPlayer)
        vm.selectMarkingPlayer(online)
        vm.startMarking(reuse = false)
        assertEquals(online.toString(), vm.uiState.value.record!!.launchUuid)
        vm.onGameExited()
        vm.continueToMapping()
        assertEquals(me, vm.uiState.value.rows.single { it.player.uuid == online }.target!!.accountId)
        assertNull(vm.uiState.value.rows.single { it.player.uuid == owner }.target)
    }

    @Test
    fun anExistingSelfRdiRoleIsNotSilentlyAssignedAnotherRole() {
        val gateway = FakeGateway(players = listOf(player(owner, host = true), player(me.toUUID())), existingRdi = setOf(me.toUUID()))
        val vm = mapped(gateway)
        assertNull(vm.uiState.value.rows.single { it.player.uuid == owner }.target)
        vm.setTarget(owner, vm.selfTarget())
        assertTrue(vm.uiState.value.dualIdentities.isNotEmpty())
    }

    @Test
    fun reuseKeepsItsRoleAndANewSelectionDoesNotDeleteIt() {
        val gateway = FakeGateway(players = listOf(player(owner, host = true), player(online)))
        val vm = marked(gateway)
        vm.pickSave()
        vm.selectMarkingPlayer(online)
        vm.startMarking(reuse = true)
        assertEquals(owner.toString(), vm.uiState.value.record!!.launchUuid)
        vm.onGameExited()
        vm.pickSave()
        vm.selectMarkingPlayer(online)
        vm.startMarking(reuse = false)
        assertEquals(online.toString(), vm.uiState.value.record!!.launchUuid)
        assertEquals(0, gateway.deleted)
    }

    @Test
    fun failedLaunchPreparationLeavesSelectionAvailable() {
        val gateway = FakeGateway(players = listOf(player(owner, host = true)), failLaunch = true)
        val vm = HostSaveImportViewModel("host", gateway, picker = { save.toFile() }, self = { me to "我" }, ioDispatcher = Dispatchers.Unconfined)
        vm.pickSave()
        vm.startMarking(reuse = false)
        assertEquals(SaveImportStep.SelectPlayer, vm.uiState.value.step)
        assertNull(vm.launch.value)
        assertEquals("无法启动", vm.uiState.value.error)
        assertNotNull(vm.uiState.value.reusableRecord)
    }

    // ---- helpers ----

    private fun marked(gateway: FakeGateway): HostSaveImportViewModel {
        val vm = HostSaveImportViewModel("host", gateway, picker = { save.toFile() }, self = { me to "我" }, ioDispatcher = Dispatchers.Unconfined)
        vm.pickSave()
        vm.startMarking(reuse = false)
        assertNotNull(vm.launch.value, "the game is launched in marking mode")
        vm.onLaunchHandled()
        vm.onGameExited()
        assertEquals(SaveImportStep.Summary, vm.uiState.value.step, vm.uiState.value.error)
        return vm
    }

    private fun mapped(gateway: FakeGateway): HostSaveImportViewModel =
        marked(gateway).also {
            it.continueToMapping()
            assertEquals(SaveImportStep.Mapping, it.uiState.value.step, it.uiState.value.error)
        }

    private fun player(uuid: UUID, host: Boolean = false, ticks: Long? = null) =
        SavePlayer(uuid, SavePlayerScanner.classify(uuid), emptyList(), null, host, null, ticks)

    private inner class FakeGateway(
        val players: List<SavePlayer>,
        val msid: Map<UUID, ObjectId> = emptyMap(),
        val existingRdi: Set<UUID> = emptySet(),
        val hardcore: Boolean = false,
        val brands: List<String> = listOf("forge"),
        val precheckVo: HostWorldImportPrecheckVo = HostWorldImportPrecheckVo(emptyList(), 0),
        val marked: List<SaveSyncChunk> = listOf(SaveSyncChunk("minecraft:overworld", 0, 0, owner)),
        val hostOwner: ObjectId = me,
        val failLaunch: Boolean = false,
    ) : HostSaveImportGateway {
        var deleted = 0
        var created = false
        var lastPrecheck: HostWorldImportPrecheckDto? = null
        val submitted = ArrayList<SaveImportPlan>()
        private var record = SaveImportMarkingRecord(save.toString(), "host", save.resolveSibling("copy").toString(), owner.toString(), 0)

        override suspend fun hostDetail(hostId: String) = Host.DetailVo(
            name = "房间", ownerId = hostOwner, modpack = Modpack.BriefVo(mcVer = McVersion.V201, modloader = ModLoader.forge),
            packVer = "1", version = 2, port = 1, difficulty = 1, gameMode = 0, levelType = "normal",
        )

        override suspend fun precheck(hostId: ObjectId, dto: HostWorldImportPrecheckDto): HostWorldImportPrecheckVo {
            lastPrecheck = dto
            return precheckVo
        }

        override suspend fun findByQq(qq: String): MappingTarget = throw RequestError("无此账号")
        override fun findReusable(source: Path, hostId: String): SaveImportMarkingRecord? = record.takeIf { created }
        override suspend fun markingPlayers(source: Path) = players.map { calebxzau.rdi.anvilrw.remap.SaveMarkingPlayer(it, null) }
        override suspend fun createCopy(source: Path, host: Host.DetailVo, selectedPlayer: UUID?, onProgress: (String) -> Unit): SaveImportMarkingRecord {
            created = true
            record = record.copy(identityVersion = 1, originalOwnerUuid = owner.toString(),
                selectedPlayerUuid = selectedPlayer?.toString(), launchUuid = (selectedPlayer ?: me.toUUID()).toString())
            return record
        }
        override fun checkIdentity(record: SaveImportMarkingRecord) = Result.success(Unit)
        override fun summary(record: SaveImportMarkingRecord) =
            Result.success(SaveImportMarkingSummary(marked, marked.groupingBy { it.dimensionId }.eachCount(), marked.size, emptyList()))
        override fun deleteCopy(record: SaveImportMarkingRecord) = Result.success(Unit).also { deleted++ }
        override suspend fun prepare(record: SaveImportMarkingRecord) = SaveImportPreparation(
            SaveScanResult("1.20.1", 3465, brands, listOf("forge"), SaveLoader.Forge, hardcore, owner, players, marked, 0),
            emptyMap(),
            msid.mapValues { (uuid, id) -> SavePlayerMsidMatchVo(uuid, id, "正版玩家") },
            existingRdi,
        )
        override suspend fun markingLaunch(host: Host.DetailVo, record: SaveImportMarkingRecord, onExit: () -> Unit) =
            if (failLaunch) throw RequestError("无法启动") else
                McPlayArgs(title = "标记", mcVer = McVersion.V201, modLoader = ModLoader.forge, versionId = "v", playArg = "", cleanup = onExit)
        override fun submit(plan: SaveImportPlan): String {
            submitted += plan
            return "run-${submitted.size}"
        }
    }

    /** A gzip `level.dat` with version 1.20.1 saved by Forge. */
    private fun levelDat(): ByteArray {
        val nbt = ByteArrayOutputStream()
        DataOutputStream(nbt).use { out ->
            fun named(type: Int, name: String) { out.writeByte(type); out.writeUTF(name) }
            named(10, "")
            named(10, "Data")
            named(10, "Version"); named(8, "Name"); out.writeUTF("1.20.1"); out.writeByte(0)
            out.writeByte(0)
            named(10, "fml")
            named(9, "LoadingModList"); out.writeByte(10); out.writeInt(1); named(8, "ModId"); out.writeUTF("forge"); out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
        }
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(nbt.toByteArray()) } }.toByteArray()
    }
}
