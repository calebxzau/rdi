package calebxzau.rdi.mc.zstdcodec

import java.util.concurrent.Executor

/**
 * The server's copies of the persistent packets it sent, shared by all connections.
 *
 * A delta needs the base bytes the client named, so recent packets stay in memory and on disk across
 * restarts. Deltas are memoized, because the players of one host usually hold the same base.
 * Disk writes run on [executor]; a base missing from memory is read on the calling event loop.
 */
class PersistentPacketServerStore(
    private val disk: PersistentPacketStore,
    private val executor: Executor,
) {
    private val memory = object : LinkedHashMap<PersistentPacketHash, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PersistentPacketHash, ByteArray>): Boolean =
            size > MEMORY_ENTRIES
    }

    private val deltas = object : LinkedHashMap<Pair<PersistentPacketHash, PersistentPacketHash>, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<PersistentPacketHash, PersistentPacketHash>, ByteArray>,
        ): Boolean = size > DELTA_ENTRIES
    }

    /** The bytes of an earlier packet, or null when neither memory nor disk holds a valid copy. */
    fun base(kind: Int, hash: PersistentPacketHash): ByteArray? {
        synchronized(memory) { memory[hash] }?.let { return it }
        val loaded = disk.load(kind, hash).getOrElse { error ->
            LOGGER.log(System.Logger.Level.ERROR, "Failed to read persistent packet ${hash} of ${PersistentPacketKind.name(kind)}", error)
            return null
        } ?: return null
        synchronized(memory) { memory[hash] = loaded }
        return loaded
    }

    /** The delta from [base] to [target], computed once per pair while it stays recent. */
    internal fun delta(
        base: PersistentPacketHash,
        baseContent: ByteArray,
        target: PersistentPacketHash,
        targetContent: ByteArray,
    ): Result<ByteArray> {
        val key = base to target
        synchronized(deltas) { deltas[key] }?.let { return Result.success(it) }
        return runCatching { PersistentPacketDelta.encode(baseContent, targetContent) }
            .onSuccess { delta -> synchronized(deltas) { deltas[key] = delta } }
    }

    /** Keeps a sent packet as a future base; the disk copy is written in the background. */
    fun sent(kind: Int, hash: PersistentPacketHash, content: ByteArray) {
        synchronized(memory) { memory[hash] = content }
        executor.execute {
            disk.save(kind, hash, content).onFailure { error ->
                LOGGER.log(System.Logger.Level.ERROR, "Failed to store persistent packet ${hash} of ${PersistentPacketKind.name(kind)}", error)
            }
        }
    }

    private companion object {
        val LOGGER: System.Logger = System.getLogger(PersistentPacketServerStore::class.java.name)

        /** Recipes and tags, each in a current and a previous version. */
        const val MEMORY_ENTRIES = 4
        const val DELTA_ENTRIES = 4
    }
}
