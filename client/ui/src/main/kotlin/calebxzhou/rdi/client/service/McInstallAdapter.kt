package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.util.javaExePath
import calebxzau.rdi.client.CONF
import calebxzau.rdi.client.RDIClient
import calebxzau.rdi.mcinstall.McInstall
import calebxzau.rdi.mcinstall.McInstallDirectories
import calebxzau.rdi.mcinstall.McInstallEnvironment
import calebxzau.rdi.mcinstall.McLaunchPreparer

val mcInstall: McInstall by lazy {
    McInstall(
        McInstallEnvironment(
            directories = McInstallDirectories(
                mcDir = ClientDirs.mcDir,
                versionsDir = ClientDirs.versionsDir,
                librariesDir = ClientDirs.librariesDir,
                assetsDir = ClientDirs.assetsDir,
                assetIndexesDir = ClientDirs.assetIndexesDir,
                assetObjectsDir = ClientDirs.assetObjectsDir,
            ),
            preferMirror = { CONF.preferMcMirror },
            javaPath = { javaExePath },
            resourceLoader = { name -> runCatching { RDIClient.jarResource(name) } },
            launchPreparer = McLaunchPreparer { mcVersion, loader, versionId, versionDir, onProgress ->
                    createMinecraftLauncher().prepare(
                    request = minecraftLaunchRequest(
                        mcVersion = mcVersion,
                        loader = loader,
                        versionId = versionId,
                        versionDir = versionDir,
                    ),
                    onProgress = onProgress,
                )
            },
        )
    )
}
