package calebxzhou.rdi.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import calebxzhou.rdi.client.ui.comp.ConsoleState
import calebxzhou.rdi.client.service.ModpackLifecycleCoordinator
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val mcPlaySessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

data class McPlayArgs(
    val title: String,
    val mcVer: McVersion,
    val modLoader: ModLoader,
    val versionId: String,
    val playArg: String,
    val modpackName: String = "",
    val mcpPort: Int? = null,
    val versionDir: String? = null,
    val activeBaseMods: List<Mod> = emptyList(),
    val disabledBaseMods: List<Mod> = emptyList(),
    val manageHostBaseMods: Boolean = false,
    val extraMods: List<Mod> = emptyList(),
    val manageHostExtraMods: Boolean = false,
    val startupWarnings: List<String> = emptyList(),
    /** Runs once when the session ends, including a failed start. */
    val cleanup: (() -> Unit)? = null,
    val hostId: String? = null,
    /** Plays as this identity instead of the logged-in account (save import marking mode). */
    val account: calebxzau.rdi.mclaunch.MinecraftAccount? = null,
    val extraJvmArgs: List<String> = emptyList(),
    val extraGameArgs: List<String> = emptyList(),
)

class McGameSession(
    val id: String = UUID.randomUUID().toString(),
    val args: McPlayArgs,
    val consoleState: ConsoleState = ConsoleState(),
    val startedAt: Long = System.currentTimeMillis(),
    private val cleanup: () -> Unit = {}
) {
    var process: Process? by mutableStateOf(null)
    var preparing: Boolean by mutableStateOf(true)
    var exitMessage: String? by mutableStateOf(null)
    var stopRequested: Boolean by mutableStateOf(false)
    private var cleanedUp = false

    val title: String get() = args.title
    val versionId: String get() = args.versionId

    fun isAlive(): Boolean = process?.isAlive == true

    fun appendLog(line: String) {
        mcPlaySessionScope.launch {
            consoleState.append(line)
        }
    }

    fun markExited(message: String? = null) {
        preparing = false
        process = null
        exitMessage = message
        if (!cleanedUp) {
            cleanedUp = true
            runCatching { args.cleanup?.invoke() }
                .onFailure { error -> consoleState.append("[RDI] 清理房间内容失败: ${error.message ?: error.javaClass.simpleName}") }
            cleanup()
        }
    }

    fun requestStop(force: Boolean = false) {
        stopRequested = true
        val currentProcess = process
        when {
            force -> currentProcess?.destroyForcibly()
            else -> currentProcess?.destroy()
        }
        markExited(if (force) "已强制结束" else "已停止")
    }
}

object McPlayStore {
    var pendingLaunch: McPlayArgs? = null
    var onBack: (() -> Unit)? = null
    var openConsoleOnly: Boolean = false
    var selectedSessionId: String? by mutableStateOf(null)
    val sessions = mutableStateListOf<McGameSession>()

    fun launchSessionTask(block: suspend () -> Unit) {
        mcPlaySessionScope.launch { block() }
    }

    fun appendProxyLog(line: String) {
        launchSessionTask {
            sessions
                .filter { it.isAlive() || it.preparing }
                .forEach { session -> session.consoleState.append(line) }
        }
    }

    fun createSession(args: McPlayArgs, cleanup: () -> Unit = {}): McGameSession {
        val session = McGameSession(args = args, cleanup = cleanup)
        sessions += session
        selectedSessionId = session.id
        return session
    }

    suspend fun admitSession(args: McPlayArgs, allowDuplicateVersion: Boolean): McGameSession? =
        ModpackLifecycleCoordinator.withVersionLock(args.versionId) {
            withContext(Dispatchers.Main.immediate) {
                if (!allowDuplicateVersion && aliveCount(args.versionId) > 0) {
                    null
                } else {
                    createSession(args)
                }
            }
        }

    fun selectedSession(): McGameSession? {
        val selected = selectedSessionId?.let { id -> sessions.firstOrNull { it.id == id } }
        return selected ?: sessions.lastOrNull()?.also { selectedSessionId = it.id }
    }

    fun closeStoppedSession(sessionId: String): Boolean {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index < 0) return false

        val session = sessions[index]
        if (session.preparing || session.isAlive()) return false

        val nextSelectedId = when {
            sessions.size <= 1 -> null
            index < sessions.lastIndex -> sessions[index + 1].id
            else -> sessions.getOrNull(index - 1)?.id
        }
        session.markExited(session.exitMessage)
        sessions.removeAt(index)
        if (selectedSessionId == sessionId || sessions.none { it.id == selectedSessionId }) {
            selectedSessionId = nextSelectedId
        }
        return true
    }

    fun hasAliveSessions(): Boolean = sessions.any { it.isAlive() || it.preparing }

    fun aliveCount(versionId: String): Int =
        sessions.count { it.versionId == versionId && (it.preparing || it.isAlive()) }

    fun markExited(sessionId: String, message: String? = null) {
        sessions.firstOrNull { it.id == sessionId }?.markExited(message)
    }
}
