package calebxzhou.rdi.mc.client.mcpimpl211

import calebxzau.mc.common2021.makeItemTagKey
import calebxzau.mc.common2021.mc
import calebxzau.mc.common2021.parseResId
import calebxzau.mc.common2021.resId
import calebxzau.mc.common2021.tagItemIds
import calebxzhou.rdi.mc.client.RDIMain
import calebxzau.mc.common2021.mcp.McpCommandControl
import calebxzau.mc.common2021.mcp.McpScreenControl
import calebxzhou.rdi.mc.client.mcp.McpGameInterface
import calebxzhou.rdi.mc.common2.mcp.McpBadRequestError
import calebxzhou.rdi.mc.common2.mcp.McpBadSlotError
import calebxzhou.rdi.mc.common2.mcp.McpError
import calebxzhou.rdi.mc.common2.mcp.McpNoPlayerError
import calebxzhou.rdi.mc.common2.mcp.model.*
import com.mojang.blaze3d.pipeline.RenderCall
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Screenshot
import net.minecraft.world.item.ItemStack
import net.neoforged.fml.ModList
import net.neoforged.neoforge.network.PacketDistributor
import java.util.concurrent.*

object McpGameImpl : McpGameInterface {
    
    private const val RESPONSE_TIMEOUT_SECONDS = 30L

    private val pendingPacketMap = ConcurrentHashMap<String, CompletableFuture<McpS2CNetPacket>>()

    override fun send(packet: McpC2SNetPacket): Result<String> {
        return runCatching {
            val future = CompletableFuture<McpS2CNetPacket>()
            pendingPacketMap[packet.reqId] = future
            mc.execute {
                try {
                    PacketDistributor.sendToServer(McpNetPayload211.fromC2S(packet))
                } catch (e: Throwable) {
                    pendingPacketMap.remove(packet.reqId)?.completeExceptionally(e)
                }
            }
            future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS).text
        }.recoverCatching { e ->
            throw when (e) {
                is TimeoutException -> McpError("server time out")
                is ExecutionException -> McpError("server execution error")
                else -> McpError("server unavailable")
            }
        }.also {
            pendingPacketMap.remove(packet.reqId)
        }
    }

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
        val snbt = stack.saveOptional(player.registryAccess()).toString()
        "${stack}\n${snbt}"
    }

    override fun blockFind(req: BlockFindQ): Result<BlockFindP> = BlockMcpImpl.find(req)

    override fun blockFetchBox(req: BlockFetchBoxQ): Result<BlockFetchBoxP> = BlockMcpImpl.fetchBox(req)

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

    override fun recipes(req: RecipeQ): Result<String> = runCatching {
        if (!RecipeProcessIndex.isReady()) {
            return@runCatching "unresolved reason=jei_not_ready"
        }
        val recipesByItem = req.items.associateWith { RecipeProcessIndex.recipesByOutputItem(it) }
        val processes = recipesByItem.values.flatten()
        RecipeProcessTextView.render(req.items, recipesByItem, processes.tagInventoryMatches())
    }

    override fun recipeTree(req: RecipeTreeQ): Result<String> = runCatching {
        val tree = RecipeTreeResolver.resolve(req)
        RecipeTextView.render(tree, tree.tagInventoryMatches())
    }

    override fun screenshotPngData(): Result<ByteArray> = runCatching {
        val future = CompletableFuture<ByteArray>()
        val capture = RenderCall {
            try {
                val image = Screenshot.takeScreenshot(mc.getMainRenderTarget())
                RDIMain.SCREENSHOT_EXECUTOR.execute {
                    try {
                        image.use {
                            future.complete(it.asByteArray())
                        }
                    } catch (e: Throwable) {
                        future.completeExceptionally(e)
                    }
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

    override fun modIds(): Result<String> = runCatching {
        ModList.get().getSortedMods().joinToString(" ") { it.modInfo.modId }
    }

    override fun modInfo(id: String): Result<ModInfo> = runCatching {
        val mod = ModList.get().getModContainerById(id).orElseThrow {
            McpBadRequestError("unknown mod $id")
        }.modInfo
        ModInfo(
            id = mod.modId,
            name = mod.displayName,
            version = mod.version.toString(),
            description = mod.description,
            dependencies = mod.dependencies.map { dep ->
                ModDependency(
                    id = dep.modId,
                    versionRange = dep.versionRange.toString(),
                    type = dep.type.name.lowercase(),
                    ordering = dep.ordering.name.lowercase(),
                    side = dep.side.name.lowercase(),
                )
            },
        )
    }

    override fun questChapterList(): Result<String> = runCatching {
        mc.player ?: throw McpNoPlayerError()
        FtbQuestsMcpBridge.questChapterList().joinToString("\n") { chapter ->
            buildString {
                append(chapter.id)
                append(" title=").append(chapter.title)
                append(" group=").append(chapter.groupId).append(":").append(chapter.groupTitle)
                append(" completed=").append(chapter.completed)
                append(" quests=").append(chapter.questCount)
                append(" completedQuests=").append(chapter.completedQuestCount)
            }
        }
    }

    override fun questsOfChapter(chapterId: String): Result<String> = runCatching {
        val player = mc.player ?: throw McpNoPlayerError()
        val quests = FtbQuestsMcpBridge.questsOfChapter(chapterId, player.uuid)
        if (quests.isEmpty()) {
            return@runCatching "none"
        }
        quests.joinToString("\n") { quest ->
            val dependencyIds = quest.dependencies.joinToString(",") { it.id }.ifEmpty { "none" }
            "${quest.id} title=${quest.title} completed=${quest.state.completed} optional=${quest.rules.optional} repeatable=${quest.rules.repeatable} tasks=${quest.tasks.size} rewards=${quest.rewards.size} deps=$dependencyIds"
        }
    }

    override fun gameVersion(): String {
        return "1.21.1"
    }


    fun complete(packet: McpS2CNetPacket) {
        pendingPacketMap.remove(packet.reqId)?.complete(packet)
    }

    private fun slot(id: Int, stack: ItemStack): ContainerSlot {
        return if (stack.isEmpty) {
            ContainerSlot.empty(id)
        } else {
            ContainerSlot(id, stack.item.resId.toString(), stack.count)
        }
    }

    private fun RecipeTreeP.tagInventoryMatches(): Map<String, List<String>> {
        return processes().tagInventoryMatches()
    }

    private fun Iterable<RecipeProcess>.tagInventoryMatches(): Map<String, List<String>> {
        val player = mc.player ?: return emptyMap()
        val inventoryItemIds = (player.inventory.items + player.inventory.armor + player.inventory.offhand)
            .asSequence()
            .filterNot { it.isEmpty }
            .map { it.item.resId.toString() }
            .distinct()
            .toList()
        if (inventoryItemIds.isEmpty()) return emptyMap()

        val tagIds = linkedSetOf<String>()
        forEach { it.collectTagIds(tagIds) }
        if (tagIds.isEmpty()) return emptyMap()

        return tagIds.associateWith { tagId ->
            val ids = tagId.parseResId()?.makeItemTagKey()?.tagItemIds ?: return@associateWith emptyList()
            inventoryItemIds.filter { it in ids }
        }
    }

    private fun RecipeTreeP.processes(): List<RecipeProcess> {
        val processes = mutableListOf<RecipeProcess>()
        root?.collectProcesses(processes)
        return processes
    }

    private fun RecipeTreeNode.collectProcesses(processes: MutableList<RecipeProcess>) {
        process?.let { processes += it }
        children.forEach { it.collectProcesses(processes) }
    }

    private fun RecipeProcess.collectTagIds(tagIds: MutableSet<String>) {
        inputs.forEach { it.collectTagIds(tagIds) }
        catalysts.forEach { it.collectTagIds(tagIds) }
        renderOnly.forEach { it.collectTagIds(tagIds) }
        shape?.key?.values?.forEach { it.collectTagIds(tagIds) }
    }

    private fun RecipeIngredient.collectTagIds(tagIds: MutableSet<String>) {
        tags.forEach { tagIds += it.tagId }
    }


    private val screenControl = McpScreenControl(McpScreenPlatform211)

    override fun screenRead(): Result<ScreenSnapshot> = screenControl.read()

    override fun screenClick(req: ScreenClickQ): Result<ScreenSnapshot> = screenControl.click(req)

    override fun screenClickAt(req: ScreenClickAtQ): Result<ScreenSnapshot> = screenControl.clickAt(req)

    override fun screenType(req: ScreenTypeQ): Result<ScreenSnapshot> = screenControl.type(req)

    override fun screenKey(req: ScreenKeyQ): Result<ScreenSnapshot> = screenControl.key(req)

    override fun screenScroll(req: ScreenScrollQ): Result<ScreenSnapshot> = screenControl.scroll(req)

    override fun runCommand(req: CommandQ): Result<CommandResult> = McpCommandControl.run(req)
}
