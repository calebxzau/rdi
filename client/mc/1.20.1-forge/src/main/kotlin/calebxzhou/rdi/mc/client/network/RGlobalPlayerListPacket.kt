package calebxzhou.rdi.mc.client.network

import calebxzau.rdi.mc.v20.client.GlobalPlayerListParser
import calebxzau.rdi.mc.v20.client.GlobalPlayerListState as SharedGlobalPlayerListState
import net.minecraft.network.FriendlyByteBuf
import net.minecraftforge.network.NetworkEvent
import org.apache.logging.log4j.LogManager
import java.util.function.Supplier

@JvmRecord
data class RGlobalPlayerListPacket(val json: String) {
    companion object {
        private const val MAX_JSON_LENGTH = 262144
        private val LOGGER = LogManager.getLogger("RDI Global Player List")

        fun encode(packet: RGlobalPlayerListPacket, buf: FriendlyByteBuf) {
            buf.writeUtf(packet.json, MAX_JSON_LENGTH)
        }

        fun decode(buf: FriendlyByteBuf) = RGlobalPlayerListPacket(buf.readUtf(MAX_JSON_LENGTH))

        fun handle(packet: RGlobalPlayerListPacket, contextSupplier: Supplier<NetworkEvent.Context>) {
            val context = contextSupplier.get()
            val connection = context.networkManager
            val generation = SharedGlobalPlayerListState.generationFor(connection)
            if (generation == null) {
                context.packetHandled = true
                return
            }
            GlobalPlayerListParser.parse(packet.json)
                .onSuccess { playerList ->
                    context.enqueueWork { SharedGlobalPlayerListState.update(connection, generation, playerList) }
                }
                .onFailure { LOGGER.error("Rejected invalid RDI room player list", it) }
            context.packetHandled = true
        }
    }
}
