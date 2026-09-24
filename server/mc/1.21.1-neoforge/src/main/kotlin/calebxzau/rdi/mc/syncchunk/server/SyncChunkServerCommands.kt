package calebxzau.rdi.mc.syncchunk.server

import calebxzau.rdi.mc.syncchunk.SyncChunkAddResult
import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.SyncChunkRemoveResult
import calebxzau.rdi.mc.syncchunk.SyncChunkState
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.Commands
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent

@EventBusSubscriber(modid = "rdi")
object SyncChunkServerCommands {
    private const val PAGE_SIZE = 10
    private const val UNAVAILABLE_MESSAGE = "同步区块功能当前不可用，请联系服务器管理员"

    @JvmStatic
    @SubscribeEvent
    fun register(event: RegisterCommandsEvent) {
        register(event.dispatcher)
    }

    internal fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("syncchunk")
                .then(Commands.literal("add").executes { context -> add(context.source) })
                .then(
                    Commands.literal("del")
                        .executes { context -> removeCurrent(context.source) }
                        .then(
                            Commands.argument("dimension", ResourceLocationArgument.id())
                                .then(
                                    Commands.argument("chunkX", IntegerArgumentType.integer())
                                        .then(
                                            Commands.argument("chunkZ", IntegerArgumentType.integer())
                                                .executes { context ->
                                                    removeAt(
                                                        context.source,
                                                        SyncChunkKey(
                                                            ResourceLocationArgument.getId(context, "dimension").toString(),
                                                            IntegerArgumentType.getInteger(context, "chunkX"),
                                                            IntegerArgumentType.getInteger(context, "chunkZ"),
                                                        ),
                                                    )
                                                }
                                        )
                                )
                        )
                )
                .then(
                    Commands.literal("list")
                        .executes { context -> list(context.source, 1) }
                        .then(
                            Commands.argument("page", IntegerArgumentType.integer())
                                .executes { context ->
                                    list(
                                        context.source,
                                        IntegerArgumentType.getInteger(context, "page"),
                                    )
                                }
                        )
                )
        )
    }

    private fun add(source: CommandSourceStack): Int {
        val player = source.entity as? ServerPlayer ?: return error(source, "此命令只能由玩家执行")
        return SyncChunkService.add(player).fold(
            onSuccess = { result ->
                val message = when (result) {
                    SyncChunkAddResult.Added -> "已加入同步区块：${currentChunkLabel(player)}"
                    SyncChunkAddResult.AlreadyPresent -> "这个区块已经在同步列表中"
                    SyncChunkAddResult.OccupiedByOther -> "这个区块已由其他玩家设为同步区块"
                    SyncChunkAddResult.QuotaReached -> "同步区块数量已达到全服上限${SyncChunkState.DEFAULT_MAX_TOTAL}个"
                }
                player.sendSystemMessage(Component.literal(message))
                if (result == SyncChunkAddResult.Added || result == SyncChunkAddResult.AlreadyPresent) 1 else 0
            },
            onFailure = { error(source, UNAVAILABLE_MESSAGE) },
        )
    }

    private fun removeCurrent(source: CommandSourceStack): Int {
        val player = source.entity as? ServerPlayer ?: return error(source, "此命令只能由玩家执行")
        val chunk = player.chunkPosition()
        return remove(source, player, SyncChunkKey(player.level().dimension().location().toString(), chunk.x, chunk.z))
    }

    private fun removeAt(source: CommandSourceStack, key: SyncChunkKey): Int {
        val player = source.entity as? ServerPlayer ?: return error(source, "此命令只能由玩家执行")
        return remove(source, player, key)
    }

    private fun remove(source: CommandSourceStack, player: ServerPlayer, key: SyncChunkKey): Int = SyncChunkService.remove(player, key).fold(
        onSuccess = { result ->
            val message = when (result) {
                SyncChunkRemoveResult.Removed -> "已取消同步区块：${key.dimensionId},${key.chunkX},${key.chunkZ}"
                SyncChunkRemoveResult.AlreadyAbsent -> "这个区块不在同步列表中"
                SyncChunkRemoveResult.OwnedByOther -> "这个区块由其他玩家设置，只有设置者可以取消"
            }
            player.sendSystemMessage(Component.literal(message))
            if (result == SyncChunkRemoveResult.Removed || result == SyncChunkRemoveResult.AlreadyAbsent) 1 else 0
        },
        onFailure = { error(source, UNAVAILABLE_MESSAGE) },
    )

    private fun list(source: CommandSourceStack, page: Int): Int {
        val player = source.entity as? ServerPlayer ?: return error(source, "此命令只能由玩家执行")
        if (page < 1) {
            player.sendSystemMessage(Component.literal("页码必须大于0"))
            return 0
        }
        val entries = SyncChunkService.snapshot(player.server).getOrElse {
            return error(source, UNAVAILABLE_MESSAGE)
        }
        val pageCount = maxOf(1, (entries.size + PAGE_SIZE - 1) / PAGE_SIZE)
        if (page > pageCount) {
            player.sendSystemMessage(Component.literal("页码无效，可查看第1至${pageCount}页"))
            return 0
        }
        if (entries.isEmpty()) {
            player.sendSystemMessage(Component.literal("当前没有同步区块（0/${SyncChunkState.DEFAULT_MAX_TOTAL}）"))
            return 1
        }
        val start = (page - 1) * PAGE_SIZE
        val pageEntries = entries.subList(start, minOf(start + PAGE_SIZE, entries.size))
        val lines = buildList {
            add("同步区块列表：${entries.size}/${SyncChunkState.DEFAULT_MAX_TOTAL}（第${page}/${pageCount}页）")
            pageEntries.forEachIndexed { index, entry ->
                add("${start + index + 1}. ${entry.key.dimensionId},${entry.key.chunkX},${entry.key.chunkZ}（${entry.ownerId}）")
            }
        }
        player.sendSystemMessage(Component.literal(lines.joinToString("\n")))
        return 1
    }

    private fun error(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }

    private fun currentChunkLabel(player: ServerPlayer): String {
        val chunk = player.chunkPosition()
        return "${player.level().dimension().location()},${chunk.x},${chunk.z}"
    }
}
