package calebxzau.rdi.server.service.hostworldimport

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process execution claims for import sessions. The master runs as a single process, like
 * `HostLifecycleLock`.
 *
 * A claim is taken when a session is enqueued, so it covers both the queued and the running time.
 * The worker releases it after its last journal write. Recovery only works on sessions it can claim,
 * so it never touches a session that is queued or running, and a session is never enqueued twice.
 */
class ImportExecutionRegistry {
    private val claimed: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Claims [importId], or returns null if it is already claimed. */
    fun tryClaim(importId: UUID): ImportClaim? = if (claimed.add(importId)) ImportClaim(importId) else null

    fun isClaimed(importId: UUID): Boolean = importId in claimed

    inner class ImportClaim internal constructor(val importId: UUID) : AutoCloseable {
        private val released = AtomicBoolean(false)

        val isActive: Boolean get() = !released.get()

        /** Releases the claim. Calling it more than once has no further effect. */
        fun release() {
            if (released.compareAndSet(false, true)) claimed.remove(importId)
        }

        override fun close() = release()
    }
}
