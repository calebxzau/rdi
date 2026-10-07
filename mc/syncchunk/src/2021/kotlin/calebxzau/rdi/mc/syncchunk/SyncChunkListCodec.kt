package calebxzau.rdi.mc.syncchunk

import io.netty.handler.codec.DecoderException
import net.minecraft.network.FriendlyByteBuf

/** Body layout: count, then dimension, chunkX, reserved 0 (a former section Y) and chunkZ per chunk. */
object SyncChunkListCodec {
    fun write(list: SyncChunkList, buf: FriendlyByteBuf) {
        buf.writeVarInt(list.chunks.size)
        list.chunks.forEach { key ->
            buf.writeUtf(key.dimensionId, SyncChunkList.MAX_DIMENSION_ID_LENGTH)
            buf.writeInt(key.chunkX)
            buf.writeInt(0)
            buf.writeInt(key.chunkZ)
        }
    }

    /**
     * Reads a whole body; malformed, oversized or trailing data fails without a partial list. A rejected
     * body is skipped entirely so the enclosing packet still ends where it is expected to.
     */
    fun read(buf: FriendlyByteBuf): Result<SyncChunkList> {
        val result = try {
            val count = buf.readVarInt()
            require(count in 0..SyncChunkList.MAX_ENTRY_COUNT) { "同步区块数量超出范围：${count}" }
            val chunks = List(count) {
                val dimensionId = buf.readUtf(SyncChunkList.MAX_DIMENSION_ID_LENGTH)
                val chunkX = buf.readInt()
                buf.readInt()
                SyncChunkKey(dimensionId, chunkX, buf.readInt())
            }
            require(!buf.isReadable) { "同步区块数据末尾有多余的${buf.readableBytes()}字节" }
            Result.success(SyncChunkList(chunks))
        } catch (exception: IllegalArgumentException) {
            Result.failure(exception)
        } catch (exception: IndexOutOfBoundsException) {
            Result.failure(exception)
        } catch (exception: DecoderException) {
            Result.failure(exception)
        }
        if (result.isFailure) buf.skipBytes(buf.readableBytes())
        return result
    }
}
