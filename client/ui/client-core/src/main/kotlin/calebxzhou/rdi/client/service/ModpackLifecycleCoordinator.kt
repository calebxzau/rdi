package calebxzhou.rdi.client.service

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** Serializes launch and deletion lifecycle transitions for one installed version. */
object ModpackLifecycleCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withVersionLock(versionId: String, block: suspend () -> T): T {
        val lock = locks.computeIfAbsent(versionId) { Mutex() }
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
