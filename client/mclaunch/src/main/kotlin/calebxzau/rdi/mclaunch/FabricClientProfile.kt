package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import java.net.URI

object FabricClientProfile {
    private const val FABRIC_LOADER = "net.fabricmc.loader.impl.launch.knot.KnotClient"
    private const val FABRIC_MAVEN_HOST = "maven.fabricmc.net"
    private val sha1Pattern = Regex("[0-9a-fA-F]{40}")
    private val coordinatePartPattern = Regex("[A-Za-z0-9_.+\\-]+")

    fun validate(
        manifest: MojangVersionManifest,
        runtime: ClientLoaderRuntime,
        requireChecksums: Boolean = false,
    ): Result<Unit> = runCatching {
        require(runtime.mcVersion == McVersion.V201 && runtime.loader == ModLoader.Fabric) {
            "Fabric profile runtime must target Minecraft1.20.1"
        }
        require(runtime.profileId == "fabric-loader-0.19.5-1.20.1" && runtime.loaderVersion == "0.19.5") {
            "Unsupported Fabric client profile runtime"
        }
        require(manifest.id == runtime.profileId) { "Fabric profile id mismatch: ${manifest.id}" }
        require(manifest.inheritsFrom == runtime.mcVersion.mcVer) { "Fabric profile Minecraft version mismatch" }
        require(manifest.jar == null || manifest.jar == runtime.mcVersion.mcVer) { "Fabric profile declares an unexpected runtime JAR" }
        require(manifest.mainClass == FABRIC_LOADER) { "Unexpected Fabric client main class: ${manifest.mainClass}" }

        val coordinatesByGa = mutableMapOf<String, String>()
        val coordinates = manifest.libraries.map { library ->
            val parsed = parseCoordinate(library.name)
            val ga = "${parsed.group}:${parsed.artifact}"
            require(!(parsed.group == "net.minecraftforge" && parsed.artifact == "forge") &&
                !(parsed.group == "net.neoforged" && parsed.artifact == "neoforge")) {
                "Fabric profile contains a competing Forge loader: ${library.name}"
            }
            val previous = coordinatesByGa.putIfAbsent(ga, library.name)
            require(previous == null || previous == library.name) {
                "Conflicting Maven coordinates for ${ga}: $previous and ${library.name}"
            }
            val artifact = requireNotNull(library.mainArtifact()) { "Fabric library has no main artifact: ${library.name}" }
            if (library.downloads.artifact == null && library.sha1 != null) {
                require(sha1Pattern.matches(library.sha1)) { "Malformed SHA1 for ${library.name}" }
            }
            val canonicalPath = descriptorToLibraryPath(library.name)
            validateMavenPath(canonicalPath)
            val path = artifact.path?.takeIf(String::isNotBlank) ?: canonicalPath
            validateMavenPath(path)
            require(path == canonicalPath) { "Fabric artifact path does not match its Maven coordinate: ${library.name}" }
            val uri = URI(artifact.url)
            require(uri.scheme == "https" && uri.host == FABRIC_MAVEN_HOST && uri.userInfo == null && uri.port == -1) {
                "Fabric profile library URL must use https://${FABRIC_MAVEN_HOST}: ${artifact.url}"
            }
            require(uri.query == null && uri.fragment == null) { "Fabric profile library URL cannot include query or fragment: ${artifact.url}" }
            require(uri.path?.removePrefix("/") == canonicalPath) { "Fabric library URL path does not match its Maven coordinate: ${artifact.url}" }
            require(artifact.size >= 0L) { "Negative artifact size for ${library.name}" }
            if (artifact.sha1.isNotBlank()) require(sha1Pattern.matches(artifact.sha1)) {
                "Malformed SHA1 for ${library.name}"
            }
            if (requireChecksums) require(sha1Pattern.matches(artifact.sha1)) {
                "Missing or malformed SHA1 for ${library.name}"
            }
            parsed
        }
        require(coordinates.count { it.group == "net.fabricmc" && it.artifact == "fabric-loader" && it.version == runtime.loaderVersion } == 1) {
            "Fabric loader coordinate must occur exactly once"
        }
        require(coordinates.count { it.group == "net.fabricmc" && it.artifact == "intermediary" && it.version == runtime.mcVersion.mcVer } == 1) {
            "Fabric intermediary coordinate must occur exactly once"
        }
    }

    private data class Coordinate(val group: String, val artifact: String, val version: String)

    private fun parseCoordinate(name: String): Coordinate {
        val coordinate = name.substringBefore('@')
        val parts = coordinate.split(':')
        require(parts.size in 3..4 && parts.all { it.isNotEmpty() && coordinatePartPattern.matches(it) }) {
            "Invalid Maven coordinate: $name"
        }
        val extension = name.substringAfter('@', "jar")
        require(coordinatePartPattern.matches(extension)) { "Invalid Maven extension: $name" }
        return Coordinate(parts[0], parts[1], parts[2])
    }

    private fun validateMavenPath(path: String) {
        require(isSafeMavenPath(path)) { "Unsafe Fabric library path: $path" }
    }

    private fun isSafeMavenPath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\') &&
            !path.contains('\\') && !path.contains(':') &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

}
