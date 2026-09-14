package calebxzau.rdi.modpacktest

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzau.rdi.common.model.ContentSide
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.hardLinkDirectory
import calebxzhou.rdi.common.util.hardLinkFile
import calebxzhou.rdi.common.util.sha1
import calebxzau.rdi.client.packproc.LoadedLocalModpack
import calebxzau.rdi.mcinstall.writeMinecraftOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.random.Random

const val CLIENT_TEST_SUCCESS_MARKER = "开始运行客户端测试"

private const val CLIENT_ONLY_MARK_PREFIX = "C" + "$$" + "_"
private const val CLIENT_TEST_VERSION_PREFIX = "_rdi_client_test_"

class ModpackTestSession(
    private val loadedModpack: LoadedLocalModpack,
    val target: ModpackTestTarget,
    private val environment: ModpackTestEnvironment,
    private val modSourceResolver: ModpackTestModSourceResolver,
    private val clientExtraResolver: ModpackTestClientExtraResolver? = null,
) : AutoCloseable {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ModpackTestState())
    private val logChannel = Channel<String>(Channel.UNLIMITED)

    val state: StateFlow<ModpackTestState> = _state.asStateFlow()
    val logs: Flow<String> = logChannel.receiveAsFlow()

    @Volatile
    private var process: ModpackTestProcess? = null
    private var testJob: Job? = null
    private var testDir: File? = null
    @Volatile
    private var currentMods: List<Mod> = loadedModpack.mods
    private var crashTriggered = false
    private var startedAtMillis = 0L

    fun isRunning(): Boolean = testJob?.isActive == true || process?.isAlive() == true

    fun start(mods: List<Mod>): Result<Unit> = runCatching {
        check(!isRunning()) { if (target == ModpackTestTarget.CLIENT) "测试客户端已经在运行中" else "测试服务器已经在运行中" }
        if (target == ModpackTestTarget.SERVER) {
            checkNotNull(loadedModpack.mcVersion.loaderVersions[loadedModpack.modloader]) {
                "缺少加载器版本配置，无法启动测试服务器"
            }
        }
        currentMods = mods.toList()
        crashTriggered = false
        startedAtMillis = System.currentTimeMillis()
        _state.value = ModpackTestState(status = ModpackTestStatus.RUNNING)
        emitLog(if (target == ModpackTestTarget.CLIENT) "[RDI] 启动客户端测试..." else "[RDI] 启动测试服务器...")

        val job = scope.launch {
            try {
                when (target) {
                    ModpackTestTarget.CLIENT -> runClientTest()
                    ModpackTestTarget.SERVER -> runServerTest()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                fail(
                    message = if (target == ModpackTestTarget.CLIENT) {
                        "启动客户端测试失败: ${cause.message}"
                    } else {
                        "启动测试服务器失败: ${cause.message}"
                    },
                    cause = cause,
                )
                terminateProcess().onFailure { logger.warn(it) { "终止整合包测试进程失败" } }
            }
        }
        testJob = job
        job.invokeOnCompletion {
            if (testJob === job) testJob = null
        }
        return@runCatching
    }

    fun onModsChanged(mods: List<Mod>) {
        currentMods = mods.toList()
        val previous = _state.value
        _state.value = previous.copy(
            status = if (previous.status == ModpackTestStatus.PASSED) ModpackTestStatus.NOT_RUN else previous.status,
            passSeconds = if (previous.status == ModpackTestStatus.PASSED) null else previous.passSeconds,
            testedModsSignature = null,
        )
    }

    fun stop(): Result<Unit> = stop(markStopped = true, emitMessage = true)

    override fun close() {
        testJob?.cancel()
        testJob = null
        terminateProcess().onFailure { logger.warn(it) { "关闭整合包测试进程失败" } }
        scope.cancel()
        testDir?.let { dir ->
            runCatching { dir.deleteRecursivelyNoSymlink() }
                .onFailure { logger.warn(it) { "清理整合包测试目录失败: ${dir.absolutePath}" } }
        }
        testDir = null
        logChannel.close()
    }

    private suspend fun runClientTest() {
        environment.launcher.prepareClientLoader(
            mcVersion = loadedModpack.mcVersion,
            loader = loadedModpack.modloader,
            onProgress = { emitLog("[RDI] $it") },
        ).getOrThrow()
        val versionDir = createClientTestVersionDir(
            loadedModpack = loadedModpack,
            mods = currentMods,
            workDir = environment.paths.workDir,
            existingDir = testDir,
            modSourceResolver = modSourceResolver,
            clientExtraResolver = clientExtraResolver,
        )
        testDir = versionDir
        environment.launcher.prepareClientLibraries(
            mcVersion = loadedModpack.mcVersion,
            versionId = versionDir.name,
            versionDir = versionDir,
            onProgress = { emitLog("[RDI] $it") },
        ).getOrThrow()
        val launchedProcess = environment.launcher.launchClient(
            mcVersion = loadedModpack.mcVersion,
            versionId = versionDir.name,
            versionDir = versionDir,
            onLine = ::handleClientLine,
        ).getOrThrow()
        process = launchedProcess
        completeProcess(launchedProcess, launchedProcess.waitFor().getOrThrow(), "客户端测试异常退出")
    }

    private suspend fun runServerTest() {
        val loaderVersion = checkNotNull(loadedModpack.mcVersion.loaderVersions[loadedModpack.modloader])
        val workDir = createServerTestWorkDir(
            loadedModpack = loadedModpack,
            mods = currentMods,
            paths = environment.paths,
            existingDir = testDir,
            modSourceResolver = modSourceResolver,
        )
        testDir = workDir
        val launchedProcess = environment.launcher.launchServer(
            mcVersion = loadedModpack.mcVersion,
            loaderVersion = loaderVersion,
            workDir = workDir,
            onLine = ::handleServerLine,
        ).getOrThrow()
        process = launchedProcess
        completeProcess(launchedProcess, launchedProcess.waitFor().getOrThrow(), "测试服务器异常退出")
    }

    private fun completeProcess(completedProcess: ModpackTestProcess, exitCode: Int, exitMessage: String) {
        if (process === completedProcess) process = null
        val current = _state.value
        if (current.status == ModpackTestStatus.PASSED) return
        _state.value = current.copy(
            status = if (crashTriggered) ModpackTestStatus.FAILED else ModpackTestStatus.STOPPED,
            errorMessage = if (exitCode != 0 && crashTriggered) "$exitMessage: $exitCode" else current.errorMessage,
        )
    }

    private fun handleClientLine(line: String) {
        emitLog(line)
        if (_state.value.status != ModpackTestStatus.RUNNING) return
        when {
            line.contains(CLIENT_TEST_SUCCESS_MARKER) -> {
                val elapsed = (System.currentTimeMillis() - startedAtMillis) / 1000.0
                _state.value = _state.value.copy(
                    status = ModpackTestStatus.PASSED,
                    passSeconds = "%.1f".format(elapsed),
                    testedModsSignature = currentModsSignature(),
                    errorMessage = null,
                )
                emitLog("[RDI] 客户端测试通过")
                terminateProcessAfter(500L)
            }

            CLIENT_CRASH_TRIGGER_KEYWORDS.any { line.contains(it, ignoreCase = true) } -> {
                crashTriggered = true
                _state.value = _state.value.copy(status = ModpackTestStatus.FAILED)
                terminateProcessAfter(1000L)
            }
        }
    }

    private fun handleServerLine(line: String) {
        emitLog(line)
        if (_state.value.status != ModpackTestStatus.RUNNING) return
        val passed = SERVER_PASS_REGEX.find(line)
        when {
            passed != null -> {
                _state.value = _state.value.copy(
                    status = ModpackTestStatus.PASSED,
                    passSeconds = passed.groupValues.getOrNull(1),
                    testedModsSignature = currentModsSignature(),
                    errorMessage = null,
                )
                terminateProcessAfter(1000L)
            }

            line.contains("Error: could not open") -> emitLog(
                "${loadedModpack.mcVersion.mcVer}-${loadedModpack.modloader.name}缺少测试服务端文件，请在界面上方下载"
            )

            SERVER_CRASH_TRIGGER_KEYWORDS.any { line.contains(it, ignoreCase = true) } -> {
                crashTriggered = true
                _state.value = _state.value.copy(status = ModpackTestStatus.FAILED)
                terminateProcessAfter(1000L)
            }
        }
    }

    private fun terminateProcessAfter(delayMillis: Long) {
        scope.launch {
            delay(delayMillis)
            terminateProcess().onFailure { logger.warn(it) { "终止整合包测试进程失败" } }
        }
    }

    private fun stop(markStopped: Boolean, emitMessage: Boolean): Result<Unit> {
        val wasRunning = isRunning()
        testJob?.cancel()
        testJob = null
        return terminateProcess().map {
            if (wasRunning && markStopped && _state.value.status != ModpackTestStatus.PASSED) {
                _state.value = _state.value.copy(status = ModpackTestStatus.STOPPED)
            }
            if (wasRunning && emitMessage) {
                emitLog(
                    if (target == ModpackTestTarget.CLIENT) {
                        "[RDI] 已发送停止客户端测试指令"
                    } else {
                        "[RDI] 已发送停止测试服务器指令"
                    }
                )
            }
        }.onFailure { cause ->
            logger.warn(cause) { "停止整合包测试失败" }
            _state.value = _state.value.copy(errorMessage = "停止测试失败: ${cause.message}")
        }
    }

    private fun terminateProcess(): Result<Unit> {
        val activeProcess = process ?: return Result.success(Unit)
        process = null
        return activeProcess.stop()
    }

    private fun currentModsSignature(): String = currentMods.asSequence()
        .sortedBy(::modStableKey)
        .joinToString("|") { "${modStableKey(it)}:${it.side.name}" }

    private fun emitLog(message: String) {
        logChannel.trySend(message)
    }

    private fun fail(message: String, cause: Throwable) {
        logger.error(cause) { message }
        _state.value = _state.value.copy(
            status = ModpackTestStatus.FAILED,
            errorMessage = message,
        )
    }
}

private val SERVER_PASS_REGEX = Regex("""Done \((\d+(?:\.\d+)?)s\)! For help""")

private val SERVER_CRASH_TRIGGER_KEYWORDS = listOf(
    "Preparing crash report",
    "Failed to start the minecraft server",
    "Minecraft Crash Report",
    "Missing or unsupported mandatory dependencies",
)

private val CLIENT_CRASH_TRIGGER_KEYWORDS = listOf(
    "Preparing crash report",
    "MixinTransformerError",
    "Mod Loading has failed",
    "Missing mandatory dependencies",
)

private fun modStableKey(mod: Mod): String =
    "${mod.platform}:${mod.projectId}:${mod.fileId}:${mod.hash}"

private suspend fun createServerTestWorkDir(
    loadedModpack: LoadedLocalModpack,
    mods: List<Mod>,
    paths: ModpackTestPaths,
    existingDir: File?,
    modSourceResolver: ModpackTestModSourceResolver,
) = withContext(Dispatchers.IO) {
    val testDir = existingDir
        ?.takeIf { it.exists() && it.isDirectory }
        ?: createServerTestBaseDir(loadedModpack, paths)
    val modSourceDir = modSourceResolver.resolve(mods, testDir).getOrThrow()
    prepareServerTestRunContent(testDir, loadedModpack.sourceDir, mods, modSourceDir)
    testDir
}

private fun createServerTestBaseDir(
    loadedModpack: LoadedLocalModpack,
    paths: ModpackTestPaths,
): File {
    paths.workDir.mkdirs()
    val testDir = Files.createTempDirectory(paths.workDir.toPath(), "servertest-").toFile()
    if (paths.librariesDir.exists()) {
        hardLinkDirectory(paths.librariesDir, testDir.resolve("libraries")).getOrElse {
            throw IllegalStateException("创建测试目录libraries硬链接失败: ${it.message}", it)
        }
    }
    copyTestPackBaseContent(
        sourceDir = loadedModpack.sourceDir,
        targetDir = testDir,
        skipRootChild = ::isClientOnlyMarkedModFile,
        skipModsDirectories = true,
    )
    return testDir
}

private fun prepareServerTestRunContent(
    testDir: File,
    sourceDir: File,
    mods: List<Mod>,
    modSourceDir: File,
) {
    cleanRuntimeOutput(testDir, listOf("logs", "crash-reports", "world"))
    val modsDir = testDir.resolve("mods")
    if (modsDir.exists()) modsDir.deleteRecursivelyNoSymlink()
    modsDir.mkdirs()
    stageSourceModFiles(modsDir, sourceDir, mods) { it.side != Mod.Side.CLIENT && it.side != Mod.Side.UNKNOWN }
    stageDownloadedMods(modsDir, modSourceDir, mods) {
        it.side != Mod.Side.CLIENT && it.side != Mod.Side.UNKNOWN && !isClientOnlyMarkedModName(it.fileName)
    }
    modsDir.listFiles()?.filter(::isClientOnlyMarkedModFile)?.forEach { Files.deleteIfExists(it.toPath()) }
}

private suspend fun createClientTestVersionDir(
    loadedModpack: LoadedLocalModpack,
    mods: List<Mod>,
    workDir: File,
    existingDir: File?,
    modSourceResolver: ModpackTestModSourceResolver,
    clientExtraResolver: ModpackTestClientExtraResolver?,
) = withContext(Dispatchers.IO) {
    val versionDir = existingDir
        ?.takeIf { it.exists() && it.isDirectory }
        ?: createClientTestBaseDir(loadedModpack, workDir)
    val modSourceDir = modSourceResolver.resolve(mods, versionDir).getOrThrow()
    prepareClientTestRunContent(
        versionDir,
        loadedModpack.sourceDir,
        mods,
        loadedModpack.mcVersion,
        modSourceDir,
    )
    val clientExtras = loadedModpack.clientExtras.filter { it.side != ContentSide.Server }
    if (clientExtras.isNotEmpty()) {
        requireNotNull(clientExtraResolver) { "客户端测试缺少资源包和光影包解析器" }
            .resolve(clientExtras, versionDir)
            .getOrThrow()
    }
    writeMinecraftOptions(versionDir, loadedModpack.mcVersion).getOrThrow()
    versionDir
}

private fun createClientTestBaseDir(loadedModpack: LoadedLocalModpack, workDir: File): File {
    val versionId = CLIENT_TEST_VERSION_PREFIX + System.currentTimeMillis() + "_" + Random.nextInt(1000, 9999)
    workDir.mkdirs()
    val versionDir = workDir.resolve(versionId).apply {
        if (exists()) deleteRecursivelyNoSymlink()
        mkdirs()
    }
    copyDirectClientExtraRoots(loadedModpack.sourceDir, versionDir)
    copyTestPackBaseContent(
        sourceDir = loadedModpack.sourceDir,
        targetDir = versionDir,
        skipModsDirectories = true,
    )
    writeMinecraftOptions(versionDir, loadedModpack.mcVersion).getOrThrow()
    return versionDir
}

private fun copyDirectClientExtraRoots(sourceDir: File, targetDir: File) {
    listOf("resourcepacks", "shaderpacks").forEach { rootName ->
        val source = sourceDir.resolve(rootName)
        if (source.exists()) copyFileOrDirectory(source, targetDir.resolve(rootName))
    }
}

private fun prepareClientTestRunContent(
    versionDir: File,
    sourceDir: File,
    mods: List<Mod>,
    mcVersion: McVersion,
    modSourceDir: File,
) {
    cleanRuntimeOutput(versionDir, listOf("logs", "crash-reports", "saves"))
    writeMinecraftOptions(versionDir, mcVersion).getOrThrow()
    val modsDir = versionDir.resolve("mods")
    if (modsDir.exists()) modsDir.deleteRecursivelyNoSymlink()
    modsDir.mkdirs()
    stageSourceModDirectories(modsDir, sourceDir)
    stageSourceModFiles(modsDir, sourceDir, mods) { it.side != Mod.Side.SERVER && it.side != Mod.Side.UNKNOWN }
    stageDownloadedMods(modsDir, modSourceDir, mods) { it.side != Mod.Side.SERVER && it.side != Mod.Side.UNKNOWN }
}

private fun cleanRuntimeOutput(directory: File, children: List<String>) {
    children.forEach { name ->
        val file = directory.resolve(name)
        if (file.exists()) file.deleteRecursivelyNoSymlink()
    }
}

private fun copyTestPackBaseContent(
    sourceDir: File,
    targetDir: File,
    skipRootChild: (File) -> Boolean = { false },
    skipModsDirectories: Boolean = false,
) {
    val overridesDir = sourceDir.resolve("overrides")
    if (overridesDir.exists() && overridesDir.isDirectory) {
        copyDirectoryContent(overridesDir, targetDir, skipModsDirectories)
        return
    }
    sourceDir.listFiles()?.forEach { child ->
        if (skipRootChild(child)) return@forEach
        if (child.name.equals("mods", ignoreCase = true)) return@forEach
        if (child.name.equals("manifest.json", ignoreCase = true)) return@forEach
        if (child.name.equals("modrinth.index.json", ignoreCase = true)) return@forEach
        copyFileOrDirectory(child, targetDir.resolve(child.name), skipModsDirectories)
    }
}

private fun copyDirectoryContent(source: File, target: File, skipModsDirectories: Boolean = false) {
    source.listFiles()?.forEach { child ->
        if (skipModsDirectories && child.isDirectory && child.name.equals("mods", ignoreCase = true)) return@forEach
        if (isClientOnlyMarkedModFile(child)) return@forEach
        copyFileOrDirectory(child, target.resolve(child.name), skipModsDirectories)
    }
}

private fun copyFileOrDirectory(source: File, target: File, skipModsDirectories: Boolean = false) {
    if (source.isDirectory) {
        if (!target.exists()) target.mkdirs()
        copyDirectoryContent(source, target, skipModsDirectories)
        return
    }
    target.parentFile?.mkdirs()
    Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

private fun stageSourceModFiles(
    modsDir: File,
    sourceDir: File,
    mods: List<Mod>,
    includeMod: (Mod) -> Boolean,
) {
    listOf(sourceDir.resolve("mods"), sourceDir.resolve("overrides/mods")).forEach { sourceModsDir ->
        if (!sourceModsDir.exists() || !sourceModsDir.isDirectory) return@forEach
        sourceModsDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            ?.filterNot(::isClientOnlyMarkedModFile)
            ?.forEach { source ->
                val matchedMod = findMatchedSourceMod(source, mods)
                if (matchedMod != null && !includeMod(matchedMod)) return@forEach
                stageModFile(source, modsDir.resolve(matchedMod?.fileName ?: source.name))
            }
    }
}

private fun stageSourceModDirectories(modsDir: File, sourceDir: File) {
    listOf(sourceDir.resolve("mods"), sourceDir.resolve("overrides/mods")).forEach { sourceModsDir ->
        if (!sourceModsDir.exists() || !sourceModsDir.isDirectory) return@forEach
        sourceModsDir.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.forEach { copyFileOrDirectory(it, modsDir.resolve(it.name)) }
    }
}

private fun findMatchedSourceMod(source: File, mods: List<Mod>): Mod? {
    mods.firstOrNull { mod -> mod.fileNames.any { it.equals(source.name, ignoreCase = true) } }?.let { return it }
    val sourceHash = runCatching { source.sha1 }.getOrElse { cause ->
        throw IllegalStateException("读取源Mod SHA-1失败: ${source.absolutePath}", cause)
    }
    return mods.firstOrNull { it.hash.equals(sourceHash, ignoreCase = true) }
}

private fun stageDownloadedMods(
    modsDir: File,
    modSourceDir: File,
    mods: List<Mod>,
    includeMod: (Mod) -> Boolean,
) {
    mods.asSequence().filter(includeMod).forEach { mod ->
        val source = mod.fileNames.asSequence()
            .map(modSourceDir::resolve)
            .firstOrNull(File::isFile)
            ?: return@forEach
        stageModFile(source, modsDir.resolve(mod.fileName))
    }
}

private fun stageModFile(source: File, target: File) {
    check(source.isFile) { "源Mod文件不存在: ${source.absolutePath}" }
    target.parentFile?.mkdirs()
    val targetPath = target.toPath()
    if (!Files.exists(targetPath)) {
        hardLinkFile(source, target).getOrThrow()
        return
    }

    val sameContent = runCatching {
        Files.isSameFile(source.toPath(), targetPath) || Files.mismatch(source.toPath(), targetPath) == -1L
    }.getOrElse { cause ->
        throw IllegalStateException(
            "检查Mod文件冲突失败: 源文件${source.absolutePath}，目标文件${target.absolutePath}",
            cause,
        )
    }
    check(sameContent) {
        "Mod文件目标冲突: 目标文件${target.name}已存在，源文件${source.absolutePath}，目标文件${target.absolutePath}"
    }
}

private fun isClientOnlyMarkedModFile(file: File): Boolean =
    file.isFile && isClientOnlyMarkedModName(file.name) && file.extension.equals("jar", ignoreCase = true)

private fun isClientOnlyMarkedModName(fileName: String): Boolean = fileName.startsWith(CLIENT_ONLY_MARK_PREFIX)
