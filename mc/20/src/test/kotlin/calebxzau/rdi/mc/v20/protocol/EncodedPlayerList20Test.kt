package calebxzau.rdi.mc.v20.protocol

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncodedPlayerList20Test {
    private val json = """{"generatedAt":123,"hosts":[{"hostName":"测试房间","players":[]}]}"""

    @Test
    fun preservesExistingWireEncoding() {
        val reference = FriendlyByteBuf(Unpooled.buffer())
        val payload = EncodedPlayerList20.encode(json).getOrThrow().newPayload()
        try {
            FabricRoomWire20.encodePlayersJson(reference, json)
            val expected = ByteArray(reference.readableBytes()).also(reference::readBytes)
            val actual = ByteArray(payload.readableBytes()).also(payload::readBytes)
            assertContentEquals(expected, actual)
        } finally {
            reference.release()
            payload.release()
        }
    }

    @Test
    fun recipientsCannotMutateOtherRecipientsOrTheCachedSnapshot() {
        val snapshot = EncodedPlayerList20.encode(json).getOrThrow()
        val first = snapshot.newPayload()
        val second = snapshot.newPayload()
        try {
            first.setByte(0, 99)
            first.readerIndex(first.writerIndex())
            assertEquals(json, FabricRoomWire20.decodePlayersJson(second))
        } finally {
            first.release()
            second.release()
        }
        val lateJoin = snapshot.newPayload()
        try {
            assertEquals(json, FabricRoomWire20.decodePlayersJson(lateJoin))
        } finally {
            lateJoin.release()
        }
    }

    @Test
    fun rejectsOversizedSnapshotBeforeItCanBePublished() {
        assertTrue(EncodedPlayerList20.encode("a".repeat(FabricRoomWire20.MAX_JSON_LENGTH + 1)).isFailure)
    }
}
