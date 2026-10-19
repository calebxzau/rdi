package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.ServerEntry
import calebxzau.rdi.client.lgr
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object NodeRefreshCoordinator {
    private val refreshMutex = Mutex()

    suspend fun refreshCurrent(
        gameBackup: Boolean = false,
                                forceMain: Boolean = false): Result<ServerEntry> = refreshMutex.withLock {
        lgr.info { "刷新节点入口" }
        refreshNodeSettings(gameBackup,forceMain)
            .onSuccess { lgr.info { "节点刷新成功: ${it.nodeName}" } }
            .onFailure { lgr.warn(it) { "节点刷新失败" } }
    }
}
