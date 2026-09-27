package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.common.util.humanSpeed
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.model.LibraryOsArch
import calebxzau.rdi.mclaunch.model.MojangDownloadArtifact
import calebxzau.rdi.mclaunch.model.MojangLibrary
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val libraryRepairLocks = Array(64) { Mutex() }

private fun libraryRepairLock(file: File): Mutex {
    val key = file.canonicalPath.lowercase(Locale.ROOT)
    return libraryRepairLocks[(key.hashCode() and Int.MAX_VALUE) % libraryRepairLocks.size]
}

private val hostOs = LibraryOsArch.detectHostOs()
val hostNativeArch = System.getProperty("os.arch")?.lowercase(Locale.ROOT).orEmpty().let { raw ->
    if (raw.contains("64") || raw.contains("amd64") || raw.contains("x86_64") || raw.contains("aarch64")) {
        "64"
    } else {
        "32"
    }
}

private val classpathOverrideArtifacts = setOf(
    "com.google.code.gson:gson",
    "com.google.guava:guava",
    "commons-codec:commons-codec",
    "commons-io:commons-io",
    "commons-logging:commons-logging",
    "it.unimi.dsi:fastutil",
    "net.java.dev.jna:jna",
    "net.java.jinput:jinput",
    "net.sf.jopt-simple:jopt-simple",
    "org.apache.commons:commons-compress",
    "org.apache.commons:commons-lang3",
    "org.apache.httpcomponents:httpclient",
    "org.apache.httpcomponents:httpcore",
    "org.apache.logging.log4j:log4j-api",
    "org.apache.logging.log4j:log4j-core",
    "org.apache.logging.log4j:log4j-slf4j18-impl",
    "org.apache.logging.log4j:log4j-slf4j2-impl",
    "org.slf4j:slf4j-api",
)

fun descriptorToLibraryPath(descriptor: String): String {
    val parts = descriptor.split("@", limit = 2)
    val coordinates = parts[0].split(":")
    require(coordinates.size >= 3) { "非法的库坐标: $descriptor" }
    val group = coordinates[0].replace('.', '/')
    val artifact = coordinates[1]
    val version = coordinates[2]
    val classifier = coordinates.getOrNull(3)?.takeIf(String::isNotBlank)
    val extension = parts.getOrNull(1)?.ifBlank { null } ?: "jar"
    return buildString {
        append(group).append('/').append(artifact).append('/').append(version).append('/')
        append(artifact).append('-').append(version)
        if (classifier != null) append('-').append(classifier)
        append('.').append(extension)
    }
}

fun MojangLibrary.shouldDownloadByArch(): Boolean = rulesAllow(rules)

fun MojangLibrary.nativeClassifierKey(): String? =
    natives?.get(hostOs.ruleOsName)?.replace("\${arch}", hostNativeArch)

fun MojangLibrary.nativeArtifact(): MojangDownloadArtifact? =
    nativeClassifierKey()?.let { downloads.classifiers?.get(it) }

fun MojangLibrary.mainArtifact(): MojangDownloadArtifact? {
    downloads.artifact?.let { return it }
    if (!downloads.classifiers.isNullOrEmpty() || !natives.isNullOrEmpty()) return null
    val descriptor = name.takeIf(String::isNotBlank) ?: return null
    val path = descriptorToLibraryPath(descriptor)
    val baseUrl = url?.trim().orEmpty().ifBlank {
        when {
            path.startsWith("net/neoforged/") -> "https://maven.neoforged.net/releases"
            path.startsWith("net/minecraftforge/") -> "https://maven.minecraftforge.net"
            path.startsWith("cpw/mods/") -> "https://maven.minecraftforge.net"
            else -> "https://libraries.minecraft.net"
        }
    }
    return MojangDownloadArtifact(
        sha1 = sha1 ?: checksums.firstOrNull().orEmpty(),
        size = size ?: 0L,
        url = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}",
        path = path,
    )
}

fun MojangLibrary.file(librariesDir: File): File =
    mainArtifact()?.path?.let { File(librariesDir, it) }
        ?: error("库$name 缺少artifact路径")

fun buildMinecraftClasspath(
    baseLibraries: List<MojangLibrary>,
    overrideLibraries: List<MojangLibrary>,
    librariesDir: File,
): List<String> {
    val archMatchedOverrides = overrideLibraries.filter(MojangLibrary::shouldDownloadByArch)
    val overrideEntries = archMatchedOverrides
    val baseEntries = baseLibraries
        .filter(MojangLibrary::shouldDownloadByArch)
    val overrideByKey = overrideEntries
        .mapNotNull { library -> classpathOverrideKey(library)?.let { it to library } }
        .toMap()
    val usedOverrideKeys = mutableSetOf<String>()
    val merged = buildList {
        baseEntries.forEach { library ->
            val key = classpathOverrideKey(library)
            if (key == null) {
                add(library)
            } else {
                add(overrideByKey[key]?.also { usedOverrideKeys += key } ?: library)
            }
        }
        overrideEntries
            .filter { library ->
                val key = classpathOverrideKey(library)
                key == null || key !in usedOverrideKeys
            }
            .forEach(::add)
    }
    val entries = merged.mapNotNull { library ->
        val path = library.mainArtifact()?.path?.takeIf(String::isNotBlank)
            ?: runCatching { descriptorToLibraryPath(library.name) }.getOrNull()
        path?.let { File(librariesDir, it) }
    }.filter(File::exists).distinctBy { it.absolutePath }
    return addClasspathCompatibilityLibraries(entries, librariesDir)
}

private fun libraryGroupArtifact(library: MojangLibrary): String? {
    val coordinates = library.name.split(':')
    if (coordinates.size < 2) return null
    return "${coordinates[0]}:${coordinates[1]}"
}

private fun String?.isIn(values: Set<String>): Boolean = this?.let(values::contains) == true

private fun classpathOverrideKey(library: MojangLibrary): String? =
    libraryGroupArtifact(library)?.takeIf { it in classpathOverrideArtifacts }

private fun addClasspathCompatibilityLibraries(entries: List<File>, librariesDir: File): List<String> {
    val files = entries.toMutableList()
    val hasSlf4jBinding = files.any { it.name.startsWith("log4j-slf4j18-impl-") }
    val hasSlf4jApi = files.any { it.name.startsWith("slf4j-api-") }
    if (hasSlf4jBinding && !hasSlf4jApi) {
        listOf(
            librariesDir.resolve("org/slf4j/slf4j-api/1.8.0-beta4/slf4j-api-1.8.0-beta4.jar"),
            librariesDir.resolve("org/slf4j/slf4j-api/2.0.1/slf4j-api-2.0.1.jar"),
            librariesDir.resolve("org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"),
        ).firstOrNull(File::exists)?.let(files::add)
    }
    return files.map(File::getAbsolutePath).distinct()
}

data class MinecraftLaunchLibraryIssue(
    val libraryName: String,
    val file: File,
    val reason: String,
    val expectedSha1: String = "",
    val actualSha1: String = "",
) {
    val summary: String
        get() = buildString {
            append(libraryName).append(": ").append(reason).append(" (").append(file.name).append(')')
            if (expectedSha1.isNotBlank()) append(" expected=").append(expectedSha1)
            if (actualSha1.isNotBlank()) append(" actual=").append(actualSha1)
        }
}

class MinecraftLaunchLibraryPreparer(
    private val librariesDir: File,
    private val downloader: MinecraftArtifactDownloader,
) {
    private data class Artifact(
        val library: MojangLibrary,
        val artifact: MojangDownloadArtifact,
        val file: File,
        val kind: String,
    )

    fun validate(
        baseLibraries: List<MojangLibrary>,
        overrideLibraries: List<MojangLibrary>,
    ): List<MinecraftLaunchLibraryIssue> = collect(baseLibraries, overrideLibraries).mapNotNull(::validate)

    suspend fun ensure(
        baseLibraries: List<MojangLibrary>,
        overrideLibraries: List<MojangLibrary>,
        onProgress: (String) -> Unit,
    ): Result<Unit> = runCatching {
        val broken = collect(baseLibraries, overrideLibraries).filter { validate(it) != null }
        if (broken.isEmpty()) {
            onProgress("运行库完整")
            return@runCatching
        }
        broken.forEachIndexed { index, item ->
            libraryRepairLock(item.file).withLock {
                val issue = validate(item) ?: return@withLock
                onProgress("${issue.summary}，开始修复")
                onProgress("修复${index + 1}/${broken.size}: ${item.file.name}")
                downloader.download(item.library.name, item.artifact, item.file) { progress ->
                    val total = progress.totalBytes.takeIf { it > 0 }?.humanFileSize ?: "未知"
                    onProgress("修复${item.file.name} ${progress.bytesDownloaded.humanFileSize}/$total ${progress.speedBytesPerSecond.humanSpeed}")
                }.getOrThrow()
                check(validate(item) == null) { "运行库修复后校验失败: ${item.library.name}" }
            }
        }
        val remaining = validate(baseLibraries, overrideLibraries)
        check(remaining.isEmpty()) { "运行库修复失败: ${remaining.first().summary}" }
        onProgress("运行库修复完成")
    }

    private fun collect(
        baseLibraries: List<MojangLibrary>,
        overrideLibraries: List<MojangLibrary>,
    ): List<Artifact> {
        val merged = mergeLibraries(baseLibraries, overrideLibraries)
        return merged.flatMap { library ->
            buildList {
                library.mainArtifact()?.let { artifact ->
                    val path = artifact.path?.takeIf(String::isNotBlank)
                        ?: runCatching { descriptorToLibraryPath(library.name) }.getOrNull()
                    if (path != null) add(Artifact(library, artifact, File(librariesDir, path), "运行库"))
                }
                library.nativeArtifact()?.let { artifact ->
                    artifact.path?.takeIf(String::isNotBlank)?.let { path ->
                        add(Artifact(library, artifact, File(librariesDir, path), "原生库"))
                    }
                }
            }
        }.distinctBy { it.file.absolutePath }
    }

    private fun mergeLibraries(
        baseLibraries: List<MojangLibrary>,
        overrideLibraries: List<MojangLibrary>,
    ): List<MojangLibrary> {
        val overrides = overrideLibraries.filter(MojangLibrary::shouldDownloadByArch)
        val filteredBase = baseLibraries
            .filter(MojangLibrary::shouldDownloadByArch)
        val overrideByKey = overrides
            .mapNotNull { it -> classpathOverrideKey(it)?.let { key -> key to it } }
            .toMap()
        val used = mutableSetOf<String>()
        return buildList {
            filteredBase.forEach { library ->
                val key = classpathOverrideKey(library)
                add(if (key == null) library else overrideByKey[key]?.also { used += key } ?: library)
            }
            overrides.filter { library ->
                val key = classpathOverrideKey(library)
                key == null || key !in used
            }.forEach(::add)
        }
    }

    private fun validate(item: Artifact): MinecraftLaunchLibraryIssue? {
        if (!item.file.exists()) return MinecraftLaunchLibraryIssue(item.library.name, item.file, "${item.kind}缺失")
        if (item.file.length() <= 0L) return MinecraftLaunchLibraryIssue(item.library.name, item.file, "${item.kind}文件为空")
        val expected = item.artifact.sha1.trim()
        if (expected.isNotBlank()) {
            val actual = runCatching { item.file.sha1 }.getOrElse { error ->
                return MinecraftLaunchLibraryIssue(item.library.name, item.file, "无法计算${item.kind}校验值: ${error.message}")
            }
            if (!actual.equals(expected, true)) {
                return MinecraftLaunchLibraryIssue(item.library.name, item.file, "${item.kind}校验失败", expected, actual)
            }
        } else if (!item.file.canOpenJar()) {
            return MinecraftLaunchLibraryIssue(item.library.name, item.file, "${item.kind}无法打开jar")
        }
        return null
    }

    private fun File.canOpenJar(): Boolean = runCatching {
        ZipFile(this).use { it.entries().hasMoreElements() }
    }.getOrDefault(false)
}
