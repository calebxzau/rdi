package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import java.io.File
import java.nio.file.Files

object EarlyDisplayMount {
    private const val JAR_NAME = "rdi-early-display.jar"
    private const val FML_CONFIG_NAME = "fml.toml"
    private const val DEFAULT_PROVIDER = "fmlearlywindow"
    private const val RDI_PROVIDER = "rdiearlywindow"
    private val EARLY_WINDOW_CONTROL_PATTERN =
        Regex("(?m)^[ \\t]*earlyWindowControl[ \\t]*=[ \\t]*(true|false)[ \\t]*\\r?$")
    private val EARLY_WINDOW_PROVIDER_PATTERN =
        Regex("(?m)^[ \\t]*earlyWindowProvider[ \\t]*=[ \\t]*\"([^\"]*)\"[ \\t]*\\r?$")

    fun mount(
        mcVersion: McVersion,
        modLoader: ModLoader,
        versionDir: File,
    ): Result<Boolean> = mount(
        sourceJar = ClientDirs.launcherLibDir.resolve(JAR_NAME),
        mcVersion = mcVersion,
        modLoader = modLoader,
        versionDir = versionDir,
    )

    fun mount(
        sourceJar: File,
        mcVersion: McVersion,
        modLoader: ModLoader,
        versionDir: File,
    ): Result<Boolean> {
        if (!isSupported(mcVersion, modLoader)) {
            return Result.success(false)
        }
        return runCatching {
            check(sourceJar.isFile) { "缺少Early Display运行库: ${sourceJar.absolutePath}" }
            val target = versionDir.resolve("mods").apply { mkdirs() }.resolve(JAR_NAME)
            val sourcePath = sourceJar.toPath()
            val targetPath = target.toPath()
            if (!Files.isSymbolicLink(targetPath) && target.isFile && Files.isSameFile(sourcePath, targetPath)) {
                return@runCatching false
            }
            Files.deleteIfExists(targetPath)
            Files.createLink(targetPath, sourcePath)
            true
        }
    }

    fun configureProvider(
        mcVersion: McVersion,
        modLoader: ModLoader,
        versionDir: File,
    ): Result<Boolean> {
        if (!isSupported(mcVersion, modLoader)) {
            return Result.success(false)
        }
        return runCatching {
            val configFile = versionDir.resolve("config").resolve(FML_CONFIG_NAME)
            val config = if (configFile.isFile) configFile.readText() else ""
            if (EARLY_WINDOW_CONTROL_PATTERN.find(config)?.groupValues?.get(1) == "false") {
                return@runCatching false
            }

            val providerMatch = EARLY_WINDOW_PROVIDER_PATTERN.find(config)
            val currentProvider = providerMatch?.groupValues?.get(1)
            if (currentProvider != null && currentProvider != DEFAULT_PROVIDER && currentProvider != RDI_PROVIDER) {
                return@runCatching false
            }
            if (currentProvider == RDI_PROVIDER) {
                return@runCatching true
            }

            val updatedConfig = if (providerMatch != null) {
                config.replaceRange(
                    providerMatch.range,
                    providerMatch.value.replace("\"$currentProvider\"", "\"$RDI_PROVIDER\""),
                )
            } else {
                val separator = if (config.isEmpty() || config.endsWith("\n")) "" else "\n"
                "$config$separator" + "earlyWindowProvider = \"$RDI_PROVIDER\"\n"
            }
            configFile.parentFile.mkdirs()
            configFile.writeText(updatedConfig)
            true
        }
    }

    private fun isSupported(mcVersion: McVersion, modLoader: ModLoader): Boolean =
        (mcVersion == McVersion.V201 && modLoader == ModLoader.forge) ||
            (mcVersion == McVersion.V211 && modLoader == ModLoader.neoforge)
}
