package calebxzau.rdi.mc.v20.client.mcp

import calebxzau.mc.common2021.mcp.McpCommandControl
import calebxzau.mc.common2021.mcp.McpScreenControl
import calebxzhou.rdi.mc.client.mcp.McpGameInterface
import calebxzhou.rdi.mc.client.mcpimpl.McpScreenPlatform20
import calebxzhou.rdi.mc.common2.mcp.McpBadSlotError
import calebxzhou.rdi.mc.common2.mcp.McpError
import calebxzhou.rdi.mc.common2.mcp.McpNoPlayerError
import calebxzhou.rdi.mc.common2.mcp.model.*
import com.mojang.blaze3d.pipeline.RenderCall
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

abstract class McpGame20 : McpGameInterface {
    private val mc: Minecraft
        get() = Minecraft.getInstance()

    override fun inventory(): Result<InventoryListP> = runCatching {
        val inventory = mc.player?.inventory ?: throw McpNoPlayerError()
        InventoryListP(
            inv = inventory.items.mapIndexed(::slot),
            armor = inventory.armor.mapIndexed(::slot),
            offhand = inventory.offhand.mapIndexed(::slot),
        )
    }

    override fun inventorySlot(req: InventorySlotQ): Result<String> = runCatching {
        val player = mc.player ?: throw McpNoPlayerError()
        val stacks = when (req.compart) {
            InventoryCompart.INV -> player.inventory.items
            InventoryCompart.ARMOR -> player.inventory.armor
            InventoryCompart.OFFHAND -> player.inventory.offhand
        }
        val stack = stacks.getOrNull(req.slotId) ?: throw McpBadSlotError()
        val tag = stack.tag?.toString() ?: "{}"
        "${stack}\n$tag"
    }

    override fun playerInfo(): Result<PlayerInfo> = runCatching {
        val player = mc.player ?: throw McpNoPlayerError()
        PlayerInfo(
            dim = player.level().dimension().location().toString(),
            uuid = player.uuid.toString(),
            name = player.name.string,
            pos = EntityPos(player.x, player.y, player.z, player.yRot, player.xRot),
            health = player.health,
            maxHealth = player.maxHealth,
            food = player.foodData.foodLevel,
            gameMode = mc.gameMode?.playerMode?.getName() ?: "unknown",
        )
    }

    override fun screenshotPngData(): Result<ByteArray> = runCatching {
        val future = CompletableFuture<ByteArray>()
        val capture = RenderCall {
            try {
                val image = Screenshot.takeScreenshot(mc.mainRenderTarget)
                try {
                    future.complete(image.asByteArray())
                } catch (e: Throwable) {
                    future.completeExceptionally(e)
                } finally {
                    image.close()
                }
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        if (RenderSystem.isOnRenderThread()) {
            capture.execute()
        } else {
            RenderSystem.recordRenderCall(capture)
        }
        future.get(5, TimeUnit.SECONDS)
    }.recoverCatching { e ->
        if (e is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        throw when (e) {
            is TimeoutException -> McpError("screenshot timeout")
            is InterruptedException -> McpError("screenshot interrupted")
            else -> e
        }
    }

    override fun gameVersion(): String = "1.20.1"

    private val screenControl = McpScreenControl(McpScreenPlatform20)

    override fun screenRead(): Result<ScreenSnapshot> = screenControl.read()

    override fun screenClick(req: ScreenClickQ): Result<ScreenSnapshot> = screenControl.click(req)

    override fun screenClickAt(req: ScreenClickAtQ): Result<ScreenSnapshot> = screenControl.clickAt(req)

    override fun screenType(req: ScreenTypeQ): Result<ScreenSnapshot> = screenControl.type(req)

    override fun screenKey(req: ScreenKeyQ): Result<ScreenSnapshot> = screenControl.key(req)

    override fun screenScroll(req: ScreenScrollQ): Result<ScreenSnapshot> = screenControl.scroll(req)

    override fun runCommand(req: CommandQ): Result<CommandResult> = McpCommandControl.run(req)

    private fun slot(id: Int, stack: ItemStack): ContainerSlot {
        return if (stack.isEmpty) {
            ContainerSlot.empty(id)
        } else {
            ContainerSlot(id, BuiltInRegistries.ITEM.getKey(stack.item).toString(), stack.count)
        }
    }
}
