package calebxzau.rdi.mc.v20.fabric.network

import calebxzhou.rdi.mc.server.network.RServerIdle
import calebxzau.rdi.mc.v20.protocol.PacketExtensions20
import calebxzau.rdi.mc.v20.server.network.ServerPacketExtensions20
import calebxzau.rdi.mc.v20.server.network.ServerPersistentPackets20
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import io.netty.buffer.Unpooled
import io.netty.util.AttributeKey
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object FabricPacketExtensionsServer {
    private val logger = LoggerFactory.getLogger(FabricPacketExtensionsServer::class.java)
    private val negotiationKey = AttributeKey.valueOf<Negotiation>("rdi.fabric.packet-extensions")
    private val persistentChannel = ResourceLocation(PersistentPacketQuery.CHANNEL)
    private val idleChannel = ResourceLocation("rdi:idle")
    private val persistentPendingKey = AttributeKey.valueOf<CompletableFuture<Void>>("rdi.fabric.pcache")

    private class Negotiation(val offer: Int) {
        val accepted = CompletableFuture<Int>()
    }

    fun register() {
        ServerLoginConnectionEvents.QUERY_START.register { handler, server, sender, synchronizer ->
            val connection = handler.connection
            val offer = ServerPacketExtensions20.offeredExtensions()
            if (connection.isMemoryConnection || server.compressionThreshold < 0 || !PacketExtensions20.supportsStream(offer)) {
                handler.disconnect(Component.literal("此房间未启用流式Zstd压缩，请联系房主检查房间配置。"))
                return@register
            }
            val channel = connection.channel
            if (ServerPersistentPackets20.isEnabled()) {
                val pending = CompletableFuture<Void>()
                channel.attr(persistentPendingKey).set(pending)
                synchronizer.waitFor(pending)
                sender.sendPacket(persistentChannel, FriendlyByteBuf(Unpooled.wrappedBuffer(PersistentPacketQuery.encodeQuery())))
            }
            val negotiation = Negotiation(offer)
            channel.attr(negotiationKey).set(negotiation)
            // The Fabric addon removes the query ID before invoking its receiver. Hold the login
            // until the receiver has published the accepted mask, even if a server tick runs then.
            synchronizer.waitFor(negotiation.accepted)
            val timeout = channel.eventLoop().schedule({
                if (channel.isActive && !negotiation.accepted.isDone) {
                    server.execute {
                        if (channel.isActive && !negotiation.accepted.isDone) {
                            handler.disconnect(Component.literal("流式Zstd压缩协商超时，请更新RDI客户端后重试。"))
                        }
                    }
                }
            }, 10, TimeUnit.SECONDS)
            negotiation.accepted.whenComplete { _, _ -> timeout.cancel(false) }
            sender.sendPacket(PacketExtensions20.channel, PacketExtensions20.encode(offer))
        }
        ServerLoginNetworking.registerGlobalReceiver(PacketExtensions20.channel) { server, handler, understood, buffer, _, _ ->
            val channel = handler.connection.channel
            val negotiation = channel.attr(negotiationKey).get() ?: return@registerGlobalReceiver
            val response = if (understood) PacketExtensions20.decode(buffer) else 0
            val accepted = PacketExtensions20.accepted(negotiation.offer, response)
            if (!PacketExtensions20.supportsStream(accepted)) {
                server.execute {
                    handler.disconnect(Component.literal("此房间要求流式Zstd压缩，请更新RDI客户端后重试。"))
                }
                return@registerGlobalReceiver
            }
            // Start the stream before releasing login. The client checks for STREAM_START before entering Play.
            ServerPacketExtensions20.startStream(channel, handler.userName, remotePresent = true)
            if (!ZstdCompressionPipeline.isOutboundStreamEnabled(channel)) {
                server.execute {
                    handler.disconnect(Component.literal("房间无法启动流式Zstd压缩，请联系房主。"))
                }
                return@registerGlobalReceiver
            }
            negotiation.accepted.complete(accepted)
        }
        ServerLoginNetworking.registerGlobalReceiver(persistentChannel) { _, handler, understood, buffer, _, _ ->
            val channel = handler.connection.channel
            val pending = channel.attr(persistentPendingKey).getAndSet(null) ?: return@registerGlobalReceiver
            try {
                if (ZstdCompressionPipeline.hasOutboundEncoder(channel)) {
                    ServerPersistentPackets20.accept(channel, buffer.takeIf { understood }, handler.userName)
                }
            } finally {
                pending.complete(null)
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(idleChannel) { server, player, _, buffer, _ ->
            if (buffer.readableBytes() != 1) return@registerGlobalReceiver
            val away = when (buffer.readUnsignedByte().toInt()) {
                0 -> false
                1 -> true
                else -> return@registerGlobalReceiver
            }
            server.execute {
                if (server.playerList.getPlayer(player.uuid) === player) RServerIdle.onReport(player, away)
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            val player = handler.player
            server.execute {
                val current = server.playerList.getPlayer(player.uuid)
                if (current == null || current === player) RServerIdle.onLogout(player)
            }
        }
    }

    fun onJoin(player: ServerPlayer) {
        val channel = player.connection.connection.channel
        val accepted = channel.attr(negotiationKey).get()?.accepted?.getNow(0) ?: 0
        val name = player.gameProfile.name
        channel.eventLoop().execute {
            if (!channel.isActive) return@execute
            if (!PacketExtensions20.supportsStream(accepted) || !ZstdCompressionPipeline.isOutboundStreamEnabled(channel)) {
                logger.warn("Player {} cannot activate packet extensions; compression handlers: {}",
                    name, ZstdCompressionPipeline.describeHandlers(channel))
                player.connection.disconnect(Component.literal("房间的流式Zstd压缩未启动，连接已断开。"))
                return@execute
            }
            if (accepted and ZstdInboundPreparation.BATCH != 0) {
                ServerPacketExtensions20.startBatching(channel, name, emptySet())
            }
            if (accepted and ZstdInboundPreparation.REFERENCES != 0) {
                ServerPacketExtensions20.startReferences(channel, name, remotePresent = true)
            }
        }
    }

    fun flushAtTickEnd(server: MinecraftServer) {
        val settings = ServerPacketExtensions20.batchSettings()
        for (player in server.playerList.players) {
            val channel = player.connection.connection.channel
            val accepted = channel.attr(negotiationKey).get()?.accepted?.getNow(0) ?: 0
            if (accepted and ZstdInboundPreparation.BATCH != 0) {
                ServerPacketExtensions20.flushBatching(channel, settings, emptySet())
            }
        }
    }
}
