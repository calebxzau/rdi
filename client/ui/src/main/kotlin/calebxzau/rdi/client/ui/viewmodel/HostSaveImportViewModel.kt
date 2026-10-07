package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzau.rdi.anvilrw.remap.DualIdentity
import calebxzau.rdi.anvilrw.remap.DualIdentityKeep
import calebxzau.rdi.anvilrw.remap.NbtMetadataReader
import calebxzau.rdi.anvilrw.remap.SaveIdentityMapping
import calebxzau.rdi.anvilrw.remap.SavePlayer
import calebxzau.rdi.anvilrw.remap.SaveMarkingPlayer
import calebxzau.rdi.anvilrw.remap.SavePlayerKind
import calebxzau.rdi.client.service.HostSaveImportApi
import calebxzau.rdi.client.service.HostSaveImportMarkingService
import calebxzau.rdi.client.service.HostSaveImportService
import calebxzau.rdi.client.service.SaveImportMarkingRecord
import calebxzau.rdi.client.service.SaveImportMarkingSummary
import calebxzau.rdi.client.service.SaveImportPlan
import calebxzau.rdi.client.service.SaveImportPreparation
import calebxzau.rdi.client.service.currentHostSaveImportApi
import calebxzau.rdi.client.service.toModLoader
import calebxzau.rdi.common.model.HostWorldImportMemberWarning
import calebxzau.rdi.common.model.HostWorldImportPrecheckDto
import calebxzau.rdi.common.model.HostWorldImportPrecheckVo
import calebxzau.rdi.mclaunch.MinecraftAccount
import calebxzhou.rdi.client.auth.AccountSessionStore
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.service.StartPlayResult
import calebxzhou.rdi.client.service.startPlay
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.util.toObjectId
import calebxzhou.rdi.common.util.toUUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import java.io.File
import java.nio.file.Path
import java.util.UUID

enum class SaveImportStep { PickSave, SelectPlayer, Marking, Summary, Mapping, Confirm, Submitted }

enum class MappingTargetSource { MsidMatch, Self, Qq }

/** The RDI account a save player is assigned to. */
data class MappingTarget(val accountId: ObjectId, val name: String, val source: MappingTargetSource) {
    val uuid: UUID get() = accountId.toUUID()
}

data class SavePlayerRow(
    val player: SavePlayer,
    val displayName: String,
    /** 正版 / 离线 / 离线（已验证）/ 已是RDI玩家 / 未知 */
    val badge: String,
    val target: MappingTarget?,
    /** RDI players are kept as they are. */
    val readOnly: Boolean,
    /** An RDI player whose account no longer exists. */
    val accountMissing: Boolean,
)

data class SaveImportConfirmation(
    val precheck: HostWorldImportPrecheckVo,
    val messages: List<String>,
    val plan: SaveImportPlan,
)

data class HostSaveImportUiState(
    val step: SaveImportStep = SaveImportStep.PickSave,
    val host: Host.DetailVo? = null,
    val source: Path? = null,
    val markingPlayers: List<SaveMarkingPlayer> = emptyList(),
    val selectedPlayer: UUID? = null,
    val record: SaveImportMarkingRecord? = null,
    /** A copy from an earlier attempt that can be reused. */
    val reusableRecord: SaveImportMarkingRecord? = null,
    val busy: String? = null,
    val error: String? = null,
    val summary: SaveImportMarkingSummary? = null,
    val preparation: SaveImportPreparation? = null,
    val rows: List<SavePlayerRow> = emptyList(),
    val dualIdentities: List<DualIdentity> = emptyList(),
    val dualChoices: Map<UUID, DualIdentityKeep> = emptyMap(),
    val confirmation: SaveImportConfirmation? = null,
    val runId: String? = null,
)

/** Server, file and game operations of the import screen; tests substitute their own. */
interface HostSaveImportGateway {
    suspend fun hostDetail(hostId: String): Host.DetailVo
    suspend fun precheck(hostId: ObjectId, dto: HostWorldImportPrecheckDto): HostWorldImportPrecheckVo
    suspend fun findByQq(qq: String): MappingTarget
    fun findReusable(source: Path, hostId: String): SaveImportMarkingRecord?
    suspend fun markingPlayers(source: Path): List<SaveMarkingPlayer>
    suspend fun createCopy(source: Path, host: Host.DetailVo, selectedPlayer: UUID?, onProgress: (String) -> Unit): SaveImportMarkingRecord
    fun checkIdentity(record: SaveImportMarkingRecord): Result<Unit>
    fun summary(record: SaveImportMarkingRecord): Result<SaveImportMarkingSummary>
    fun deleteCopy(record: SaveImportMarkingRecord): Result<Unit>
    suspend fun prepare(record: SaveImportMarkingRecord): SaveImportPreparation

    /** Game launch arguments for the marking mode, or a [RequestError] if the modpack is not installed. */
    suspend fun markingLaunch(host: Host.DetailVo, record: SaveImportMarkingRecord, onExit: () -> Unit): McPlayArgs
    fun submit(plan: SaveImportPlan): String
}

/**
 * The singleplayer save import screen (plan §4, §7): pick a save, mark chunks in a copy, map players,
 * confirm, and hand the remap and upload to a background task.
 */
class HostSaveImportViewModel(
    private val hostId: String,
    private val gateway: HostSaveImportGateway = RdiHostSaveImportGateway(),
    private val picker: suspend (String) -> File? = { calebxzau.rdi.client.ui.pickLocalDirectory(it) },
    private val self: () -> Pair<ObjectId, String> = { AccountSessionStore.current.let { it._id to it.name } },
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HostSaveImportUiState())
    val uiState: StateFlow<HostSaveImportUiState> = _uiState.asStateFlow()

    /** Set when the game should be launched; the screen navigates and then calls [onLaunchHandled]. */
    private val _launch = MutableStateFlow<McPlayArgs?>(null)
    val launch: StateFlow<McPlayArgs?> = _launch.asStateFlow()

    init {
        work("正在加载房间") {
            val host = gateway.hostDetail(hostId)
            if (host.ownerId != self().first) throw RequestError("只有房主可以导入存档")
            if (host.version != 2) throw RequestError("仅2026.9.5以后创建的房间支持此功能")
            _uiState.update { it.copy(host = host) }
        }
    }

    fun pickSave() = work("正在检查存档") {
        val host = requireHost()
        val folder = picker("选择要导入的单人存档文件夹") ?: return@work
        val source = folder.toPath().toAbsolutePath().normalize()
        val level = withContext(ioDispatcher) { NbtMetadataReader.readLevelDat(source.resolve("level.dat")) }
            .getOrElse { throw RequestError("无效的存档，无法读取主要数据", it) }
        val loader = level.fmlModIds?.let { ids ->
            when {
                "neoforge" in ids -> ModLoader.neoforge
                "forge" in ids -> ModLoader.forge
                else -> null
            }
        } ?: throw RequestError("该存档最后一次不是用Forge/NeoForge保存的")
        gateway.precheck(host._id, HostWorldImportPrecheckDto(level.versionName ?: "", loader))
        val players = gateway.markingPlayers(source)
        _uiState.update {
            HostSaveImportUiState(host = host, source = source, reusableRecord = gateway.findReusable(source, hostId),
                step = SaveImportStep.SelectPlayer, markingPlayers = players,
                selectedPlayer = players.firstOrNull { it.player.isSingleplayerHost }?.player?.uuid ?: players.firstOrNull()?.player?.uuid)
        }
    }

    fun selectMarkingPlayer(uuid: UUID) {
        if (_uiState.value.busy != null || _uiState.value.markingPlayers.none { it.player.uuid == uuid }) return
        _uiState.update { it.copy(selectedPlayer = uuid) }
    }

    /** Copies the save (or reuses the earlier copy) and opens it in marking mode. */
    fun startMarking(reuse: Boolean) = work("正在复制存档") {
        val host = requireHost()
        val state = _uiState.value
        val record = if (reuse && state.reusableRecord != null) {
            state.reusableRecord
        } else {
            val source = state.source ?: throw RequestError("请先选择存档")
            gateway.createCopy(source, host, state.selectedPlayer) { message -> _uiState.update { it.copy(busy = message) } }
        }
        _uiState.update { it.copy(reusableRecord = record) }
        gateway.checkIdentity(record).getOrThrow()
        val launch = gateway.markingLaunch(host, record) { onGameExited() }
        _uiState.update { it.copy(record = record, reusableRecord = null, step = SaveImportStep.Marking) }
        _launch.value = launch
    }

    /** Opens the copy again to change the marks. */
    fun relaunch() = work("正在准备游戏") {
        val record = _uiState.value.record ?: throw RequestError("请先选择存档")
        gateway.checkIdentity(record).getOrThrow()
        val launch = gateway.markingLaunch(requireHost(), record) { onGameExited() }
        _uiState.update { it.copy(step = SaveImportStep.Marking) }
        _launch.value = launch
    }

    fun onLaunchHandled() {
        _launch.value = null
    }

    fun onGameExited() = work("正在读取标记") {
        val record = _uiState.value.record ?: return@work
        gateway.checkIdentity(record).getOrThrow()
        val summary = gateway.summary(record).getOrThrow()
        _uiState.update { it.copy(step = SaveImportStep.Summary, summary = summary) }
    }

    fun continueToMapping() = work("正在识别存档中的玩家") {
        val summary = _uiState.value.summary
        if (summary == null || summary.marked.isEmpty()) throw RequestError("请至少标记一个要保留的区块")
        val record = _uiState.value.record ?: throw RequestError("请先选择存档")
        val preparation = gateway.prepare(record)
        val rows = suggestRows(preparation)
        _uiState.update { state ->
            state.copy(step = SaveImportStep.Mapping, preparation = preparation, rows = rows).withDuals()
        }
    }

    /** Assigns [uuid] to [target], or clears it. Two players cannot share one account. */
    fun setTarget(uuid: UUID, target: MappingTarget?) {
        val rows = _uiState.value.rows
        val row = rows.firstOrNull { it.player.uuid == uuid } ?: return
        if (row.readOnly) return
        if (target != null && rows.any { it.player.uuid != uuid && it.target?.accountId == target.accountId }) {
            _uiState.update { it.copy(error = "${target.name}已对应存档中的另一名玩家") }
            return
        }
        _uiState.update { state ->
            state.copy(rows = rows.map { if (it.player.uuid == uuid) it.copy(target = target) else it }, error = null).withDuals()
        }
    }

    suspend fun searchQq(qq: String): Result<MappingTarget> = try {
        Result.success(gateway.findByQq(qq.trim()))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun selfTarget(): MappingTarget = self().let { (id, name) -> MappingTarget(id, name, MappingTargetSource.Self) }

    fun setDualKeep(other: UUID, keep: DualIdentityKeep) {
        _uiState.update { it.copy(dualChoices = it.dualChoices + (other to keep)) }
    }

    /** Builds the mapping, runs the server precheck and prepares the confirmation (plan §4 steps 9–10). */
    fun review() = work("正在检查房间") {
        val state = _uiState.value
        val host = requireHost()
        val preparation = state.preparation ?: throw RequestError("请先识别存档中的玩家")
        val record = state.record ?: throw RequestError("请先选择存档")
        val assignments = state.rows.mapNotNull { row -> row.target?.let { row.player.uuid to it.uuid } }.toMap()
        val mapping = SaveIdentityMapping.build(preparation.scan, assignments, state.dualChoices).getOrElse { throw RequestError(it.message ?: "玩家对应关系无效", it) }
        val members = (state.rows.mapNotNull { it.target?.accountId } +
            preparation.existingRdiPlayers.map { it.toObjectId().getOrThrow() }).distinct()
        val scan = preparation.scan
        val loader = scan.lastSavedLoader?.toModLoader() ?: throw RequestError("该存档最后一次不是用Forge/NeoForge保存的")
        val dto = HostWorldImportPrecheckDto(scan.mcVersionName ?: "", loader, members)
        val precheck = gateway.precheck(host._id, dto)
        val plan = SaveImportPlan(host._id, host.name, record, mapping, members, dto.mcVersionName, loader, precheck.memberWarnings)
        _uiState.update {
            it.copy(step = SaveImportStep.Confirm, confirmation = SaveImportConfirmation(precheck, confirmationMessages(state, precheck), plan))
        }
    }

    fun backToMapping() {
        _uiState.update { it.copy(step = SaveImportStep.Mapping, confirmation = null) }
    }

    fun confirm() = work("正在开始导入") {
        val plan = _uiState.value.confirmation?.plan ?: return@work
        val runId = gateway.submit(plan)
        _uiState.update { it.copy(step = SaveImportStep.Submitted, runId = runId) }
    }

    /** Deletes the marking copy ("清理导入副本"). */
    fun cleanupCopy() = work("正在清理副本") {
        val record = _uiState.value.record ?: _uiState.value.reusableRecord ?: return@work
        gateway.deleteCopy(record).getOrThrow()
        _uiState.update { HostSaveImportUiState(host = it.host) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun suggestRows(preparation: SaveImportPreparation): List<SavePlayerRow> {
        val selfId = self().first
        val msidTargets = preparation.msidMatches.mapValues { (_, match) -> MappingTarget(match.id, match.name, MappingTargetSource.MsidMatch) }
        val selfUsed = msidTargets.values.any { it.accountId == selfId } || preparation.existingRdiPlayers.contains(selfId.toUUID())
        val record = _uiState.value.record
        val suggestedSelf = if (record?.identityVersion == 1) record.selectedPlayerUuid?.let(UUID::fromString)
            else preparation.scan.players.firstOrNull { it.isSingleplayerHost }?.uuid
        return preparation.scan.players
            .sortedWith(compareByDescending<SavePlayer> { it.isSingleplayerHost }.thenByDescending { it.playTimeTicks ?: 0 })
            .map { player ->
                val name = preparation.mojangNames[player.uuid] ?: player.verifiedName ?: player.nameHints.firstOrNull()?.name ?: player.uuid.toString().take(8)
                when (player.kind) {
                    SavePlayerKind.Rdi -> SavePlayerRow(player, name, "已是RDI玩家", null, readOnly = true, accountMissing = player.uuid !in preparation.existingRdiPlayers)
                    else -> {
                        val target = msidTargets[player.uuid]
                            ?: if (player.uuid == suggestedSelf && !selfUsed) selfTarget() else null
                        val badge = when (player.kind) {
                            SavePlayerKind.Online -> "正版"
                            SavePlayerKind.Offline -> if (player.verifiedName != null) "离线（已验证）" else "离线"
                            else -> "未知"
                        }
                        SavePlayerRow(player, name, badge, target, readOnly = false, accountMissing = false)
                    }
                }
            }
    }

    private fun HostSaveImportUiState.withDuals(): HostSaveImportUiState {
        val scan = preparation?.scan ?: return this
        val assignments = rows.mapNotNull { row -> row.target?.let { row.player.uuid to it.uuid } }.toMap()
        val duals = SaveIdentityMapping.findDualIdentities(scan, assignments)
        return copy(dualIdentities = duals, dualChoices = duals.associate { it.other to (dualChoices[it.other] ?: it.defaultKeep) })
    }

    private fun confirmationMessages(state: HostSaveImportUiState, precheck: HostWorldImportPrecheckVo): List<String> = buildList {
        val scan = state.preparation?.scan
        add("当前房间的存档将被删除 并替换为导入的存档，此操作无法撤销。")
        state.summary?.let { add("只有标记的${it.marked.size}个区块会被完整保留，其余区块会在房间中重新生成(建筑、箱子和生物都会消失)") }
        if (precheck.gameRuleOverrideCount > 0) add("房间已设置的${precheck.gameRuleOverrideCount}条游戏规则将被清除，改用存档中的游戏规则。")
        if (scan != null && scan.serverBrands.any { it.lowercase() in OTHER_LOADERS }) add("该存档曾被其他加载器打开过，部分数据可能已丢失。")
        if (scan?.isHardcore == true) add("该存档是极限模式，导入后将关闭极限模式。")
        precheck.memberWarnings.forEach { warning ->
            val name = state.rows.firstOrNull { it.target?.accountId == warning.playerId }?.target?.name ?: warning.playerId.toHexString()
            add("${name}无法加入房间：${warning.reason}")
        }
    }

    private suspend fun requireHost(): Host.DetailVo = _uiState.value.host ?: throw RequestError("房间信息未加载")

    /** Runs [block] with a busy message; failures become a player-facing error. */
    private fun work(message: String, block: suspend () -> Unit) {
        if (_uiState.value.busy != null) return
        _uiState.update { it.copy(busy = message, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error !is RequestError) calebxzhou.rdi.client.net.lgr.error(error) { "存档导入操作失败: ${message}" }
                _uiState.update { it.copy(error = error.message ?: "操作失败") }
            } finally {
                _uiState.update { it.copy(busy = null) }
            }
        }
    }

    private companion object {
        val OTHER_LOADERS = setOf("fabric", "quilt", "vanilla")
    }
}

/** The production gateway. */
class RdiHostSaveImportGateway(
    private val apiProvider: () -> HostSaveImportApi = { currentHostSaveImportApi() },
    private val marking: HostSaveImportMarkingService = HostSaveImportMarkingService(),
    private val submitTask: (Task2, String) -> String = { task, key -> ClientTaskManager.submit(task, key) },
) : HostSaveImportGateway {
    override suspend fun hostDetail(hostId: String): Host.DetailVo {
        val response = server.makeRequest<Host.DetailVo>("host/${hostId}/detail")
        if (!response.ok) throw RequestError(response.msg)
        return response.data ?: throw RequestError("无法加载房间信息")
    }

    override suspend fun precheck(hostId: ObjectId, dto: HostWorldImportPrecheckDto): HostWorldImportPrecheckVo =
        apiProvider().precheck(hostId, dto)

    override suspend fun findByQq(qq: String): MappingTarget =
        apiProvider().findByQq(qq).let { MappingTarget(it.id, it.name, MappingTargetSource.Qq) }

    override fun findReusable(source: Path, hostId: String): SaveImportMarkingRecord? = marking.findReusable(source, hostId)

    override suspend fun markingPlayers(source: Path): List<SaveMarkingPlayer> = withContext(Dispatchers.IO) {
        marking.players(source).getOrThrow()
    }

    override suspend fun createCopy(source: Path, host: Host.DetailVo, selectedPlayer: UUID?, onProgress: (String) -> Unit): SaveImportMarkingRecord {
        val args = playArgs(host)
        val saves = File(args.versionDir ?: throw RequestError("找不到房间整合包目录")).toPath().resolve("saves")
        return withContext(Dispatchers.IO) {
            marking.createCopy(source, host._id.toHexString(), saves, AccountSessionStore.current.uuid, selectedPlayer) { done, total ->
                onProgress("正在复制存档 ${done * 100 / maxOf(total, 1)}%")
            }.getOrThrow()
        }
    }

    override fun checkIdentity(record: SaveImportMarkingRecord): Result<Unit> = marking.checkIdentity(record)

    override fun summary(record: SaveImportMarkingRecord): Result<SaveImportMarkingSummary> = marking.summary(record)

    override fun deleteCopy(record: SaveImportMarkingRecord): Result<Unit> = marking.delete(record)

    override suspend fun prepare(record: SaveImportMarkingRecord): SaveImportPreparation =
        HostSaveImportService(apiProvider(), marking).prepare(record).getOrThrow()

    override suspend fun markingLaunch(host: Host.DetailVo, record: SaveImportMarkingRecord, onExit: () -> Unit): McPlayArgs {
        withContext(Dispatchers.IO) {
            marking.checkIdentity(record).getOrThrow()
            marking.checkReadable(record).getOrThrow()
        }
        return playArgs(host).copy(
            title = "标记要保留的区块 ${host.name}",
            account = MinecraftAccount(
                record.selectedPlayerName?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,16}")) } ?: AccountSessionStore.current.name,
                record.launchUuid, "0",
            ),
            extraJvmArgs = listOf("-Drdi.syncChunkMarking=true"),
            extraGameArgs = listOf("--quickPlaySingleplayer", record.copyFolderName),
            cleanup = onExit,
        )
    }

    override fun submit(plan: SaveImportPlan): String =
        submitTask(HostSaveImportService(apiProvider(), marking).importTask(plan), "host-save-import:${plan.hostId}")

    private suspend fun playArgs(host: Host.DetailVo): McPlayArgs = when (val result = host.startPlay(startHost = false)) {
        is StartPlayResult.Ready -> result.args
        is StartPlayResult.Installing -> throw RequestError("房间整合包正在安装，请等待安装完成后再试")
        else -> throw RequestError("请先进入房间游玩一次，安装好房间整合包后再导入存档")
    }
}
