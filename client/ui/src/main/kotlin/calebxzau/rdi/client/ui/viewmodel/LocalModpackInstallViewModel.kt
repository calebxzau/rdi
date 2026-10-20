package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.client.service.ContentResolutionUpdate
import calebxzhou.rdi.client.service.LocalModpackCandidate
import calebxzhou.rdi.client.service.LocalModpackInstallRequest
import calebxzhou.rdi.client.service.LocalModpackInstallSource
import calebxzhou.rdi.client.service.LocalModpackInstaller
import calebxzhou.rdi.client.service.LocalModpackScanner
import calebxzhou.rdi.client.service.ModpackArchiveContentResolutionSource
import calebxzhou.rdi.client.service.ModpackArchiveMetadata
import calebxzhou.rdi.client.service.ModpackArchiveReadProgress
import calebxzhou.rdi.client.service.ModpackArchiveReadStage
import calebxzhou.rdi.client.service.ModpackArchiveReader
import calebxzhou.rdi.client.service.ResolutionStage
import calebxzhou.rdi.client.service.ResolvedArchiveMod
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.runtimeLoaders
import calebxzhou.rdi.common.model.supportsModpackUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

private val lgr by Loggers

/** Minecraft versions and loaders a local pack can be created or imported for. */
val localModpackRuntimes: List<Pair<McVersion, ModLoader>> =
    McVersion.entries.filter(McVersion::supportsModpackUpload)
        .sortedByDescending(McVersion::mcVer)
        .flatMap { version -> version.runtimeLoaders.map { version to it } }

data class LocalModpackInstallState(
    val scanning: Boolean = true,
    val candidates: List<LocalModpackCandidate> = emptyList(),
    /** Several versions found in a folder the player picked; one has to be chosen. */
    val pickedCandidates: List<LocalModpackCandidate> = emptyList(),
    val scanError: String? = null,
    val archiveLoading: Boolean = false,
    val directoryLoading: Boolean = false,
    val archiveError: String? = null,
    val editor: LocalModpackInstallEditorState? = null,
)

data class LocalModpackInstallEditorState(
    val source: LocalModpackInstallSource?,
    val mcVersion: McVersion,
    val modLoader: ModLoader,
    val name: String,
    val comment: String = "",
    val iconUrl: String = "",
    /** The folder had no version manifest, so the player picks the game version. */
    val versionSelectable: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    /** Shown before installing an archive whose unavailable files would be left out. */
    val confirmMissingFiles: Boolean = false,
    val resolvedMods: Map<String, ResolvedArchiveMod> = emptyMap(),
    val contentResolving: Boolean = false,
    val archivePath: Path? = null,
    val archivePreviewLoading: Boolean = false,
    val archiveProgress: ModpackArchiveReadProgress? = null,
) {
    val canSubmit: Boolean
        get() = source != null && !archivePreviewLoading && !submitting
}

data class LocalModpackInstallStarted(val runId: String)

class LocalModpackInstallViewModel(
    private val scanner: LocalModpackScanner,
    private val installer: LocalModpackInstaller,
    private val archiveReader: ModpackArchiveReader,
    private val contentResolver: ModpackArchiveContentResolutionSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LocalModpackInstallState())
    private val eventChannel = Channel<LocalModpackInstallStarted>(Channel.BUFFERED)
    private var archiveLoadJob: Job? = null
    private var archiveGeneration = 0L
    private val resolutionCache = mutableMapOf<ArchiveIdentity, ResolutionCache>()

    val state: StateFlow<LocalModpackInstallState> = mutableState.asStateFlow()
    val events = eventChannel.receiveAsFlow()

    init {
        scanCandidates()
    }

    fun scanCandidates() {
        mutableState.update { it.copy(scanning = true, scanError = null) }
        viewModelScope.launch {
            scanner.scan().onSuccess { candidates ->
                mutableState.update { it.copy(scanning = false, candidates = candidates) }
            }.onFailure { cause ->
                lgr.warn(cause) { "扫描电脑里的整合包失败" }
                mutableState.update {
                    it.copy(scanning = false, scanError = cause.message ?: "扫描本地整合包失败")
                }
            }
        }
    }

    fun openInstall(candidate: LocalModpackCandidate) {
        cancelArchiveLoad()
        mutableState.update {
            it.copy(
                archiveLoading = false,
                archiveError = null,
                pickedCandidates = emptyList(),
                editor = LocalModpackInstallEditorState(
                    source = LocalModpackInstallSource.CopyFrom(candidate.gameDirectory),
                    mcVersion = candidate.mcVersion,
                    modLoader = candidate.modLoader,
                    name = candidate.name,
                ),
            )
        }
    }

    /** A folder picked by the player: a `.minecraft`, one version directory, or a bare game directory. */
    fun openDirectory(path: Path) {
        cancelArchiveLoad()
        mutableState.update { it.copy(directoryLoading = true, archiveError = null, pickedCandidates = emptyList()) }
        viewModelScope.launch {
            scanner.scanDirectory(path).onSuccess { candidates ->
                when {
                    candidates.size == 1 -> {
                        mutableState.update { it.copy(directoryLoading = false) }
                        openInstall(candidates.single())
                    }
                    candidates.isNotEmpty() -> mutableState.update {
                        it.copy(directoryLoading = false, pickedCandidates = candidates)
                    }
                    Files.isDirectory(path.resolve("mods")) -> {
                        val (version, loader) = localModpackRuntimes.first()
                        mutableState.update {
                            it.copy(
                                directoryLoading = false,
                                editor = LocalModpackInstallEditorState(
                                    source = LocalModpackInstallSource.CopyFrom(path),
                                    mcVersion = version,
                                    modLoader = loader,
                                    name = path.fileName?.toString().orEmpty(),
                                    versionSelectable = true,
                                ),
                            )
                        }
                    }
                    else -> mutableState.update {
                        it.copy(
                            directoryLoading = false,
                            archiveError = "这个文件夹里没有找到1.20.1或1.21.1的整合包（需要包含mods文件夹或版本文件）",
                        )
                    }
                }
            }.onFailure { cause ->
                lgr.warn(cause) { "读取整合包文件夹失败：$path" }
                mutableState.update {
                    it.copy(directoryLoading = false, archiveError = cause.message ?: "读取文件夹失败")
                }
            }
        }
    }

    fun dismissPickedCandidates() {
        mutableState.update { it.copy(pickedCandidates = emptyList()) }
    }

    fun openCreate(mcVersion: McVersion, modLoader: ModLoader) {
        cancelArchiveLoad()
        mutableState.update {
            it.copy(
                archiveLoading = false,
                archiveError = null,
                editor = LocalModpackInstallEditorState(
                    source = LocalModpackInstallSource.Empty,
                    mcVersion = mcVersion,
                    modLoader = modLoader,
                    name = mcVersion.mcVer,
                ),
            )
        }
    }

    fun openArchive(path: Path) {
        cancelArchiveLoad()
        val generation = archiveGeneration
        mutableState.update { it.copy(archiveLoading = true, archiveError = null, editor = null) }
        archiveLoadJob = viewModelScope.launch {
            val metadata = archiveReader.readMetadata(path).getOrElse { cause ->
                if (cause is CancellationException) throw cause
                lgr.warn(cause) { "读取整合包元数据失败" }
                showArchiveError(generation, cause)
                return@launch
            }
            if (!isCurrentArchive(generation)) return@launch
            showArchiveMetadata(generation, metadata)

            val preview = archiveReader.inspectWithProgress(path) { progress ->
                updateArchiveProgress(generation, progress)
            }.getOrElse { cause ->
                if (cause is CancellationException) throw cause
                lgr.warn(cause) { "读取整合包内容失败" }
                finishArchiveLoadWithError(generation, cause)
                return@launch
            }
            if (!isCurrentArchive(generation)) return@launch
            val identity = ArchiveIdentity(preview.archive, preview.archiveSize, preview.archiveModifiedAt)
            val cached = resolutionCache[identity] ?: ResolutionCache()
            mutableState.update { current ->
                if (!isCurrentArchive(generation)) return@update current
                current.copy(
                    archiveLoading = false,
                    editor = current.editor?.copy(
                        source = LocalModpackInstallSource.Archive(preview = preview, selectedOptionalFiles = emptySet()),
                        mcVersion = preview.mcVersion,
                        modLoader = preview.modLoader,
                        archivePath = path,
                        archivePreviewLoading = false,
                        resolvedMods = cached.items,
                        contentResolving = cached.completedStages.size < ResolutionStage.entries.size,
                        archiveProgress = null,
                    ),
                )
            }
            try {
                contentResolver.resolve(preview, cached.completedStages).collect { update ->
                    when (update) {
                        is ContentResolutionUpdate.Resolved ->
                            mergeResolution(generation, identity, update.stage, update.items)
                        is ContentResolutionUpdate.Failed ->
                            mergeResolution(generation, identity, update.stage, emptyMap())
                        ContentResolutionUpdate.Complete ->
                            updateResolutionState(generation, identity, resolving = false)
                    }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // Names and icons are only decoration here; installing still works without them.
                lgr.warn(cause) { "识别整合包内容资料失败" }
                updateResolutionState(generation, identity, resolving = false)
            }
        }
    }

    fun closeEditor() {
        if (state.value.editor?.submitting == true) return
        cancelArchiveLoad()
        mutableState.update { it.copy(archiveLoading = false, editor = null) }
    }

    fun dismissError() {
        mutableState.update {
            it.copy(scanError = null, archiveError = null, editor = it.editor?.copy(error = null))
        }
    }

    fun updateName(value: String) = updateEditor { copy(name = value, error = null) }
    fun updateComment(value: String) = updateEditor { copy(comment = value, error = null) }
    fun updateIconUrl(value: String) = updateEditor { copy(iconUrl = value, error = null) }

    fun updateRuntime(mcVersion: McVersion, modLoader: ModLoader) = updateEditor {
        if (!versionSelectable) this else copy(mcVersion = mcVersion, modLoader = modLoader, error = null)
    }

    fun toggleIncludeSaves() = updateEditor {
        val copy = source as? LocalModpackInstallSource.CopyFrom ?: return@updateEditor this
        copy(source = copy.copy(includeSaves = !copy.includeSaves))
    }

    fun toggleOptionalFile(key: String) = updateEditor {
        val archive = source as? LocalModpackInstallSource.Archive ?: return@updateEditor this
        val selected = archive.selectedOptionalFiles.toMutableSet()
        if (!selected.add(key)) selected.remove(key)
        copy(source = archive.copy(selectedOptionalFiles = selected), error = null)
    }

    fun dismissMissingFiles() = updateEditor { copy(confirmMissingFiles = false) }

    /** Installs; an archive with unavailable files first asks whether to continue without them. */
    fun submitInstall(missingFilesConfirmed: Boolean = false) {
        val editor = state.value.editor ?: return
        val source = editor.source ?: return
        if (!editor.canSubmit) return
        val unavailable = (source as? LocalModpackInstallSource.Archive)?.preview?.unavailableFiles.orEmpty()
        if (unavailable.isNotEmpty() && !missingFilesConfirmed) {
            updateEditor { copy(confirmMissingFiles = true) }
            return
        }
        mutableState.update { it.copy(editor = editor.copy(submitting = true, error = null, confirmMissingFiles = false)) }
        installer.install(
            LocalModpackInstallRequest(
                source = source,
                mcVersion = editor.mcVersion,
                modLoader = editor.modLoader,
                name = editor.name,
                comment = editor.comment,
                iconUrl = editor.iconUrl,
            )
        ).onSuccess { runId ->
            cancelArchiveLoad()
            mutableState.update { it.copy(editor = null) }
            viewModelScope.launch { eventChannel.send(LocalModpackInstallStarted(runId)) }
        }.onFailure { cause ->
            mutableState.update {
                it.copy(editor = it.editor?.copy(submitting = false, error = cause.message ?: "无法开始安装"))
            }
        }
    }

    private fun updateEditor(transform: LocalModpackInstallEditorState.() -> LocalModpackInstallEditorState) {
        mutableState.update { it.copy(editor = it.editor?.transform()) }
    }

    private fun mergeResolution(
        generation: Long,
        identity: ArchiveIdentity,
        stage: ResolutionStage,
        items: Map<String, ResolvedArchiveMod>,
    ) {
        if (!isCurrentArchive(generation)) return
        val cached = resolutionCache[identity] ?: ResolutionCache()
        val merged = cached.items.toMutableMap()
        if (stage == ResolutionStage.URL_HINTS) merged.putAll(items)
        else items.forEach { (key, value) -> merged.putIfAbsent(key, value) }
        resolutionCache[identity] = ResolutionCache(merged, cached.completedStages + stage)
        updateResolutionState(
            generation,
            identity,
            resolving = cached.completedStages.size + 1 < ResolutionStage.entries.size,
        )
    }

    private fun updateResolutionState(generation: Long, identity: ArchiveIdentity, resolving: Boolean) {
        if (!isCurrentArchive(generation)) return
        val cached = resolutionCache[identity] ?: ResolutionCache()
        mutableState.update { current ->
            if (!isCurrentArchive(generation)) return@update current
            val editor = current.editor ?: return@update current
            val preview = (editor.source as? LocalModpackInstallSource.Archive)?.preview ?: return@update current
            if (ArchiveIdentity(preview.archive, preview.archiveSize, preview.archiveModifiedAt) != identity) {
                return@update current
            }
            current.copy(editor = editor.copy(resolvedMods = cached.items, contentResolving = resolving))
        }
    }

    private fun showArchiveMetadata(generation: Long, metadata: ModpackArchiveMetadata) {
        mutableState.update { current ->
            if (!isCurrentArchive(generation)) return@update current
            current.copy(
                archiveLoading = false,
                editor = LocalModpackInstallEditorState(
                    source = null,
                    mcVersion = metadata.mcVersion,
                    modLoader = metadata.modLoader,
                    name = metadata.name,
                    comment = metadata.summary.orEmpty(),
                    archivePath = metadata.archive,
                    archivePreviewLoading = true,
                    contentResolving = true,
                    archiveProgress = ModpackArchiveReadProgress(ModpackArchiveReadStage.SCANNING_ENTRIES),
                ),
            )
        }
    }

    private fun updateArchiveProgress(generation: Long, progress: ModpackArchiveReadProgress) {
        if (!isCurrentArchive(generation)) return
        mutableState.update { current ->
            if (!isCurrentArchive(generation)) return@update current
            current.copy(editor = current.editor?.copy(archiveProgress = progress))
        }
    }

    private fun finishArchiveLoadWithError(generation: Long, cause: Throwable) {
        mutableState.update { current ->
            if (!isCurrentArchive(generation)) return@update current
            current.copy(
                archiveLoading = false,
                archiveError = cause.message ?: "无法读取整合包安装包",
                editor = null,
            )
        }
    }

    private fun showArchiveError(generation: Long, cause: Throwable) {
        mutableState.update { current ->
            if (archiveGeneration != generation) return@update current
            current.copy(archiveLoading = false, archiveError = cause.message ?: "无法读取整合包安装包")
        }
    }

    private fun cancelArchiveLoad() {
        archiveGeneration++
        archiveLoadJob?.cancel()
        archiveLoadJob = null
    }

    private fun isCurrentArchive(generation: Long): Boolean = archiveGeneration == generation

    private data class ArchiveIdentity(val path: Path, val size: Long, val modifiedAt: Long)
    private data class ResolutionCache(
        val items: Map<String, ResolvedArchiveMod> = emptyMap(),
        val completedStages: Set<ResolutionStage> = emptySet(),
    )
}
