package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.ServerLoaderRuntime
import calebxzau.rdi.modpacktest.ModpackTestEnvironment
import calebxzau.rdi.modpacktest.ModpackTestLauncher
import calebxzau.rdi.modpacktest.ModpackTestModSourceResolver
import calebxzau.rdi.modpacktest.ModpackTestClientExtraResolver
import calebxzau.rdi.modpacktest.ModpackTestPaths
import calebxzau.rdi.modpacktest.ModpackTestProcess
import calebxzau.rdi.modpacktest.ModpackTestPreparedClient
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
    ): Result<Unit> = mcInstall.ensureDesktopLaunchProfile(
        mcVer = mcVersion,
        loader = loader,
        onProgress = onProgress,
    )

    override suspend fun prepareClientLaunch(
        mcVersion: McVersion,
        loader: ModLoader,
        versionId: String,
        versionDir: File,
        onProgress: (String) -> Unit,
    ): Result<ModpackTestPreparedClient> {
        val launcher = createMinecraftLauncher()
        val request = minecraftLaunchRequest(mcVersion, loader, versionId, versionDir)
        val preparedLibraries = withContext(Dispatchers.IO) { launcher.prepare(request, onProgress) }
        preparedLibraries.exceptionOrNull()?.let { return Result.failure(it) }
        onProgress("准备游戏媒体运行库")
        return launcher.prepareLaunch(request).map { prepared ->
            ModpackTestPreparedClient { onLine ->
                prepared.launch(onLine).map { DesktopModpackTestProcess(it) }
            }
        }
    }

    override fun launchServer(
        runtime: ServerLoaderRuntime,
        workDir: File,
        onLine: (String) -> Unit,
    ): Result<ModpackTestProcess> = runCatching {
        DesktopModpackTestProcess(
            mcInstall.startServerDesktop(
                runtime = runtime,
                workDir = workDir,
                onLine = onLine,
            ),
            requestGracefulStop = runtime.loader == ModLoader.Fabric,
        )
    }
}

private class DesktopModpackTestProcess(
    private val process: Process,
    private val requestGracefulStop: Boolean = false,
) : ModpackTestProcess {
    override fun isAlive(): Boolean = process.isAlive

    override fun stop(): Result<Unit> = runCatching {
        if (!process.isAlive) return@runCatching
        var inputFailure: Throwable? = null
        if (requestGracefulStop) {
            runCatching {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write("stop")
                    writer.newLine()
                    writer.flush()
                }
            }.onFailure {
                inputFailure = it
            }
            if (inputFailure == null && process.waitFor(FABRIC_STOP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                return@runCatching
            }
        } else {
            process.destroy()
            if (!process.isAlive) return@runCatching
        }
        process.destroyForcibly()
        check(process.waitFor(FORCE_STOP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            "无法停止测试服务端进程"
        }
        inputFailure?.let { throw IllegalStateException("无法向Fabric测试服务端发送stop命令", it) }
    }

    override suspend fun waitFor(): Result<Int> = runCatching {
        withContext(Dispatchers.IO) { process.waitFor() }
    }
}

private const val FABRIC_STOP_TIMEOUT_SECONDS = 20L
private const val FORCE_STOP_TIMEOUT_SECONDS = 5L
