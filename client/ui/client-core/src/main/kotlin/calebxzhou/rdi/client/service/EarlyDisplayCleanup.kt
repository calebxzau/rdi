package calebxzhou.rdi.client.service

import java.io.File
import java.nio.file.Files

/** Removes what earlier launcher versions left in a game folder for the old RDI loading screen. */
object EarlyDisplayCleanup {
    private const val LEGACY_JAR_NAME = "rdi-early-display.jar"
    private const val FML_CONFIG_NAME = "fml.toml"
    private const val DEFAULT_PROVIDER = "fmlearlywindow"
    private val LEGACY_PROVIDER_PATTERN =
        Regex("(?m)^([ \\t]*earlyWindowProvider[ \\t]*=[ \\t]*)\"rdiearlywindow\"")

    /**
     * FML disables the loading window when the configured provider is missing, so the config changes before the jar
     * goes away. A provider or `earlyWindowControl` value the player chose stays as it is.
     */
    fun clean(versionDir: File): Result<Unit> = runCatching {
        val configFile = versionDir.resolve("config").resolve(FML_CONFIG_NAME)
        if (configFile.isFile) {
            val config = configFile.readText()
            val updatedConfig = LEGACY_PROVIDER_PATTERN.replace(config) { match ->
                "${match.groupValues[1]}\"$DEFAULT_PROVIDER\""
            }
            if (updatedConfig != config) {
                configFile.writeText(updatedConfig)
            }
        }
        Files.deleteIfExists(versionDir.resolve("mods").resolve(LEGACY_JAR_NAME).toPath())
    }
}
