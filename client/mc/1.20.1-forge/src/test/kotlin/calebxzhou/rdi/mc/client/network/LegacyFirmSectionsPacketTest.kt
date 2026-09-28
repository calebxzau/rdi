package calebxzhou.rdi.mc.client.network

import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import net.minecraft.network.FriendlyByteBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LegacyFirmSectionsPacketTest {
    @Test
    fun acceptsEmptyLoginPacket(): Unit = withBuffer { buf ->
        buf.writeVarInt(0)

        assertSame(LegacyFirmSectionsPacket, LegacyFirmSectionsPacket.decode(buf))
        assertEquals(0, buf.readableBytes())
    }

    @Test
    fun consumesLegacyEntriesInTheirOriginalWireOrder(): Unit = withBuffer { buf ->
        buf.writeVarInt(2)
        buf.writeUtf("minecraft:overworld", 512)
        buf.writeInt(-123)
        buf.writeInt(-4)
        buf.writeInt(456)
        buf.writeUtf("example:custom_dimension", 512)
        buf.writeInt(Int.MAX_VALUE)
        buf.writeInt(19)
        buf.writeInt(Int.MIN_VALUE)
        buf.writeByte(0x5A)

        assertSame(LegacyFirmSectionsPacket, LegacyFirmSectionsPacket.decode(buf))
        assertEquals(1, buf.readableBytes())
        assertEquals(0x5A, buf.readUnsignedByte().toInt())
    }

    @Test
    fun rejectsCountsOutsideLegacyLimit(): Unit {
        for (count in listOf(-1, 65537)) {
            withBuffer { buf ->
                buf.writeVarInt(count)

                assertFailsWith<IllegalArgumentException> { LegacyFirmSectionsPacket.decode(buf) }
            }
        }
    }

    @Test
    fun rejectsTruncatedEntry(): Unit = withBuffer { buf ->
        buf.writeVarInt(1)
        buf.writeUtf("minecraft:overworld", 512)
        buf.writeInt(0)
        buf.writeInt(0)

        assertFailsWith<IndexOutOfBoundsException> { LegacyFirmSectionsPacket.decode(buf) }
    }

    @Test
    fun rejectsDimensionIdBeyondLegacyLimit(): Unit = withBuffer { buf ->
        buf.writeVarInt(1)
        buf.writeUtf("a".repeat(513))
        buf.writeInt(0)
        buf.writeInt(0)
        buf.writeInt(0)

        assertFailsWith<DecoderException> { LegacyFirmSectionsPacket.decode(buf) }
    }

    private fun withBuffer(block: (FriendlyByteBuf) -> Unit) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        try {
            block(buf)
        } finally {
            buf.release()
        }
    }
}
