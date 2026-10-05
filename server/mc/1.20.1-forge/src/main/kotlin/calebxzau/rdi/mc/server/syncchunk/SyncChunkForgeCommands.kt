package calebxzau.rdi.mc.server.syncchunk

import calebxzau.rdi.mc.syncchunk.SyncChunkCommands
import calebxzau.rdi.mc.v20.server.syncchunk.SyncChunkSavedData20
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = "rdi")
object SyncChunkForgeCommands {
    @SubscribeEvent
    @JvmStatic
    fun register(event: RegisterCommandsEvent) {
        SyncChunkCommands.register(event.dispatcher, SyncChunkSavedData20::of, SyncChunkForgeNetwork::broadcast)
    }
}
