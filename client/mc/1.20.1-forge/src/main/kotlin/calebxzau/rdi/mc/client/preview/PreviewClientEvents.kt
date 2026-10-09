package calebxzau.rdi.mc.client.preview

import net.minecraft.commands.Commands
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RegisterClientCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = "rdi", value = [Dist.CLIENT])
object PreviewClientEvents {
    @JvmStatic
    @SubscribeEvent
    fun registerCommands(event: RegisterClientCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("rdi").then(
                Commands.literal("preview").then(
                    Commands.literal("export").executes {
                        ItemPreviewExporter.forceExport()
                        1
                    }
                )
            )
        )
    }

    @JvmStatic
    @SubscribeEvent
    fun renderFrame(event: TickEvent.RenderTickEvent) {
        if (event.phase == TickEvent.Phase.END) ItemPreviewExporter.advance()
    }

    @JvmStatic
    @SubscribeEvent
    fun enteredWorld(event: ClientPlayerNetworkEvent.LoggingIn) {
        ItemPreviewExporter.onWorldEntered()
    }

    @JvmStatic
    @SubscribeEvent
    fun leftWorld(event: ClientPlayerNetworkEvent.LoggingOut) {
        ItemPreviewExporter.onWorldLeft()
    }
}
