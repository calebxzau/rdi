package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCounted

/** Netty 4.1 operations selected by the module's source path. */
internal object NettyCompat {
    /** Returns the shared key for [name]. */
    fun <T> attributeKey(name: String): AttributeKey<T> = AttributeKey.valueOf<T>(name)

    /** Advances the reader index and transfers one retained slice to the caller. */
    fun readRetainedSlice(buffer: ByteBuf, length: Int): ByteBuf = buffer.readRetainedSlice(length)

    /** Copies the readable bytes without moving the reader index. */
    fun copyReadable(buffer: ByteBuf): ByteArray = ByteBufUtil.getBytes(buffer)
}

/** Forwards Netty 4.1 leak-detection hints to the message's owned buffer. */
internal abstract class NettyRefCountedBase : ReferenceCounted {
    /** Buffer whose lifetime is owned by this message. */
    protected abstract val touchTarget: ByteBuf

    override fun touch(): ReferenceCounted {
        touchTarget.touch()
        return this
    }

    override fun touch(hint: Any?): ReferenceCounted {
        touchTarget.touch(hint)
        return this
    }
}
