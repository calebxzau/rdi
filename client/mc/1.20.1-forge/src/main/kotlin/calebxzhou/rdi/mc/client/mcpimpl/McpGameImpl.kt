package calebxzhou.rdi.mc.client.mcpimpl

import calebxzau.mc.common2021.mc
import calebxzau.rdi.mc.v20.client.mcp.McpGame20
import calebxzau.mc.common2021.makeItemTagKey
import calebxzau.mc.common2021.parseResId
import calebxzau.mc.common2021.resId
import calebxzau.mc.common2021.tagItemIds
import calebxzhou.rdi.mc.common2.mcp.McpBadRequestError
import calebxzhou.rdi.mc.common2.mcp.McpError
import calebxzhou.rdi.mc.common2.mcp.model.*
import net.minecraftforge.fml.ModList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object McpGameImpl : McpGame20() {
    private const val RESPONSE_TIMEOUT_SECONDS = 30L

    private val pendingPacketMap = ConcurrentHashMap<String, CompletableFuture<McpS2CNetPacket>>()

    override fun send(packet: McpC2SNetPacket): Result<String> {
        return runCatching {
            val future = CompletableFuture<McpS2CNetPacket>()
            pendingPacketMap[packet.reqId] = future
            mc.execute {
                try {
                    McpNetwork.sendToServer(packet)
                } catch (e: Throwable) {
                    pendingPacketMap.remove(packet.reqId)?.completeExceptionally(e)
                }
            }
            future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS).text
        }.recoverCatching { e ->
            throw when (e) {
                is TimeoutException -> McpError("server time out")
                is ExecutionException -> McpError(e.cause?.message ?: "server execution error")
                else -> McpError(e.message ?: "server unavailable")
            }
        }.also {
            pendingPacketMap.remove(packet.reqId)
        }
    }

    override fun blockFind(req: BlockFindQ): Result<BlockFindP> = BlockMcpImpl.find(req)

    override fun blockFetchBox(req: BlockFetchBoxQ): Result<BlockFetchBoxP> = BlockMcpImpl.fetchBox(req)

    override fun recipes(req: RecipeQ): Result<String> = runCatching {
        if (!RecipeProcessIndex.isReady()) {
            return@runCatching "unresolved reason=jei_not_ready"
        }
        val recipesByItem = req.items.associateWith { RecipeProcessIndex.recipesByOutputItem(it) }
        val processes = recipesByItem.values.flatten()
        RecipeProcessTextView.render(req.items, recipesByItem, processes.tagInventoryMatches())
    }

    override fun recipeTree(req: RecipeTreeQ): Result<String> = runCatching {
        "unsupported reason=not_implemented_1_20"
    }

    override fun modIds(): Result<String> = runCatching {
        ModList.get().mods.joinToString(" ") { it.modId }
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
                    type = if (dep.isMandatory) "mandatory" else "optional",
                    ordering = dep.ordering.name.lowercase(),
                    side = dep.side.name.lowercase(),
                )
            },
        )
    }

    override fun questChapterList(): Result<String> = runCatching {
        "unsupported reason=not_implemented_1_20"
    }

    override fun questsOfChapter(chapterId: String): Result<String> = runCatching {
        "unsupported reason=not_implemented_1_20"
    }

    fun complete(packet: McpS2CNetPacket) {
        pendingPacketMap.remove(packet.reqId)?.complete(packet)
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

    private fun RecipeProcess.collectTagIds(tagIds: MutableSet<String>) {
        inputs.forEach { it.collectTagIds(tagIds) }
        catalysts.forEach { it.collectTagIds(tagIds) }
        renderOnly.forEach { it.collectTagIds(tagIds) }
        shape?.key?.values?.forEach { it.collectTagIds(tagIds) }
    }

    private fun RecipeIngredient.collectTagIds(tagIds: MutableSet<String>) {
        tags.forEach { tagIds += it.tagId }
    }
}
