package calebxzau.rdi.service

import calebxzhou.rdi.client.service.LocalMcProxyService
import calebxzhou.rdi.client.service.NodeRefreshCoordinator
import calebxzhou.rdi.client.ui.McGameSession
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.client.ui.McPlayStore
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Only non-secret process identity survives in the UI; credentials/launch arguments do not. */
@Serializable
internal data class GameProcessSnapshot(
    val id: String,
    val title: String,
    val versionId: String,
    val versionName: String,
    val mcVersion: String,
    val modLoader: String,
    val pid: Long,
    val processStartedAt: Long,
)

internal object GameRecovery {
    fun snapshot(session: McGameSession): GameProcessSnapshot? {
        val process = session.process ?: return null
        if (!process.isAlive) return null
        val started = process.info().startInstant().orElse(null) ?: return null
        return GameProcessSnapshot(
            session.id, session.title, session.versionId, session.args.versionName,
            session.args.mcVer.name, session.args.modLoader.name, process.pid(), started.toEpochMilli(),
        )
    }

    fun matches(handle: ProcessHandle, startedAt: Long): Boolean =
        handle.isAlive && handle.info().startInstant().orElse(null)?.toEpochMilli() == startedAt

    suspend fun restore(
        snapshots: List<GameProcessSnapshot>,
        onExit: (McGameSession) -> Unit,
    ): Result<Int> = runCatching {
        var restored = 0
        snapshots.forEach { snapshot ->
            val handle = ProcessHandle.of(snapshot.pid).orElse(null) ?: return@forEach
            if (!matches(handle, snapshot.processStartedAt)) return@forEach
            val command = handle.info().command().orElse("")
            require(java.io.File(command).name.lowercase() in setOf("java", "java.exe", "javaw.exe")) {
                "无法恢复游戏进程身份"
            }
            if (McPlayStore.sessions.any { it.id == snapshot.id }) return@forEach
            val args = McPlayArgs(
                title = snapshot.title,
                mcVer = McVersion.valueOf(snapshot.mcVersion),
                modLoader = ModLoader.valueOf(snapshot.modLoader),
                versionId = snapshot.versionId,
                versionName = snapshot.versionName,
                playArg = "", // Never reconstruct launch credentials for an already-running process.
            )
            val session = McGameSession(id = snapshot.id, args = args)
            session.attachProcess(RecoveredGameProcess(handle))
            McPlayStore.sessions += session
            McPlayStore.changed()
            handle.onExit().thenRun { onExit(session) }
            restored++
        }
        if (restored > 0) {
            NodeRefreshCoordinator.refreshCurrent().onFailure { error ->
                calebxzau.rdi.client.lgr.warn(error) { "恢复游戏时刷新房间连接失败" }
            }
            LocalMcProxyService.start(McPlayStore::appendProxyLog).onFailure { error ->
                calebxzau.rdi.client.lgr.warn(error) { "游戏进程已恢复管理，但房间连接恢复失败" }
            }
        }
        restored
    }
}

/** An existing process has no recoverable stdio or exit status; ownership and stop still work. */
private class RecoveredGameProcess(private val handle: ProcessHandle) : Process() {
    override fun pid(): Long = handle.pid()
    override fun toHandle(): ProcessHandle = handle
    override fun isAlive(): Boolean = handle.isAlive
    override fun getInputStream(): InputStream = InputStream.nullInputStream()
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun destroy() { if (handle.isAlive && !handle.destroy()) error("停止游戏失败") }
    override fun destroyForcibly(): Process {
        if (handle.isAlive && !handle.destroyForcibly()) error("强制结束游戏失败")
        return this
    }
    override fun waitFor(): Int { handle.onExit().get(); return 0 }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = try {
        handle.onExit().get(timeout, unit)
        true
    } catch (_: TimeoutException) { false }
    override fun exitValue(): Int {
        if (handle.isAlive) throw IllegalThreadStateException("游戏仍在运行")
        return 0
    }
}
