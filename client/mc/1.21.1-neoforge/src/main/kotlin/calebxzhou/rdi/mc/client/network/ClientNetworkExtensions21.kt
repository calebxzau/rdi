package calebxzhou.rdi.mc.client.network

import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import calebxzau.rdi.mc.zstdcodec.v21.RdiExtensionChannels21
import net.minecraft.network.Connection
import java.util.concurrent.TimeUnit

object ClientNetworkExtensions21 {
    /** Called on the main thread immediately before the vanilla Configuration acknowledgement. */
    @JvmStatic
    fun prepare(connection: Connection): Unit {
        if (connection.isMemoryConnection) return
        val channel = connection.channel()
        val future = ZstdInboundPreparation.armInbound(channel, RdiExtensionChannels21.inboundExtensions(connection))
        try {
            // armInbound executes inline on the event loop; never wait there on a queued task.
            check(!channel.eventLoop().inEventLoop() || future.isDone)
            future.get(5, TimeUnit.SECONDS)
        } catch (error: Exception) {
            ZstdInboundPreparation.abort(channel, error)
            future.cancel(false)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw IllegalStateException("Unable to prepare negotiated RDI decoder", error)
        }
    }
}
