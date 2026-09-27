package calebxzhou.rdi.master.service.host

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.common.util.jarResource
import calebxzhou.rdi.common.util.readAllString
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.model.Host.Companion.getDifficultyText
import calebxzhou.rdi.common.model.Host.Companion.getGameModeText
import calebxzhou.rdi.common.util.ioScope
import calebxzhou.rdi.common.util.str
import calebxzhou.rdi.common.util.validateName
import calebxzhou.rdi.common.util.toUUID
import calebxzhou.rdi.master.HOSTS_DIR
import calebxzhou.rdi.master.service.*
import calebxzhou.rdi.master.service.modpack.ModpackInstallService.installToHost
import calebxzhou.rdi.master.service.modpack.ModpackVersionMutationLock
import calebxzhou.rdi.master.service.modpack.ModpackQueryService
import calebxzhou.rdi.master.service.WorldService.updateWorldSize
import calebxzhou.rdi.master.service.host.HostContainerService.makeContainer
import calebxzhou.rdi.master.service.host.HostContainerService.requireModernLog4j2Config
import calebxzhou.rdi.master.service.host.HostRuntimeService.listenCrashOnStart
import calebxzau.rdi.server.service.baseworld.BaseWorldService
import calebxzhou.rdi.model.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.bson.types.ObjectId
import java.io.File
import java.nio.file.Files
import java.util.*
import kotlin.time.Duration.Companion.seconds

object HostInstallService {
    private val lgr by Loggers

    private data class HostCreateAdmission(
        val modpack: Modpack,
        val version: Modpack.Version,
        val host: Host,
        val mailId: ObjectId,
        val snapshotLease: BaseWorldService.SnapshotLease?,
    )

    val ModLoader.Version.legacyForgeUniversalJarName: String
        get() {
            val artifactVersion = dirName
                .removePrefix("${McVersion.V071.mcVer}-Forge")
                .takeIf { it != dirName && it.isNotBlank() }
                ?.let { "${McVersion.V071.mcVer}-$it" }
                ?: id
            return "forge-$artifactVersion-universal.jar"
        }

    fun Host.overlayRootDir(): File = HOSTS_DIR.resolve(_id.str)

    fun Host.writeServerProperties() {
        dir.resolve("allowed_symlinks.txt").writeText("[regex].*")
        dir.resolve("eula.txt").writeText("eula=true")
        syncAllOpMarkers()
        "server.properties".run {
            val configuredLevelType = if (baseWorldId == null) levelType else "{}"
            var txt = this.jarResource(this).readAllString()
                .replace("#{port}", port.toString())
                .replace("#{difficulty}", getDifficultyText(difficulty))

                .replace("#{gamemode}", getGameModeText(gameMode))
            //水星迫降
            if (this@writeServerProperties.modpackId == ObjectId("6a211c654467027dd3ed3f1d")) {
                txt = txt.replace(
                    "#{gen-settings}",
                    "3;minecraft\\:bedrock,11*minecraft\\:stained_hardened_clay\\:7,4*minecraft\\:stained_hardened_clay\\:12,4*minecraft\\:stained_hardened_clay\\:14,4*minecraft\\:stained_hardened_clay\\:1,4*minecraft\\:gravel,9*minecraft\\:sand,58*minecraft\\:water;0;"
                ).replace("#{level-type}", "flat")
            } else {
                txt = txt.replace("#{gen-settings}", "{}")
                    .replace("#{level-type}", configuredLevelType)
            }
            dir.resolve(this).writeText(txt)
        }
        val defaultPropsFile = dir.resolve("default-server.properties")
        val serverPropsFile = dir.resolve("server.properties")
        if (serverPropsFile.exists() && (defaultPropsFile.exists() || baseWorldId != null)) {
            val serverProps = Properties().apply {
                serverPropsFile.inputStream().use { load(it) }
            }
            if (defaultPropsFile.exists()) {
                val defaultProps = Properties().apply {
                    defaultPropsFile.inputStream().use { load(it) }
                }
                applyDefaultServerProperties(serverProps, defaultProps)
            }
            if (baseWorldId != null) {
                serverProps.setProperty("level-type", levelType)
                serverProps.setProperty("generator-settings", generatorSettings ?: "{}")
            }
            serverProps.setProperty("level-name", "world")
            serverPropsFile.outputStream().use { serverProps.store(it, null) }
        }
    }

    internal fun applyDefaultServerProperties(serverProps: Properties, defaultProps: Properties) {
        defaultProps.forEach { key, value ->
            if (key.toString() != "server-port" && key.toString() != "online-mode") {
                lgr.info { "apply prop $key = $value" }
                serverProps.setProperty(key.toString(), value.toString())
            }
        }
        serverProps.setProperty("level-name", "world")
    }

    fun Host.deleteTransientStartupDirs() {
        listOf("tacz_backup", "dynamic-data-pack-cache").forEach { dirName ->
            val targetDir = dir.resolve(dirName)
            if (!targetDir.exists()) return@forEach
            runCatching { targetDir.deleteRecursivelyNoSymlink() }
                .onFailure { err -> throw RequestError("删除${dirName}失败: ${err.message}") }
        }
    }

    fun Host.syncAllOpMarkers() {
        val allOpMarker = dir.resolve("R_ALL_OP")
        val opsFile = dir.resolve("ops.json")
        if (allowCheats) {
            if (!allOpMarker.exists()) {
                allOpMarker.writeText("")
            }
            return
        }
        runCatching { Files.deleteIfExists(allOpMarker.toPath()) }
            .onFailure { err -> lgr.warn { "Host ${_id} 删除R_ALL_OP失败: ${err.message}" } }
        runCatching { Files.deleteIfExists(opsFile.toPath()) }
            .onFailure { err -> lgr.warn { "Host ${_id} 删除ops.json失败: ${err.message}" } }
    }

    fun Host.ensureWorkdirQuota() {
        if (!dir.exists()) return
        val totalSize = dir.walkTopDown()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .sumOf { it.length() }
        if (totalSize > HostService.HOST_WORKDIR_LIMIT_BYTES) {
            throw RequestError("房间目录超过 8GB (${totalSize.humanFileSize})，请删除不必要文件后再启动")
        }
    }

    suspend fun allocateRoomPort(): Int {
        val used = HostService.dbcl.find().map { it.port }.toList().toSet()
        val candidates = (HostService.PORT_START until HostService.PORT_END_EXCLUSIVE).asSequence()
            .filter { it !in used }
            .toList()
        if (candidates.isEmpty()) {
            throw RequestError("没有可用端口，请联系管理员")
        }
        return candidates.random()
    }

    internal fun resolveHostCreateVersion(modpack: Modpack, packVer: String): Modpack.Version {
        return if (packVer == "latest") {
            modpack.versions.lastOrNull() ?: throw RequestError("此整合包没有可用版本")
        } else {
            modpack.versions.firstOrNull { it.name == packVer } ?: throw RequestError("无此版本")
        }
    }

    internal fun resolveBaseWorldId(
        binding: Modpack.Version.BaseWorldBinding?,
        requestedId: UUID?,
    ): UUID? = when {
        binding == null -> requestedId
        !binding.required -> requestedId
        requestedId == null || requestedId == binding.id -> binding.id
        else -> throw RequestError("此版本必须使用指定地图模板，请刷新后重试")
    }

    suspend fun RAccount.createHost(host: Host.CreateDto, baseWorldService: BaseWorldService? = null) {
        host.name.validateName()
        val playerId = _id
        if (HostQueryService.getByOwner(playerId).size > 3 && !isDav) {
            throw RequestError("最多只可创建3张房间")
        }
        if (HostQueryService.findByOwnerAndModpack(playerId, host.modpackId) != null && !this.isDav) {
            throw RequestError("同一个整合包只能创建一张房间")
        }
        val initiallyResolvedPack = ModpackQueryService.getById(host.modpackId) ?: throw RequestError("无此包")
        val pinnedVersionName = resolveHostCreateVersion(initiallyResolvedPack, host.packVer).name
        if (initiallyResolvedPack.versions.first { it.name == pinnedVersionName }.status != Modpack.Status.OK) {
            throw RequestError("此整合包版本未准备好，请等待构建完成后再创建房间")
        }
        val admission = ModpackVersionMutationLock.withLock(host.modpackId, pinnedVersionName) {
            val freshPack = ModpackQueryService.getById(host.modpackId) ?: throw RequestError("无此包")
            val freshVersion = freshPack.versions.firstOrNull { it.name == pinnedVersionName }
                ?: throw RequestError("无此版本")
            if (freshVersion.status != Modpack.Status.OK) {
                throw RequestError("此整合包版本未准备好，请等待构建完成后再创建房间")
            }
            if (!freshPack.mcVer.supportsRuntime(freshPack.modloader)) {
                throw RequestError("此整合包使用的Minecraft版本与Mod加载器组合暂不支持")
            }
            val binding = freshVersion.baseWorld
            val selectedBaseWorldId = resolveBaseWorldId(binding, host.baseWorldId)
            var snapshotLease: BaseWorldService.SnapshotLease? = selectedBaseWorldId?.let { worldId ->
                (baseWorldService ?: throw RequestError("地图模板服务不可用"))
                    .acquireSnapshotLease(worldId)
                    .getOrThrow()
            }
            val baseWorld = snapshotLease?.world
            val createdHost = Host(
                    name = host.name,
                    ownerId = playerId,
                    modpackId = host.modpackId,
                    packVer = freshVersion.name,
                    worldId = null,
                    port = allocateRoomPort(),
                    difficulty = host.difficulty,
                    allowCheats = host.allowCheats,
                    whitelist = host.whitelist,
                    gameMode = host.gameMode,
                    levelType = baseWorld?.levelType ?: host.levelType,
                    generatorSettings = baseWorld?.generatorSettings,
                    baseWorldId = baseWorld?.id,
                    members = listOf(Host.Member(id = playerId, role = Role.OWNER)),
                    gameRules = host.gameRules,
                    version = 2
                )
            var mailId: ObjectId? = null
            try {
                mailId = MailService.sendSystemMail(
                    playerId,
                    "房间创建中",
                    "${createdHost.name}正在创建中，请稍等几分钟..."
                )._id
                HostService.dbcl.insertOne(createdHost)
                HostCreateAdmission(
                    freshPack,
                    freshVersion,
                    createdHost,
                    mailId ?: error("房间创建通知ID不存在"),
                    snapshotLease,
                )
            } catch (error: Throwable) {
                snapshotLease?.release()
                snapshotLease = null
                mailId?.let { createdMailId ->
                    runCatching {
                        MailService.changeMail(createdMailId, "房间创建失败", newContent = "无法创建房间，错误：$error")
                    }.onFailure { mailError ->
                        lgr.error(mailError) { "更新房间创建失败通知失败: ${createdHost._id}" }
                    }
                }
                throw error
            }
        }
        try {
            startCreateHost(
                admission.host,
                admission.modpack,
                admission.version,
                admission.mailId,
                newHost = true,
                baseWorldService = baseWorldService,
                snapshotLease = admission.snapshotLease,
            )
        } catch (error: Throwable) {
            runCatching { HostService.dbcl.deleteOne(com.mongodb.client.model.Filters.eq("_id", admission.host._id)) }
                .onFailure { cleanupError -> lgr.error(cleanupError) { "提交创建任务失败时删除房间记录失败: ${admission.host._id}" } }
            runCatching {
                MailService.changeMail(admission.mailId, "房间创建失败", newContent = "无法创建房间，错误：$error")
            }.onFailure { mailError -> lgr.error(mailError) { "更新房间创建失败通知失败: ${admission.host._id}" } }
            runCatching { admission.snapshotLease?.release() }
            throw error
        }
    }

    fun startCreateHost(
        host: Host,
        modpack: Modpack,
        version: Modpack.Version,
        mailId: ObjectId,
        runningTitle: String = "房间创建中",
        successTitle: String = "房间创建成功",
        successContent: String = "可以玩了",
        failureTitle: String = "房间创建失败",
        newHost: Boolean = false,
        persistPackVersion: Boolean = false,
        baseWorldService: BaseWorldService? = null,
        snapshotLease: BaseWorldService.SnapshotLease? = null,
    ) {
        ServerTaskManager.submit(
            task = Task2.Leaf("创建房间 ${host.name}") { ctx ->
                HostLifecycleLock.withLock(host._id) {
                val currentHost = HostQueryService.getById(host._id) ?: run {
                    snapshotLease?.release()
                    val error = RequestError("房间不存在")
                    MailService.changeMail(mailId, failureTitle, newContent = "无法创建房间，错误：$error")
                    throw error
                }
                val installHost = currentHost.copy(packVer = host.packVer)
                var baseWorldSnapshot: File? = null
                var pendingSnapshotLease = snapshotLease
                try {
                    try {
                        requireModernLog4j2Config(modpack.mcVer)
                        if (modpack.modloader == ModLoader.Fabric) {
                            FabricServerRuntimeFiles.requireAvailable(
                                modpack.libsDir.absoluteFile,
                                modpack.mcVer,
                                modpack.modloader,
                            )
                        }
                        if (newHost && installHost.baseWorldId != null && pendingSnapshotLease?.generated != true) {
                        val baseWorldId = installHost.baseWorldId ?: throw RequestError("地图模板ID不存在")
                        val service = baseWorldService ?: throw RequestError("地图模板服务不可用")
                        ctx.emit(LoadProgress.Phase("准备地图模板"))
                        MailService.changeMail(mailId, runningTitle, newContent = "准备地图模板")
                        baseWorldSnapshot = service.snapshotForHost(
                            baseWorldId,
                        ) { ctx.ensureActive() }.getOrThrow()
                        pendingSnapshotLease?.release()
                        pendingSnapshotLease = null
                        }
                        ctx.emit(LoadProgress.Phase("准备房间目录"))
                    MailService.changeMail(mailId, runningTitle, newContent = "准备房间目录")
                    if (installHost.realVersion == 2) {
                        cleanForV2Install(installHost.dir)
                    } else {
                        if (installHost.dir.exists()) installHost.dir.deleteRecursivelyNoSymlink()
                    }
                    installHost.dir.mkdirs()

                    modpack.installToHost(installHost.packVer, installHost) {
                        MailService.changeMail(mailId, runningTitle, newContent = it)
                        ctx.emit(LoadProgress.Phase(it))
                    }

                    if (newHost && pendingSnapshotLease?.generated == true) {
                        moveGeneratedWorldAside(installHost.dir.resolve("world"))
                    }

                    if (newHost && baseWorldSnapshot != null) {
                        ctx.emit(LoadProgress.Phase("安装地图模板"))
                        MailService.changeMail(mailId, runningTitle, newContent = "安装地图模板")
                        val worldDir = installHost.dir.resolve("world")
                        val service = baseWorldService ?: throw RequestError("地图模板服务不可用")
                        val snapshot = baseWorldSnapshot ?: throw RequestError("地图模板快照不存在")
                        service.extractSnapshotForHost(
                            snapshot,
                            worldDir,
                        ) { ctx.ensureActive() }.getOrThrow()
                    }

                    ctx.emit(LoadProgress.Phase("写入房间配置"))
                    MailService.changeMail(mailId, runningTitle, newContent = "写入房间配置")
                    installHost.writeServerProperties()

                    ctx.emit(LoadProgress.Phase("清理启动前缓存"))
                    MailService.changeMail(mailId, runningTitle, newContent = "清理启动前缓存")
                    installHost.deleteTransientStartupDirs()

                    ctx.emit(LoadProgress.Phase("准备运行库"))
                    MailService.changeMail(mailId, runningTitle, newContent = "准备运行库")
                    installHost.makeContainer(installHost.worldId, modpack, version)

                    /*lgr.info { "installToHost returned. Proceeding to start Docker container for host ${installHost._id} (Logic Error Tracing)." }
                    ctx.emit(LoadProgress.Phase("启动房间"))
                    MailService.changeMail(mailId, runningTitle, newContent = "启动房间")
                    DockerService.start(installHost._id.str)
                    installHost.listenCrashOnStart()*/

                    HostControlService.clearShutFlag(installHost._id)
                    if (persistPackVersion) {
                        HostService.dbcl.updateOne(
                            com.mongodb.client.model.Filters.eq("_id", installHost._id),
                            com.mongodb.client.model.Updates.set(Host::packVer.name, installHost.packVer)
                        )
                    }
                    } catch (error: Throwable) {
                        lgr.error { error }
                        error.printStackTrace()
                    if (newHost) {
                        runCatching { DockerService.deleteContainer(installHost._id.str) }
                            .onFailure { cleanupError -> lgr.error(cleanupError) { "创建失败时删除房间容器失败: ${installHost._id}" } }
                        runCatching { installHost.dir.takeIf(File::exists)?.deleteRecursivelyNoSymlink() }
                            .onFailure { cleanupError -> lgr.error(cleanupError) { "创建失败时删除房间目录失败: ${installHost.dir}" } }
                        runCatching { HostService.dbcl.deleteOne(com.mongodb.client.model.Filters.eq("_id", installHost._id)) }
                            .onFailure { cleanupError -> lgr.error(cleanupError) { "创建失败时删除房间记录失败: ${installHost._id}" } }
                    }
                        MailService.changeMail(mailId, failureTitle, newContent = "无法创建房间，错误：${error}")
                        throw error
                    }
                    MailService.changeMail(mailId, successTitle, newContent = successContent)
                } finally {
                    pendingSnapshotLease?.release()
                    baseWorldSnapshot?.let { snapshot ->
                        runCatching { Files.deleteIfExists(snapshot.toPath()) }
                            .onFailure { cleanupError -> lgr.error(cleanupError) { "清理地图模板快照失败: ${snapshot.absolutePath}" } }
                    }
                }
                }
            },
            dedupeKey = HostService.createHostTaskKey(host._id)
        )
    }

    internal fun cleanForV2Install(hostDir: File) {
        if (!hostDir.exists()) {
            if (!hostDir.mkdirs() && !hostDir.exists()) {
                throw RequestError("创建房间目录失败: ${hostDir.absolutePath}")
            }
            return
        }
        val children = hostDir.listFiles() ?: throw RequestError("无法读取房间目录: ${hostDir.absolutePath}")
        children.filterNot { it.name == "world" }.forEach(::deleteStrictNoSymlink)
        val remaining = hostDir.listFiles()?.filterNot { it.name == "world" }
            ?: throw RequestError("无法读取房间目录: ${hostDir.absolutePath}")
        if (remaining.isNotEmpty()) {
            throw RequestError("清理房间目录失败，仍有文件未删除: ${remaining.joinToString { it.name }}")
        }
    }

    private fun moveGeneratedWorldAside(worldDir: File) {
        if (!Files.exists(worldDir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(worldDir.toPath())) {
            return
        }
        val backup = worldDir.resolveSibling(".${worldDir.name}.generated-backup-${UUID.randomUUID()}")
        Files.move(worldDir.toPath(), backup.toPath())
    }

    internal fun deleteStrictNoSymlink(target: File) {
        target.deleteRecursivelyNoSymlink()
        if (target.exists() || Files.isSymbolicLink(target.toPath())) {
            throw RequestError("删除文件失败: ${target.absolutePath}")
        }
    }

    fun Host.refreshWorldSizeAfterStop(waitForStop: Boolean) {
        val worldId = worldId ?: return
        ioScope.launch {
            if (HostService.skipWorldSizeUpdateOnce(_id)) {
                lgr.info { "Host ${_id} 跳过崩溃后的存档大小刷新" }
                return@launch
            }
            if (waitForStop) {
                repeat(60) {
                    if (!DockerService.isStarted(_id.str)) return@repeat
                    delay(2.seconds)
                }
            }
            if (!DockerService.isStarted(_id.str)) {
                runCatching { updateWorldSize(worldId) }
                    .onSuccess { size ->
                        lgr.info { "Host ${_id} world size updated: ${size} bytes" }
                    }
                    .onFailure { err ->
                        lgr.warn { "Host ${name} 更新存档大小失败: ${err.message}" }
                    }
            }
        }
    }
}
