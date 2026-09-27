package calebxzhou.rdi.mc.server.rcmd

import calebxzau.rdi.mc.v20.server.rcmd.AbstractRcmdServerAdapter20
import calebxzhou.rdi.mc.rcmd.Rcmd
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * Forge bridge for the shared 1.20.1 rcmd/chat mixins. Command execution and player data
 * stay on the existing Forge implementations.
 */
class RcmdForgeServerAdapter(server: MinecraftServer) : AbstractRcmdServerAdapter20(server) {

    override fun dispatchCommand(source: Any, command: String): Int? {
        val normalized = command.removePrefix("/")
        if (!Rcmd.isRcmd(normalized)) {
            return null
        }
        val commandSource = source as? CommandSourceStack ?: return null
        val rcmdSource = RcmdCommandSourceStackSource(commandSource)
        val result = RcmdServerCommands.dispatcher().execute(rcmdSource, normalized)
        RcmdServerCommands.reply(rcmdSource, result)
        return if (result.success) 1 else 0
    }

    override fun dispatchPlayerRcmd(player: ServerPlayer, message: String): Boolean {
        val source = RcmdServerSource201(player)
        val result = RcmdServerCommands.dispatcher().execute(source, message)
        RcmdServerCommands.reply(source, result)
        return true
    }
}
