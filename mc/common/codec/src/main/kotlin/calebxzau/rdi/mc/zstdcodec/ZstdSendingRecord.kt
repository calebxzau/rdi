package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.util.ReferenceCounted

/** Owns one encoded packet buffer together with its batching decision until the codec releases it. */
internal class ZstdSendingRecord(
    val content: ByteBuf,
    val policy: ZstdBatchPolicy,
    val identity: ZstdPacketIdentity?,
) : ReferenceCounted {
    override fun refCnt(): Int = content.refCnt()

    override fun retain(): ZstdSendingRecord {
        content.retain()
        return this
    }

    override fun retain(increment: Int): ZstdSendingRecord {
        content.retain(increment)
        return this
    }

    override fun touch(): ZstdSendingRecord {
        content.touch()
        return this
    }

    override fun touch(hint: Any?): ZstdSendingRecord {
        content.touch(hint)
        return this
    }

    override fun release(): Boolean = content.release()

    override fun release(decrement: Int): Boolean = content.release(decrement)
}
