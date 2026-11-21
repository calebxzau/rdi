package calebxzau.rdi.mc.v20.fabric.network

import calebxzhou.rdi.mc.client.network.RClientIdle
import calebxzau.rdi.mc.v20.client.network.ClientPersistentPackets20
import calebxzau.rdi.mc.v20.protocol.PacketExtensions20
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import io.netty.buffer.Unpooled
import io.netty.util.AttributeKey
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object FabricPacketExtensionsClient {
    private val idleChannel = ResourceLocation("rdi:idle")
    private val streamAccepted = AttributeKey.valueOf<Boolean>("rdi.fabric.zstd-stream-accepted")

    fun register() {
        ClientLoginNetworking.registerGlobalReceiver(PacketExtensions20.channel) { _, handler, buffer, _ ->
            val connection = handler.connection
            val channel = connection.channel
            val offer = PacketExtensions20.decode(buffer)
            if (!PacketExtensions20.supportsStream(offer)) {
                connection.disconnect(Component.literal("此房间不支持流式Zstd压缩，请联系房主更新房间核心。"))
                CompletableFuture.completedFuture(null)
            } else if (!ZstdCompressionPipeline.isInboundBatchingAvailable(channel)) {
                connection.disconnect(Component.literal("客户端无法准备流式Zstd解码器，请检查客户端的网络Mod。"))
                CompletableFuture.completedFuture(null)
            } else {
                // The pinned Fabric Networking API sends the compression setup packet before its login queries.
                // Complete the reply only after the actual decoder has been prepared on the Netty event loop.
                ZstdInboundPreparation.armInbound(channel, offer).thenApply { ready ->
                    if (!ready) {
                        connection.disconnect(Component.literal("客户端无法启动流式Zstd压缩。"))
                        return@thenApply null
                    }
                    channel.attr(streamAccepted).set(true)
                    PacketExtensions20.encode(offer)
                }
            }
        }
        ClientLoginNetworking.registerGlobalReceiver(ResourceLocation(PersistentPacketQuery.CHANNEL)) { _, handler, buffer, _ ->
            ClientPersistentPackets20.prepareReply(handler.connection.channel, buffer).thenApply { body ->
                body?.let { FriendlyByteBuf(Unpooled.wrappedBuffer(it)) }
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            RClientIdle.onClientTick(client, ClientPlayNetworking.canSend(idleChannel)) { away ->
                val report = FriendlyByteBuf(Unpooled.buffer(1)).apply { writeBoolean(away) }
                ClientPlayNetworking.send(idleChannel, report)
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { listener, client ->
            client.execute {
                val current = client.connection
                if (current == null || current === listener) RClientIdle.reset()
            }
        }
    }

    @JvmStatic
    fun requireStream(connection: Connection): Boolean {
        if (connection.isMemoryConnection) return true
        val channel = connection.channel
        if (channel.attr(streamAccepted).get() != true) {
            connection.disconnect(Component.literal("此房间未协商流式Zstd压缩，请联系房主更新房间核心。"))
            return false
        }
        // Earlier stream-capable room cores send STREAM_START just after the transition to Play.
        if (!ZstdCompressionPipeline.isInboundStreamActive(channel)) {
            channel.eventLoop().schedule({
                if (channel.isActive && !ZstdCompressionPipeline.isInboundStreamActive(channel)) {
                    connection.disconnect(Component.literal("此房间未启动流式Zstd压缩，连接已断开。"))
                }
            }, 10, TimeUnit.SECONDS)
        }
        return true
    }
}
