package calebxzhou.rdi.client.service

import java.math.BigInteger
import java.net.URI
import java.util.Locale

internal sealed interface ArchiveUrlIdentity {
    data class Modrinth(
        val projectId: String,
        val fileId: String,
    ) : ArchiveUrlIdentity

    data class CurseForge(
        val fileId: Int,
    ) : ArchiveUrlIdentity
}

internal fun resolveArchiveModIdentity(urls: List<String>): ArchiveUrlIdentity? {
    urls.forEach { url ->
        parseModrinthIdentity(url)?.let { return it }
    }
    urls.forEach { url ->
        parseCurseForgeIdentity(url)?.let { return it }
    }
    return null
}

private fun parseModrinthIdentity(url: String): ArchiveUrlIdentity.Modrinth? {
    val uri = parseUri(url) ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (host != MODRINTH_HOST && !host.endsWith(MODRINTH_HOST_SUFFIX)) return null

    val segments = decodedPathSegments(uri) ?: return null
    if (segments.size != 5 || segments[0] != "data" || segments[2] != "versions") return null

    val projectId = segments[1]
    // The path is /data/{projectId}/versions/{fileId}/{fileName}.
    val fileId = segments[3]
    val fileName = segments[4]
    if (!isValidModrinthSegment(projectId) || !isValidModrinthSegment(fileId)) return null
    if (!fileName.endsWith(".jar", ignoreCase = true)) return null

    return ArchiveUrlIdentity.Modrinth(projectId = projectId, fileId = fileId)
}

private fun parseCurseForgeIdentity(url: String): ArchiveUrlIdentity.CurseForge? {
    val uri = parseUri(url) ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (!host.endsWith(CURSEFORGE_HOST_SUFFIX) || host == CURSEFORGE_HOST) return null

    val segments = decodedPathSegments(uri) ?: return null
    if (segments.size != 4 || segments[0] != "files") return null
    val firstSegment = segments[1]
    val secondSegment = segments[2]
    val fileName = segments[3]
    if (!DECIMAL.matches(firstSegment) || !DECIMAL.matches(secondSegment)) return null
    if (secondSegment.length !in 1..3 || !fileName.endsWith(".jar", ignoreCase = true)) return null

    val first = runCatching { BigInteger(firstSegment) }.getOrNull() ?: return null
    val second = secondSegment.toIntOrNull() ?: return null
    val fileId = first.multiply(THOUSAND).add(BigInteger.valueOf(second.toLong()))
    val checkedFileId = runCatching { fileId.intValueExact() }.getOrNull() ?: return null
    return ArchiveUrlIdentity.CurseForge(checkedFileId)
}

private fun parseUri(url: String): URI? = runCatching { URI(url) }.getOrNull()

private fun decodedPathSegments(uri: URI): List<String>? {
    val path = runCatching { uri.path }.getOrNull() ?: return null
    if (!path.startsWith('/')) return null
    return path.substring(1).split('/')
}

private fun isValidModrinthSegment(value: String): Boolean =
    value.isNotEmpty() && value != "." && value != ".."

private const val MODRINTH_HOST = "modrinth.com"
private const val MODRINTH_HOST_SUFFIX = ".modrinth.com"
private const val CURSEFORGE_HOST = "forgecdn.net"
private const val CURSEFORGE_HOST_SUFFIX = ".forgecdn.net"
private val DECIMAL = Regex("^[0-9]+$")
private val THOUSAND = BigInteger.valueOf(1_000L)
