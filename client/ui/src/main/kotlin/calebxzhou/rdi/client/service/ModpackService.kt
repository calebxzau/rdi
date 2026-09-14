package calebxzhou.rdi.client.service

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.client.service.content.ContentDigest
import calebxzhou.rdi.client.service.content.ContentDigestAlgorithm
import calebxzhou.rdi.client.service.content.ContentRequest
import calebxzhou.rdi.client.service.content.ContentSource
import calebxzhou.rdi.client.service.content.toClientContentRequests
import calebxzhou.rdi.client.service.content.toClientContentRequest
import calebxzhou.rdi.client.service.ModpackService.startInstallTask2
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzau.rdi.client.ui.moveToOsTrash
import calebxzau.rdi.client.ui.loadResourceStream
import calebxzhou.rdi.common.archive.extractArchiveToDir
import calebxzhou.rdi.common.json
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.net.json
import calebxzhou.rdi.common.util.str
import calebxzau.rdi.mcinstall.writeMinecraftOptions
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import org.bson.types.ObjectId
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

private val lgr by Loggers
private const val INVALID_PACK_CHECK_BATCH_SIZE = 512

/** Modpack download and installation service. */
object ModpackService {

    fun modpackInstallTaskKey(modpackId: ObjectId, verName: String): String =
        "modpack-install:${modpackId.toHexString()}:$verName"

    fun getVersionDir(modpackId: ObjectId, verName: String): java.io.File {
        return ClientDirs.versionsDir.resolve("${modpackId}_${verName}")
    }

    suspend fun getBriefInfos(ids: List<ObjectId>): Result<List<Modpack.BriefVo>> {
        if (ids.isEmpty()) return Result.success(emptyList())
        return try {
            val response = server.makeRequest<List<Modpack.BriefVo>>("modpack/infos", HttpMethod.Post) {
                json()
                setBody(ids.json)
            }
            if (!response.ok) throw RequestError(response.msg)
            Result.success(response.data.orEmpty())
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            lgr.warn(cause) { "批量获取整合包简介失败" }
            Result.failure(cause)
        }
    }

    private fun normalizeInstalledClientPackPath(path: String): String? {
        val normalized = path.replace('\\', '/').trimStart('/')
        return normalized.removePrefix("overrides/").takeIf { it.isNotBlank() }
    }

    internal fun clientPackContentRequest(
        modpackId: ObjectId,
        verName: String,
        sha1: String
    ): ContentRequest = ContentRequest(
        id = "modpack-client:$modpackId:$verName",
        // The archive is consumed from the resolved path; this name is only
        // the request identity and must not become a dl-packs output path.
        relativePath = "${modpackId}_$verName.client-pack",
        digests = listOf(ContentDigest(ContentDigestAlgorithm.SHA1, sha1.trim().lowercase())),
        sources = listOf(
            ContentSource(
                url = "${server.hqUrl}/modpack/$modpackId/version/$verName/client",
                headers = mapOf(
                    HttpHeaders.Authorization to "Bearer ${loggedAccount.jwt.orEmpty()}"
                ),
                name = "modpack:$modpackId:$verName"
            )
        ),
        displayName = "客户端整合包 $verName"
    )

    suspend fun deleteLocalPack(packdir: ModpackLocalDir): Result<Unit> = withContext(Dispatchers.IO) {
        ModpackLifecycleCoordinator.withVersionLock(packdir.versionId) {
            try {
                withContext(Dispatchers.Main.immediate) {
                    require(calebxzhou.rdi.client.ui.McPlayStore.aliveCount(packdir.versionId) == 0) {
                        "整合包正在运行，不能删除"
                    }
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    moveToOsTrash(packdir.dir.toPath()).getOrThrow()
                    require(!Files.exists(packdir.dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        "无法确认整合包目录已移入回收站: ${packdir.dir}"
                    }
                    ModpackLaunchOptionsService.delete(packdir.versionId).getOrThrow()
                }
                Result.success(Unit)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                Result.failure(cause)
            }
        }
    }

    fun installVersionTask2(
        mcVersion: McVersion,
        modLoader: ModLoader,
        modpackId: ObjectId,
        verName: String,
        mods: List<Mod>,
        clientExtras: List<calebxzau.rdi.common.model.Content> = emptyList(),
    ): Task2 {
        var clientPackRequest: ContentRequest? = null
        val installableMods = mods.filter(::isClientInstallableMod)

        val downloadClientPackTask = Task2.Leaf("下载客户端整合包") { ctx ->
            val hash = server.makeRequest<String>("modpack/$modpackId/version/$verName/client/hash").data
                ?: throw IllegalStateException("客户端包hash为空")
            clientPackRequest = clientPackContentRequest(modpackId, verName, hash)
            ctx.emit(Task2Progress("客户端整合包已准备", 1f))
        }
        val localInstallTasks = createInstallClientZipTasks2(
            mcVersion = mcVersion,
            modLoader = modLoader,
            modpackId = modpackId,
            verName = verName,
            mods = installableMods,
            clientExtras = clientExtras,
            clientPackProvider = null,
            clientPackRequestProvider = {
                clientPackRequest ?: throw IllegalStateException("客户端包请求未准备好")
            },
        )

        return Task2.Sequence(
            title = "安装整合包 $verName",
            children = listOf(
                downloadClientPackTask,
                *localInstallTasks.toTypedArray()
            )
        )
    }

    fun installBuiltClientZipTask2(
        mcVersion: McVersion,
        modLoader: ModLoader,
        modpackId: ObjectId,
        verName: String,
        mods: List<Mod>,
        clientPackFile: File,
        modpackName: String? = null,
        embeddedModOriginalFileNames: Map<String, String> = emptyMap(),
        clientExtras: List<calebxzau.rdi.common.model.Content> = emptyList(),
    ): Task2 = Task2.Sequence(
        title = buildString {
            append("本地安装整合包")
            if (!modpackName.isNullOrBlank()) append(" ").append(modpackName)
        },
        children = createInstallClientZipTasks2(
            mcVersion = mcVersion,
            modLoader = modLoader,
            modpackId = modpackId,
            verName = verName,
            mods = mods,
            embeddedModOriginalFileNames = embeddedModOriginalFileNames,
            clientExtras = clientExtras,
            clientPackProvider = { clientPackFile },
        )
    )

    suspend fun installRdiCore(
        mcVersion: McVersion,
        modLoader: ModLoader,
        modsDir: File,
        update: McCoreUpdateResult,
        onDetail: (String) -> Unit = {},
        onProgress: (calebxzhou.rdi.common.model.Task2Progress) -> Unit = {},
    ) {
        McCoreUpdater.materializeCore(
            update = update,
            modsDir = modsDir,
            onDetail = onDetail,
            onProgress = onProgress,
        ).getOrThrow()
    }

    fun Modpack.Version.startInstallTask2(
        mcVersion: McVersion,
        modLoader: ModLoader,
        modpackName: String? = null,
        includeClientExtras: Boolean = true,
    ): Task2 {
        val title = modpackInstallTaskTitle(modpackName)
        return Task2.Sequence(
            title = title,
            children = listOf(
                Task2.Leaf("刷新节点") { ctx ->
                    ctx.emit(Task2Progress("正在刷新节点...", 0f))
                    NodeRefreshCoordinator.refreshCurrent()
                        .onSuccess { ctx.emit(Task2Progress("节点已刷新: ${it.nodeName}", 1f)) }
                        .onFailure {
                            lgr.warn(it) { "下载整合包前刷新节点失败，继续使用当前节点" }
                            ctx.emit(Task2Progress("节点刷新失败，继续使用当前节点", 1f))
                        }
                },
                installVersionTask2(
                    mcVersion,
                    modLoader,
                    modpackId,
                    this@startInstallTask2.name,
                    mods,
                    if (includeClientExtras) clientExtras else emptyList(),
                )
            )
        )
    }

    fun isVersionInstalled(modpackId: ObjectId, verName: String): Boolean {
        val versionDir = getVersionDir(modpackId, verName)
        if (!versionDir.exists()) return false
        versionDir.resolve("mods").takeIf { it.exists() } ?: return false
        return true
    }

    fun isVersionReadyToLaunch(version: Modpack.Version, activeMods: List<Mod>): Boolean {
        val versionDir = getVersionDir(version.modpackId, version.name)
        return versionDir.exists() && versionDir.resolve("mods").isDirectory
    }

    private suspend fun collectReferencedModFileNamesExcluding(
        packdir: ModpackLocalDir,
        fallbackPreserve: Set<String>
    ): Set<String> = coroutineScope {
        val otherLocalDirs = getLocalPackDirs().filter { it.versionId != packdir.versionId }
        if (otherLocalDirs.isEmpty()) return@coroutineScope emptySet()
        val failed = AtomicBoolean(false)
        val references = otherLocalDirs.map { other ->
            async {
                val remote = other.vo ?: return@async emptySet()
                runCatching {
                    server.makeRequest<Modpack.Version>("modpack/${remote.id}/version/${other.verName}").data
                        ?.mods
                        ?.flatMap { it.fileNames }
                        ?.toSet()
                        .orEmpty()
                }.getOrElse {
                    failed.set(true)
                    emptySet()
                }
            }
        }.awaitAll().flatten().toSet()
        if (failed.get()) fallbackPreserve else references
    }

    internal fun createInstallClientZipTasks2(
        mcVersion: McVersion,
        modLoader: ModLoader,
        modpackId: ObjectId,
        verName: String,
        mods: List<Mod>,
        clientExtras: List<calebxzau.rdi.common.model.Content> = emptyList(),
        embeddedModOriginalFileNames: Map<String, String> = emptyMap(),
        clientPackProvider: (() -> File)?,
        clientPackRequestProvider: (() -> ContentRequest)? = null,
        versionDir: File = getVersionDir(modpackId, verName),
        contentStore: ClientContentStore = ClientContentStores.shared,
    ): List<Task2> {
        val installableMods = mods.filter(::isClientInstallableMod)
        val prepareVersionDirTask = Task2.Leaf("准备安装目录") { ctx ->
            if (versionDir.exists()) {
                ctx.emit(Task2Progress("清理旧版本文件...", null))
                runCatching {
                    versionDir.deleteRecursivelyNoSymlink()
                }.getOrElse { throw IllegalStateException("无法清理旧版本目录: ${versionDir.absolutePath}", it) }
            }
            if (!versionDir.exists()) {
                versionDir.mkdirs()
            }
            ctx.emit(Task2Progress("目录已就绪", 1f))
        }

        val extractTask = Task2.Leaf("解压客户端整合包") { ctx ->
            fun extract(clientPack: File) {
                ctx.emit(Task2Progress("扫描压缩包内容...", 0f))
                extractArchiveToDir(
                    archiveFile = clientPack,
                    targetDir = versionDir,
                    pathTransform = ::normalizeInstalledClientPackPath
                ) { done, total, currentPath ->
                    val fraction = done.toFloat() / total.coerceAtLeast(1).toFloat()
                    ctx.emit(Task2Progress("解压中 ${currentPath.substringAfterLast('/')}($done/$total)", fraction))
                }
                ctx.emit(Task2Progress("解压完成", 1f))
            }

            val request = clientPackRequestProvider?.invoke()
            if (request == null) {
                extract(requireNotNull(clientPackProvider).invoke())
            } else {
                // A cache failure may return a temporary source. Keep archive
                // detection and extraction inside use until the archive closes.
                contentStore.use(
                    requests = listOf(request),
                    onProgress = ctx::emit,
                ) { paths ->
                    extract(paths.getValue(request.id).toFile())
                }.getOrThrow()
            }
        }

        val patchFancyMenuTask = Task2.Leaf("写入菜单") { ctx ->
            patchFancyMenuOptions(versionDir)
            ctx.emit(Task2Progress("写入完成", 1f))
        }

        val copyModsTask = Task2.Leaf("补充模组") { ctx ->
            val modsDir = versionDir.resolve("mods").apply { mkdirs() }
            val requests = installableMods.toClientContentRequests(
                targetRelativePath = { mod ->
                    if (mod.fileName != mod.legacyFileName) {
                        mod.fileName
                    } else {
                        embeddedModOriginalFileNames[mod.fileName]
                            ?.substringAfterLast('/')
                            ?.substringAfterLast('\\')
                            ?.takeIf { it.isNotBlank() }
                            ?: mod.fileName
                    }
                },
            )
            contentStore.materialize(
                requests = requests,
                targetRoot = modsDir.toPath(),
                onProgress = ctx::emit
            ).getOrThrow()
            ctx.emit(Task2Progress("完成", 1f))
        }

        val copyClientExtrasTask = Task2.Leaf("补充资源包和光影包") { ctx ->
            contentStore.materializeClientExtras(clientExtras, versionDir.toPath(), ctx::emit).getOrThrow()
            ctx.emit(Task2Progress("完成", 1f))
        }

        val writeOptionsTask = Task2.Leaf("补充配置") { ctx ->
            writeMinecraftOptions(versionDir, mcVersion).getOrThrow()
            ctx.emit(Task2Progress("写入完成", 1f))
        }
        return listOf(
            prepareVersionDirTask,
            extractTask,
            patchFancyMenuTask,
            copyModsTask,
            copyClientExtrasTask,
            writeOptionsTask,
        )
    }


    private fun patchFancyMenuOptions(versionDir: File) {
        val optionsFile = versionDir.resolve("config/fancymenu/options.txt")
        optionsFile.parentFile?.mkdirs()
        loadResourceStream("overrides/fancymenu-options.txt").use { input ->
            optionsFile.outputStream().use(input::copyTo)
        }
    }

}

// ---- Local modpack loading ----

sealed class StartPlayResult {
    data class Ready(val args: McPlayArgs) : StartPlayResult()
    data class NeedMod(val modSlugs: List<String>) : StartPlayResult()
    data class NeedInstall(
        val title: String,
        val dedupeKey: String,
        val createTask: (Boolean) -> Task2,
    ) : StartPlayResult()
    data class Installing(val runId: String) : StartPlayResult()
}

data class ModpackLocalDir(
    val dir: java.io.File,
    val verName: String,
    val vo: Modpack.BriefVo?,
    val createTime: Long,
) {
    val versionId = dir.name
    val name get() = requireNotNull(vo).name
    val comment get() = vo?.info
    val iconUrl get() = vo?.icon
    val mcVersion get() = requireNotNull(vo).mcVer
    val modLoader get() = requireNotNull(vo).modloader
}

suspend fun Host.DetailVo.startPlay(): StartPlayResult {
    NodeRefreshCoordinator.refreshCurrent()
        .onFailure { lgr.warn(it) { "启动游戏前刷新节点失败，继续使用当前节点" } }

    val statusResp = server.makeRequest<HostStatus>("host/${_id}/status")
    val status = statusResp.data ?: throw RequestError("获取房间状态失败: ${statusResp.msg}")

    val versionResp = server.makeRequest<Modpack.Version>("modpack/${modpack.id}/version/$packVer")
    val version = versionResp.data ?: throw RequestError("获取整合包版本信息失败: ${versionResp.msg}")
    val installTaskKey = ModpackService.modpackInstallTaskKey(version.modpackId, version.name)
    ClientTaskManager.entries.value.firstOrNull { entry ->
        entry.dedupeKey == installTaskKey && !entry.status.isTerminal
    }?.let { activeInstall ->
        return StartPlayResult.Installing(activeInstall.runId)
    }

    val activeBaseMods = version.mods
        .filterNot { versionMod -> disabledMods.any { sameMod(it, versionMod) } }
        .filter(::isClientInstallableMod)
    if (!ModpackService.isVersionReadyToLaunch(version, activeBaseMods)) {
        return StartPlayResult.NeedInstall(
            title = version.modpackInstallTaskTitle(modpack.name),
            dedupeKey = installTaskKey,
            createTask = { includeClientExtras ->
                version.startInstallTask2(
                    mcVersion = modpack.mcVer,
                    modLoader = modpack.modloader,
                    modpackName = modpack.name,
                    includeClientExtras = includeClientExtras,
                )
            },
        )
    }

    val startResp = server.makeRequest<Unit>("host/${_id}/start", HttpMethod.Post)
    if (!startResp.ok) {
        throw RequestError("启动房间失败: ${startResp.msg}")
    }

    val gameAddr = "127.0.0.1:55667"
    val versionId = "${modpack.id.str}_${version.name}"
    val playArg = "${server.hqUrl}\n" +
            "${gameAddr}\n" +
            "${name}\n" +
            "$port\n" +
            "${loggedAccount.uuid}\n" +
            loggedAccount.name
    lgr.info { "play arg: $playArg" }
    runCatching {
        server.makeRequest<Unit>("modpack/${modpack.id}/play", HttpMethod.Post)
    }

    return StartPlayResult.Ready(
        McPlayArgs(
            title = "游玩 $name",
            mcVer = modpack.mcVer,
            modLoader = modpack.modloader,
            versionId = versionId,
            playArg = playArg,
            modpackName = modpack.name,
            versionDir = ModpackService.getVersionDir(version.modpackId, version.name).absolutePath,
            activeBaseMods = activeBaseMods,
            disabledBaseMods = disabledMods,
            manageHostBaseMods = true,
            extraMods = extraMods,
            manageHostExtraMods = true
        )
    )
}

private fun Modpack.Version.modpackInstallTaskTitle(modpackName: String?): String = buildString {
    append("完整下载整合包")
    if (!modpackName.isNullOrBlank()) append(" ").append(modpackName)
    totalSize?.humanFileSize?.let { append(" ").append(it) }
}

private fun isClientInstallableMod(mod: Mod): Boolean =
    mod.side != Mod.Side.SERVER && mod.side != Mod.Side.UNKNOWN

internal data class LocalPackRef(
    val dir: File,
    val modpackId: ObjectId,
    val verName: String
)

internal fun scanLocalPackRefs(root: File): List<LocalPackRef> {
    val pattern = Regex("^([0-9a-fA-F]{24})_(.+)$")
    return root.listFiles()?.asSequence()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir ->
            pattern.matchEntire(dir.name)?.destructured?.let { (idStr, verName) ->
                LocalPackRef(dir, ObjectId(idStr), verName)
            }
        }
        ?.toList()
        .orEmpty()
}

private fun localPackCreateTime(ref: LocalPackRef): Long = runCatching {
    Files.readAttributes(
        ref.dir.toPath(),
        java.nio.file.attribute.BasicFileAttributes::class.java
    ).creationTime().toMillis()
}.getOrElse { ref.dir.lastModified() }

internal suspend fun findInvalidLocalPackDirs(
    root: File,
    findMissingIds: suspend (List<ObjectId>) -> List<ObjectId>,
): List<ModpackLocalDir> {
    val refs = scanLocalPackRefs(root)
    if (refs.isEmpty()) return emptyList()

    val ids = refs.map { it.modpackId }.distinct()
    val missingIds = ids.chunked(INVALID_PACK_CHECK_BATCH_SIZE)
        .flatMap { chunk -> findMissingIds(chunk) }
        .toSet()
    return refs.asSequence()
        .filter { it.modpackId in missingIds }
        .map { ref -> ModpackLocalDir(ref.dir, ref.verName, null, localPackCreateTime(ref)) }
        .sortedByDescending(ModpackLocalDir::createTime)
        .toList()
}

suspend fun ModpackService.getInvalidLocalPackDirs(): Result<List<ModpackLocalDir>> {
    return try {
        Result.success(
            findInvalidLocalPackDirs(mcInstall.versionListDir) { chunk ->
                val response = server.makeRequest<List<ObjectId>>("modpack/missing", HttpMethod.Post) {
                    json()
                    setBody(chunk.json)
                }
                if (!response.ok) {
                    throw RequestError(response.msg.ifBlank { "检查无效整合包失败" })
                }
                response.data.orEmpty()
            }
        )
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (cause: Throwable) {
        lgr.warn(cause) { "检查无效整合包失败" }
        Result.failure(cause)
    }
}

suspend fun ModpackService.getLocalPackDirs(): List<ModpackLocalDir> {
    val refs = scanLocalPackRefs(mcInstall.versionListDir)

    val briefs = if (refs.isEmpty()) emptyList() else ModpackService.getBriefInfos(refs.map { it.modpackId }.distinct()).getOrElse {
        lgr.warn(it) { "读取已安装的RDI整合包信息失败" }
        emptyList()
    }
    val briefsById = briefs.associateBy { it.id }
    val remotePacks = refs.mapNotNull { ref ->
        val vo = briefsById[ref.modpackId]
        if (vo == null) {
            lgr.warn { "本地整合包${ref.dir.name}在服务器不存在，将跳过该目录" }
            return@mapNotNull null
        }
        val createTime = localPackCreateTime(ref)
        ModpackLocalDir(ref.dir, ref.verName, vo, createTime)
    }
    return remotePacks.sortedByDescending(ModpackLocalDir::createTime)
}
