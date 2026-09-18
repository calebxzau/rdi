package calebxzau.rdi.mc.client.dm

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class DmJoinScreen(private val parent: Screen) : Screen(Component.literal("加入DM房间")) {
    private val generation = AtomicLong()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private lateinit var hostIdBox: EditBox
    private var message = Component.empty()
    private var joinButton: Button? = null
    private var redownloadButton: Button? = null

    override fun init() {
        hostIdBox = EditBox(font, width / 2 - 120, height / 2 - 30, 240, 20, Component.literal("房间ID"))
        hostIdBox.setMaxLength(36)
        addRenderableWidget(hostIdBox)
        joinButton = addRenderableWidget(Button.builder(Component.literal("加入/重连")) { join(false) }
            .bounds(width / 2 - 120, height / 2 + 5, 115, 20).build())
        redownloadButton = addRenderableWidget(Button.builder(Component.literal("重新下载资料")) { join(true) }
            .bounds(width / 2 + 5, height / 2 + 5, 115, 20).build())
        addRenderableWidget(Button.builder(Component.literal("返回")) { onClose() }
            .bounds(width / 2 - 120, height / 2 + 32, 240, 20).build())
    }

    private fun join(forceDownload: Boolean) {
        val id = runCatching { UUID.fromString(hostIdBox.value.trim()) }.getOrElse {
            message = Component.literal("房间ID格式无效")
            return
        }
        val config = DmConfig.fromSystemProperty().getOrElse {
            message = Component.literal("DM服务器配置无效")
            return
        } ?: run {
            message = Component.literal("DM服务器未配置")
            return
        }
        val token = generation.incrementAndGet()
        joinButton?.active = false
        redownloadButton?.active = false
        message = Component.literal("正在查询房间…")
        executor.submit {
            runCatching {
                val client = DmHttpClient(config)
                client.getHost(id).getOrThrow()
                val cache = Minecraft.getInstance().gameDirectory.toPath().resolve("rdi").resolve("dm").resolve(id.toString())
                Files.createDirectories(cache)
                val world = cache.resolve("world")
                val marker = cache.resolve("complete")
                val prepared = Files.isRegularFile(marker) && Files.isDirectory(world)
                if (forceDownload || !prepared) {
                    update(token, "正在下载世界资料…")
                    downloadAndReplace(client, id, cache, world, marker, token)
                }
                val host = client.getHost(id).getOrThrow()
                if (host.gamePort == null || host.gamePort !in 1..65535) {
                    update(token, "世界资料已准备，房主当前离线")
                    return@runCatching null
                }
                update(token, "正在连接房间…")
                val latestInfo = client.info().getOrThrow()
                val publicHost = latestInfo.publicHost ?: config.baseUri.host
                val endpoint = DmEndpoint.parse("${formatHost(publicHost)}:${host.gamePort}").getOrThrow()
                JoinTarget(endpoint.host, host.gamePort, host.name, endpoint.gameAddress(host.gamePort))
            }.onSuccess { target ->
                Minecraft.getInstance().execute {
                    if (generation.get() != token) return@execute
                    if (target == null) {
                        joinButton?.active = true
                        redownloadButton?.active = true
                        return@execute
                    }
                    ConnectScreen.startConnecting(
                        parent,
                        Minecraft.getInstance(),
                        ServerAddress(target.host, target.port),
                        ServerData(target.name, target.address, ServerData.Type.OTHER),
                        false,
                        null,
                    )
                }
            }.onFailure { error ->
                update(token, "加入失败：${error.message ?: "未知错误"}")
                Minecraft.getInstance().execute {
                    if (generation.get() == token) {
                        joinButton?.active = true
                        redownloadButton?.active = true
                    }
                }
            }
        }
    }

    private fun downloadAndReplace(client: DmHttpClient, id: UUID, cache: Path, world: Path, marker: Path, token: Long) {
        val parentDirectory = cache.parent ?: error("DM缓存目录没有父目录")
        val zip = Files.createTempFile(parentDirectory, "world-init-", ".zip")
        val staging = Files.createTempDirectory(parentDirectory, "world-staging-")
        var published = false
        try {
            client.downloadWorldInit(id, zip).getOrThrow()
            DmWorldInitArchive.extract(zip, staging).getOrThrow()
            if (generation.get() != token) throw java.util.concurrent.CancellationException("加入操作已取消")
            if (Files.exists(world)) moveToArchive(world, id)
            Files.deleteIfExists(marker)
            try {
                Files.move(staging, world, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staging, world)
            }
            published = true
            val markerTemp = Files.createTempFile(cache, "complete-", ".tmp")
            try {
                Files.writeString(markerTemp, id.toString())
                try {
                    Files.move(markerTemp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(markerTemp, marker, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(markerTemp)
            }
        } finally {
            Files.deleteIfExists(zip)
            if (!published) DmWorldInitArchive.deleteRecursively(staging)
        }
    }

    private fun moveToArchive(world: Path, id: UUID) {
        val gameDirectory = Minecraft.getInstance().gameDirectory.toPath()
        val archiveDirectory = gameDirectory.resolve("DEL").resolve("rdi").resolve("dm")
        Files.createDirectories(archiveDirectory)
        var archive = archiveDirectory.resolve("${id}-${System.currentTimeMillis()}-world")
        var suffix = 0
        while (Files.exists(archive)) {
            suffix++
            archive = archiveDirectory.resolve("${id}-${System.currentTimeMillis()}-$suffix-world")
        }
        Files.move(world, archive)
    }

    private fun update(token: Long, text: String) {
        Minecraft.getInstance().execute {
            if (generation.get() == token) message = Component.literal(text)
        }
    }

    override fun onClose() {
        generation.incrementAndGet()
        super.onClose()
    }

    override fun removed() {
        generation.incrementAndGet()
        executor.shutdownNow()
        super.removed()
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick)
        guiGraphics.drawCenteredString(font, title, width / 2, height / 2 - 65, 0xFFFFFF)
        guiGraphics.drawCenteredString(font, message, width / 2, height / 2 + 65, 0xFFFFFF)
        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    private data class JoinTarget(val host: String, val port: Int, val name: String, val address: String)

    companion object {
        private fun formatHost(value: String): String = when {
            value.startsWith("[") && value.endsWith("]") -> value
            value.contains(':') -> "[$value]"
            else -> value
        }
    }
}
