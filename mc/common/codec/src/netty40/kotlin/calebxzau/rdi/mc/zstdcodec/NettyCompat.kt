package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCounted

/** Netty 4.0.10 operations selected by the module's source path. */
internal object NettyCompat {
    /** Creates a key once per name; callers must keep it in a static val. */
    fun <T> attributeKey(name: String): AttributeKey<T> = AttributeKey<T>(name)

    /**
     * Advances the reader index and transfers one retained slice to the caller.
     *
     * Netty 4.0.10 `ByteToMessageDecoder` compacts a kept cumulation before it fires the decoded list,
     * so the codec relies on the upstream splitter handing over each frame as its own copied buffer.
     */
    fun readRetainedSlice(buffer: ByteBuf, length: Int): ByteBuf = buffer.readSlice(length).retain()

    /** Copies the readable bytes without moving the reader index. */
    fun copyReadable(buffer: ByteBuf): ByteArray =
        ByteArray(buffer.readableBytes()).also { buffer.getBytes(buffer.readerIndex(), it) }
}

/** Base for owned-buffer messages on Netty 4.0, which has no touch methods. */
internal abstract class NettyRefCountedBase : ReferenceCounted {
    /** Buffer whose lifetime is owned by this message. */
    protected abstract val touchTarget: ByteBuf
}
