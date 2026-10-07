package calebxzhou.rdi.client.service

import calebxzau.rdi.mcinstall.McInstall
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.ServerLoaderRuntime
import calebxzau.rdi.mclaunch.MinecraftLaunchOverrides
import calebxzau.rdi.mclaunch.PreparedMinecraftLaunch
import calebxzhou.rdi.common.util.hardLinkFile
import java.io.File

fun McInstall.startDesktop(
    mcVer: calebxzhou.rdi.common.model.McVersion,
    loader: ModLoader,
    versionId: String,
    vararg jvmArgs: String,
    onLine: (String) -> Unit,
): Process = startDesktopInDir(
    mcVer,
    loader,
    versionId,
    versionListDir.resolve(versionId),
    MinecraftLaunchOverrides(),
    *jvmArgs,
    onLine = onLine,
)

fun McInstall.startDesktop(
    mcVer: calebxzhou.rdi.common.model.McVersion,
    loader: ModLoader,
    versionId: String,
    launchOverrides: MinecraftLaunchOverrides,
    vararg jvmArgs: String,
    onLine: (String) -> Unit,
): Process = startDesktopInDir(
    mcVer,
    loader,
    versionId,
    versionListDir.resolve(versionId),
    launchOverrides,
    *jvmArgs,
    onLine = onLine,
)

internal fun McInstall.startDesktopInDir(
    mcVer: calebxzhou.rdi.common.model.McVersion,
    loader: ModLoader,
    versionId: String,
    versionDir: File,
    vararg jvmArgs: String,
    onLine: (String) -> Unit,
): Process = startDesktopInDir(
    mcVer,
    loader,
    versionId,
    versionDir,
    MinecraftLaunchOverrides(),
    *jvmArgs,
    onLine = onLine,
)

internal fun McInstall.startDesktopInDir(
    mcVer: calebxzhou.rdi.common.model.McVersion,
    loader: ModLoader,
    versionId: String,
    versionDir: File,
    launchOverrides: MinecraftLaunchOverrides,
    vararg jvmArgs: String,
    onLine: (String) -> Unit,
): Process = createMinecraftLauncher()
    .launch(
        request = minecraftLaunchRequest(
            mcVersion = mcVer,
            loader = loader,
            versionId = versionId,
            versionDir = versionDir,
            launchOverrides = launchOverrides,
            extraJvmArgs = jvmArgs.toList(),
        ),
        onLine = onLine,
    )
    .getOrThrow()

internal suspend fun McInstall.prepareDesktopLaunch(
    mcVer: calebxzhou.rdi.common.model.McVersion,
    loader: ModLoader,
    versionId: String,
    launchOverrides: MinecraftLaunchOverrides,
    vararg jvmArgs: String,
    account: calebxzau.rdi.mclaunch.MinecraftAccount? = null,
    extraGameArgs: List<String> = emptyList(),
): Result<PreparedMinecraftLaunch> = createMinecraftLauncher().prepareLaunch(
    minecraftLaunchRequest(
        mcVersion = mcVer,
        loader = loader,
        versionId = versionId,
        versionDir = versionListDir.resolve(versionId),
        launchOverrides = launchOverrides,
        extraJvmArgs = jvmArgs.toList(),
        account = account,
        extraGameArgs = extraGameArgs,
    ),
)

internal suspend fun McInstall.ensureDesktopLaunchLibraries(
    mcVer: calebxzhou.rdi.common.model.McVersion,
    loader: ModLoader,
    versionId: String,
    versionDir: File = versionListDir.resolve(versionId),
    onProgress: (String) -> Unit,
): Result<Unit> = createMinecraftLauncher().prepare(
    request = minecraftLaunchRequest(
        mcVersion = mcVer,
        loader = loader,
        versionId = versionId,
        versionDir = versionDir,
    ),
    onProgress = onProgress,
)

fun McInstall.startServerDesktop(
    runtime: ServerLoaderRuntime,
    workDir: File,
    onLine: (String) -> Unit,
): Process {
    if (runtime.loader == ModLoader.Fabric) {
        stageFabricServerRuntime(runtime, workDir)
    }
    return createMinecraftLauncher()
        .launchServer(runtime, workDir, onLine)
        .getOrThrow()
}

internal fun linkServerRuntimeFile(link: File, source: File) {
    link.parentFile?.mkdirs()
    hardLinkFile(source, link).getOrThrow()
}
