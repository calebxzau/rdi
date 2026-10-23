package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.ReferenceCountUtil
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Verifies the buffer ownership and attributes used by the Netty 4.0 adapter. */
class NettyCompat40Test {
    @Test
    fun `attribute key carries a value on a channel`() {
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())
        try {
            channel.attr(KEY).set("packet session")
            assertEquals("packet session", channel.attr(KEY).get())
        } finally {
            channel.finish()
        }
    }

    @Test
    fun `retained slice advances the reader and keeps the parent alive`() {
        val parent = Unpooled.wrappedBuffer(byteArrayOf(10, 20, 30, 40))
        try {
            parent.readByte()
            val slice = NettyCompat.readRetainedSlice(parent, 2)
            try {
                assertEquals(2, parent.refCnt())
                assertEquals(2, slice.refCnt())
                assertEquals(3, parent.readerIndex())
                assertEquals(2, slice.readableBytes())
                assertEquals(20, slice.readUnsignedByte().toInt())
                assertEquals(30, slice.readUnsignedByte().toInt())
            } finally {
                assertFalse(slice.release())
            }
            assertEquals(1, parent.refCnt())
        } finally {
            assertTrue(parent.release())
        }
        assertEquals(0, parent.refCnt())
    }

    @Test
    fun `copy includes only readable bytes without sharing storage or moving indices`() {
        val buffer = Unpooled.buffer(8).writeBytes(byteArrayOf(10, 20, 30, 40))
        try {
            buffer.readerIndex(1)
            val copy = NettyCompat.copyReadable(buffer)
            assertContentEquals(byteArrayOf(20, 30, 40), copy)
            assertEquals(1, buffer.readerIndex())
            assertEquals(4, buffer.writerIndex())
            buffer.setByte(1, 99)
            assertContentEquals(byteArrayOf(20, 30, 40), copy)
            buffer.readerIndex(buffer.writerIndex())
            assertContentEquals(byteArrayOf(), NettyCompat.copyReadable(buffer))
        } finally {
            buffer.release()
        }
    }

    @Test
    fun `sending record forwards counts and can be released by Netty`() {
        val content = Unpooled.buffer().writeByte(42)
        val record = ZstdSendingRecord(content, ZstdBatchPolicy.Immediate, null)
        try {
            assertSame(record, record.retain())
            assertSame(record, record.retain(2))
            assertEquals(4, content.refCnt())
            assertEquals(4, record.refCnt())
            assertFalse(record.release(2))
            assertFalse(record.release())
            assertEquals(1, content.refCnt())
            assertTrue(ReferenceCountUtil.release(record))
            assertEquals(0, content.refCnt())
            assertEquals(0, record.refCnt())
        } finally {
            if (content.refCnt() > 0) content.release(content.refCnt())
        }
    }

    private companion object {
        val KEY = NettyCompat.attributeKey<String>("rdi.test.netty40.attribute")
    }
}
