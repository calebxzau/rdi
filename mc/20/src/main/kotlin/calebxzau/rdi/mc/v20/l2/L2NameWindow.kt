package calebxzau.rdi.mc.v20.l2

import java.util.Collections
import java.util.UUID

typealias AttributeNames = Map<String, Map<UUID, String>>

/** Collects changed attribute-name snapshots until a fixed one-second deadline. */
class L2NameWindow {
    private var lastSent: AttributeNames = immutableCopy(emptyMap())
    private var pending: AttributeNames = emptyMap()
    private var deadlineNanos: Long? = null

    fun reset(baseline: AttributeNames) {
        lastSent = immutableCopy(baseline)
        pending = emptyMap()
        deadlineNanos = null
    }

    fun offer(updates: AttributeNames, nowNanos: Long) {
        for ((attribute, names) in updates) {
            val snapshot = immutableNames(names)
            if (lastSent[attribute] == snapshot && lastSent.containsKey(attribute)) {
                pending = pending - attribute
            } else {
                pending = pending + (attribute to snapshot)
            }
        }

        if (pending.isEmpty()) {
            deadlineNanos = null
        } else if (deadlineNanos == null) {
            deadlineNanos = nowNanos + WINDOW_NANOS
        }
    }

    /** Returns changed complete attribute maps when the original deadline has elapsed. */
    fun due(nowNanos: Long): AttributeNames? {
        val deadline = deadlineNanos ?: return null
        // Subtraction remains valid across Long wrap for intervals shorter than half its range.
        if (nowNanos - deadline < 0) return null

        val result = immutableCopy(pending)
        lastSent = immutableCopy(lastSent + result)
        pending = emptyMap()
        deadlineNanos = null
        return result
    }

    fun clear() {
        lastSent = emptyMap()
        pending = emptyMap()
        deadlineNanos = null
    }

    private companion object {
        const val WINDOW_NANOS = 1_000_000_000L

        fun immutableCopy(names: AttributeNames): AttributeNames = Collections.unmodifiableMap(
            names.mapValuesTo(LinkedHashMap()) { (_, attributeNames) -> immutableNames(attributeNames) }
        )

        fun immutableNames(names: Map<UUID, String>): Map<UUID, String> =
            Collections.unmodifiableMap(LinkedHashMap(names))
    }
}
