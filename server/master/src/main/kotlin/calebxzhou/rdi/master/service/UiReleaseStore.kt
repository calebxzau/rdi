package calebxzhou.rdi.master.service

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.LibraryOsArch
import calebxzhou.rdi.common.serdesJson
import java.io.File

/**
 * Reads UI library releases laid out as:
 *
 * ```text
 * <rootDir>/latest.txt
 * <rootDir>/{version}/release.zip        files shared by every platform
 * <rootDir>/{version}/{platform}.json    file name -> sha1 for one platform
 * ```
 */
class UiReleaseStore(private val rootDir: File) {
    data class Release(
        val version: String,
        val archive: File,
        val manifest: Map<String, String>,
    )

    fun load(rawVersion: String, platform: String): Release {
        val version = validateVersion(rawVersion)
        val versionDir = rootDir.resolve(version)
        val archive = versionDir.resolve(ARCHIVE_NAME)
        val manifestFile = versionDir.resolve("${validatePlatform(platform)}.json")
        if (!archive.isFile) throw RequestError("找不到UI版本ZIP: $version")
        if (!manifestFile.isFile) throw RequestError("该平台暂无此UI版本: $version")
        val manifest = runCatching {
            serdesJson.decodeFromString<Map<String, String>>(manifestFile.readText())
        }.getOrElse { throw RequestError("UI版本manifest格式错误: $version") }
        return Release(version, archive, validateManifest(manifest))
    }

    fun loadLatest(platform: String): Release {
        val latestFile = rootDir.resolve(LATEST_FILE_NAME)
        if (!latestFile.isFile) throw RequestError("找不到UI最新版本")
        return load(latestFile.readText(), platform)
    }

    /** Throws unless [name] is a plain file name listed in this platform's manifest. */
    fun requireLibrary(release: Release, name: String) {
        if (!isSafeFileName(name)) throw RequestError("非法文件名")
        if (name !in release.manifest) throw RequestError("无此文件")
    }

    companion object {
        const val WINDOWS_X64 = "windows-x64"
        const val ARCHIVE_NAME = "release.zip"
        const val LATEST_FILE_NAME = "latest.txt"

        private val versionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        private val platformPattern = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        private val sha1Pattern = Regex("^[0-9a-fA-F]{40}$")

        /** Platforms served to rdi.sh; Windows keeps using the legacy start.exe routes. */
        fun requireScriptPlatform(id: String): String {
            val osArch = LibraryOsArch.fromClientPlatformId(id)
            if (osArch == null || osArch.isWindows) throw RequestError("不支持的平台")
            return id
        }

        /** One `<sha1> <file name>` line per file, sorted by name, so rdi.sh can parse it with `read`. */
        fun manifestText(manifest: Map<String, String>): String =
            manifest.toSortedMap().entries.joinToString("") { (name, hash) -> "${hash.lowercase()} $name\n" }

        private fun validateVersion(rawVersion: String): String {
            val version = rawVersion.trim()
            if (!versionPattern.matches(version)) throw RequestError("非法UI版本")
            return version
        }

        private fun validatePlatform(platform: String): String {
            if (!platformPattern.matches(platform)) throw RequestError("不支持的平台")
            return platform
        }

        private fun isSafeFileName(name: String): Boolean =
            name.isNotBlank() && name != "." && name != ".." && name.trim() == name &&
                !name.contains('/') && !name.contains('\\') && !name.contains(':') && name.none { it.isISOControl() }

        private fun validateManifest(manifest: Map<String, String>): Map<String, String> {
            if (manifest.isEmpty() || manifest.keys.none { it.endsWith(".jar", ignoreCase = true) }) {
                throw RequestError("UI库manifest为空或缺少JAR")
            }
            if (manifest.any { (name, hash) -> !isSafeFileName(name) || !sha1Pattern.matches(hash) }) {
                throw RequestError("UI库manifest包含非法文件")
            }
            return manifest
        }
    }
}
