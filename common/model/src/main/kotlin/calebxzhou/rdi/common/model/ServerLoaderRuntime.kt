package calebxzhou.rdi.common.model

/** Pinned runtime settings used by server launchers. */
data class ServerLoaderRuntime(
    val mcVersion: String,
    val loader: ModLoader,
    val loaderVersion: String,
    val javaMajor: Int,
    val launcherJarName: String?,
    val serverJarName: String?,
    val launchArgs: List<String>,
)

/**
 * Resolves the launch contract separately from [McVersion.loaderVersions] installer metadata
 * and [McVersion.supportsModpackUpload] upload eligibility.
 */
fun McVersion.resolveServerRuntime(loader: ModLoader): ServerLoaderRuntime? {
    if (this == McVersion.V201 && loader == ModLoader.Fabric) {
        return ServerLoaderRuntime(
            mcVersion = mcVer,
            loader = loader,
            loaderVersion = FABRIC_1_20_1_LOADER_VERSION,
            javaMajor = 25,
            launcherJarName = FABRIC_SERVER_LAUNCHER_JAR,
            serverJarName = FABRIC_SERVER_JAR,
            launchArgs = listOf("-jar", FABRIC_SERVER_LAUNCHER_JAR),
        )
    }

    val configuredVersion = loaderVersions[loader] ?: return null
    val serverArgs = when (loader) {
        ModLoader.Fabric -> return null
        ModLoader.forge, ModLoader.neoforge -> listOf(configuredVersion.serverArgsPath(true))
    }
    return ServerLoaderRuntime(
        mcVersion = mcVer,
        loader = loader,
        loaderVersion = configuredVersion.id,
        javaMajor = jreSupport,
        launcherJarName = null,
        serverJarName = null,
        launchArgs = serverArgs,
    )
}

const val FABRIC_1_20_1_LOADER_VERSION = "0.19.5"
const val FABRIC_SERVER_LAUNCHER_JAR = "fabric-server-launch.jar"
const val FABRIC_SERVER_JAR = "server.jar"
