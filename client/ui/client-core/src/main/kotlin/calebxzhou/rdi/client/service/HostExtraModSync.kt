package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.client.service.content.toClientContentRequests
import calebxzhou.rdi.common.model.EXTRA_MOD_PREFIX
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

fun buildHostExtraModSyncTask2(
    versionId: String,
    extraMods: List<Mod>,
    loader: ModLoader = ModLoader.forge,
): Task2 {
    val distinctMods = prepareHostExtraModSyncMods(extraMods, loader).distinctBy { it.fileName }
    return Task2.Sequence(
        title = "同步房间附加Mod",
        children = listOf(
            Task2.Leaf("下载并同步附加Mod") { ctx ->
                materializeHostExtraMods(versionId, distinctMods, ctx)
            }
        )
    )
}

suspend fun syncHostExtraMods(
    versionId: String,
    extraMods: List<Mod>,
    onProgress: (Task2Progress) -> Unit = {},
    loader: ModLoader = ModLoader.forge,
) = withContext(Dispatchers.IO) {
    buildHostExtraModSyncTask2(versionId, extraMods, loader).runInline(
        Task2Context(emitProgress = onProgress)
    )
}

private suspend fun materializeHostExtraMods(
    versionId: String,
    extraMods: List<Mod>,
    ctx: Task2Context
) {
    val versionDir = mcInstall.versionListDir.resolve(versionId)
    require(versionDir.exists()) { "未找到整合包目录: ${versionDir.absolutePath}" }
    val modsDir = versionDir.resolve("mods").apply { mkdirs() }
    val expectedTargets = extraMods
        .associateBy(::extraModTargetFileName)

    modsDir.listFiles()
        ?.filter { it.name.startsWith(EXTRA_MOD_PREFIX) }
        ?.forEach { file ->
            if (file.name !in expectedTargets) {
                Files.deleteIfExists(file.toPath())
                ctx.emit(Task2Progress("移除旧附加Mod ${file.name}"))
            }
        }

    val missingMods = missingHostExtraMods(modsDir.toPath(), extraMods)
    if (missingMods.isEmpty()) {
        ctx.emit(Task2Progress("没有附加Mod需要同步", 1f))
        return
    }

    ClientContentStores.shared.materialize(
        requests = missingMods.toClientContentRequests(::extraModTargetFileName),
        targetRoot = modsDir.toPath(),
        onProgress = ctx::emit
    ).getOrThrow()
}

internal fun missingHostExtraMods(modsDir: Path, mods: List<Mod>): List<Mod> =
    mods.filter { mod ->
        !Files.isRegularFile(
            modsDir.resolve(extraModTargetFileName(mod)),
            LinkOption.NOFOLLOW_LINKS,
        )
    }

private fun extraModTargetFileName(mod: Mod): String = EXTRA_MOD_PREFIX + mod.fileName

internal fun prepareHostExtraModSyncMods(
    mods: List<Mod>,
    loader: ModLoader = ModLoader.forge,
): List<Mod> = mods.filterNot { it.normalizedSlug in ModpackModProcessor.removedSlugsFor(loader) }
