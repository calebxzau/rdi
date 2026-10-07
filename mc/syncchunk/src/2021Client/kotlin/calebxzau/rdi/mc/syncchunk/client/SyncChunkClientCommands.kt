package calebxzau.rdi.mc.syncchunk.client

import calebxzau.rdi.mc.syncchunk.SyncChunkMessages
import com.mojang.brigadier.CommandDispatcher
import net.minecraft.client.Minecraft
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component

/**
 * Client `/syncchunk show|here`. The loaders pass the other `/syncchunk` subcommands on to the server,
 * because the client tree does not know them.
 */
object SyncChunkClientCommands {
    /** [isSupported] tells whether the server on [Connection] sends sync chunk lists. */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, isSupported: (Connection) -> Boolean) {
        dispatcher.register(
            Commands.literal("syncchunk")
                .then(Commands.literal("show").executes { context -> toggleShow(context.source, isSupported) })
                .then(Commands.literal("here").executes { context -> toggleHere(context.source) })
        )
    }

    private fun toggleShow(source: CommandSourceStack, isSupported: (Connection) -> Boolean): Int {
        if (SyncChunkClientState.showSet) {
            SyncChunkClientState.showSet = false
            source.sendSuccess({ Component.literal(SyncChunkMessages.SHOW_OFF) }, false)
            return 1
        }
        val minecraft = Minecraft.getInstance()
        val connection = minecraft.connection?.connection
        if (connection == null || !isSupported(connection)) {
            source.sendFailure(Component.literal(SyncChunkMessages.SHOW_UNSUPPORTED))
            return 0
        }
        SyncChunkClientState.showSet = true
        val count = minecraft.level?.let { level ->
            SyncChunkClientState.chunksIn(connection, level.dimension().location().toString())?.size
        }
        source.sendSuccess({ Component.literal(SyncChunkMessages.showOn(count)) }, false)
        return 1
    }

    private fun toggleHere(source: CommandSourceStack): Int {
        val enabled = !SyncChunkClientState.showHere
        SyncChunkClientState.showHere = enabled
        val message = if (enabled) SyncChunkMessages.HERE_ON else SyncChunkMessages.HERE_OFF
        source.sendSuccess({ Component.literal(message) }, false)
        return 1
    }
}
