package calebxzau.rdi.mc.server.pvp

import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = "rdi")
object ForcePvpCommands {
    @SubscribeEvent
    @JvmStatic
    fun register(event: RegisterCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("forcenopvp")
                .requires { it.hasPermission(2) }
                .executes { context ->
                    val source = context.source
                    setProtection(source, !ForcePvpSavedData.get(source.server).protectionEnabled)
                }
                .then(Commands.literal("on").executes { setProtection(it.source, true) })
                .then(Commands.literal("off").executes { setProtection(it.source, false) })
                .then(Commands.literal("status").executes { context ->
                    val source = context.source
                    source.sendSuccess({ statusMessage(ForcePvpSavedData.get(source.server).protectionEnabled) }, false)
                    1
                })
        )
    }

    private fun setProtection(source: CommandSourceStack, enabled: Boolean): Int {
        val data = ForcePvpSavedData.get(source.server)
        val changed = data.protectionEnabled != enabled
        data.setProtection(enabled)
        val message = statusMessage(enabled)
        source.sendSuccess({ message }, true)
        if (changed) {
            source.server.playerList.broadcastSystemMessage(message, false)
        }
        return 1
    }

    private fun statusMessage(enabled: Boolean): Component = Component.literal(
        if (enabled) {
            "全房间PvP保护已开启：禁止玩家互相攻击和右键交互"
        } else {
            "全房间PvP保护已关闭：恢复原有PvP规则"
        }
    )
}
