package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.client.service.content.toClientContentRequests
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.normalizedSlug
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.service.ModpackModProcessor
import calebxzhou.rdi.common.service.runInline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

fun buildHostBaseModSyncTask2(
    versionId: String,
    activeBaseMods: List<Mod>,
    disabledBaseMods: List<Mod>,
    loader: ModLoader = ModLoader.forge,
): Task2 {
    val input = prepareHostBaseModSyncInputs(activeBaseMods, disabledBaseMods, loader)
    val serverOnlyBaseMods = input.activeMods.filter { it.side == Mod.Side.SERVER }
    val unknownSideBaseMods = input.activeMods.filter { it.side == Mod.Side.UNKNOWN }
    val distinctDisabledMods = (input.disabledMods + serverOnlyBaseMods + unknownSideBaseMods).distinctBy { it.fileName }
    val distinctActiveMods = input.activeMods
        .filter { it.side != Mod.Side.SERVER && it.side != Mod.Side.UNKNOWN }
        .distinctBy { it.fileName }

    return Task2.Sequence(
        title = "同步房间基础Mod",
        children = listOf(
            Task2.Leaf("整理基础Mod状态") { ctx ->
                removeDisabledBaseMods(versionId, distinctDisabledMods, ctx)
            },
            Task2.Leaf("下载并同步基础Mod") { ctx ->
                materializeHostBaseMods(versionId, distinctActiveMods, ctx)
            }
        )
    )
}

suspend fun syncHostManagedBaseMods(
    versionId: String,
    activeBaseMods: List<Mod>,
    disabledBaseMods: List<Mod>,
    onProgress: (Task2Progress) -> Unit = {},
    loader: ModLoader = ModLoader.forge,
) = withContext(Dispatchers.IO) {
    buildHostBaseModSyncTask2(versionId, activeBaseMods, disabledBaseMods, loader).runInline(
        Task2Context(emitProgress = onProgress)
    )
}

private fun removeDisabledBaseMods(
    versionId: String,
    disabledBaseMods: List<Mod>,
    ctx: Task2Context
) {
    val versionDir = mcInstall.versionListDir.resolve(versionId)
    require(versionDir.exists()) { "未找到整合包目录: ${versionDir.absolutePath}" }
    val modsDir = versionDir.resolve("mods").apply { mkdirs() }

    disabledBaseMods.forEach { mod ->
        mod.fileNames.forEach { fileName ->
            val target = modsDir.resolve(fileName)
            if (target.exists()) {
                Files.deleteIfExists(target.toPath())
                ctx.emit(Task2Progress("移除已禁用基础Mod ${target.name}"))
            }
        }
    }
}

private suspend fun materializeHostBaseMods(
    versionId: String,
    activeBaseMods: List<Mod>,
    ctx: Task2Context
) {
    val versionDir = mcInstall.versionListDir.resolve(versionId)
    require(versionDir.exists()) { "未找到整合包目录: ${versionDir.absolutePath}" }
    val modsDir = versionDir.resolve("mods").apply { mkdirs() }

    val missingMods = missingHostBaseMods(modsDir.toPath(), activeBaseMods)
    if (missingMods.isEmpty()) {
        ctx.emit(Task2Progress("没有基础Mod需要同步", 1f))
        return
    }

    ClientContentStores.shared.materialize(
        requests = missingMods.toClientContentRequests(),
        targetRoot = modsDir.toPath(),
        onProgress = ctx::emit
    ).getOrThrow()
}

internal fun missingHostBaseMods(modsDir: Path, mods: List<Mod>): List<Mod> =
    mods.filter { mod ->
        mod.fileNames.none { fileName ->
            Files.isRegularFile(modsDir.resolve(fileName), LinkOption.NOFOLLOW_LINKS)
        }
    }

internal data class HostBaseModSyncInputs(
    val activeMods: List<Mod>,
    val disabledMods: List<Mod>,
)

internal fun prepareHostBaseModSyncInputs(
    activeMods: List<Mod>,
    disabledMods: List<Mod>,
    loader: ModLoader = ModLoader.forge,
): HostBaseModSyncInputs = HostBaseModSyncInputs(
    activeMods = activeMods.filterNot { it.normalizedSlug in ModpackModProcessor.removedSlugsFor(loader) },
    disabledMods = disabledMods.filterNot { it.normalizedSlug in ModpackModProcessor.removedSlugsFor(loader) },
)
