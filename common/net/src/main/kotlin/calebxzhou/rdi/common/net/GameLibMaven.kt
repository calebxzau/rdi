package calebxzhou.rdi.common.net

/**
 * Maven-layout game libraries that the RDI server can serve as a last-resort source
 * when both BMCLAPI and the official repositories fail for a player.
 */
object GameLibMaven {
    const val ROUTE = "/game-lib/maven"
    const val SHA1_PARAM = "sha1"
    const val BMCLAPI_BASE = "https://bmclapi2.bangbang93.com/maven"

    private const val MOJANG_BASE = "https://libraries.minecraft.net"
    private const val NEOFORGED_BASE = "https://maven.neoforged.net/releases"
    private const val FORGE_BASE = "https://maven.minecraftforge.net"
    private const val FABRIC_BASE = "https://maven.fabricmc.net"
    private val OFFICIAL_BASES = listOf(MOJANG_BASE, NEOFORGED_BASE, FORGE_BASE, FABRIC_BASE)

    // Library URLs the launcher may see; files.minecraftforge.net redirects to the Forge maven.
    private val KNOWN_BASES = OFFICIAL_BASES + "https://files.minecraftforge.net/maven"
    private val ALLOWED_EXTENSIONS = setOf("jar", "zip", "lzma")
    private val SEGMENT_REGEX = Regex("^[A-Za-z0-9._+-]+$")
    private val SHA1_REGEX = Regex("^[0-9a-fA-F]{40}$")

    /** Relative library path when [url] points into a known library repository. */
    fun libraryPathOf(url: String): String? {
        val trimmed = url.trim().substringBefore('?').substringBefore('#')
        val base = KNOWN_BASES.firstOrNull { trimmed.startsWith("$it/") } ?: return null
        return trimmed.removePrefix("$base/").takeIf(::isValidLibraryPath)
    }

    fun isValidLibraryPath(path: String): Boolean {
        val segments = path.split('/')
        // group/artifact/version/file at minimum.
        if (segments.size < 4) return false
        if (segments.any { it == "." || it == ".." || !SEGMENT_REGEX.matches(it) }) return false
        return segments.last().substringAfterLast('.', "").lowercase() in ALLOWED_EXTENSIONS
    }

    fun isValidSha1(sha1: String): Boolean = SHA1_REGEX.matches(sha1)

    /** RDI server URL for the library behind [officialUrl], or null when it is not a known library. */
    fun rdiSourceUrl(serverUrl: String, officialUrl: String, sha1: String = ""): String? {
        val path = libraryPathOf(officialUrl) ?: return null
        val query = sha1.trim().takeIf(::isValidSha1)?.let { "?${SHA1_PARAM}=${it.lowercase()}" }.orEmpty()
        return "${serverUrl.trimEnd('/')}${ROUTE}/${path}${query}"
    }

    /** Official repositories for [path], the one most likely to host it first. */
    fun officialUrlsFor(path: String): List<String> {
        val preferred = when {
            path.startsWith("net/neoforged/") -> NEOFORGED_BASE
            path.startsWith("net/minecraftforge/") || path.startsWith("cpw/mods/") -> FORGE_BASE
            path.startsWith("net/fabricmc/") -> FABRIC_BASE
            else -> MOJANG_BASE
        }
        return (listOf(preferred) + OFFICIAL_BASES.filterNot { it == preferred }).map { "$it/$path" }
    }
}
