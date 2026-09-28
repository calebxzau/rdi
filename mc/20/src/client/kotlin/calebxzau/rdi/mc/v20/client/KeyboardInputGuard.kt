package calebxzau.rdi.mc.v20.client

import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

/** Called on the client thread; never retries a partially completed input callback. */
class KeyboardInputGuard internal constructor(
    private val nanoTime: () -> Long,
    private val report: (String, Exception) -> Unit
) {
    private data class FailureKey(val event: String, val screen: String, val type: String, val origin: StackTraceElement?)
    private data class FailureState(var lastReport: Long, var suppressed: Long = 0)

    private val failures = LinkedHashMap<FailureKey, FailureState>()

    fun run(event: String, screen: String, details: String, action: Runnable): Boolean {
        try {
            action.run()
            return true
        } catch (exception: Exception) {
            // Screen.wrapScreenError wraps even VM errors in ReportedException.
            // Inspect causes and suppressed exceptions, with identity-based cycle protection.
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val pending = ArrayDeque<Throwable>()
            pending.add(exception)
            while (pending.isNotEmpty()) {
                val current = pending.removeLast()
                if (!seen.add(current)) continue
                if (current is Error) throw current
                current.cause?.let(pending::add)
                current.suppressed.forEach(pending::add)
            }

            seen.clear()
            var origin: Throwable = exception
            while (seen.add(origin)) {
                val cause = origin.cause ?: break
                if (cause in seen) break
                origin = cause
            }
            val key = FailureKey(event, screen, origin.javaClass.name, origin.stackTrace.firstOrNull())
            val now = nanoTime()
            val previous = failures[key]
            if (previous != null && now - previous.lastReport < REPORT_INTERVAL_NANOS) {
                previous.suppressed++
                return false
            }
            val suppressed = previous?.suppressed ?: 0
            if (previous == null && failures.size >= MAX_FAILURES) {
                failures.remove(failures.keys.first())
            }
            failures[key] = FailureState(now)
            report("键盘输入异常，已跳过本次操作：event=${event}, screen=${screen}, ${details}, 期间省略重复异常${suppressed}次", exception)
            return false
        }
    }

    companion object {
        private const val REPORT_INTERVAL_NANOS = 10_000_000_000L
        private const val MAX_FAILURES = 128
        private val logger = LoggerFactory.getLogger("RDI Keyboard Input")
        private val instance = KeyboardInputGuard(System::nanoTime) { message, exception ->
            logger.error(message, exception)
        }

        @JvmStatic
        fun guard(event: String, screen: String, details: String, action: Runnable): Boolean =
            instance.run(event, screen, details, action)
    }
}
