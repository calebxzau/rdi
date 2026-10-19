package calebxzhou.rdi.client.service

/** Counts the game sessions of a modpack version that are still preparing or running. */
fun interface RunningGameProbe {
    fun aliveCount(versionId: String): Int
}

/**
 * Lets launcher services refuse content changes while that version is being played.
 * The UI registers its session store at startup; until then every check fails instead of assuming nothing runs.
 */
object RunningGameGuard {
    @Volatile
    private var probe: RunningGameProbe? = null

    fun register(probe: RunningGameProbe) {
        this.probe = probe
    }

    fun aliveCount(versionId: String): Int =
        checkNotNull(probe) { "游戏运行状态尚未初始化" }.aliveCount(versionId)
}
