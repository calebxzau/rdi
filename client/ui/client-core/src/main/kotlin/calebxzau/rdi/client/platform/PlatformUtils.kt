package calebxzau.rdi.client.platform

import calebxzau.rdi.client.RDIClient
import calebxzhou.rdi.common.model.LibraryOsArch
import com.sun.management.OperatingSystemMXBean
import java.io.File
import java.io.InputStream
import java.lang.management.ManagementFactory

fun loadResourceStream(name: String): InputStream {
    return RDIClient.jarResource(name)
}

fun readTotalPhysicalMemoryMb(): Int {
    val osBean = runCatching {
        ManagementFactory.getOperatingSystemMXBean()
    }.getOrNull()
    val totalBytes = (osBean as? OperatingSystemMXBean)
        ?.totalPhysicalMemorySize
        ?: return 0
    return (totalBytes / (1024L * 1024L)).toInt()
}

internal fun resolveJavaExecutable(raw: String, osArch: LibraryOsArch = LibraryOsArch.detectHostOs()): File? {
    val javaName = if (osArch.isWindows) "java.exe" else "java"
    val input = File(raw.trim())
    if (input.isDirectory) {
        // A Mac JDK bundle keeps its home in Contents/Home; the player may pick the .jdk bundle or Contents.
        val homes = if (osArch.isMac) listOf(input, input.resolve("Contents/Home"), input.resolve("Home")) else listOf(input)
        return homes.map { it.resolve("bin").resolve(javaName) }.firstOrNull { it.isFile }
    }
    if (input.exists()) {
        val name = input.name.lowercase()
        if (name == javaName) return input
        if (osArch.isWindows && name == "javaw.exe") {
            return input.parentFile?.resolve("java.exe")?.takeIf { it.exists() } ?: input
        }
        return null
    }
    if (!osArch.isWindows) return null
    val exe = File(raw.trim() + ".exe")
    return exe.takeIf { it.exists() }
}

fun validateJdkExecutablePath(rawPath: String): Result<String> = runCatching {
    require(rawPath.isNotBlank()) { "请输入JDK路径" }
    val javaExe = resolveJavaExecutable(rawPath)
        ?: error("JDK路径无效: $rawPath")
    val javacName = if (javaExe.name.equals("java.exe", ignoreCase = true)) "javac.exe" else "javac"
    val javacExe = javaExe.parentFile.resolve(javacName)
    require(javacExe.isFile) { "选择的目录不是完整JDK，缺少${javacName}" }
    val process = ProcessBuilder(javaExe.absolutePath, "-XshowSettings:properties", "-version")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    require(exitCode == 0) { "JDK无法运行java -version，退出代码$exitCode" }
    jdkArchMismatchMessage(output)?.let { throw IllegalArgumentException(it) }
    javaExe.toPath().toAbsolutePath().normalize().toString()
}

/**
 * Natives are only shipped for the launcher's own architecture, so an x64 JDK on Apple Silicon
 * (running under Rosetta) cannot start the game. Returns null when the architectures match or are unknown.
 */
internal fun jdkArchMismatchMessage(
    javaSettingsOutput: String,
    hostArch: String = System.getProperty("os.arch").orEmpty(),
    osArch: LibraryOsArch = LibraryOsArch.detectHostOs(),
): String? {
    val jdkArch = Regex("""^\s*os\.arch = (\S+)""", RegexOption.MULTILINE)
        .find(javaSettingsOutput)?.groupValues?.get(1) ?: return null
    val selected = normalizeJavaArch(jdkArch)
    val host = normalizeJavaArch(hostArch)
    if (selected == host || host.isEmpty()) return null
    return if (osArch.isMac) {
        "所选JDK是${jdkArch}版，请选择arm64版（Apple Silicon）JDK"
    } else {
        "所选JDK是${jdkArch}版，与本机架构${hostArch}不一致"
    }
}

private fun normalizeJavaArch(arch: String): String = when (val lower = arch.lowercase()) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> lower
}
