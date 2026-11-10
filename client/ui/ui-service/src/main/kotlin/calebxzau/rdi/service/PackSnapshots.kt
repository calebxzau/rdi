package calebxzau.rdi.service

import calebxzhou.rdi.client.service.*
import calebxzhou.rdi.common.model.*
import calebxzau.rdi.client.ui.viewmodel.*
import kotlinx.serialization.json.*

private fun ModLoader.launcherLabel(): String = when (this) {
    ModLoader.forge -> "Forge"
    ModLoader.neoforge -> "NeoForge"
    ModLoader.Fabric -> "Fabric"
}

internal fun installerSnapshot(state: LocalModpackInstallState): JsonObject = buildJsonObject {
    put("scanning", state.scanning); put("archiveLoading", state.archiveLoading); put("directoryLoading", state.directoryLoading)
    put("error", state.editor?.error ?: state.archiveError ?: state.scanError ?: "")
    fun candidates(items: List<LocalModpackCandidate>) = buildJsonArray {
        items.forEachIndexed { index, candidate -> add(buildJsonObject {
            put("index", index); put("name", candidate.name); put("directory", candidate.minecraftInstallation.toString())
            put("gameDirectory", candidate.gameDirectory.toString()); put("mcVersion", candidate.mcVersion.mcVer)
            put("modLoader", candidate.modLoader.name)
        }) }
    }
    put("candidates", candidates(state.candidates)); put("pickedCandidates", candidates(state.pickedCandidates))
    put("runtimes", buildJsonArray { localModpackRuntimes.forEach { (version, loader) -> add(buildJsonObject {
        put("mcVersion", version.name); put("modLoader", loader.name); put("label", "${version.mcVer} ${loader.launcherLabel()}")
    }) } })
    state.editor?.let { editor -> put("editor", buildJsonObject {
        put("name", editor.name); put("comment", editor.comment); put("iconUrl", editor.iconUrl)
        put("archivePath", editor.archivePath?.toString().orEmpty())
        put("mcVersion", editor.mcVersion.name); put("modLoader", editor.modLoader.name)
        put("versionSelectable", editor.versionSelectable); put("canSubmit", editor.canSubmit)
        put("submitting", editor.submitting); put("previewLoading", editor.archivePreviewLoading)
        put("resolving", editor.contentResolving); put("confirmMissingFiles", editor.confirmMissingFiles)
        put("progress", editor.archiveProgress?.let {
            it.stage.displayName + (it.completed?.let { count -> "${count}/${it.total ?: "?"}" } ?: "")
        }.orEmpty())
        put("progressFraction", editor.archiveProgress?.fraction ?: -1f)
        val source = editor.source
        put("source", when(source) { is LocalModpackInstallSource.Archive -> "archive"; is LocalModpackInstallSource.CopyFrom -> "directory"; LocalModpackInstallSource.Empty -> "empty"; null -> "reading" })
        put("includeSaves", (source as? LocalModpackInstallSource.CopyFrom)?.includeSaves ?: false)
        put("sourceDirectory", (source as? LocalModpackInstallSource.CopyFrom)?.directory?.toString().orEmpty())
        put("contents", buildJsonArray {
            val archive = source as? LocalModpackInstallSource.Archive
            archive?.preview?.files?.forEach { file -> add(buildJsonObject {
                put("key", file.key); put("name", editor.resolvedMods[file.key]?.mod?.let { it.nameCn ?: it.name } ?: file.displayName)
                put("detail", file.summary.orEmpty()); put("kind", when (file.contentType) {
                    ModpackArchiveContentType.MOD -> "Mod"
                    ModpackArchiveContentType.SHADER_PACK -> "光影"
                    else -> file.contentType.displayName
                })
                put("optional", !file.required); put("selected", file.required || file.key in archive.selectedOptionalFiles)
                put("icon", file.iconUrls.firstOrNull().orEmpty()); put("size", file.size)
            }) }
            archive?.preview?.embeddedMods?.forEach { file -> add(buildJsonObject {
                put("key", file.key); put("name", editor.resolvedMods[file.key]?.mod?.let { it.nameCn ?: it.name } ?: file.displayName)
                put("detail", file.targetPath); put("kind", "Mod"); put("optional", false); put("selected", true)
                put("icon", ""); put("size", file.size)
            }) }
        })
        put("missingFiles", buildJsonArray { (source as? LocalModpackInstallSource.Archive)?.preview?.unavailableFiles?.forEach { add(JsonPrimitive(it.displayName + if (it.required) "" else "（可选）")) } })
    }) }
}

/** Keep complete trailing lines within the framed protocol's byte budget. */
internal fun boundedConsoleLines(lines: List<String>, byteLimit: Int = 1_000_000): List<String> {
    var remaining = byteLimit
    val result = ArrayDeque<String>()
    for (line in lines.asReversed()) {
        val bounded = line.take(4096)
        // JSON escaping can use six bytes for one UTF-16 character.
        val bytes = bounded.length * 6 + 8
        if (bytes > remaining) break
        remaining -= bytes
        result.addFirst(bounded)
        if (result.size >= 5000) break
    }
    return result.toList()
}

/** Stable task paths let the UI expand groups without copying task execution logic. */
internal fun taskSnapshot(entry: Task2Entry, snapshot: Task2Snapshot = entry.snapshot) = buildJsonObject {
    put("runId", entry.runId); put("title", entry.task.title); put("message", snapshot.currentMessage)
    put("fraction", snapshot.currentFraction ?: -1f); put("error", snapshot.errorMessage.orEmpty())
    put("terminal", snapshot.status.isTerminal); put("status", snapshot.status.ordinal)
    put("rows", buildJsonArray {
        fun walk(task: Task2, path: List<String>, depth: Int) {
            val key = task2PathKey(path)
            val progress = snapshot.progressByPath[key]
            val done = key in snapshot.donePaths
            val children = when (task) { is Task2.Group -> task.children; is Task2.Sequence -> task.children; is Task2.Leaf -> emptyList() }
            add(buildJsonObject {
                put("key", key); put("title", task.title); put("detail", progress?.compactText().orEmpty())
                put("depth", depth); put("group", task !is Task2.Leaf)
                put("expanded", when (task) { is Task2.Group -> task.defaultExpanded; is Task2.Sequence -> task.defaultExpanded; is Task2.Leaf -> true })
                put("status", if (done) Task2Status.DONE.ordinal else if (snapshot.status.isTerminal) snapshot.status.ordinal else if (progress != null) Task2Status.RUNNING.ordinal else Task2Status.QUEUED.ordinal)
                put("fraction", if (done) 1f else progress?.fraction ?: -1f)
            })
            children.forEachIndexed { index, child -> walk(child, path + task2ChildPathSegment(child, index), depth + 1) }
        }
        walk(entry.task, listOf(entry.task.id), 0)
    })
}
