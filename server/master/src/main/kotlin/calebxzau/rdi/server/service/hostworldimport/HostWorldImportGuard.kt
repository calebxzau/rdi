package calebxzau.rdi.server.service.hostworldimport

import org.bson.types.ObjectId

/**
 * Rejects lifecycle operations on a host while a save import is queued or running, or needs manual
 * recovery (plan §10.6). It reads the persisted sessions, so it also holds after a restart before
 * recovery has run. Callers check it inside `HostLifecycleLock`.
 */
object HostWorldImportGuard {
    @Volatile
    private var override: HostWorldImportStore? = null
    private val default by lazy { HostWorldImportStore() }

    /** The production store, created on first use; replaced in tests. */
    var store: HostWorldImportStore
        get() = override ?: default
        set(value) {
            override = value
        }

    /** Throws a `RequestError` if an import blocks [hostId]. */
    fun check(hostId: ObjectId) {
        store.blockingSession(hostId)?.let { store.checkNotBlocked(it) }
    }

    /** Cancels uploads and removes finished sessions of a host that is being deleted. */
    fun onHostDeleted(hostId: ObjectId) {
        store.deleteHost(hostId)
    }
}
