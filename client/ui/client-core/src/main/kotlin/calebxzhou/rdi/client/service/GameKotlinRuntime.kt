package calebxzhou.rdi.client.service

import calebxzau.rdi.mclaunch.GameKotlinRuntime
import calebxzhou.rdi.common.model.McVersion
import java.io.File

internal object GameKotlinRuntime {
    fun prepare(
        mcVersion: McVersion,
        modsDir: File,
        cacheRoot: File = ClientDirs.toolsDir.resolve("kotlin-runtime"),
    ): Result<List<File>> = GameKotlinRuntime.prepare(
        mcVersion = mcVersion,
        modsDir = modsDir,
        cacheRoot = cacheRoot,
    )
}
