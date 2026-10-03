package calebxzau.rdi.mc.v20.server.attributes

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.RangedAttribute
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class AttributeDedup20Test {
    private val health = RangedAttribute("test.health", 20.0, 0.0, 1024.0)
    private val speed = RangedAttribute("test.speed", 0.1, 0.0, 1024.0)
    private val modifierId = UUID.fromString("0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b")
    private val otherModifierId = UUID.fromString("0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2c")
    private val refresh = 1_000_000_000L

    private fun modifier(
        amount: Double = 4.0,
        operation: AttributeModifier.Operation = AttributeModifier.Operation.ADDITION,
        id: UUID = modifierId,
    ) = AttributeModifier(id, "test", amount, operation)

    private fun packet(entityId: Int, vararg snapshots: Triple<Attribute, Double, List<AttributeModifier>>) =
        ClientboundUpdateAttributesPacket(entityId, emptyList()).also { packet ->
            snapshots.forEach { (attribute, base, modifiers) ->
                packet.values.add(ClientboundUpdateAttributesPacket.AttributeSnapshot(attribute, base, modifiers))
            }
        }

    private fun healthPacket(entityId: Int = 7, base: Double = 20.0, modifiers: List<AttributeModifier> = listOf(modifier())) =
        packet(entityId, Triple(health, base, modifiers))

    private fun state(enabled: Boolean = true, maxEntities: Int = AttributeDedup20.MAX_ENTITIES) =
        AttributeDedup20.newState(enabled, refresh, maxEntities)

    private fun send(
        state: AttributeDedupState,
        packet: Packet<*>,
        now: Long = 0L,
        hasListener: Boolean = false,
        serverThread: Boolean = true,
    ) = AttributeDedup20.process(state, packet, hasListener, serverThread, now)

    @Test
    fun identicalSnapshotIsCancelledUntilRefresh() {
        val state = state()
        val first = healthPacket()
        assertSame(first, send(state, first))
        assertNull(send(state, healthPacket(), now = refresh - 1))
        val refreshed = healthPacket()
        assertSame(refreshed, send(state, refreshed, now = refresh))
        assertNull(send(state, healthPacket(), now = refresh + 1))
        assertEquals(4, state.packets)
        assertEquals(2, state.cancelled)
        assertEquals(2, state.skippedSnapshots)
    }

    @Test
    fun onlyChangedSnapshotsAreKept() {
        val state = state()
        send(state, packet(7, Triple(health, 20.0, listOf(modifier())), Triple(speed, 0.1, emptyList())))
        val changed = packet(7, Triple(health, 20.0, listOf(modifier())), Triple(speed, 0.2, emptyList()))
        val result = send(state, changed, now = 1L) as ClientboundUpdateAttributesPacket
        assertNotSame(changed, result)
        assertEquals(7, result.entityId)
        assertEquals(listOf(changed.values[1]), result.values)
        assertEquals(1, state.trimmed)
        assertEquals(1, state.skippedSnapshots)
    }

    @Test
    fun everyClientVisibleDifferenceIsSent() {
        val variants = listOf(
            healthPacket(base = 21.0),
            healthPacket(modifiers = listOf(modifier(amount = 5.0))),
            healthPacket(modifiers = listOf(modifier(operation = AttributeModifier.Operation.MULTIPLY_TOTAL))),
            healthPacket(modifiers = listOf(modifier(), modifier(id = otherModifierId))),
            healthPacket(modifiers = emptyList()),
        )
        for (variant in variants) {
            val state = state()
            send(state, healthPacket())
            assertSame(variant, send(state, variant, now = 1L))
        }

        val state = state()
        send(state, healthPacket(modifiers = listOf(modifier(), modifier(id = otherModifierId))))
        val reordered = healthPacket(modifiers = listOf(modifier(id = otherModifierId), modifier()))
        assertSame(reordered, send(state, reordered, now = 1L))
    }

    @Test
    fun modifierNamesAreIgnored() {
        val state = state()
        send(state, healthPacket(modifiers = listOf(AttributeModifier(modifierId, "first", 4.0, AttributeModifier.Operation.ADDITION))))
        val renamed = healthPacket(modifiers = listOf(AttributeModifier(modifierId, "second", 4.0, AttributeModifier.Operation.ADDITION)))
        assertNull(send(state, renamed, now = 1L))
    }

    @Test
    fun entitiesAreTrackedSeparately() {
        val state = state()
        send(state, healthPacket(entityId = 7))
        val other = healthPacket(entityId = 8)
        assertSame(other, send(state, other, now = 1L))
    }

    @Test
    fun removedEntityIsSentInFullAgain() {
        val state = state()
        send(state, healthPacket())
        val remove = ClientboundRemoveEntitiesPacket(7, 9)
        assertSame(remove, send(state, remove, now = 1L))
        val readded = healthPacket()
        assertSame(readded, send(state, readded, now = 2L))
    }

    @Test
    fun bundledPairingDataIsNeverFilteredButIsRecorded() {
        val state = state()
        send(state, healthPacket())
        val pairing = healthPacket()
        val bundle = ClientboundBundlePacket(listOf<Packet<ClientGamePacketListener>>(ClientboundRemoveEntitiesPacket(7), pairing))
        assertSame(bundle, send(state, bundle, now = 1L))
        assertNull(send(state, healthPacket(), now = 2L))
        assertEquals(1, state.cancelled)
    }

    @Test
    fun packetsWithListenersPassButAreRecorded() {
        val state = state()
        val listened = healthPacket()
        assertSame(listened, send(state, listened, hasListener = true))
        assertNull(send(state, healthPacket(), now = 1L))
    }

    @Test
    fun offThreadAttributeSendResetsTheCache() {
        val state = state()
        send(state, healthPacket(entityId = 7))
        send(state, healthPacket(entityId = 8))
        val offThread = healthPacket(entityId = 9)
        assertSame(offThread, send(state, offThread, now = 1L, serverThread = false))
        assertEquals(1, state.offThreadResets)
        assertEquals(0, state.cache.entityCount)
        val again = healthPacket(entityId = 7)
        assertSame(again, send(state, again, now = 2L))
    }

    @Test
    fun disabledStatePassesEverything() {
        val state = state(enabled = false)
        send(state, healthPacket())
        val repeated = healthPacket()
        assertSame(repeated, send(state, repeated, now = 1L))
        assertEquals(0, state.packets)
    }

    @Test
    fun entitiesBeyondTheCapAreNotCached() {
        val state = state(maxEntities = 1)
        send(state, healthPacket(entityId = 7))
        send(state, healthPacket(entityId = 8))
        val repeated = healthPacket(entityId = 8)
        assertSame(repeated, send(state, repeated, now = 1L))
        assertNull(send(state, healthPacket(entityId = 7), now = 1L))
        assertEquals(1, state.cache.entityCount)
    }

    @Test
    fun newAttributeOnKnownEntityIsSent() {
        val state = state()
        send(state, healthPacket())
        val mixed = packet(7, Triple(health, 20.0, listOf(modifier())), Triple(speed, 0.1, emptyList()))
        val result = send(state, mixed, now = 1L) as ClientboundUpdateAttributesPacket
        assertEquals(listOf(mixed.values[1]), result.values)
        assertEquals(1, state.trimmed)
    }
}
