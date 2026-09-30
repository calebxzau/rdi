package calebxzau.rdi.mc.v20.l2

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class L2NamePayloadTest {
    @Test
    fun roundTripsRepresentativeL2Tabs033Payload() {
        val modifierId = UUID.fromString("00000000-0000-0000-0000-000432224337")
        val names = mapOf(
            "minecraft:generic.movement_speed" to mapOf(
                modifierId to "PMMO-modifier based on user skill"
            )
        )
        // Reduced representative of the captured entity 78 movement-speed attribute packet.
        val capturedPayload = """
            0101010000004e0100000001010001206d696e6563726166743a67656e65726963
            2e6d6f76656d656e745f737065656401000000010100012430303030303030302d
            303030302d303030302d303030302d3030303433323232343333370121504d4d4f
            2d6d6f646966696572206261736564206f6e207573657220736b696c6c
        """.filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decoded = L2NamePayload.decode(capturedPayload).getOrThrow()

        assertEquals(78, decoded.entityId)
        assertEquals(names, decoded.names)
        assertContentEquals(capturedPayload, L2NamePayload.encode(decoded.entityId, decoded.names))
    }

    @Test
    fun encodesExpectedL2BodyMarkersAndBigEndianEntityId() {
        val payload = L2NamePayload.encode(0x01020304, emptyMap())
        assertContentEquals(byteArrayOf(1, 1, 1, 1, 2, 3, 4, 1, 0, 0, 0, 0), payload)
        assertEquals(L2NamePayload.Decoded(0x01020304, emptyMap()), L2NamePayload.decode(payload).getOrThrow())
    }

    @Test
    fun rejectsInvalidMarkersMalformedUuidUtf8AndTrailingBytes() {
        val valid = L2NamePayload.encode(
            78,
            mapOf("minecraft:generic.movement_speed" to mapOf(UUID(0L, 1L) to "Boots"))
        )
        val invalidFirstMarker = valid.copyOf().also { it[0] = 2 }
        assertDecodeFailure(invalidFirstMarker)
        assertDecodeFailure(valid + byteArrayOf(0))

        val invalidUuid = valid.copyOf().also { payload ->
            val uuidText = UUID(0L, 1L).toString().toByteArray(Charsets.UTF_8)
            val index = payload.indexOfSubArray(uuidText)
            assertTrue(index >= 0)
            payload[index + uuidText.lastIndex] = 'x'.code.toByte()
        }
        assertDecodeFailure(invalidUuid)

        val invalidUtf8 = valid.copyOf().also { payload ->
            val nameBytes = "Boots".toByteArray(Charsets.UTF_8)
            val index = payload.indexOfSubArray(nameBytes)
            assertTrue(index >= 0)
            payload[index] = 0xc3.toByte()
        }
        assertDecodeFailure(invalidUtf8)
    }

    @Test
    fun rejectsOversizedCountsAndTruncatedPayloads() {
        val excessiveCount = byteArrayOf(1, 1, 0, 0, 0, 1, 1, 0, 0x80.toByte(), 0, 0)
        assertDecodeFailure(excessiveCount)
        assertDecodeFailure(byteArrayOf(1, 1, 1, 0, 0))
    }

    private fun assertDecodeFailure(payload: ByteArray) {
        val result = L2NamePayload.decode(payload)
        assertTrue(result.isFailure)
    }

    private fun ByteArray.indexOfSubArray(target: ByteArray): Int {
        if (target.isEmpty()) return 0
        return (0..size - target.size).firstOrNull { start ->
            target.indices.all { index -> this[start + index] == target[index] }
        } ?: -1
    }
}
