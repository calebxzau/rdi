package calebxzau.rdi.mc.client.l2

import calebxzau.rdi.mc.v20.forge.l2.L2NameChannel
import calebxzau.rdi.mc.v20.l2.L2NamePayload
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.ai.attributes.AttributeInstance
import net.minecraft.world.entity.player.Player
import calebxzau.rdi.mc.client.l2.mixin.AAttributeModifierName
import java.util.UUID

/** Partial updates replace each mentioned attribute while leaving other attributes intact. */
internal class L2AttributeNameCache {
    private val namesByAttribute = LinkedHashMap<String, Map<UUID, String>>()

    fun replaceMentioned(names: Map<String, Map<UUID, String>>) {
        names.forEach { (attribute, modifiers) ->
            namesByAttribute[attribute] = LinkedHashMap(modifiers)
        }
    }

    fun namesFor(attribute: String): Map<UUID, String>? = namesByAttribute[attribute]

    fun clear() = namesByAttribute.clear()
}

/** Client-side cache for L2 Tabs attribute modifier display names on the local player. */
object RClientL2Names {
    private val channel = ResourceLocation("l2tabs", "main")

    private var cachedListener: ClientPacketListener? = null
    private var cachedPlayer: LocalPlayer? = null
    private var cachedLevel: net.minecraft.client.multiplayer.ClientLevel? = null
    private val nameCache = L2AttributeNameCache()

    @JvmStatic
    fun shouldIntercept(listener: ClientPacketListener, packet: ClientboundCustomPayloadPacket): Boolean =
        packet.identifier == channel && L2NameChannel.isSupported(listener.connection)

    /** Called on the client thread from the custom-payload mixin. Returns true when it handled ID1. */
    @JvmStatic
    fun handleCustomPayload(
        listener: ClientPacketListener,
        packet: ClientboundCustomPayloadPacket,
    ): Boolean {
        if (!isCurrentListener(listener) || !L2NameChannel.isSupported(listener.connection)) {
            return false
        }

        val buffer = packet.data
        try {
            if (buffer.readableBytes() == 0 || buffer.getUnsignedByte(buffer.readerIndex()).toInt() != 1) {
                return false
            }

            val body = ByteArray(buffer.readableBytes())
            buffer.readBytes(body)
            val decoded = L2NamePayload.decode(body).getOrThrow()
            val player = Minecraft.getInstance().player ?: return false
            if (decoded.entityId != player.id) {
                return false
            }

            ensureIdentity(listener, player)
            nameCache.replaceMentioned(decoded.names)
            applyNames(player, decoded.names.keys)
            return true
        } finally {
            buffer.release()
        }
    }

    /** Restores cached names after vanilla has recreated the local player's modifiers. */
    @JvmStatic
    fun afterAttributeUpdate(listener: ClientPacketListener, packet: ClientboundUpdateAttributesPacket) {
        if (!isCurrentListener(listener)) return
        val player = Minecraft.getInstance().player ?: return
        if (packet.entityId != player.id) return

        ensureIdentity(listener, player)
        val updatedAttributes = packet.values.mapNotNull { snapshot ->
            BuiltInRegistries.ATTRIBUTE.getKey(snapshot.attribute)?.toString()
        }
        applyNames(player, updatedAttributes)
    }

    /** Clears cached data when the local player connection or world changes. */
    @JvmStatic
    fun clear() {
        reset()
    }

    @JvmStatic
    fun clearAfterLogin(listener: ClientPacketListener) {
        if (isCurrentListener(listener)) reset()
    }

    @JvmStatic
    fun clearAfterRespawn(listener: ClientPacketListener) {
        if (isCurrentListener(listener)) reset()
    }

    @JvmStatic
    fun clearIfLocalPlayerRemoved(listener: ClientPacketListener, removedEntityIds: IntArray) {
        if (!isCurrentListener(listener)) return
        val player = Minecraft.getInstance().player ?: return
        if (removedEntityIds.any { it == player.id }) reset()
    }

    private fun isCurrentListener(listener: ClientPacketListener): Boolean {
        val minecraft = Minecraft.getInstance()
        return minecraft.isSameThread && minecraft.connection === listener
    }

    private fun ensureIdentity(listener: ClientPacketListener, player: LocalPlayer) {
        val level = listener.level
        if (cachedListener !== listener || cachedPlayer !== player || cachedLevel !== level) {
            reset()
            cachedListener = listener
            cachedPlayer = player
            cachedLevel = level
        }
    }

    private fun applyNames(player: Player, attributes: Iterable<String>) {
        for (attributeName in attributes) {
            val modifierNames = nameCache.namesFor(attributeName) ?: continue
            val resourceLocation = ResourceLocation.tryParse(attributeName) ?: continue
            val attribute = BuiltInRegistries.ATTRIBUTE.getOptional(resourceLocation).orElse(null) ?: continue
            val instance: AttributeInstance = player.attributes.getInstance(attribute) ?: continue
            for (modifier in instance.modifiers) {
                val name = modifierNames[modifier.id] ?: continue
                (modifier as AAttributeModifierName).`rdi$setNameGetter` { name }
            }
        }
    }

    private fun reset() {
        nameCache.clear()
        cachedListener = null
        cachedPlayer = null
        cachedLevel = null
    }
}
