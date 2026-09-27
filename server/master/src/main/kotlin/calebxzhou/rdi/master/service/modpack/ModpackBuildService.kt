package calebxzhou.rdi.master.service.modpack

import calebxzhou.rdi.common.archive.listArchiveEntries
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.service.ModpackModProcessor
import calebxzhou.rdi.common.service.ModService
import calebxzhou.rdi.common.service.runInline
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.toFixed
import calebxzhou.rdi.master.DL_MODS_CLIENT_DIR
import calebxzhou.rdi.master.net.*
import calebxzhou.rdi.master.service.*
import calebxzhou.rdi.master.service.modpack.ModpackVersionService.requireCanManageVersion
import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import org.bson.Document
import org.bson.types.ObjectId
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Build queue, status transitions and build-task failure/mail semantics. */
object ModpackBuildService {
    private val lgr by calebxzau.rdi.common.logging.Loggers
    private val dbcl get() = ModpackServiceKernel.dbcl

    internal fun versionBuildTaskKey(modpackId: ObjectId, versionName: String): String =
        "server-modpack-build:${modpackId.toHexString()}:$versionName"

    internal fun hasActiveVersionBuildTask(modpackId: ObjectId, versionName: String): Boolean {
        val key = versionBuildTaskKey(modpackId, versionName)
        return ServerTaskManager.entries.value.any { entry ->
            entry.dedupeKey == key && !entry.status.isTerminal
        }
    }

    internal suspend fun recoverUnfinishedVersionBuildsOnStartup() {
        val affected = mutableListOf<Pair<ObjectId, String>>()
        dbcl.find(
            or(
                elemMatch(
                    Modpack::versions.name,
                    eq(Modpack.Version::status.name, Modpack.Status.WAIT)
                ),
                elemMatch(
                    Modpack::versions.name,
                    eq(Modpack.Version::status.name, Modpack.Status.BUILDING)
                )
            )
        ).toList().forEach { modpack ->
            modpack.versions
                .filter { it.status == Modpack.Status.WAIT || it.status == Modpack.Status.BUILDING }
                .forEach { affected += modpack._id to it.name }
        }
        affected.forEach { (id, name) ->
            dbcl.updateOne(
                eq(Modpack::_id.name, id),
                Updates.set(
                    "${Modpack::versions.name}.$[elem].${Modpack.Version::status.name}",
                    Modpack.Status.FAIL
                ),
                UpdateOptions().arrayFilters(listOf(Document("elem.name", name)))
            )
        }
        if (affected.isNotEmpty()) {
            lgr.warn { "启动恢复：已将${affected.size}个卡在WAIT/BUILDING的整合包版本标记为FAIL" }
        }
    }

    internal fun enqueueVersionBuild(
        player: RAccount,
        modpack: Modpack,
        version: Modpack.Version
    ) {
        ModpackServiceKernel.testBuildVersionObserver?.invoke(version)
        ServerTaskManager.submit(
            createVersionBuildTask(player, modpack, version, reprocessMods = false),
            versionBuildTaskKey(modpack._id, version.name)
        )
    }

    private fun createVersionBuildTask(
        player: RAccount,
        modpack: Modpack,
        version: Modpack.Version,
        reprocessMods: Boolean
    ): Task2 {
        requireSupportedBuildRuntime(modpack)
        return Task2.Sequence(
        title = "${if (reprocessMods) "重构" else "构建"}整合包版本 ${modpack.name} V${version.name}",
        children = buildList {
            var mailId: ObjectId? = null
            val failureHandled = AtomicBoolean(false)

            suspend fun progress(message: String) {
                lgr.info { message }
                mailId?.let { MailService.changeMail(it, newContent = message) }
            }

            suspend fun fail(error: Throwable) {
                if (!failureHandled.compareAndSet(false, true)) return
                lgr.error(error) {
                    "${if (reprocessMods) "重构" else "构建"}整合包 ${modpack._id}:${version.name} 失败"
                }
                version.setStatus(Modpack.Status.FAIL)
                mailId?.let {
                    MailService.changeMail(
                        it,
                        if (reprocessMods) "重构整合包失败：${modpack.name}" else "整合包构建失败：${modpack.name}",
                        "无法构建整合包，错误原因：${error.message}"
                    )
                }
            }

            fun leaf(
                title: String,
                action: suspend (Task2Context) -> Unit
            ) = Task2.Leaf(title) { ctx ->
                runCatching { action(ctx) }
                    .onFailure { fail(it) }
                    .getOrThrow()
            }

            add(leaf("准备构建") { ctx ->
                val mail = MailService.sendSystemMail(
                    player._id,
                    if (reprocessMods) "重构整合包：${modpack.name} V${version.name}" else "整合包${version.name}构建中",
                    "开始构建整合包 ${modpack.name} 版本${version.name}\n"
                )
                mailId = mail._id
                version.setStatus(Modpack.Status.BUILDING)
                version.setTotalSize(version.fullPackFile.length())
                val msg = "开始构建 ${modpack.name} V${version.name}"
                progress(msg)
                ctx.emit(LoadProgress.Phase(msg))
            })

            if (reprocessMods) {
                add(leaf("重新处理版本Mod信息") { ctx ->
                    val msg = "重新处理版本Mod信息"
                    progress(msg)
                    ctx.emit(LoadProgress.Phase(msg))
                    val processedMods = ModpackModProcessor.processMods(version.mods, modpack.modloader)
                    version.mods.clear()
                    version.mods += processedMods
                    version.mods.sortBy { it.slug.lowercase() }
                    dbcl.updateOne(
                        eq(Modpack::_id.name, modpack._id),
                        Updates.set(
                            "${Modpack::versions.name}.$[elem].${Modpack.Version::mods.name}",
                            version.mods
                        ),
                        UpdateOptions().arrayFilters(listOf(Document("elem.name", version.name)))
                    )
                    ctx.emit(LoadProgress.Percent("版本Mod信息重处理完成", 1f))
                })
            }

            add(leaf("校验整合包归档") { ctx ->
                val entries = listArchiveEntries(version.fullPackFile)
                val root = resolveServerRoot(entries.map { it.path })
                val msg = "校验整合包归档(${root.dirName}/)"
                progress(msg)
                ctx.emit(LoadProgress.Phase(msg))
                val total = entries.size.coerceAtLeast(1)
                entries.forEachIndexed { index, entry ->
                    ModpackArchiveService.extractServerInstallRelativePathForBuild(entry.path, root)
                    ctx.emit(
                        LoadProgress.Percent(
                            "校验整合包归档(${root.dirName}/ ${index + 1}/$total)",
                            (index + 1).toFloat() / total
                        )
                    )
                }
                ctx.emit(LoadProgress.Percent("整合包归档校验完成(${root.dirName}/)", 1f))
            })

            val effective = if (reprocessMods) {
                ModpackModProcessor.processMods(version.mods, modpack.modloader)
            } else {
                version.mods
            }
            val serverMods = effective.filter {
                it.side != Mod.Side.CLIENT &&
                    it.side != Mod.Side.UNKNOWN &&
                    !it.fileName.startsWith(CLIENT_ONLY_MARK_PREFIX)
            }
            val clientMods = effective.filter { it.side == Mod.Side.CLIENT }

            add(
                Task2.Group(
                    "下载Mod",
                    listOf(
                        Task2.Sequence(
                            "下载服务端Mod",
                            listOf(
                                leaf("准备下载服务端Mod") { ctx ->
                                    val msg = "开始下载服务端Mod，共${serverMods.size}个"
                                    progress(msg)
                                    ctx.emit(LoadProgress.Phase(msg))
                                },
                                (
                                    ModpackServiceKernel.testServerModDownloadTaskFactory?.invoke(serverMods)
                                        ?: ModService.downloadModsTask2(serverMods)
                                    ).withFailureHandler(::fail)
                            )
                        ),
                        ModpackServiceKernel.testClientModDownloadTaskFactory?.invoke(clientMods)
                            ?: ClientModCacheService(DL_MODS_CLIENT_DIR).downloadTask(clientMods)
                    )
                )
            )

            add(leaf("构建客户端版") { ctx ->
                val msg = "构建客户端版"
                progress(msg)
                ctx.emit(LoadProgress.Phase(msg))
                ModpackArchiveService.buildClientPack(version)
                ctx.emit(LoadProgress.Percent("客户端版本构建完成", 1f))
            })

            add(leaf("迁移归档到tar.zst") { ctx ->
                if (!version.zstdPack.exists() && version.zip.exists()) {
                    val msg = "迁移整合包归档到tar.zst"
                    progress(msg)
                    ctx.emit(LoadProgress.Phase(msg))
                    ModpackArchiveService.upgradeFullPackArchive(version)
                } else {
                    ctx.emit(LoadProgress.Percent("归档已是tar.zst，无需迁移", 1f))
                }
            })

            add(leaf("完成构建") { ctx ->
                val msg = "整合包构建完成"
                progress(msg)
                version.setStatus(Modpack.Status.OK)
                mailId?.let {
                    MailService.changeMail(
                        it,
                        if (reprocessMods) "重构整合包成功：${modpack.name}" else "整合包构建成功：${modpack.name}",
                        "${modpack.name} V${version.name} 已构建完成"
                    )
                }
                ctx.emit(LoadProgress.Percent(msg, 1f))
            })
        }
        )
    }

    private fun requireSupportedBuildRuntime(modpack: Modpack) {
        if (!modpack.mcVer.supportsRuntime(modpack.modloader)) {
            throw RequestError("当前版本暂不支持构建Minecraft${modpack.mcVer.mcVer}／${modpack.modloader.name}整合包")
        }
    }

    private fun resolveServerRoot(
        paths: List<String>
    ): ModpackArchiveService.BuildArchiveRoot =
        ModpackArchiveService.resolveServerInstallArchiveRootForBuild(paths)

    internal fun createVersionBuildTaskForTest(
        player: RAccount,
        modpack: Modpack,
        version: Modpack.Version,
        reprocessMods: Boolean
    ): Task2 = createVersionBuildTask(player, modpack, version, reprocessMods)

    private fun Task2.withFailureHandler(
        handler: suspend (Throwable) -> Unit
    ): Task2 = when (this) {
        is Task2.Leaf -> copy(action = { ctx ->
            runCatching { action(ctx) }.getOrElse {
                handler(it)
                throw it
            }
        })

        is Task2.Group -> copy(children = children.map { it.withFailureHandler(handler) })
        is Task2.Sequence -> copy(children = children.map { it.withFailureHandler(handler) })
    }

    suspend fun ModpackContext.rebuildVersion() {
        ModpackVersionMutationLock.withLock(modpack._id, version.name) {
            val freshPack = ModpackQueryService.getById(modpack._id) ?: throw RequestError("整合包不存在")
            val freshVersion = freshPack.versions.firstOrNull { it.name == version.name }
                ?: throw RequestError("版本${version.name}不存在")
            ModpackContext(player, freshPack, freshVersion).requireCanManageVersion()
            val previousStatus = freshVersion.status
            if (previousStatus == Modpack.Status.WAIT || previousStatus == Modpack.Status.BUILDING) {
                throw RequestError("版本${freshVersion.name}正在构建中")
            }
            if (hasActiveVersionBuildTask(freshPack._id, freshVersion.name)) {
                throw RequestError("版本${freshVersion.name}正在构建中")
            }
            val waitResult = dbcl.updateOne(
                and(
                    eq(Modpack::_id.name, freshPack._id),
                    elemMatch(
                        Modpack::versions.name,
                        and(
                            eq(Modpack.Version::name.name, freshVersion.name),
                            eq(Modpack.Version::status.name, previousStatus)
                        )
                    )
                ),
                Updates.set(
                    "${Modpack::versions.name}.$[elem].${Modpack.Version::status.name}",
                    Modpack.Status.WAIT
                ),
                UpdateOptions().arrayFilters(
                    listOf(
                        Document("elem.name", freshVersion.name)
                            .append("elem.status", previousStatus)
                    )
                )
            )
            if (waitResult.matchedCount == 0L) {
                throw RequestError("版本已被其他操作修改，请刷新后重试")
            }
            try {
                ServerTaskManager.submit(
                    createVersionBuildTask(player, freshPack, freshVersion, reprocessMods = true),
                    versionBuildTaskKey(freshPack._id, freshVersion.name)
                )
            } catch (error: CancellationException) {
                restoreRebuildStatus(freshPack._id, freshVersion.name, previousStatus)
                throw error
            } catch (error: Exception) {
                restoreRebuildStatus(freshPack._id, freshVersion.name, previousStatus)
                throw error
            }
        }
    }

    private suspend fun restoreRebuildStatus(
        modpackId: ObjectId,
        versionName: String,
        status: Modpack.Status
    ) {
        dbcl.updateOne(
            and(
                eq(Modpack::_id.name, modpackId),
                elemMatch(
                    Modpack::versions.name,
                    and(
                        eq(Modpack.Version::name.name, versionName),
                        eq(Modpack.Version::status.name, Modpack.Status.WAIT)
                    )
                )
            ),
            Updates.set(
                "${Modpack::versions.name}.$[elem].${Modpack.Version::status.name}",
                status
            ),
            UpdateOptions().arrayFilters(
                listOf(Document("elem.name", versionName).append("elem.status", Modpack.Status.WAIT))
            )
        )
    }

    suspend fun Modpack.buildVersion(
        version: Modpack.Version,
        onProgress: (String) -> Unit
    ) {
        requireSupportedBuildRuntime(this)
        if (!version.fullPackFile.exists()) {
            throw RequestError("版本压缩文件不存在 请重新上传")
        }
        version.setTotalSize(version.fullPackFile.length())
        val buildDir = createVersionBuildDir(version, ObjectId().toHexString())
        try {
            onProgress("解压整合包文件..")
            ModpackArchiveService.unzipOverrides(version.fullPackFile, buildDir, false)
            val serverMods = version.mods.filter {
                it.side != Mod.Side.CLIENT &&
                    it.side != Mod.Side.UNKNOWN &&
                    !it.fileName.startsWith(CLIENT_ONLY_MARK_PREFIX)
            }
            val clientMods = version.mods.filter { it.side == Mod.Side.CLIENT }
            Task2.Group(
                "下载Mod",
                listOf(
                    ModpackServiceKernel.testServerModDownloadTaskFactory?.invoke(serverMods)
                        ?: ModService.downloadModsTask2(serverMods),
                    ModpackServiceKernel.testClientModDownloadTaskFactory?.invoke(clientMods)
                        ?: ClientModCacheService(DL_MODS_CLIENT_DIR).downloadTask(clientMods)
                )
            ).runInline(Task2Context { progressInfo ->
                val message = progressInfo.fraction?.let {
                    "${progressInfo.message} ${(it * 100f).toFixed(2)}%"
                } ?: progressInfo.message
                onProgress("mod下载中：$message")
            })
            onProgress("所有mod下载完成 开始安装。。${serverMods.size}个mod")
            onProgress("构建客户端版。。")
            ModpackArchiveService.buildClientPack(version)
            onProgress("客户端版本构建完成")
            if (!version.zstdPack.exists() && version.zip.exists()) {
                onProgress("迁移整合包归档到tar.zst..")
                ModpackArchiveService.upgradeFullPackArchive(version)
            }
            onProgress("整合包构建完成！")
            version.setStatus(Modpack.Status.OK)
        } catch (e: Exception) {
            version.setStatus(Modpack.Status.FAIL)
            throw e
        } finally {
            if (buildDir.exists()) buildDir.deleteRecursivelyNoSymlink()
        }
    }

    internal suspend fun Modpack.Version.setStatus(status: Modpack.Status) {
        dbcl.updateOne(
            eq(Modpack::_id.name, modpackId),
            Updates.set(
                "${Modpack::versions.name}.$[elem].${Modpack.Version::status.name}",
                status
            ),
            UpdateOptions().arrayFilters(listOf(Document("elem.name", name)))
        )
    }

    internal suspend fun Modpack.Version.setTotalSize(size: Long) {
        dbcl.updateOne(
            eq(Modpack::_id.name, modpackId),
            Updates.set(
                "${Modpack::versions.name}.$[elem].${Modpack.Version::totalSize.name}",
                size
            ),
            UpdateOptions().arrayFilters(listOf(Document("elem.name", name)))
        )
    }

    internal fun cleanupVersionBuildDirs(version: Modpack.Version) {
        version.storageDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(".build-${version.name}-") }
            ?.forEach { it.deleteRecursivelyNoSymlink() }
    }

    private fun createVersionBuildDir(version: Modpack.Version, id: String): File {
        version.storageDir.mkdirs()
        val dir = version.tempDir(id)
        if (dir.exists()) dir.deleteRecursivelyNoSymlink()
        dir.mkdirs()
        return dir
    }
}
