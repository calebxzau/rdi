package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.LibraryOsArch
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.ServerLoaderRuntime
import calebxzhou.rdi.common.model.FABRIC_1_20_1_LOADER_VERSION
import calebxzhou.rdi.common.model.FABRIC_SERVER_JAR
import calebxzhou.rdi.common.model.FABRIC_SERVER_LAUNCHER_JAR
import calebxzau.rdi.mclaunch.model.MojangDownloadArtifact
import calebxzau.rdi.mclaunch.model.MojangLibrary
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import calebxzau.rdi.common.logging.Loggers
import com.sun.management.OperatingSystemMXBean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.LinkedHashSet
import java.util.zip.ZipFile
import kotlin.concurrent.thread

private val utf8LoggingJvmArgs = listOf(
    "-Dfile.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
)

internal fun earlyDisplayJvmArgs(mcVersion: McVersion, nativeLibraryDir: File): List<String> =
    if (mcVersion == McVersion.V201 || mcVersion == McVersion.V211) {
        listOf("-Drdi.earlyDisplay.ffmpeg=${nativeLibraryDir.resolve("ffmpeg.exe").absolutePath}")
    } else {
        emptyList()
    }

internal const val RDI_ROOT_LOG_LEVEL_PROPERTY = "rdi.logging.rootLevel"

/**
 * FML 4.x always reloads its bundled log4j2.xml in production, ignoring `log4j2.configurationFile`.
 * That config ACCEPTs LOADING/CORE/FORGEMOD markers globally and uses root level `all`, so every per-mod
 * `LOGGER.trace(LOADING, ...)` builds a log event that the appenders then discard.
 * The marker filters read these properties; rdi-early-display lowers the root level once FML has reloaded it.
 * Arguments the user already set in [extraJvmArgs] win.
 */
internal fun neoForgeLoggingJvmArgs(mcVersion: McVersion, loader: ModLoader, extraJvmArgs: List<String>): List<String> {
    if (mcVersion != McVersion.V211 || loader != ModLoader.neoforge) return emptyList()
    val userKeys = extraJvmArgs.map { it.substringBefore('=') }.toSet()
    return listOf(
        "-Dforge.logging.marker.loading=NEUTRAL",
        "-Dforge.logging.marker.core=NEUTRAL",
        "-Dforge.logging.marker.forgemod=NEUTRAL",
        "-D${RDI_ROOT_LOG_LEVEL_PROPERTY}=DEBUG",
    ).filter { it.substringBefore('=') !in userKeys }
}

private data class LaunchManifests(
    val manifest: MojangVersionManifest,
    val loaderManifest: MojangVersionManifest,
    val gtnhExtensionRoot: File?,
    val baseLibraries: List<MojangLibrary>,
)

class MinecraftLauncher(
    private val environment: MinecraftLaunchEnvironment,
) {
    private val lgr by Loggers
    private val directories = environment.directories
    private val gtnh = GtnhLaunchSupport(
        directories = directories,
        launcherBrand = environment.launcherBrand,
        launcherVersion = environment.launcherVersion,
    )
    private val libraryPreparer = MinecraftLaunchLibraryPreparer(
        librariesDir = directories.librariesDir,
        downloader = environment.artifactDownloader,
    )

    suspend fun prepare(
        request: MinecraftLaunchRequest,
        onProgress: (String) -> Unit = {},
    ): Result<Unit> = runCatching {
        val sourceManifests = loadManifests(request)
        val launchManifests = resolveLaunchManifests(request, sourceManifests)
        ensureMinecraftClientJar(sourceManifests.manifest, onProgress)
        launchManifests.gtnhExtensionRoot?.let {
            gtnh.ensureRuntime(request.versionDir, sourceManifests.manifest, environment.artifactDownloader, onProgress)
                .getOrThrow()
        }
        libraryPreparer.ensure(
            baseLibraries = launchManifests.baseLibraries,
            overrideLibraries = launchManifests.loaderManifest.libraries,
            onProgress = onProgress,
        ).getOrThrow()
    }

    private suspend fun ensureMinecraftClientJar(
        manifest: MojangVersionManifest,
        onProgress: (String) -> Unit,
    ) {
        val artifact = manifest.downloads?.client
            ?: error("缺少Minecraft客户端JAR下载信息: ${manifest.id}")
        val target = directories.versionsDir
            .resolve(manifest.id)
            .resolve("${manifest.id}.jar")
        if (isValidMinecraftClientJar(target, artifact)) {
            onProgress("Minecraft客户端${manifest.id}完整")
            return
        }

        val reason = if (target.isFile) "校验失败" else "缺失"
        onProgress("Minecraft客户端${manifest.id}${reason}，开始修复")
        if (target.exists()) {
            check(target.delete()) { "无法替换Minecraft客户端JAR: ${target.absolutePath}" }
        }
        target.parentFile?.mkdirs()
        environment.artifactDownloader.download(
            label = "Minecraft客户端${manifest.id}",
            artifact = artifact,
            target = target,
        ) { progress ->
            val total = progress.totalBytes.takeIf { it > 0 }?.humanFileSize ?: "未知"
            onProgress("Minecraft客户端${manifest.id} ${progress.bytesDownloaded.humanFileSize}/$total")
        }.getOrThrow()
        check(isValidMinecraftClientJar(target, artifact)) {
            "Minecraft客户端JAR修复失败: ${target.absolutePath}"
        }
        onProgress("Minecraft客户端${manifest.id}已就绪")
    }

    private fun isValidMinecraftClientJar(
        target: File,
        artifact: MojangDownloadArtifact,
    ): Boolean {
        if (!target.isFile || target.length() <= 0L) return false
        if (artifact.size > 0L && target.length() != artifact.size) return false
        val expectedSha1 = artifact.sha1.trim()
        if (expectedSha1.isNotBlank()) {
            return runCatching { target.sha1.equals(expectedSha1, ignoreCase = true) }.getOrDefault(false)
        }
        return runCatching {
            ZipFile(target).use { it.entries().hasMoreElements() }
        }.getOrDefault(false)
    }

    /** Prepares media on IO before entering the caller's synchronous process ownership section. */
    suspend fun prepareLaunch(request: MinecraftLaunchRequest): Result<PreparedMinecraftLaunch> = try {
        val mediaRuntime = withContext(Dispatchers.IO) { resolveMediaRuntime(request.mcVersion) }
        currentCoroutineContext().ensureActive()
        Result.success(PreparedMinecraftLaunch { onLine ->
            launchInternal(request, onLine) { mediaRuntime }
        })
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun launch(
        request: MinecraftLaunchRequest,
        onLine: (String) -> Unit,
    ): Result<Process> = launchInternal(request, onLine) { resolveMediaRuntime(request.mcVersion) }

    private fun resolveMediaRuntime(mcVersion: McVersion): MediaProcGameRuntime? {
        if (mcVersion != McVersion.V201 && mcVersion != McVersion.V211) return null
        return environment.mediaRuntimeResolver(directories.toolsDir.resolve("mediaproc-natives"))
            .getOrElse { error ->
                lgr.error(error) { "Minecraft媒体模块不可用" }
                throw RequestError("媒体模块缺失或损坏，请先修复", error)
            }
    }

    private fun launchInternal(
        request: MinecraftLaunchRequest,
        onLine: (String) -> Unit,
        mediaRuntimeProvider: () -> MediaProcGameRuntime?,
    ): Result<Process> = runCatching {
        val sourceManifests = loadManifests(request)
        val launchManifests = resolveLaunchManifests(request, sourceManifests)
        launchManifests.gtnhExtensionRoot?.let { gtnh.ensureRuntimeMods(request.versionDir, it) }

        val manifest = launchManifests.manifest
        val loaderManifest = launchManifests.loaderManifest
        val launchLibraryIssues = libraryPreparer.validate(
            baseLibraries = launchManifests.baseLibraries,
            overrideLibraries = loaderManifest.libraries,
        )
        check(launchLibraryIssues.isEmpty()) {
            launchLibraryIssues.joinToString("; ") { it.summary }
        }

        val gtnhLaunch = launchManifests.gtnhExtensionRoot != null
        val nativesDir = if (gtnhLaunch) {
            request.mcVersion.nativesDir(directories)
        } else {
            request.versionDir.resolve("natives").takeIf(File::exists)
                ?: request.mcVersion.nativesDir(directories)
        }
        val gameArgs = manifest.resolveLaunchGameArguments(loaderManifest)
            .map { argument ->
                argument
                    .replace("\${auth_player_name}", request.account.name)
                    .replace("\${version_name}", request.versionId)
                    .replace("\${game_directory}", request.versionDir.absolutePath)
                    .replace("\${assets_root}", directories.assetsDir.absolutePath)
                    .replace("\${assets_index_name}", manifest.assets ?: manifest.assetIndex?.id ?: manifest.id)
                    .replace("\${auth_uuid}", request.account.uuid.replace("-", ""))
                    .replace("\${auth_access_token}", request.account.accessToken)
                    .replace("\${user_type}", "msa")
                    .replace("\${version_type}", "RDI")
                    .replace("\${user_properties}", "{}")
            }
            .toMutableList()
            .apply {
                add("--width")
                add(request.windowSize.width.toString())
                add("--height")
                add(request.windowSize.height.toString())
            }

        val resolvedJvmArgs = manifest.resolveJvmArgumentList() + loaderManifest.resolveJvmArgumentList()
        val needsForgeRuntime = request.loader != ModLoader.Fabric
        val hasRdiManagedRuntime = request.mcVersion == McVersion.V201 || request.mcVersion == McVersion.V211
        val kotlinClasspath = if (needsForgeRuntime && hasRdiManagedRuntime) {
            GameKotlinRuntime.prepare(
                mcVersion = request.mcVersion,
                modsDir = request.versionDir.resolve("mods"),
                cacheRoot = directories.toolsDir.resolve("kotlin-runtime"),
            ).getOrElse { error ->
                lgr.error(error) { "Minecraft Kotlin运行库不可用" }
                throw RequestError("Kotlin运行库冲突，无法启动游戏", error)
            }
        } else {
            emptyList()
        }
        val zstdClasspath = if (hasRdiManagedRuntime) {
            RdiZstdRuntime.resolve().getOrElse { error ->
                lgr.error(error) { "Minecraft Zstd运行库不可用" }
                throw RequestError("Zstd运行库缺失或损坏，请先修复", error)
            }
        } else {
            emptyList()
        }
        val mediaRuntime = mediaRuntimeProvider()
        val launchClasspath = buildLaunchClasspath(
            manifest = manifest,
            loaderManifest = loaderManifest,
            versionDir = request.versionDir,
            versionId = request.versionId,
            baseLibraries = launchManifests.baseLibraries,
            gtnhExtensionRoot = launchManifests.gtnhExtensionRoot,
            additionalClasspath = kotlinClasspath + zstdClasspath + mediaRuntime?.classpath.orEmpty(),
        )
        val classpath = launchClasspath.joinToString(File.pathSeparator)
        val legacyLaunch = resolvedJvmArgs.isEmpty() &&
            (!manifest.minecraftArguments.isNullOrBlank() || !loaderManifest.minecraftArguments.isNullOrBlank())
        val hasClasspathDeclaration = !gtnhLaunch && resolvedJvmArgs.any {
            it == "-cp" || it == "-classpath" || it.contains("\${classpath}")
        }
        val jvmVersionName = if (loaderManifest.isBootstrapModuleLaunch()) {
            loaderManifest.jar?.takeIf(String::isNotBlank)
                ?: loaderManifest.inheritsFrom?.takeIf(String::isNotBlank)
                ?: manifest.id
        } else {
            request.versionId
        }
        val processedJvmArgs = buildList {
            var skipNextClasspathValue = false
            resolvedJvmArgs.forEach { rawArg ->
                val argument = rawArg.replaceLaunchTokens(
                    nativesDir = nativesDir,
                    versionDir = request.versionDir,
                    librariesDir = directories.librariesDir,
                    launcherBrand = environment.launcherBrand,
                    launcherVersion = environment.launcherVersion,
                    versionId = jvmVersionName,
                    classpath = classpath,
                )
                if (gtnhLaunch) {
                    if (skipNextClasspathValue) {
                        skipNextClasspathValue = false
                        return@forEach
                    }
                    if (argument == "-cp" || argument == "-classpath") {
                        skipNextClasspathValue = true
                        return@forEach
                    }
                }
                add(argument)
            }
        }.toMutableList()
        if (legacyLaunch) {
            processedJvmArgs += listOf(
                "-Djava.library.path=${nativesDir.absolutePath}",
                "-Dminecraft.launcher.brand=${environment.launcherBrand}",
                "-Dminecraft.launcher.version=${environment.launcherVersion}",
            )
        }
        if (launchClasspath.isNotEmpty() && !hasClasspathDeclaration) {
            processedJvmArgs += listOf("-cp", classpath)
        }
        val maxMemoryMb = resolveMaxMemory(request.launchOverrides.maxMemoryMb)
        processedJvmArgs += "-Xmx${maxMemoryMb}M"
        //memory-efficient params
        processedJvmArgs += listOf(
            "-Xms512M",
            "-XX:+UseZGC",
            "-XX:SoftMaxHeapSize=${maxMemoryMb * 3 / 4}M",
            "-XX:ZUncommitDelay=60",
            "-XX:+UseCompactObjectHeaders"
        )
        processedJvmArgs += utf8LoggingJvmArgs
        processedJvmArgs += neoForgeLoggingJvmArgs(request.mcVersion, request.loader, request.extraJvmArgs)
        mediaRuntime?.let {
            processedJvmArgs += "-Dorg.bytedeco.javacpp.pathsFirst=true"
            // FfmpegNativePreloader loads FFmpeg from preloadpath; JavaCPP's class-path probing scans every mod for seconds.
            processedJvmArgs += "-Dorg.bytedeco.javacpp.findLibraries=false"
            processedJvmArgs += "-Dorg.bytedeco.javacpp.platform.preloadpath=${it.nativeLibraryDir.absolutePath}"
            processedJvmArgs += earlyDisplayJvmArgs(request.mcVersion, it.nativeLibraryDir)
        }

        launchManifests.gtnhExtensionRoot?.let {
            processedJvmArgs += gtnh.java25JvmArgs(
                extensionRoot = it,
                nativesDir = nativesDir,
                versionDir = request.versionDir,
                versionId = request.versionId,
                classpath = classpath,
            )
        }
        processedJvmArgs += request.extraJvmArgs
        if (environment.debug) processedJvmArgs += "-Djavax.net.ssl.trustStoreType=Windows-ROOT"

        val mainClass = loaderManifest.mainClass ?: error("缺少启动主类")
        val command = buildList {
            add(resolveJavaPath(request.launchOverrides.javaPath))
            addAll(processedJvmArgs)
            add(mainClass)
            addAll(gameArgs)
        }
        lgr.info { "JVM Args: ${processedJvmArgs.joinToString(" ")}" }
        lgr.info { "Game Args: ${gameArgs.joinToString(" ")}" }
        lgr.info { "Launch Command: ${command.joinToString(" ")}" }
        val process = environment.processStarter.start(command, request.versionDir).getOrThrow()
        readProcessOutput(process, onLine, "mc-log-reader")
        process
    }

    fun launchServer(
        runtime: ServerLoaderRuntime,
        workDir: File,
        onLine: (String) -> Unit,
    ): Result<Process> = runCatching {
        if (runtime.mcVersion !in setOf(McVersion.V201.mcVer, McVersion.V211.mcVer)) {
            throw RequestError("不支持${runtime.mcVersion}本地测试服务器")
        }
        val hostOs = LibraryOsArch.detectHostOs()
        val command = buildList {
            add(resolveJava25Path())
            add("-Xmx6G")
            add("-Xms6G")
            addAll(utf8LoggingJvmArgs)
            when (runtime.loader) {
                ModLoader.Fabric -> {
                    check(runtime.mcVersion == McVersion.V201.mcVer &&
                        runtime.loaderVersion == FABRIC_1_20_1_LOADER_VERSION && runtime.javaMajor == 25 &&
                        runtime.launcherJarName == FABRIC_SERVER_LAUNCHER_JAR &&
                        runtime.serverJarName == FABRIC_SERVER_JAR
                    ) { "不支持的Fabric测试服务端运行时配置" }
                    check(workDir.resolve(FABRIC_SERVER_LAUNCHER_JAR).isFile && workDir.resolve(FABRIC_SERVER_JAR).isFile) {
                        "Fabric测试服务端运行文件不完整，请先下载测试服务端"
                    }
                    add("--enable-native-access=ALL-UNNAMED")
                    addAll(runtime.launchArgs)
                    add("nogui")
                }

                ModLoader.forge, ModLoader.neoforge -> {
                    check(runtime.mcVersion == McVersion.V201.mcVer || runtime.mcVersion == McVersion.V211.mcVer) {
                        "不支持的${runtime.loader}测试服务端版本"
                    }
                    val loaderPath = when (runtime.loader) {
                        ModLoader.forge -> "@libraries/net/minecraftforge/forge/"
                        ModLoader.neoforge -> "@libraries/net/neoforged/neoforge/"
                        ModLoader.Fabric -> error("unreachable")
                    }
                    add("$loaderPath${runtime.loaderVersion}/${if (hostOs.isUnixLike) "unix" else "win"}_args.txt")
                    add("%*")
                    add("--nogui")
                }
            }
        }
        workDir.resolve("eula.txt").writeText("eula=true")
        val process = environment.processStarter.start(command, workDir).getOrThrow()
        readProcessOutput(process, onLine, "mc-server-log-reader")
        process
    }

    private fun loadManifests(request: MinecraftLaunchRequest): MinecraftManifestPair =
        environment.manifestProvider.load(request.mcVersion, request.loader, request.versionId, request.versionDir).getOrThrow()

    private fun resolveLaunchManifests(
        request: MinecraftLaunchRequest,
        source: MinecraftManifestPair,
    ): LaunchManifests {
        val gtnhExtensionRoot = if (request.mcVersion == McVersion.V071) {
            gtnh.requireExtensionRoot(request.versionDir)
        } else {
            null
        }
        val loaderManifest = if (gtnhExtensionRoot != null) {
            gtnh.buildLoaderManifest(gtnhExtensionRoot, request.versionId)
        } else {
            source.loaderManifest.copy(id = request.versionId)
        }
        val baseLibraries = if (gtnhExtensionRoot != null) {
            source.manifest.libraries.filterNot(MojangLibrary::isLegacyLwjgl2Library)
        } else {
            source.manifest.libraries
        }
        return LaunchManifests(source.manifest, loaderManifest, gtnhExtensionRoot, baseLibraries)
    }

    private fun buildLaunchClasspath(
        manifest: MojangVersionManifest,
        loaderManifest: MojangVersionManifest,
        versionDir: File,
        versionId: String,
        baseLibraries: List<MojangLibrary>,
        gtnhExtensionRoot: File?,
        additionalClasspath: List<File>,
    ): List<String> {
        val entries = LinkedHashSet<String>()
        additionalClasspath.forEach { entries += it.absolutePath }
        if (gtnhExtensionRoot != null) entries += gtnh.buildJava25Classpath(gtnhExtensionRoot)
        entries += buildMinecraftClasspath(baseLibraries, loaderManifest.libraries, directories.librariesDir)
        val versionJarCandidates = resolveLaunchVersionJarCandidates(
            manifest = manifest,
            loaderManifest = loaderManifest,
            versionDir = versionDir,
            versionsDir = directories.versionsDir,
            versionId = versionId,
        )
        val versionJars = versionJarCandidates.filter(File::exists)
        if (loaderManifest.isBootstrapModuleLaunch()) {
            val minecraftVersionName = loaderManifest.jar?.takeIf(String::isNotBlank)
                ?: loaderManifest.inheritsFrom?.takeIf(String::isNotBlank)
                ?: manifest.jar?.takeIf(String::isNotBlank)
                ?: manifest.id
            val minecraftJar = listOf(
                versionDir.resolve("$minecraftVersionName.jar"),
                directories.versionsDir.resolve(minecraftVersionName).resolve("$minecraftVersionName.jar"),
            ).firstOrNull(File::exists)
            check(minecraftJar != null) {
                "缺少Minecraft客户端JAR: $minecraftVersionName"
            }
        }
        versionJars.forEach { entries += it.absolutePath }
        return entries.toList()
    }

    private fun resolveJava25Path(): String {
        if (environment.java.currentJavaMajor == 25) return environment.java.currentJavaPath
        throw RequestError("请使用Java25启动RDI")
    }

    private fun resolveJavaPath(overridePath: String?): String =
        overridePath?.trim()?.takeIf(String::isNotEmpty) ?: resolveJava25Path()

    private fun resolveMaxMemory(overrideMaxMemoryMb: Int?): Long {
        val maxMemoryMb = overrideMaxMemoryMb ?: environment.java.maxMemoryMb
        return if (maxMemoryMb > 0) {
            maxMemoryMb.toLong()
        } else {
            runCatching {
                val osBean = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
                val freeBytes = osBean?.freeMemorySize ?: return@runCatching 8192L
                val freeMb = freeBytes / (1024L * 1024)
                lgr.info { "剩余内存${freeBytes.humanFileSize}" }
                if (freeMb > 8192) freeMb else 8192L
            }.getOrDefault(8192L)
        }
    }

    private fun readProcessOutput(process: Process, onLine: (String) -> Unit, threadName: String) {
        thread(name = threadName, isDaemon = true) {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line -> if (line.isNotBlank()) onLine(line) }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                onLine("MC已结束，退出代码: $exitCode")
            } else {
                onLine("已退出")
            }
        }
    }

    private fun McVersion.nativesDir(directories: MinecraftDirectories): File =
        directories.versionsDir.resolve(mcVer).resolve("natives")

    private fun linkFile(source: File, target: File): Result<Unit> = runCatching {
        check(source.isFile) { "源文件不存在: ${source.absolutePath}" }
        target.parentFile?.mkdirs()
        val sourcePath = source.toPath()
        val targetPath = target.toPath()
        if (targetPath.toFile().exists() && Files.isSameFile(sourcePath, targetPath)) return@runCatching
        Files.deleteIfExists(targetPath)
        Files.createLink(targetPath, sourcePath)
    }
}
