package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.v20.server.network.ServerPersistentPackets20
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import io.netty.buffer.Unpooled
import io.netty.util.AttributeKey
import net.minecraft.network.Connection
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent
import org.apache.logging.log4j.LogManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Forge side of persistent packets: during login negotiation the server asks the client which recipe
 * and tag tables it kept, so the codec can name those instead of sending them again.
 *
 * On by default; `-Drdi.pcache.enabled=false` stops asking new connections, which then receive plain
 * packets. A client without the extension answers without a body and keeps plain packets as well.
 */
object RServerPersistentPackets {
    private val logger = LogManager.getLogger("rdi")
    private val queryChannel = ResourceLocation.fromNamespaceAndPath(PersistentPacketQuery.CHANNEL_NAMESPACE, PersistentPacketQuery.CHANNEL_PATH)
    private val pendingKey = AttributeKey.valueOf<CompletableFuture<Void>>("rdi.pcache.pending")
    private const val ANSWER_TIMEOUT_SECONDS = 10L

    /** Sends the query; login proceeds after the answer, or after a timeout with plain packets. */
    fun onNegotiation(event: PlayerNegotiationEvent) {
        val connection = event.connection
        if (!ServerPersistentPackets20.isEnabled() || connection.isMemoryConnection) return
        val name = event.profile?.name
        val pending = CompletableFuture<Void>()
        connection.channel().attr(pendingKey).set(pending)
        CompletableFuture.delayedExecutor(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute {
            if (pending.complete(null)) {
                connection.channel().attr(pendingKey).compareAndSet(pending, null)
                logger.warn("Player {} did not answer the persistent packet query in {}s; keeping plain join packets", name, ANSWER_TIMEOUT_SECONDS)
            }
        }
        event.enqueueWork(pending)
        val body = FriendlyByteBuf(Unpooled.wrappedBuffer(PersistentPacketQuery.encodeQuery()))
        connection.send(ClientboundCustomQueryPacket(PersistentPacketQuery.TRANSACTION_ID, queryChannel, body))
    }

    /** True when [packet] answered the query; the login listener must then ignore it. */
    @JvmStatic
    fun onQueryAnswer(connection: Connection, packet: ServerboundCustomQueryPacket, name: String): Boolean {
        if (packet.transactionId != PersistentPacketQuery.TRANSACTION_ID) return false
        val pending = connection.channel().attr(pendingKey).getAndSet(null)
        if (pending == null) {
            logger.warn("Ignoring a late or unexpected persistent packet answer from {}", name)
            return true
        }
        try {
            ServerPersistentPackets20.accept(connection.channel(), packet.data, name)
        } finally {
            pending.complete(null)
        }
        return true
    }
}
