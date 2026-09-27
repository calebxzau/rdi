package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzau.rdi.mclaunch.model.MojangDownloadArtifact
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import java.io.File

data class MinecraftDirectories(
    val mcDir: File,
    val versionsDir: File,
    val librariesDir: File,
    val assetsDir: File,
    val toolsDir: File,
)

data class MinecraftAccount(
    val name: String,
    val uuid: String,
    val accessToken: String,
)

data class MinecraftJava25Config(
    val currentJavaPath: String,
    val currentJavaMajor: Int,
    val maxMemoryMb: Int,
)

data class MinecraftWindowSize(
    val width: Int,
    val height: Int,
)

data class MinecraftManifestPair(
    val manifest: MojangVersionManifest,
    val loaderManifest: MojangVersionManifest,
)

fun interface MinecraftManifestProvider {
    fun load(
        mcVersion: McVersion,
        loader: ModLoader,
        versionId: String,
        versionDir: File,
    ): Result<MinecraftManifestPair>
}

data class MinecraftDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val fraction: Float,
    val speedBytesPerSecond: Double = 0.0,
)

fun interface MinecraftArtifactDownloader {
    suspend fun download(
        label: String,
        artifact: MojangDownloadArtifact,
        target: File,
        onProgress: (MinecraftDownloadProgress) -> Unit,
    ): Result<File>
}

fun interface MinecraftProcessStarter {
    fun start(command: List<String>, workingDirectory: File): Result<Process>
}

data class MinecraftLaunchEnvironment(
    val directories: MinecraftDirectories,
    val java: MinecraftJava25Config,
    val launcherBrand: String,
    val launcherVersion: String,
    val debug: Boolean,
    val manifestProvider: MinecraftManifestProvider,
    val artifactDownloader: MinecraftArtifactDownloader,
    val processStarter: MinecraftProcessStarter = MinecraftProcessStarter { command, workingDirectory ->
        runCatching {
            ProcessBuilder(command)
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .start()
        }
    },
    val mediaRuntimeResolver: (File) -> Result<MediaProcGameRuntime> = { nativeRoot ->
        MediaProcGameClasspath.resolve(nativeRoot = nativeRoot)
    },
)

data class MinecraftLaunchRequest(
    val mcVersion: McVersion,
    val loader: ModLoader,
    val versionId: String,
    val versionDir: File,
    val account: MinecraftAccount,
    val windowSize: MinecraftWindowSize,
    val launchOverrides: MinecraftLaunchOverrides = MinecraftLaunchOverrides(),
    val extraJvmArgs: List<String> = emptyList(),
)

data class MinecraftLaunchOverrides(
    val javaPath: String? = null,
    val maxMemoryMb: Int? = null,
)
