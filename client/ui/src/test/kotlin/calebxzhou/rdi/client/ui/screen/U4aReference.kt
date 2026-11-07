package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import calebxzhou.rdi.client.database.*
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.service.*
import calebxzhou.rdi.client.ui.comp.Console
import calebxzhou.rdi.client.ui.comp.ConsoleState
import calebxzhou.rdi.client.ui.comp.Task2DetailDialog
import calebxzhou.rdi.common.model.*
import calebxzau.rdi.client.ui.RTheme
import calebxzau.rdi.client.ui.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bson.types.ObjectId
import java.io.File
import java.nio.file.Path

/** Screenshot-only harness: real Compose components with explicit, inert display fixtures. */
fun main(args: Array<String>) {
    val screen = args.getOrElse(0) { "install-source" }
    val scale = args.getOrElse(1) { "1" }.toFloat()
    loggedAccount = RAccount(ObjectId("000000000000000000000001"), "TestPlayer", "", "")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val install = referenceInstaller(scope)
    val option = ModpackOptionViewModel("reference", object : ModpackLaunchOptionsStore {
        override suspend fun find(versionId: String) = Result.success<ModpackLaunchOptionsRecord?>(null)
        override suspend fun upsert(record: ModpackLaunchOptionsRecord) = Result.success(Unit)
        override suspend fun delete(versionId: String) = Result.success(Unit)
        override fun close() {}
    }, object : ModpackOptionRuntime {
        override fun getTotalPhysicalMemoryMb() = 16384
        override fun validateJdkPath(rawPath: String) = Result.success(rawPath)
        override fun validateMaxMemory(maxMemoryText: String, totalMemoryMb: Int) = runCatching { maxMemoryText.toInt() }
    }, scope)
    scope.cancel()
    val candidate = LocalModpackCandidate("测试整合包", Path.of("C:/Games/.minecraft"), Path.of("C:/Games/.minecraft/versions/Test"), McVersion.V211, ModLoader.neoforge)
    val files = listOf(
        ModpackArchiveFile("required", "mods/required.jar", "必需Mod", "整合包必需内容", emptyList(), 1024, "0".repeat(40), emptyList(), required = true, contentType = ModpackArchiveContentType.MOD),
        ModpackArchiveFile("optional", "mods/optional.jar", "可选Mod", "可以选择是否安装", emptyList(), 1024, "0".repeat(40), emptyList(), required = false, contentType = ModpackArchiveContentType.MOD),
    )
    val archive = ModpackArchivePreview(Path.of("C:/Downloads/example.mrpack"), 1, 1, ModpackArchiveFormat.MODRINTH,
        "测试整合包", "测试简介", McVersion.V211, ModLoader.neoforge, files, emptyList(), emptyList(),
        unavailableFiles = if (screen == "install-missing") listOf(ModpackArchiveUnavailableFile("missing", "无法下载的Mod", true)) else emptyList())
    val editor = LocalModpackInstallEditorState(LocalModpackInstallSource.Archive(archive, emptySet()), McVersion.V211, ModLoader.neoforge,
        "测试整合包", "测试简介", confirmMissingFiles = screen == "install-missing")
    val installState = when (screen) {
        "install-source" -> LocalModpackInstallState(scanning = false, candidates = listOf(candidate))
        "install-loading" -> LocalModpackInstallState(scanning = true, archiveLoading = true)
        "install-empty" -> LocalModpackInstallState(scanning = false)
        "install-error" -> LocalModpackInstallState(scanning = false, archiveError = "无法读取整合包安装包")
        "install-candidates" -> LocalModpackInstallState(scanning = false, pickedCandidates = listOf(candidate, candidate.copy(name = "另一个版本")))
        "install-reading" -> LocalModpackInstallState(scanning = false, editor = editor.copy(source = null, archivePreviewLoading = true, archiveProgress = ModpackArchiveReadProgress(ModpackArchiveReadStage.SCANNING_ENTRIES)))
        "install-directory" -> LocalModpackInstallState(scanning = false, editor = editor.copy(source = LocalModpackInstallSource.CopyFrom(Path.of("C:/Games/Test")), versionSelectable = true))
        "install-create" -> LocalModpackInstallState(scanning = false, editor = editor.copy(source = LocalModpackInstallSource.Empty))
        "install-content-empty" -> LocalModpackInstallState(scanning = false, editor = editor.copy(source = LocalModpackInstallSource.Archive(archive.copy(files = emptyList()), emptySet())))
        else -> LocalModpackInstallState(scanning = false, editor = editor)
    }
    setDisplayState(install, "mutableState", installState)
    setDisplayState(option, "_uiState", when(screen) {
        "options-loading" -> ModpackOptionUiState()
        "options-error" -> ModpackOptionUiState(loading = false, loadFailed = true, errorMessage = "读取整合包设置失败：测试错误")
        else -> ModpackOptionUiState(loading = false, totalMemoryMb = 16384)
    })
    val info = LocalModpackInfo("019a55b0-0000-7000-8000-000000000001", "测试整合包", mcVersion = McVersion.V211,
        modLoader = ModLoader.neoforge, createdAt = 1, source = LocalModpackSourceKind.Empty)
    val pack = ModpackLocalDir(File("versions/local_${info.id}"), "1.21.1", null, 1, info)
    application {
        Window(onCloseRequest = ::exitApplication, title = "U4a Compose Reference", undecorated = true,
            state = rememberWindowState(width = (1280 * scale).dp, height = (720 * scale).dp)) {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                RTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xffc4d6ca))) {
                        when {
                            screen.startsWith("install-") -> LocalModpackInstallScreen({}, {}, install)
                            screen.startsWith("options-") -> ModpackLocalAdvanceOptionScreen("reference", "测试整合包", "1.21.1", {}, option)
                            screen.startsWith("play-") -> LocalPlayDialog(pack, {}, {})
                            screen.startsWith("console-") -> Console(remember {
                                ConsoleState().apply { if (screen != "console-empty") appendAll(List(40) { "[INFO] Minecraft已启动 · 日志行${it + 1}" }) }
                            }, Modifier.fillMaxSize().padding(32.dp))
                            screen.startsWith("task-") -> {
                                val task = Task2.Sequence("导入测试整合包", listOf(Task2.Leaf("复制文件", id = "copy") {}), id = "root")
                                Task2DetailDialog(Task2Entry("reference", task, snapshot = Task2Snapshot(taskTitle = task.title,
                                    currentMessage = if (screen == "task-error") "导入失败" else "复制文件",
                                    currentFraction = .5f, status = if (screen == "task-error") Task2Status.FAILED else Task2Status.RUNNING,
                                    errorMessage = if (screen == "task-error") "无法读取文件" else null)), onClose = {})
                            }
                            else -> Text("Unknown screenshot fixture")
                        }
                    }
                }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> setDisplayState(model: Any, name: String, state: T) {
    val field = model.javaClass.getDeclaredField(name).apply { isAccessible = true }
    (field.get(model) as MutableStateFlow<T>).value = state
}

private fun referenceInstaller(scope: CoroutineScope) = LocalModpackInstallViewModel(
    LocalModpackScanner(object : MinecraftInstallationStore {
        override suspend fun list() = Result.success(emptyList<MinecraftInstallationRecord>())
        override suspend fun upsert(path: Path, seenAt: Long) = Result.success(Unit)
        override suspend fun delete(path: Path) = Result.success(Unit)
        override suspend fun lastFullScanAt() = Result.success<Long?>(null)
        override suspend fun setLastFullScanAt(scannedAt: Long) = Result.success(Unit)
        override fun close() {}
    }, Path.of("C:/RDI/mc")),
    LocalModpackInstaller(Path.of("C:/RDI/mc/versions")),
    ModpackArchiveReader(object : ModpackArchiveCatalog {
        override suspend fun resolveCurseForgeFiles(refs: List<CurseForgeArchiveRef>) = Result.success(emptyMap<Int, ArchiveCatalogFile>())
    }), ModpackArchiveContentResolutionSource { _, _ -> emptyFlow() }, scope,
)
