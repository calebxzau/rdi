package calebxzhou.rdi.client.service

import calebxzau.rdi.client.RDIClient
import java.io.File

object ClientDirs {
    val logsDir: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"))
        .resolve(".rdi/logs")
        .also { it.mkdirs() }
    /** Historical cache path; kept as a read-only candidate for migration. */
    val dlPacksDir: File = RDIClient.DIR.resolve("dl-packs")
    /** Historical cache path. The startup content migrator is its only client reader. */
    val dlModsDir: File = RDIClient.DIR.resolve("dl-mods")
    val dlcDir: File = RDIClient.DIR.resolve("dlc").also { it.mkdirs() }
    val packProcDir: File = RDIClient.DIR.resolve("pack-proc").also { it.mkdirs() }
    val mcDir: File = RDIClient.DIR.resolve("mc").also { it.mkdirs() }
    val versionsDir: File = mcDir.resolve("versions").also { it.mkdirs() }
    val librariesDir: File = mcDir.resolve("libraries").also { it.mkdirs() }
    val assetsDir: File = mcDir.resolve("assets").also { it.mkdirs() }
    val assetIndexesDir: File = assetsDir.resolve("indexes").also { it.mkdirs() }
    val assetObjectsDir: File = assetsDir.resolve("objects").also { it.mkdirs() }
    val toolsDir: File = RDIClient.DIR.resolve("tools")
    val launcherLibDir: File = RDIClient.DIR.resolve("lib")
}
