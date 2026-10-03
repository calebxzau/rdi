package calebxzau.rdi.mc.v20.server.attributes

import java.util.UUID

/** The client-visible state of one attribute. Modifier names are not part of the 1.20.1 protocol. */
internal data class AttributeSyncValue(val baseBits: Long, val modifiers: List<Modifier>) {
    /** Order is kept: a reordered modifier set counts as a change, which only costs a resend. */
    data class Modifier(val id: UUID, val amountBits: Long, val operation: Int)
}

/**
 * What one connection's client was last sent for each entity attribute. Not thread-safe; the
 * owner serializes access. Unknown entities are always sent in full.
 */
internal class AttributeSyncCache<K : Any>(private val refreshNanos: Long, private val maxEntities: Int) {
    private class Entry(var value: AttributeSyncValue, var sentNanos: Long)

    private val entities = HashMap<Int, HashMap<K, Entry>>()

    val entityCount: Int get() = entities.size

    /**
     * Returns which attributes must be sent and records them as sent. An attribute is skipped
     * only when it equals the last sent value and was sent less than [refreshNanos] ago.
     */
    fun filter(entityId: Int, attributes: List<Pair<K, AttributeSyncValue>>, nowNanos: Long): BooleanArray {
        val known = entities[entityId]
        if (known == null) {
            record(entityId, attributes, nowNanos)
            return BooleanArray(attributes.size) { true }
        }
        return BooleanArray(attributes.size) { index ->
            val (key, value) = attributes[index]
            val entry = known[key]
            when {
                entry == null -> {
                    known[key] = Entry(value, nowNanos)
                    true
                }
                entry.value != value || nowNanos - entry.sentNanos >= refreshNanos -> {
                    entry.value = value
                    entry.sentNanos = nowNanos
                    true
                }
                else -> false
            }
        }
    }

    /** Records attributes that were sent unfiltered. Entities beyond the cap stay uncached. */
    fun record(entityId: Int, attributes: List<Pair<K, AttributeSyncValue>>, nowNanos: Long) {
        val known = entities[entityId] ?: run {
            if (entities.size >= maxEntities) return
            HashMap<K, Entry>().also { entities[entityId] = it }
        }
        for ((key, value) in attributes) {
            val entry = known[key]
            if (entry == null) {
                known[key] = Entry(value, nowNanos)
            } else {
                entry.value = value
                entry.sentNanos = nowNanos
            }
        }
    }

    fun forget(entityId: Int) {
        entities.remove(entityId)
    }

    fun clear() = entities.clear()
}
