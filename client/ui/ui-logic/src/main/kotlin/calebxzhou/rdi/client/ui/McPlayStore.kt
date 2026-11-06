package calebxzhou.rdi.client.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import calebxzau.rdi.mclaunch.MinecraftAccount
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
import kotlin.uuid.Uuid

private val mcPlaySessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

class McGameSession(
    val id: String = Uuid.generateV7().toString(),
    val args: McPlayArgs,
    val consoleState: ConsoleState = ConsoleState(),
    val startedAt: Long = System.currentTimeMillis(),
    private val cleanup: () -> Unit = {}
) {
    @Volatile var process: Process? = null
        set(value) { field = value; McPlayStore.changed() }
    @Volatile var preparing: Boolean = true
        set(value) { field = value; McPlayStore.changed() }
    @Volatile var exitMessage: String? = null
        set(value) { field = value; McPlayStore.changed() }
    @Volatile var stopRequested: Boolean = false
        set(value) { field = value; McPlayStore.changed() }
    private var cleanedUp = false

    val title: String get() = args.title
    val versionId: String get() = args.versionId

    @Synchronized
    fun attachProcess(started: Process): Boolean {
        if (stopRequested) {
            started.destroyForcibly()
            return false
        }
        process = started
        preparing = false
        return true
    }

    fun isAlive(): Boolean = process?.isAlive == true

    fun appendLog(line: String) {
        mcPlaySessionScope.launch {
            consoleState.append(line)
        }
    }

    @Synchronized
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

    @Synchronized
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
    @Volatile var selectedSessionId: String? = null
        set(value) { field = value; McPlayStore.changed() }
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    fun changed() { mutableRevision.update { it + 1 } }
    val sessions = java.util.concurrent.CopyOnWriteArrayList<McGameSession>()

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
            withContext(Dispatchers.Default) {
                synchronized(sessions) {
                if (!allowDuplicateVersion && aliveCount(args.versionId) > 0) {
                    null
                } else {
                    createSession(args)
                }
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
        changed()
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
