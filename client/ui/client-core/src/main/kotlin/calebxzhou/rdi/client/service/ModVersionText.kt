package calebxzhou.rdi.client.service

import java.util.jar.JarFile

private val VERSION_SEPARATOR = Regex("[-+_ ]")
private val LOADER_TOKENS = setOf("forge", "neoforge", "fabric", "quilt", "mc")
private val PREFIXED_MC_VERSION = Regex("(?i)mc\\d+(\\.\\d+){1,2}")
private val BARE_MC_VERSION = Regex("1\\.([7-9]|1\\d|2\\d)(\\.\\d{1,2})?")
private val LEADING_V = Regex("(?i)^v(?=\\d)")
private val DOTTED_VERSION_START = Regex("(?i)^v?\\d+(\\.\\d+)+")

/**
 * Reduces a mod's declared version to the part a player cares about, e.g.
 * `mc1.20.1-0.5.1` → `0.5.1` and `1.20.1-forge-15.2.0.27` → `15.2.0.27`.
 *
 * Minecraft versions and loader names are removed only while another
 * version-like segment remains, so an ambiguous value is kept as declared.
 * Returns null for blank or unresolved build placeholders such as
 * `${file.jarVersion}`.
 */
fun simplifyModVersion(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if ("\${" in trimmed) return null

    val tokens = splitVersionTokens(trimmed).filter { it.text.isNotEmpty() }
    val withoutLoaders = tokens.filterNot { it.text.lowercase() in LOADER_TOKENS }
    val withoutPrefixedMc = withoutLoaders.filterNot { PREFIXED_MC_VERSION.matches(it.text) }
    val hasOtherVersionToken = withoutPrefixedMc.any { token ->
        DOTTED_VERSION_START.containsMatchIn(token.text) && !BARE_MC_VERSION.matches(token.text)
    }
    val kept = if (hasOtherVersionToken) {
        withoutPrefixedMc.filterNot { BARE_MC_VERSION.matches(it.text) }
    } else {
        withoutPrefixedMc
    }
    if (kept.isEmpty()) return trimmed.replace(LEADING_V, "")

    return kept.mapIndexed { index, token ->
        if (index == 0) token.text else token.separator + token.text
    }.joinToString("").replace(LEADING_V, "")
}

/** Version declared by the jar, falling back to the manifest for Forge's `${file.jarVersion}`. */
internal fun JarFile.readDisplayVersion(declaredVersion: String?): String? =
    simplifyModVersion(declaredVersion)
        ?: simplifyModVersion(manifest?.mainAttributes?.getValue("Implementation-Version"))

private data class VersionToken(val separator: String, val text: String)

private fun splitVersionTokens(value: String): List<VersionToken> = buildList {
    var separator = ""
    var start = 0
    VERSION_SEPARATOR.findAll(value).forEach { match ->
        add(VersionToken(separator, value.substring(start, match.range.first)))
        separator = match.value
        start = match.range.last + 1
    }
    add(VersionToken(separator, value.substring(start)))
}
