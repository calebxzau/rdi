package calebxzau.rdi.mc.client.syncchunk

import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent

@EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
object SyncChunkIntegratedCommands {
    private const val PAGE_SIZE = 10

    @SubscribeEvent
    @JvmStatic
    fun register(event: RegisterCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("dm").then(
                Commands.literal("chunk")
                    .then(Commands.literal("add").executes { context -> mutate(context.source.playerOrException, add = true) })
                    .then(Commands.literal("del").executes { context -> mutate(context.source.playerOrException, add = false) })
                        .then(Commands.literal("list").executes { context -> list(context.source.playerOrException, 1) }
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                            .executes { context -> list(context.source.playerOrException, IntegerArgumentType.getInteger(context, "page")) }))
            )
        )
    }

    private fun mutate(player: ServerPlayer, add: Boolean): Int {
        val accepted = if (add) SyncChunkService.add(player) else SyncChunkService.remove(player)
        if (accepted) player.sendSystemMessage(Component.literal("正在${if (add) "加入" else "取消"}同步区块，请稍候"))
        else player.sendSystemMessage(Component.literal("同步区块资料仍在加载中或暂时不可用"))
        return if (accepted) 1 else 0
    }

    private fun list(player: ServerPlayer, page: Int): Int {
        val entries = SyncChunkService.entries(player.server).sortedWith(compareBy({ it.key.dimensionId }, { it.key.chunkX }, { it.key.chunkZ }, { it.ownerId.toString() }))
        val limits = SyncChunkService.limits(player.server)
            ?: return 0.also { player.sendSystemMessage(Component.literal("同步区块资料仍在加载中或暂时不可用")) }
        val pages = maxOf(1, (entries.size + PAGE_SIZE - 1) / PAGE_SIZE)
        if (page > pages) {
            player.sendSystemMessage(Component.literal("页码超出范围，有效页码：1-$pages"))
            return 0
        }
        val from = (page - 1) * PAGE_SIZE
        player.sendSystemMessage(Component.literal("同步区块：${entries.size}/${limits.maxTotal}，第${page}/${pages}页"))
        entries.drop(from).take(PAGE_SIZE).forEach { entry ->
            val owner = player.server.playerList.getPlayer(entry.ownerId)?.gameProfile?.name ?: entry.ownerId.toString()
            player.sendSystemMessage(Component.literal("${entry.key.dimensionId} ${entry.key.chunkX},${entry.key.chunkZ}（$owner）"))
        }
        return 1
    }
}
