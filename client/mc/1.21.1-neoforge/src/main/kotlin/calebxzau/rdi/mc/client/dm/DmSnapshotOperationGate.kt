package calebxzau.rdi.mc.client.dm

import java.util.concurrent.atomic.AtomicLong

/** Mutual exclusion between manual snapshot tests and production sync jobs. */
object DmSnapshotOperationGate {
    private val nextToken = AtomicLong()
    private val owner = AtomicLong(0L)

    fun tryAcquire(kind: String): Lease? {
        require(kind.isNotBlank()) { "Snapshot operation kind must not be blank" }
        val token = nextToken.incrementAndGet()
        return if (owner.compareAndSet(0L, token)) Lease(kind, token) else null
    }

    class Lease internal constructor(val kind: String, private val token: Long) {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) owner.compareAndSet(token, 0L)
        }

        fun isHeld(): Boolean = !released.get() && owner.get() == token
    }
}
