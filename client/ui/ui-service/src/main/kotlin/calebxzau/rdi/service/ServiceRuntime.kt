package calebxzau.rdi.service

import calebxzau.rdi.client.RDIClient
import calebxzhou.rdi.client.database.MinecraftInstallationDatabase
import calebxzhou.rdi.client.service.ModpackLaunchOptionsService
import calebxzhou.rdi.client.service.RunningGameGuard
import calebxzhou.rdi.client.service.initializePlayerInfoCache
import calebxzhou.rdi.client.service.playerInfoCache
import calebxzhou.rdi.client.ui.McPlayStore

/** The business runtime initialization previously owned by the Compose entry point. */
internal class ServiceRuntime private constructor(
    private val database: MinecraftInstallationDatabase,
) : AutoCloseable {
    override fun close() {
        playerInfoCache.close()
        database.close()
    }

    companion object {
        fun open(): Result<ServiceRuntime> = runCatching {
            val database = MinecraftInstallationDatabase.open(RDIClient.DIR.toPath().resolve("data.db")).getOrThrow()
            try {
                initializePlayerInfoCache(database.playerInfoStore)
                ModpackLaunchOptionsService.initialize(database.modpackLaunchOptionsStore)
                RunningGameGuard.register(McPlayStore::aliveCount)
                ServiceRuntime(database)
            } catch (error: Exception) {
                database.close()
                throw error
            }
        }
    }
}
