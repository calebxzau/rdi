package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.database.MinecraftInstallationStore
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.supportsRuntime
import calebxzhou.rdi.common.serdesJson
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private const val BOOTSTRAP_LAUNCHER = "cpw.mods.bootstraplauncher.BootstrapLauncher"
private const val LEGACY_LAUNCHWRAPPER = "net.minecraft.launchwrapper.Launch"
private const val FABRIC_KNOT_CLIENT = "net.fabricmc.loader.impl.launch.knot.KnotClient"
private const val LEGACY_FABRIC_KNOT_CLIENT = "net.fabricmc.loader.launch.knot.KnotClient"

data class LocalModpackCandidate(
    val name: String,
    val minecraftInstallation: Path,
    val versionDirectory: Path,
    val mcVersion: McVersion,
    val modLoader: ModLoader,
)

internal fun detectLocalModLoader(
    mainClass: String?,
    libraries: List<String>,
    mcVersion: McVersion,
): Result<ModLoader> = runCatching {
    val libraryLoaders = libraries.mapNotNull { library ->
        when {
            library.startsWith("net.fabricmc:fabric-loader:") -> ModLoader.Fabric
            library.startsWith("net.minecraftforge:forge:") -> ModLoader.forge
            library.startsWith("net.neoforged:neoforge:") -> ModLoader.neoforge
            library.startsWith("org.quiltmc:quilt-loader:") -> error("不支持Quilt启动器")
            else -> null
        }
    }.distinct()
    require(libraryLoaders.size <= 1) { "版本清单包含冲突的Mod加载器运行库" }
    val libraryLoader = libraryLoaders.singleOrNull()
    val entrypointLoader = when (mainClass) {
        FABRIC_KNOT_CLIENT, LEGACY_FABRIC_KNOT_CLIENT -> ModLoader.Fabric
        BOOTSTRAP_LAUNCHER -> libraryLoader?.takeIf {
            it == ModLoader.forge || it == ModLoader.neoforge
        } ?: if (mcVersion == McVersion.V211) ModLoader.neoforge else ModLoader.forge
        LEGACY_LAUNCHWRAPPER -> ModLoader.forge
        else -> null
    }
    if (entrypointLoader == null && libraryLoader == ModLoader.Fabric) {
        error("Fabric运行库存在，但启动类${mainClass.orEmpty().ifBlank { "缺失" }}不受支持")
    }
    if (libraryLoader != null && entrypointLoader != null) {
        require(libraryLoader == entrypointLoader) { "版本清单的启动类与Mod加载器运行库冲突" }
    }
    val loader = entrypointLoader ?: error("不支持启动类${mainClass.orEmpty()}")
    require(mcVersion.supportsRuntime(loader)) { "不支持${mcVersion.mcVer}/$loader" }
    loader
}

/** Finds usable, previously installed packs without including the launcher's managed instance. */
class LocalModpackScanner(
    private val installationStore: MinecraftInstallationStore,
    private val managedMinecraftDirectory: Path,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun scan(): Result<List<LocalModpackCandidate>> = withContext(Dispatchers.IO) {
        runCatching {
            installationStore.list().getOrThrow()
                .asSequence()
                .map { it.path.toAbsolutePath().normalize() }
                .filterNot(::isManagedDirectory)
                .flatMap(::scanInstallation)
                .distinctBy { pathKey(it.versionDirectory) }
                .sortedWith(
                    compareBy<LocalModpackCandidate> { it.minecraftInstallation.toString().lowercase(Locale.ROOT) }
                        .thenBy { it.name.lowercase(Locale.ROOT) },
                )
                .toList()
        }
    }

    private fun scanInstallation(installation: Path): Sequence<LocalModpackCandidate> {
        val versionsDirectory = installation.resolve("versions")
        if (!Files.isDirectory(versionsDirectory)) return emptySequence()
        return runCatching {
            Files.list(versionsDirectory).use { paths ->
                paths.filter(Files::isDirectory)
                    .map(::scanCandidate)
                    .filter { it != null }
                    .map { it!! }
                    .toList()
                    .asSequence()
            }
        }.onFailure { cause ->
            logger.warn(cause) { "扫描外部整合包失败：$versionsDirectory" }
        }.getOrDefault(emptySequence())
    }

    private fun scanCandidate(directory: Path): LocalModpackCandidate? = runCatching {
        val name = directory.fileName.toString()
        val manifestFile = directory.resolve("$name.json")
        require(Files.isRegularFile(manifestFile)) { "缺少版本清单" }
        val modsDirectory = directory.resolve("mods")
        require(Files.isDirectory(modsDirectory) && hasDirectJar(modsDirectory)) { "没有Mod文件" }

        val manifest = serdesJson.decodeFromString<MojangVersionManifest>(Files.readString(manifestFile))
        val minecraftVersion = manifest.clientVersion ?: manifest.inheritsFrom ?: manifest.id
        val mcVersion = McVersion.from(minecraftVersion)
            ?: error("不支持MC版本$minecraftVersion")
        if(!mcVersion.isModern) error("不是现代MC版本${minecraftVersion}")
        val modLoader = detectLocalModLoader(
            manifest.mainClass,
            manifest.libraries.map { it.name },
            mcVersion,
        ).getOrThrow()
        require(mcVersion.supportsRuntime(modLoader)) { "不支持${mcVersion.mcVer}/$modLoader" }

        LocalModpackCandidate(
            name = name,
            minecraftInstallation = directory.parent.parent,
            versionDirectory = directory,
            mcVersion = mcVersion,
            modLoader = modLoader,
        )
    }.onFailure { cause ->
        logger.info { "忽略外部整合包$directory：${cause.message}" }
    }.getOrNull()

    private fun hasDirectJar(directory: Path): Boolean = Files.list(directory).use { paths ->
        paths.anyMatch { path ->
            Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar", ignoreCase = true)
        }
    }

    private fun isManagedDirectory(path: Path): Boolean = pathKey(path) == pathKey(managedMinecraftDirectory)

    private fun pathKey(path: Path): String =
        path.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT)

}
