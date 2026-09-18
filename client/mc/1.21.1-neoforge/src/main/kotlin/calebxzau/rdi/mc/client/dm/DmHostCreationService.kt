package calebxzau.rdi.mc.client.dm

import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object DmHostCreationService {
    private val logger = LoggerFactory.getLogger(DmHostCreationService::class.java)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    @Volatile private var job: Job? = null

    private class Job(
        val minecraft: Minecraft,
        val server: IntegratedServer,
        val root: Path,
        val config: DmConfig,
        val name: String,
        val screen: DmHostCreationScreen,
    ) {
        val cancelled = AtomicBoolean(false)
        @Volatile var processing = true
        @Volatile var uploadStarted = false
        @Volatile var worker: Thread? = null
        @Volatile var createdHost: UUID? = null
    }

    fun create(name: String): Result<Unit> {
        if (job != null) return Result.failure(IllegalStateException("DM房间创建正在进行"))
        val minecraft = Minecraft.getInstance()
        if (!minecraft.hasSingleplayerServer() || minecraft.player == null) {
            return Result.failure(IllegalStateException("请先进入单人世界"))
        }
        val server = minecraft.getSingleplayerServer()
            ?: return Result.failure(IllegalStateException("找不到当前单人世界服务器"))
        if (server.isPublished) {
            return Result.failure(IllegalStateException("世界已开放联机，无法使用原版暂停创建DM房间"))
        }
        val config = DmConfig.fromSystemProperty().getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("DM服务器未配置"))
        // LevelResource.ROOT is "."; resolve it before choosing a sibling staging directory.
        val root = runCatching { server.getWorldPath(LevelResource.ROOT).toRealPath() }
            .getOrElse { return Result.failure(IllegalStateException("无法定位当前世界目录", it)) }
        val association = DmWorldAssociationStore.read(root).getOrElse { return Result.failure(it) }
        if (association != null) return Result.failure(IllegalStateException("此世界已经关联DM房间：${association.hostId}"))
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("房间名称不能为空"))
        val next = Job(minecraft, server, root, config, trimmed, DmHostCreationScreen())
        if (job != null) return Result.failure(IllegalStateException("DM房间创建正在进行"))
        job = next
        DmHostCreationScreen.update("正在准备", 0f, true, null)
        minecraft.setScreen(next.screen)
        executor.submit { run(next) }
        return Result.success(Unit)
    }

    fun isRunning(): Boolean = job != null

    fun cancel(): Boolean {
        val current = job ?: return false
        if (!current.processing) return false
        current.cancelled.set(true)
        if (current.uploadStarted) current.worker?.interrupt()
        val message = if (current.uploadStarted) "上传已开始，取消后创建结果可能未知；不会自动重试" else "正在取消创建"
        showProgress(message, 0f, false)
        return true
    }

    fun onTick() {
        val current = job ?: return
        if (!current.processing || current.cancelled.get() || current.createdHost != null) return
        if (current.minecraft.getSingleplayerServer() !== current.server ||
            current.minecraft.screen !== current.screen || current.server.isPublished
        ) {
            cancel()
        }
    }

    fun returnToWorld() {
        val current = job ?: return
        current.minecraft.execute {
            if (job === current) {
                job = null
                current.minecraft.setScreen(null)
            }
        }
    }

    fun retryAssociationSave() {
        val current = job ?: return
        val hostId = current.createdHost ?: return
        if (current.minecraft.getSingleplayerServer() !== current.server) return
        DmWorldAssociationStore.write(current.root, hostId).onSuccess {
            finishCreated(current, hostId)
        }.onFailure { error -> showFailure(current, "关联文件保存失败：${error.message ?: "未知错误"}", hostId, error) }
    }

    private fun run(current: Job) {
        var tempDirectory: Path? = null
        current.worker = Thread.currentThread()
        try {
            val pauseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!current.minecraft.isPaused || !serverPauseState(current)) {
                check(!current.cancelled.get()) { "已取消DM房间创建" }
                check(current.minecraft.getSingleplayerServer() === current.server) { "已离开原世界" }
                check(System.nanoTime() < pauseDeadline) { "等待世界暂停超时" }
                Thread.sleep(25)
            }
            checkCurrentWorld(current)
            showProgress("正在保存世界", 0f, true)
            val saved = CompletableFuture<Boolean>()
            current.server.execute { saved.complete(runCatching { current.server.saveEverything(false, true, true) }.getOrDefault(false)) }
            check(saved.get(2, TimeUnit.MINUTES)) { "世界保存失败" }
            check(!current.cancelled.get()) { "已取消DM房间创建" }
            checkCurrentWorld(current)

            tempDirectory = Files.createTempDirectory(current.root.parent, "rdi-dm-create-")
            val archive = tempDirectory.resolve("world-init.zip")
            showProgress("正在打包世界初始化资料", 0f, true)
            DmWorldInitArchive.create(current.root, archive) { current.cancelled.get() }.getOrThrow()
            check(!current.cancelled.get()) { "已取消DM房间创建" }
            checkCurrentWorld(current)

            current.uploadStarted = true
            showProgress("正在上传DM房间资料", 0f, true)
            var lastProgress = 0L
            val host = DmHttpClient(current.config).createHost(current.name, archive) { sent, total ->
                if (current.cancelled.get()) throw CancellationException("已取消上传")
                if (sent == total || sent - lastProgress >= 256 * 1024) {
                    lastProgress = sent
                    showProgress("正在上传DM房间资料", if (total == 0L) 0f else sent.toFloat() / total, true)
                }
            }.getOrThrow()
            current.createdHost = host.id
            if (current.cancelled.get()) throw CancellationException("已取消DM房间创建")
            checkCurrentWorld(current)

            DmWorldAssociationStore.write(current.root, host.id).getOrElse { error ->
                showFailure(current, "房间已创建：${host.id}；关联文件保存失败", host.id, error)
                return
            }
            finishCreated(current, host.id)
        } catch (error: CancellationException) {
            finishCancelled(current)
        } catch (error: Throwable) {
            if (current.cancelled.get()) {
                finishCancelled(current)
                return
            }
            logger.error("Failed to create DM host", error)
            val uncertainty = if (current.uploadStarted && current.createdHost == null) {
                "；创建结果可能未知，不会自动重试"
            } else {
                ""
            }
            val message = "DM房间创建失败：${error.message ?: "未知错误"}${uncertainty}"
            if (current.createdHost == null) finishWithoutHost(current, message)
            else showFailure(current, message, current.createdHost, error)
        } finally {
            tempDirectory?.let { DmWorldInitArchive.deleteRecursively(it) }
            current.worker = null
            current.processing = false
        }
    }

    private fun finishCancelled(current: Job) {
        val hostId = current.createdHost
        when {
            hostId != null -> showFailure(current, "房间已创建：${hostId}；创建已取消，可重试保存关联", hostId)
            current.uploadStarted -> finishWithoutHost(current, "上传已取消，创建结果可能未知；不会自动重试")
            else -> finishWithoutHost(current, "已取消DM房间创建")
        }
    }

    private fun serverPauseState(current: Job): Boolean {
        val result = CompletableFuture<Boolean>()
        current.server.execute { result.complete(current.server.isPaused) }
        return result.get(5, TimeUnit.SECONDS)
    }

    private fun checkCurrentWorld(current: Job) {
        val result = CompletableFuture<Boolean>()
        current.minecraft.execute {
            result.complete(
                job === current && !current.cancelled.get() &&
                    current.minecraft.getSingleplayerServer() === current.server &&
                    current.minecraft.screen === current.screen && !current.server.isPublished &&
                    current.minecraft.isPaused
            )
        }
        check(result.get(5, TimeUnit.SECONDS)) { "世界已离开暂停状态，已停止创建房间" }
    }

    private fun finishCreated(current: Job, hostId: UUID) {
        current.minecraft.execute {
            if (job !== current) return@execute
            job = null
            if (current.minecraft.getSingleplayerServer() === current.server) {
                current.minecraft.setScreen(null)
                if (current.cancelled.get()) {
                    sendMessage(current.minecraft, "房间已创建：${hostId}；创建操作已取消，暂未托管")
                    return@execute
                }
                DmHostService.start(hostId).onFailure { error ->
                    logger.error("DM room {} was created but hosting failed", hostId, error)
                    sendMessage(current.minecraft, "房间已创建：${hostId}；托管失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun finishWithoutHost(current: Job, message: String) {
        current.minecraft.execute {
            if (job !== current) return@execute
            job = null
            if (current.minecraft.getSingleplayerServer() === current.server) {
                current.minecraft.setScreen(null)
                sendMessage(current.minecraft, message)
            }
        }
    }

    private fun sendMessage(minecraft: Minecraft, message: String) {
        minecraft.player?.displayClientMessage(Component.literal(message), false)
            ?: minecraft.gui.chat.addMessage(Component.literal(message))
    }

    private fun showProgress(message: String, progress: Float, canCancel: Boolean) {
        val current = job ?: return
        current.minecraft.execute {
            if (job === current) DmHostCreationScreen.update(message, progress, canCancel, current.createdHost)
        }
    }

    private fun showFailure(current: Job, message: String, hostId: UUID?, error: Throwable? = null) {
        if (error != null) logger.error(message, error)
        current.minecraft.execute {
            if (job === current) {
                DmHostCreationScreen.update(message, 0f, false, hostId, pauseWorld = false)
                current.minecraft.setScreen(DmHostCreationScreen())
            }
        }
    }
}
