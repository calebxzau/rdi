package calebxzau.rdi.mc.syncchunk

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Server `/syncchunk add|del|list`; each loader only hooks [register] into its command event. */
object SyncChunkCommands {
    /**
     * [storeOf] finds the current server's selections. [onChanged] runs on the server thread after a
     * selection was actually added or removed.
     */
    fun register(
        dispatcher: CommandDispatcher<CommandSourceStack>,
        storeOf: (MinecraftServer) -> Result<SyncChunkStore>,
        onChanged: (MinecraftServer) -> Unit = {},
    ) {
        val commands = Handlers(storeOf, onChanged)
        dispatcher.register(
            Commands.literal("syncchunk")
                .then(Commands.literal("add").executes { context -> commands.add(context.source) })
                .then(
                    Commands.literal("del")
                        .executes { context -> commands.removeCurrent(context.source) }
                        .then(
                            Commands.argument("dimension", ResourceLocationArgument.id())
                                .then(
                                    Commands.argument("chunkX", IntegerArgumentType.integer())
                                        .then(
                                            Commands.argument("chunkZ", IntegerArgumentType.integer())
                                                .executes { context ->
                                                    commands.remove(
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
                        .executes { context -> commands.list(context.source, 1) }
                        .then(
                            Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes { context ->
                                    commands.list(context.source, IntegerArgumentType.getInteger(context, "page"))
                                }
                        )
                )
        )
    }

    private class Handlers(
        private val storeOf: (MinecraftServer) -> Result<SyncChunkStore>,
        private val onChanged: (MinecraftServer) -> Unit,
    ) {
        fun add(source: CommandSourceStack): Int {
            val player = source.entity as? ServerPlayer ?: return fail(source, SyncChunkMessages.PLAYER_ONLY)
            val store = storeOf(player.server).getOrElse { return fail(source, SyncChunkMessages.UNAVAILABLE) }
            val key = currentKey(player)
            val result = store.add(key, player.uuid)
            if (result == SyncChunkAddResult.Added) onChanged(player.server)
            return reply(source, SyncChunkMessages.added(result, key))
        }

        fun removeCurrent(source: CommandSourceStack): Int {
            val player = source.entity as? ServerPlayer ?: return fail(source, SyncChunkMessages.PLAYER_ONLY)
            return remove(source, currentKey(player))
        }

        fun remove(source: CommandSourceStack, key: SyncChunkKey): Int {
            val player = source.entity as? ServerPlayer ?: return fail(source, SyncChunkMessages.PLAYER_ONLY)
            val store = storeOf(player.server).getOrElse { return fail(source, SyncChunkMessages.UNAVAILABLE) }
            val result = store.remove(key, player.uuid)
            if (result == SyncChunkRemoveResult.Removed) onChanged(player.server)
            return reply(source, SyncChunkMessages.removed(result, key))
        }

        fun list(source: CommandSourceStack, page: Int): Int {
            val player = source.entity as? ServerPlayer ?: return fail(source, SyncChunkMessages.PLAYER_ONLY)
            val store = storeOf(player.server).getOrElse { return fail(source, SyncChunkMessages.UNAVAILABLE) }
            val keys = store.snapshot().map { it.key }
            val pageCount = SyncChunkMessages.pageCount(keys.size)
            if (page > pageCount) return fail(source, SyncChunkMessages.invalidPage(pageCount))
            return reply(source, SyncChunkMessages.Reply(true, SyncChunkMessages.listPage(keys, page)))
        }

        private fun currentKey(player: ServerPlayer): SyncChunkKey {
            val chunk = player.chunkPosition()
            return SyncChunkKey(player.serverLevel().dimension().location().toString(), chunk.x, chunk.z)
        }

        private fun reply(source: CommandSourceStack, reply: SyncChunkMessages.Reply): Int {
            if (!reply.success) return fail(source, reply.text)
            source.sendSuccess({ Component.literal(reply.text) }, false)
            return 1
        }

        private fun fail(source: CommandSourceStack, message: String): Int {
            source.sendFailure(Component.literal(message))
            return 0
        }
    }
}
