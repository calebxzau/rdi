package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.model.LibraryOsArch
import calebxzhou.rdi.common.serdesJson
import calebxzau.rdi.mclaunch.model.MojangRule
import calebxzau.rdi.mclaunch.model.MojangRuleAction
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale

private val hostOs = LibraryOsArch.detectHostOs()
private val hostOsArchRaw = System.getProperty("os.arch")?.lowercase(Locale.ROOT).orEmpty()
private val hostOsVersionRaw = System.getProperty("os.version").orEmpty()
private val launcherFeatures: Map<String, Boolean> = emptyMap()

fun resolveArgumentList(source: List<JsonElement>, osArch: LibraryOsArch = hostOs): List<String> {
    val args = mutableListOf<String>()
    val ruleListSerializer = ListSerializer(MojangRule.serializer())
    source.forEach { element ->
        when (element) {
            is JsonPrimitive -> if (element.isString) args += element.content
            is JsonObject -> {
                val rules = element["rules"]?.let { serdesJson.decodeFromJsonElement(ruleListSerializer, it) }
                if (!rulesAllow(rules, osArch)) return@forEach
                when (val value = element["value"]) {
                    is JsonPrimitive -> if (value.isString) args += value.content
                    is JsonArray -> value.forEach { item ->
                        if (item is JsonPrimitive && item.isString) args += item.content
                    }

                    else -> Unit
                }
            }

            else -> Unit
        }
    }
    return args
}

fun MojangVersionManifest.resolveGameArgumentList(): List<String> {
    val modernArgs = resolveArgumentList(arguments.game)
    if (modernArgs.isNotEmpty()) return modernArgs
    return minecraftArguments
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.filter(String::isNotBlank)
        .orEmpty()
}

fun MojangVersionManifest.resolveJvmArgumentList(osArch: LibraryOsArch = hostOs): List<String> =
    resolveArgumentList(arguments.jvm, osArch)

fun rulesAllow(rules: List<MojangRule>?, osArch: LibraryOsArch = hostOs): Boolean {
    if (rules.isNullOrEmpty()) return true
    var allowed = false
    rules.forEach { rule ->
        if (rule.matchesHost(osArch)) {
            allowed = rule.action == MojangRuleAction.allow
        }
    }
    return allowed
}

private fun MojangRule.matchesHost(osArch: LibraryOsArch): Boolean {
    os?.let { spec ->
        val osName = spec.name
        if (osName != null && !osArch.ruleOsName.equals(osName, true)) return false
        val archSpec = spec.arch?.lowercase(Locale.ROOT)
        if (archSpec != null && !hostOsArchRaw.contains(archSpec)) return false
        spec.version?.let { versionSpec ->
            val regex = runCatching { Regex(versionSpec) }.getOrNull()
            val matches = regex?.containsMatchIn(hostOsVersionRaw)
                ?: hostOsVersionRaw.contains(versionSpec, true)
            if (!matches) return false
        }
    }
    val requiredFeatures = features.orEmpty()
    return requiredFeatures.all { (feature, expected) -> launcherFeatures[feature] == expected }
}

fun MojangVersionManifest.resolveLaunchGameArguments(loaderManifest: MojangVersionManifest): List<String> =
    if (!loaderManifest.minecraftArguments.isNullOrBlank()) {
        loaderManifest.resolveGameArgumentList()
    } else {
        resolveGameArgumentList() + loaderManifest.resolveGameArgumentList()
    }

fun String.replaceLaunchTokens(
    nativesDir: File,
    versionDir: File,
    librariesDir: File,
    launcherBrand: String,
    launcherVersion: String,
    versionId: String,
    classpath: String,
): String = replace("\${natives_directory}", nativesDir.absolutePath)
    .replace("\${game_directory}", versionDir.absolutePath)
    .replace("\${library_directory}", librariesDir.absolutePath)
    .replace("\${libraries_directory}", librariesDir.absolutePath)
    .replace("\${launcher_name}", launcherBrand)
    .replace("\${launcher_version}", launcherVersion)
    .replace("\${version_name}", versionId)
    .replace("\${classpath}", classpath)
    .replace("\${classpath_separator}", File.pathSeparator)

fun MojangVersionManifest.isBootstrapModuleLaunch(): Boolean {
    if (mainClass != "cpw.mods.bootstraplauncher.BootstrapLauncher") return false
    val jvmArgs = resolveJvmArgumentList()
    return "-p" in jvmArgs && "ALL-MODULE-PATH" in jvmArgs
}

fun resolveLaunchVersionJarCandidates(
    manifest: MojangVersionManifest,
    loaderManifest: MojangVersionManifest,
    versionDir: File,
    versionsDir: File,
    versionId: String,
): List<File> {
    val versionNames = linkedSetOf<String>()
    versionNames += versionId
    loaderManifest.id.takeIf(String::isNotBlank)?.let(versionNames::add)
    loaderManifest.jar?.takeIf(String::isNotBlank)?.let(versionNames::add)
    loaderManifest.inheritsFrom?.takeIf(String::isNotBlank)?.let(versionNames::add)
    manifest.jar?.takeIf(String::isNotBlank)?.let(versionNames::add)
    manifest.id.takeIf(String::isNotBlank)?.let(versionNames::add)
    return versionNames.flatMap { name ->
        listOf(
            versionDir.resolve("$name.jar"),
            versionsDir.resolve(name).resolve("$name.jar")
        )
    }.distinctBy { it.absolutePath }
}
