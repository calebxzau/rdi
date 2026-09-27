package calebxzau.rdi.mclaunch

import java.util.concurrent.atomic.AtomicReference

/** Media prepared for one launch request; library integrity is still checked when launched. */
class PreparedMinecraftLaunch internal constructor(
    launch: ((String) -> Unit) -> Result<Process>,
) {
    private val pending = AtomicReference<(((String) -> Unit) -> Result<Process>)?>(launch)

    fun launch(onLine: (String) -> Unit): Result<Process> {
        val action = pending.getAndSet(null)
            ?: return Result.failure(IllegalStateException("本次启动准备结果已使用，请重新准备"))
        return action(onLine)
    }
}
