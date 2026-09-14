package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzau.rdi.modpacktest.ModpackTestEnvironment
import calebxzau.rdi.modpacktest.ModpackTestLauncher
import calebxzau.rdi.modpacktest.ModpackTestModSourceResolver
import calebxzau.rdi.modpacktest.ModpackTestClientExtraResolver
import calebxzau.rdi.modpacktest.ModpackTestPaths
import calebxzau.rdi.modpacktest.ModpackTestProcess
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.client.service.content.toClientContentRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val modpackTestModSourceResolver = ModpackTestModSourceResolver { mods, testDir ->
    val modSourceDir = testDir.resolve(".rdi-mod-sources")
    ClientContentStores.shared.materialize(
        requests = mods.toClientContentRequests(),
        targetRoot = modSourceDir.toPath(),
    ).map { modSourceDir }
}

val modpackTestClientExtraResolver = ModpackTestClientExtraResolver { extras, testDir ->
    ClientContentStores.shared.materializeClientExtras(extras, testDir.toPath()).map { Unit }
}

val modpackTestEnvironment: ModpackTestEnvironment by lazy {
    ModpackTestEnvironment(
        paths = ModpackTestPaths(
            workDir = ClientDirs.packProcDir,
            librariesDir = ClientDirs.librariesDir,
        ),
        launcher = McModpackTestLauncher,
    )
}

private object McModpackTestLauncher : ModpackTestLauncher {
    override suspend fun prepareClientLoader(
        mcVersion: McVersion,
        loader: ModLoader,
        onProgress: (String) -> Unit,
    ): Result<Unit> = mcInstall.ensureDesktopLaunchLoader(
        mcVer = mcVersion,
        loader = loader,
        onProgress = onProgress,
    )

    override suspend fun prepareClientLibraries(
        mcVersion: McVersion,
        versionId: String,
        versionDir: File,
        onProgress: (String) -> Unit,
    ): Result<Unit> = mcInstall.ensureDesktopLaunchLibraries(
        mcVer = mcVersion,
        versionId = versionId,
        versionDir = versionDir,
        onProgress = onProgress,
    )

    override fun launchClient(
        mcVersion: McVersion,
        versionId: String,
        versionDir: File,
        onLine: (String) -> Unit,
    ): Result<ModpackTestProcess> = runCatching {
        DesktopModpackTestProcess(
            mcInstall.startDesktopInDir(
                mcVer = mcVersion,
                versionId = versionId,
                versionDir = versionDir,
                onLine = onLine,
            )
        )
    }

    override fun launchServer(
        mcVersion: McVersion,
        loaderVersion: ModLoader.Version,
        workDir: File,
        onLine: (String) -> Unit,
    ): Result<ModpackTestProcess> = runCatching {
        DesktopModpackTestProcess(
            mcInstall.startServerDesktop(
                mcVer = mcVersion,
                loaderVer = loaderVersion,
                workDir = workDir,
                onLine = onLine,
            )
        )
    }
}

private class DesktopModpackTestProcess(
    private val process: Process,
) : ModpackTestProcess {
    override fun isAlive(): Boolean = process.isAlive

    override fun stop(): Result<Unit> = runCatching {
        if (process.isAlive) process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }

    override suspend fun waitFor(): Result<Int> = runCatching {
        withContext(Dispatchers.IO) { process.waitFor() }
    }
}
