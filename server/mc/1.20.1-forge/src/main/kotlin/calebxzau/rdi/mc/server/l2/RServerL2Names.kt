package calebxzau.rdi.mc.server.l2

import calebxzau.rdi.mc.v20.forge.l2.L2NameChannel
import calebxzau.rdi.mc.v20.l2.AttributeNames
import calebxzau.rdi.mc.v20.l2.L2NamePayload
import calebxzau.rdi.mc.v20.l2.L2NameWindow
import io.netty.buffer.Unpooled
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.Connection
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.PacketSendListener
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.apache.logging.log4j.LogManager
import java.util.IdentityHashMap

/** Self names only; all state and snapshots belong to the server thread. */
@Mod.EventBusSubscriber(modid = "rdi")
object RServerL2Names {
    private val logger = LogManager.getLogger("rdi.l2-names")
    private val states = IdentityHashMap<Connection, State>()
    private val payloadChannel = ResourceLocation.fromNamespaceAndPath("l2tabs", "main")

    // Resolve only after Forge has loaded the optional mod's configuration. No packaged L2 dependency.
    private val selfSyncConfig: ForgeConfigSpec.BooleanValue by lazy {
        val config = Class.forName("dev.xkmc.l2tabs.init.data.L2TabsConfig").getField("COMMON").get(null)
        config.javaClass.getField("syncPlayerAttributeName").get(config) as ForgeConfigSpec.BooleanValue
    }

    private class State(val player: ServerPlayer, val level: ServerLevel) {
        val window = L2NameWindow()
        var observations = 0L
        var suppressed = 0L
        var sent = 0L
    }

    /** Called inside L2's deferred self-send, before its serializer; tracking sends stay stock. */
    @JvmStatic
    fun suppressLegacy(player: ServerPlayer): Boolean {
        if (!L2NameChannel.isSupported(player.connection.connection)) return false
        check(player.server.isSameThread) { "L2 self updates must run on the server thread" }
        // Also discard an already queued self-send for a player instance replaced by respawn/logout.
        if (player.server.playerList.getPlayer(player.uuid) !== player) return true
        if (!selfSyncConfig.get()) return false
        states[player.connection.connection]?.suppressed =
            (states[player.connection.connection]?.suppressed ?: 0L) + 1L
        return true
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    @JvmStatic
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || !L2NameChannel.isLocalSupported()) return
        val now = System.nanoTime()
        // L2's NORMAL-priority END tasks have completed. Each eligible self-send was intercepted,
        // so it cannot later overwrite a baseline or a coalesced name update.
        for (player in event.server.playerList.players) {
            val connection = player.connection.connection
            if (!connection.isConnected || !L2NameChannel.isSupported(connection) || !selfSyncConfig.get()) continue
            val names = snapshot(player)
            var state = states[connection]
            if (state == null || state.player !== player || state.level !== player.serverLevel()) {
                state = State(player, player.serverLevel())
                states[connection] = state
                send(player, names)
                state.window.reset(names)
                state.sent++
                logger.debug("Initialized L2 self-name cache for {}", player.gameProfile.name)
            } else {
                state.observations++
                state.window.offer(names, now)
                state.window.due(now)?.let { changed ->
                    send(player, changed)
                    state.sent++
                }
            }
        }
    }

    /** Full self snapshots also notice attributes losing their final modifier (stock L2 omits those). */
    internal fun snapshot(player: ServerPlayer): AttributeNames = player.attributes.syncableAttributes.associate { attribute ->
        BuiltInRegistries.ATTRIBUTE.getKey(attribute.attribute).toString() to
            attribute.modifiers.associate { modifier -> modifier.id to modifier.name }
    }

    private fun send(player: ServerPlayer, names: AttributeNames) {
        val data = FriendlyByteBuf(Unpooled.wrappedBuffer(L2NamePayload.encode(player.id, names)))
        try {
            player.connection.send(ClientboundCustomPayloadPacket(payloadChannel, data), PacketSendListener.thenRun {
                data.release()
            })
        } catch (error: Throwable) {
            if (data.refCnt() > 0) data.release()
            throw error
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        states.remove(player.connection.connection)?.let {
            logger.info("L2 names for {}: snapshots={}, suppressed={}, sent={}",
                player.gameProfile.name, it.observations, it.suppressed, it.sent)
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun onRespawn(event: PlayerEvent.PlayerRespawnEvent) = reset(event.entity as? ServerPlayer)

    @SubscribeEvent
    @JvmStatic
    fun onChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) = reset(event.entity as? ServerPlayer)

    private fun reset(player: ServerPlayer?) {
        if (player != null) states.remove(player.connection.connection)
    }

    @SubscribeEvent
    @JvmStatic
    fun onStopped(event: ServerStoppedEvent) {
        states.clear()
    }
}
