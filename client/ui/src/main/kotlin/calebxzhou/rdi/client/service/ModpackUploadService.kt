package calebxzhou.rdi.client.service

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.util.*
import calebxzau.rdi.client.packproc.*
import calebxzau.rdi.client.service.ModpackChunkedUploader
import calebxzau.rdi.client.service.ModpackUploadApi
import calebxzau.rdi.client.service.currentModpackUploadApi
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.client.service.content.toClientContentRequest
import calebxzhou.rdi.client.service.content.toClientContentRequests
import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.model.*
import calebxzau.rdi.common.model.Content
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.service.runInline
import kotlinx.coroutines.*
import org.bson.types.ObjectId
import java.io.File

private val lgr by Loggers

suspend fun uploadModpack(
    processor: ModpackProcessor,
    payload: UploadPayload,
    mods: List<Mod>,
    modpackName: String,
    versionName: String,
    categories: List<Modpack.Category>,
    iconUrl: String?,
    sourceUrl: String?,
    info: String?,
    updateModpackId: ObjectId?,
    onProgress: (String) -> Unit,
    onPackProcessProgress: LoadProgressConsumer = {},
    onError: (String) -> Unit,
    onDone: (String) -> Unit
) {
    val uploadApi = currentModpackUploadApi()
    requireModpackUploadVersion(payload.mcVersion)
    onProgress("正在打包整合包...请等一两分钟")
    val uploadZip = try {
        processor.buildUploadArchive(
            payload = payload,
            onProgress = onPackProcessProgress
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Task2CancelledException) {
        throw e
    } catch (e: Exception) {
        lgr.warn { "打包整合包失败: ${payload.sourceDir.absolutePath + "\n" + e}" }
        payload.sourceDir.deleteRecursivelyNoSymlink()
        onError("打包失败: ${e.message}")
        return
    }

    val totalBytes = uploadZip.length()
    try {
        if (updateModpackId != null) {
            uploadNewVersion(
                modpackId = updateModpackId,
                versionName = versionName,
                mods = mods,
                clientExtras = payload.clientExtras,
                uploadZip = uploadZip,
                totalBytes = totalBytes,
                onProgress = { progress -> onProgress(progress.message) },
                onDone = onDone,
                api = uploadApi,
                onPublicationUncertain = { message -> onProgress(message) },
            )
        } else {
            uploadNewModpack(
                modpackName = modpackName,
                versionName = versionName,
                mcVersion = payload.mcVersion,
                modloader = payload.modloader,
                mods = mods,
                clientExtras = payload.clientExtras,
                categories = categories,
                iconUrl = iconUrl,
                sourceUrl = sourceUrl,
                info = info,
                uploadZip = uploadZip,
                totalBytes = totalBytes,
                onProgress = { progress -> onProgress(progress.message) },
                onDone = onDone,
                api = uploadApi,
                onPublicationUncertain = { message -> onProgress(message) },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Task2CancelledException) {
        throw e
    } catch (e: Exception) {
        lgr.warn { "上传整合包失败: ${payload.sourceName + "\n" + e} $versionName" }
        onError("上传失败: ${e.message ?: "未知错误"}")
    } finally {
        uploadZip.delete()
        payload.sourceDir.deleteRecursivelyNoSymlink()
    }
}

fun createUploadModpackTask2(
    processor: ModpackProcessor,
    payload: UploadPayload,
    mods: List<Mod>,
    modpackName: String,
    versionName: String,
    categories: List<Modpack.Category>,
    iconUrl: String?,
    sourceUrl: String?,
    info: String?,
    updateModpackId: ObjectId?,
    api: ModpackUploadApi = currentModpackUploadApi(),
): Task2 {
    requireModpackUploadVersion(payload.mcVersion)
    var uploadedModpackId: ObjectId? = updateModpackId
    var builtClientZip: File? = null
    val processedMods = processor.processUploadMods(mods)
    val uploadTask = Task2.Leaf("上传整合包") { ctx ->
        var doneSummary: String? = null
        ctx.emit(Task2Progress("开始上传整合包", 0f))
        try {
            builtClientZip = processor.buildUploadArchive(
                payload = payload,
                onProgress = { progress -> ctx.emit(progress) }
            )
            val uploadZip = builtClientZip ?: throw ModpackError("整合包打包失败")
            val totalBytes = uploadZip.length()
            if (updateModpackId != null) {
                uploadNewVersion(
                    modpackId = updateModpackId,
                    versionName = versionName,
                    mods = processedMods,
                    clientExtras = payload.clientExtras,
                    uploadZip = uploadZip,
                    totalBytes = totalBytes,
                    onProgress = ctx::emit,
                    onDone = { summary ->
                        doneSummary = summary
                        ctx.emit(Task2Progress(summary, 1f))
                    },
                    api = api,
                    ensureActive = ctx::ensureActive,
                    onPublicationUncertain = { message -> ctx.emit(Task2Progress(message)) },
                )
            } else {
                uploadNewModpack(
                    modpackName = modpackName,
                    versionName = versionName,
                    mcVersion = payload.mcVersion,
                    modloader = payload.modloader,
                    mods = processedMods,
                    clientExtras = payload.clientExtras,
                    categories = categories,
                    iconUrl = iconUrl,
                    sourceUrl = sourceUrl,
                    info = info,
                    uploadZip = uploadZip,
                    totalBytes = totalBytes,
                    onProgress = ctx::emit,
                    onDone = { summary ->
                        doneSummary = summary
                        ctx.emit(Task2Progress(summary, 1f))
                    },
                    api = api,
                    ensureActive = ctx::ensureActive,
                    onPublicationUncertain = { message -> ctx.emit(Task2Progress(message)) },
                )
            }
            uploadedModpackId = resolveUploadedModpackId(
                api = api,
                updateModpackId = updateModpackId,
                modpackName = modpackName,
                versionName = versionName
            )
            if (doneSummary == null) {
                throw ModpackError("上传任务未返回结果")
            }
        } catch (e: CancellationException) {
            builtClientZip?.let { runCatching { it.delete() } }
            runCatching { payload.sourceDir.deleteRecursivelyNoSymlink() }
            throw e
        } catch (e: Task2CancelledException) {
            builtClientZip?.let { runCatching { it.delete() } }
            runCatching { payload.sourceDir.deleteRecursivelyNoSymlink() }
            throw e
        } catch (e: Throwable) {
            builtClientZip?.let { runCatching { it.delete() } }
            runCatching { payload.sourceDir.deleteRecursivelyNoSymlink() }
            throw e
        }
    }
    val downloadMissingModsTask = Task2.Leaf("下载剩余客户端Mod") { ctx ->
        val missingMods = withContext(Dispatchers.IO) {
            buildList {
                processedMods
                    .filter(::isUploadClientInstallableMod)
                    .forEach { mod ->
                        if (!isUploadClientContentAvailable(mod)) add(mod)
                    }
            }
        }
        if (missingMods.isEmpty()) {
            ctx.emit(Task2Progress("客户端Mod已齐全", 1f))
        } else {
            ctx.emit(Task2Progress("开始下载剩余客户端Mod，共${missingMods.size}个", 0f))
            createUploadClientModDownloadTask2(missingMods).runInline(ctx)
        }
    }
    val installTask = Task2.Leaf("本地安装整合包") { ctx ->
        val modpackId = uploadedModpackId ?: throw ModpackError("上传后未找到整合包")
        val uploadZip = builtClientZip ?: throw ModpackError("本地客户端包不存在")
        try {
            ModpackService.installBuiltClientZipTask2(
                mcVersion = payload.mcVersion,
                modLoader = payload.modloader,
                modpackId = modpackId,
                verName = versionName,
                mods = processedMods,
                clientPackFile = uploadZip,
                modpackName = modpackName,
                embeddedModOriginalFileNames = payload.embeddedModOriginalFileNames,
                clientExtras = payload.clientExtras,
            ).runInline(ctx)
        } finally {
            runCatching { uploadZip.delete() }
            runCatching { payload.sourceDir.deleteRecursivelyNoSymlink() }
        }
    }
    return Task2.Sequence(
        title = "上传并安装整合包 $modpackName $versionName",
        children = listOf(uploadTask, downloadMissingModsTask, installTask)
    )
}

private fun isUploadClientInstallableMod(mod: Mod): Boolean =
    mod.side != Mod.Side.SERVER && mod.side != Mod.Side.UNKNOWN

private fun Mod.toUploadClientContentRequest() = toClientContentRequest()

internal fun List<Mod>.toUploadClientContentRequests() = toClientContentRequests()

internal suspend fun isUploadClientContentAvailable(mod: Mod): Boolean =
    ClientContentStores.shared.use(
        requests = listOf(mod.toUploadClientContentRequest().copy(allowNetwork = false))
    ) { }.isSuccess

internal fun createUploadClientModDownloadTask2(mods: List<Mod>): Task2 =
    Task2.Leaf("下载${mods.size}个Mod") { ctx ->
        ClientContentStores.shared.use(
            requests = mods.toUploadClientContentRequests(),
            onProgress = ctx::emit
        ) { }
            .getOrThrow()
    }

fun modpackUploadTaskKey(
    updateModpackId: ObjectId?,
    modpackName: String,
    versionName: String
): String = buildString {
    append("modpack-upload:")
    append(updateModpackId?.toHexString() ?: "new")
    append(':')
    append(modpackName.trim().lowercase())
    append(':')
    append(versionName.trim().lowercase())
}

private suspend fun resolveUploadedModpackId(
    api: ModpackUploadApi,
    updateModpackId: ObjectId?,
    modpackName: String,
    versionName: String
): ObjectId {
    if (updateModpackId != null) return updateModpackId
    val myModpacks = try {
        api.listMy()
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Task2CancelledException) {
        throw cause
    } catch (cause: Throwable) {
        throw ModpackError("上传成功，但未能定位到刚创建的整合包，请刷新列表查看", cause)
    }
    return myModpacks
        .filter { it.name == modpackName && it.versions.any { version -> version.name == versionName } }
        .maxByOrNull { pack -> pack.versions.maxOfOrNull { it.time } ?: 0L }
        ?._id
        ?: throw ModpackError("上传成功，但未能定位到刚创建的整合包")
}

private suspend fun uploadNewModpack(
    modpackName: String,
    versionName: String,
    mcVersion: McVersion,
    modloader: ModLoader,
    mods: List<Mod>,
    clientExtras: List<Content> = emptyList(),
    categories: List<Modpack.Category>,
    iconUrl: String?,
    sourceUrl: String?,
    info: String?,
    uploadZip: File,
    totalBytes: Long,
    onProgress: (Task2Progress) -> Unit,
    onDone: (String) -> Unit,
    api: ModpackUploadApi,
    ensureActive: () -> Unit = {},
    onPublicationUncertain: (String) -> Unit = {},
) {
    onProgress(Task2Progress("创建新整合包 $modpackName..."))
    val dto = Modpack.CreateWithVersionDto(
        name = modpackName,
        verName = versionName,
        mcVer = mcVersion,
        modLoader = modloader,
        iconUrl = iconUrl?.trim()?.ifBlank { null },
        sourceUrl = sourceUrl?.trim()?.ifBlank { null },
        info = info?.trim()?.ifBlank { null },
        categories = Modpack.normalizeCategories(categories),
        mods = mods.toMutableList(),
        clientExtras = clientExtras.toMutableList(),
    )
    val startTime = System.nanoTime()
    ModpackChunkedUploader(api).upload(
        file = uploadZip,
        publish = { uploadId -> api.publishNew(ModpackCreateFromUploadDto(uploadId, dto)) },
        ensureActive = ensureActive,
        onProgress = { progress -> onProgress(ModpackChunkedUploader.toTask2Progress(modpackName, progress)) },
        onPublicationUncertain = onPublicationUncertain,
    )
    onDone(uploadSummary(totalBytes, startTime))
}

private suspend fun uploadNewVersion(
    modpackId: ObjectId,
    versionName: String,
    mods: List<Mod>,
    clientExtras: List<Content> = emptyList(),
    uploadZip: File,
    totalBytes: Long,
    onProgress: (Task2Progress) -> Unit,
    onDone: (String) -> Unit,
    api: ModpackUploadApi,
    ensureActive: () -> Unit = {},
    onPublicationUncertain: (String) -> Unit = {},
) {
    onProgress(Task2Progress("上传新版本 $versionName..."))
    val startTime = System.nanoTime()
    ModpackChunkedUploader(api).upload(
        file = uploadZip,
        publish = { uploadId ->
            api.publishVersion(
                modpackId = modpackId,
                versionName = versionName,
                request = ModpackVersionCreateFromUploadDto(
                    uploadId = uploadId,
                    mods = mods.toMutableList(),
                    clientExtras = clientExtras.toMutableList(),
                ),
            )
        },
        ensureActive = ensureActive,
        onProgress = { progress -> onProgress(ModpackChunkedUploader.toTask2Progress("版本 $versionName", progress)) },
        onPublicationUncertain = onPublicationUncertain,
    )
    onDone(uploadSummary(totalBytes, startTime))
}

private fun uploadSummary(totalBytes: Long, startedAt: Long): String {
    val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
    val speed = if (elapsedSeconds <= 0) 0.0 else totalBytes / elapsedSeconds
    return buildString {
        appendLine("文件大小: ${totalBytes.humanFileSize}")
        appendLine("平均速度: ${speed.humanSpeed}")
        appendLine("耗时: ${"%.1f".format(elapsedSeconds)}秒")
        appendLine("上传完成，正在继续本地安装")
    }
}
