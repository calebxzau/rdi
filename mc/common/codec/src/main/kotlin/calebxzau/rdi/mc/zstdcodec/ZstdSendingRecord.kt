package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf

/**
 * Owns one encoded packet buffer together with its batching decision until the codec releases it.
 * A [persistentKind] other than [PersistentPacketKind.NONE] marks a packet the client may keep across joins.
 */
internal class ZstdSendingRecord(
    val content: ByteBuf,
    val policy: ZstdBatchPolicy,
    val identity: ZstdPacketIdentity?,
    val persistentKind: Int = PersistentPacketKind.NONE,
) : NettyRefCountedBase() {
    override val touchTarget: ByteBuf get() = content

    override fun refCnt(): Int = content.refCnt()

    override fun retain(): ZstdSendingRecord {
        content.retain()
        return this
    }

    override fun retain(increment: Int): ZstdSendingRecord {
        content.retain(increment)
        return this
    }

    override fun release(): Boolean = content.release()

    override fun release(decrement: Int): Boolean = content.release(decrement)
}
