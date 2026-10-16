package calebxzhou.rdi.common.model

import java.util.Locale
import kotlin.text.contains
import kotlin.text.lowercase

/**
 * @param clientPlatformId ID of the RDI launcher platform (UI library manifests, update routes, rdi.sh);
 * null when the launcher does not support this OS/arch.
 */
enum class LibraryOsArch(val archSuffix: String, val ruleOsName: String, val clientPlatformId: String?) {
    WIN_X64("-windows","windows", "windows-x64"),
    WIN_ARM64("-windows-arm64","windows", null),
    MAC_X64("-macos","osx", null),
    MAC_ARM64("-macos-arm64","osx", "macos-arm64"),
    LINUX_X64("-linux","linux", "linux-x64"),
    LINUX_ARM64("-linux-aarch_64","linux", null),
    ;
    val isWindows get()= this == WIN_X64 || this == WIN_ARM64
    val isMac get() = this == MAC_X64 || this == MAC_ARM64
    val isLinux get() = this == LINUX_X64 || this == LINUX_ARM64
    val isUnixLike get() = !isWindows
    companion object{

        fun fromClientPlatformId(id: String): LibraryOsArch? = entries.firstOrNull { it.clientPlatformId == id }

        fun detectHostOs(): LibraryOsArch {
            val osName = System.getProperty("os.name")?.lowercase(Locale.ROOT) ?: ""
            val normalizedName = when {
                osName.contains("win") -> "windows"
                osName.contains("mac") || osName.contains("os x") -> "osx"
                else -> "linux"
            }
            val rawArch = System.getProperty("os.arch")?.lowercase(Locale.ROOT) ?: ""
            val normalizedArch = when {
                rawArch.contains("aarch64") || (rawArch.contains("arm") && rawArch.contains("64")) -> "arm64"
                rawArch.contains("arm") -> "arm"
                rawArch.contains("64") || rawArch.contains("x86_64") || rawArch.contains("amd64") -> "x64"
                else -> "x86"
            }

            return when (normalizedName) {
                "windows" -> if (normalizedArch == "arm64") WIN_ARM64 else WIN_X64
                "osx" -> if (normalizedArch == "arm64") MAC_ARM64 else MAC_X64
                else -> if (normalizedArch == "arm64") LINUX_ARM64 else LINUX_X64
            }
        }

    }
}