package calebxzhou.rdi.client.ui.screen

import calebxzau.rdi.client.RDIClient
import calebxzau.rdi.client.lgr
import calebxzau.rdi.client.service.dmGameJvmArgs
import calebxzau.rdi.mcinstall.McLaunchPreparationRequest
import calebxzau.rdi.mclaunch.MinecraftLaunchOverrides
import calebxzhou.rdi.client.net.RServer
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.service.*
import calebxzhou.rdi.client.ui.McGameSession
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.client.ui.McPlayStore
import calebxzhou.rdi.common.model.FORGEGUARD_AGENT_FILE_NAME
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.model.compactText
import calebxzhou.rdi.common.model.isDav
import calebxzhou.rdi.common.util.encodeBase64
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException



class McPlayController(
    private val gameWindowService: GameWindowService = GameWindowService(),
    private val extraJvmArgs: List<String> = emptyList(),
    private val onDuplicate: (McPlayArgs) -> Unit = {},
    private val beforeProcessStart: suspend (McPlayArgs) -> Unit = {},
) {
    fun stopLocalMcProxy() {
        LocalMcProxyService.stop().onFailure { error ->
            McPlayStore.appendProxyLog("[LocalMcProxy] 停止失败: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun markSessionExited(session: McGameSession, message: String? = null) {
        McPlayStore.markExited(session.id, message)
        if (!McPlayStore.hasAliveSessions()) {
            stopLocalMcProxy()
        }
    }

    fun stopSession(session: McGameSession, force: Boolean = false) {
        session.requestStop(force)
        if (!McPlayStore.hasAliveSessions()) {
            stopLocalMcProxy()
        }
    }

    fun startSession(args: McPlayArgs, allowDuplicateVersion: Boolean = false) {
        McPlayStore.launchSessionTask {
            val session = McPlayStore.admitSession(args, allowDuplicateVersion)
            if (session == null) {
                onDuplicate(args)
                return@launchSessionTask
            }
            session.appendLog("[RDI] 准备启动 ${args.title}")
            args.startupWarnings.forEach { warning ->
                session.appendLog("[RDI] 警告: $warning")
            }

            try {
                val baseModProgress = SyncProgressFilter()
                val extraModProgress = SyncProgressFilter()
                val coreProgress = SyncProgressFilter()
                val launchSnapshot = ModpackLaunchOptionsService.loadSnapshot(args.versionId).getOrThrow()
                if (args.manageHostBaseMods) {
                    session.appendLog("[RDI] 检查房间基础Mod...")
                    syncHostManagedBaseMods(
                        args.versionId,
                        args.activeBaseMods,
                        args.disabledBaseMods,
                        onProgress = { progress -> appendSyncProgress(session, baseModProgress, progress) },
                        loader = args.modLoader,
                    )
                    session.appendLog("[RDI] 房间基础Mod已同步")
                }
                if (session.stopRequested) return@launchSessionTask

                if (args.manageHostExtraMods) {
                    session.appendLog("[RDI] 检查房间附加Mod...")
                    syncHostExtraMods(
                        args.versionId,
                        args.extraMods,
                        onProgress = { progress -> appendSyncProgress(session, extraModProgress, progress) },
                        loader = args.modLoader,
                    )
                    session.appendLog("[RDI] 房间附加Mod已同步")
                }
                if (session.stopRequested) return@launchSessionTask

                val versionDir = args.versionDir?.let(Paths::get)
                    ?: mcInstall.versionListDir.resolve(args.versionId).toPath()
                if (args.withRdiCore) {
                    RemovedModCleanupService.cleanup(versionDir, args.modLoader).getOrThrow().forEach { fileName ->
                        session.appendLog("[RDI] 已移除不兼容Mod $fileName")
                    }

                    UpdateService.prepareMcCore(
                        mcVersion = args.mcVer,
                        modLoader = args.modLoader,
                        modsDir = mcInstall.versionListDir.resolve(args.versionId).resolve("mods"),
                        onStatus = { session.appendLog("[RDI] $it") },
                        onDetail = { if (it.isNotBlank()) session.appendLog("[RDI] $it") },
                        onProgress = { progress -> appendSyncProgress(session, coreProgress, progress) },
                    ).getOrThrow()
                    session.appendLog("[RDI] RDI核心Mod已同步")
                    if (session.stopRequested) return@launchSessionTask
                }

                session.appendLog("[RDI] 检查游戏安装...")
                mcInstall.prepareForLaunch(
                    request = McLaunchPreparationRequest(
                        mcVersion = args.mcVer,
                        loader = args.modLoader,
                        versionId = args.versionId,
                    ),
                    onProgress = { progress -> session.appendLog("[RDI] $progress") },
                    isCancelled = { session.stopRequested },
                ).getOrThrow()
                session.appendLog("[RDI] 游戏安装已就绪")
                if (session.stopRequested) return@launchSessionTask

                EarlyDisplayCleanup.clean(mcInstall.versionListDir.resolve(args.versionId)).onFailure { error ->
                    lgr.warn(error) { "旧版加载界面清理失败：${args.versionId}" }
                    session.appendLog("[RDI] 旧版加载界面清理失败: ${error.message ?: error.javaClass.simpleName}")
                }

                val windowJvmArgs = if (!args.withRdiCore) emptyList() else gameWindowService.prepareJvmArgs(
                    versionDir = versionDir,
                    modpackName = args.modpackName,
                    versionName = args.versionName,
                    hostName = args.hostName,
                    iconUrl = args.modpackIconUrl,
                    onIconFailure = { error ->
                        lgr.warn(error) { "整合包图标准备失败：${args.versionId}" }
                        session.appendLog("[RDI] 整合包图标准备失败，将使用游戏默认图标")
                    },
                )
                if (session.stopRequested) return@launchSessionTask

                val launchJvmArgs = buildList {
                    addAll(launchSnapshot.customJvmArgs.filterNot(::isGameWindowJvmArg))
                    addAll(extraJvmArgs.filterNot { it.startsWith("-Drdi.play=") || isGameWindowJvmArg(it) })
                    addAll(args.extraJvmArgs.filterNot { it.startsWith("-Drdi.play=") || isGameWindowJvmArg(it) })
                    addAll(windowJvmArgs)
                    launchSnapshot.jdwpJvmArg?.let{
                        add("-Xlog:os+exit=trace")
                        add(it)
                    }
                    if (args.withRdiCore && Modpack.supportForgeGuard(args.modpackName)) {
                        val forgeguardAgent = RDIClient.DIR.resolve("lib/$FORGEGUARD_AGENT_FILE_NAME")
                        require(forgeguardAgent.isFile) { "缺少Forgeguard启动保护文件: ${forgeguardAgent.absolutePath}" }
                        add("\"-javaagent:${forgeguardAgent.absolutePath}\"")
                    }
                    if (args.withRdiCore) {
                        val localGameAddr = LocalMcProxyService.start(McPlayStore::appendProxyLog).getOrThrow()
                        val proxiedPlayArg = args.playArg.withGameAddr(localGameAddr)
                        add("-Drdi.play=${proxiedPlayArg.encodeBase64}")
                        args.hostId?.let { add("-Drdi.host.id=${it}") }
                        addAll(dmGameJvmArgs(loggedAccount.isDav, RServer.now.ip))
                    }

                }
                if (session.stopRequested) return@launchSessionTask

                val preparedLaunch = mcInstall.prepareDesktopLaunch(
                    args.mcVer,
                    args.modLoader,
                    args.versionId,
                    MinecraftLaunchOverrides(
                        javaPath = launchSnapshot.javaPath,
                        maxMemoryMb = launchSnapshot.maxMemoryMb,
                    ),
                    *launchJvmArgs.toTypedArray(),
                    account = args.account,
                    extraGameArgs = args.extraGameArgs,
                    rdiRuntime = args.withRdiCore,
                ).getOrThrow()
                if (session.stopRequested) return@launchSessionTask

                beforeProcessStart(args)
                if (session.stopRequested) return@launchSessionTask

                val started = preparedLaunch.launch { line ->
                    McPlayStore.launchSessionTask {
                        session.consoleState.append(line)
                        if (line.startsWith("启动失败") || line.startsWith("已退出") || line.startsWith("MC已结束")) {
                            markSessionExited(session, line)
                        }
                    }
                }.getOrThrow()
                session.attachProcess(started)
            } catch (e: CancellationException) {
                markSessionExited(session, "启动已取消")
                throw e
            } catch (e: Exception) {
                lgr.error(e) { "游戏启动失败：${args.versionId}" }
                session.appendLog("[RDI] 启动前检查失败: ${e.message ?: "unknown"}")
                markSessionExited(session, e.message)
            }
        }
    }

}

fun formatSyncProgress(progress: Task2Progress): String {
    val compact = progress.compactText()
    val percent = progress.fraction
        ?.coerceIn(0f, 1f)
        ?.let { (it * 100).toInt() }
    val compactIsOnlyFraction = percent != null && compact == "$percent%"
    return when {
        compact == progress.message -> progress.message
        percent != null && !compactIsOnlyFraction -> "${progress.message} · $compact · $percent%"
        else -> "${progress.message} · $compact"
    }
}

class SyncProgressFilter {
    private var first = true
    private var lastFractionPercent: Int? = null
    private var lastCompletedItems: Int? = null
    private var lastCompletedBytesBucket: Long? = null
    private var lastMessage: String? = null
    private var lastOutput: String? = null

    fun accept(progress: Task2Progress): String? {
        val formatted = formatSyncProgress(progress)
        val hasStructuredProgress = progress.fraction != null ||
            progress.completedBytes != null ||
            progress.totalBytes != null ||
            progress.bytesPerSecond != null ||
            progress.completedItems != null ||
            progress.totalItems != null
        val percent = progress.fraction
            ?.coerceIn(0f, 1f)
            ?.let { (it * 100).toInt() }
        val bytesBucket = progress.completedBytes?.div(PROGRESS_BYTE_BUCKET)
        val shouldEmit = first || when {
            progress.fraction != null -> percent != lastFractionPercent ||
                progress.completedItems != lastCompletedItems
            progress.completedBytes != null -> bytesBucket != lastCompletedBytesBucket ||
                progress.completedItems != lastCompletedItems
            !hasStructuredProgress -> progress.message != lastMessage
            else -> progress.completedItems != lastCompletedItems
        }

        first = false
        lastFractionPercent = percent
        lastCompletedItems = progress.completedItems
        lastCompletedBytesBucket = bytesBucket
        lastMessage = progress.message
        if (!shouldEmit || formatted == lastOutput) return null
        lastOutput = formatted
        return formatted
    }

    companion object {
        private const val PROGRESS_BYTE_BUCKET = 8L * 1024L * 1024L
    }
}

private fun appendSyncProgress(
    session: McGameSession,
    filter: SyncProgressFilter,
    progress: Task2Progress,
) {
    filter.accept(progress)?.let { session.appendLog("[RDI] $it") }
}

private fun String.withGameAddr(gameAddr: String): String {
    val lines = split(Regex("\\r?\\n")).toMutableList()
    require(lines.size >= 2) { "RDI参数错误，请重新启动房间" }
    lines[1] = gameAddr
    return lines.joinToString("\n")
}
