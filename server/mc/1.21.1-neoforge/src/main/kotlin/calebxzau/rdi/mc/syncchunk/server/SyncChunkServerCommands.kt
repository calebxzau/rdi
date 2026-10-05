package calebxzau.rdi.mc.syncchunk.server

import calebxzau.rdi.mc.syncchunk.SyncChunkCommands
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent

@EventBusSubscriber(modid = "rdi")
object SyncChunkServerCommands {
    @JvmStatic
    @SubscribeEvent
    fun register(event: RegisterCommandsEvent) {
        SyncChunkCommands.register(event.dispatcher, SyncChunkService::store, SyncChunkService::broadcast)
    }
}
