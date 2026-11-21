package calebxzau.rdi.mc.v20.fabric.network

import calebxzau.rdi.mc.chunkcache.network.ChunkCacheAbsentPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheCancelPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheContextPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheOfferPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCachePausedPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCachePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheResultPayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheRetirePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheReusePayload
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheWarmupPayload
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation

/** Each Fabric play channel identifies one bounded 1.20.1 chunk cache payload. */
internal enum class FabricChunkCacheWire(path: String, private val read: (FriendlyByteBuf) -> ChunkCachePayload) {
    Context("context", ::ChunkCacheContextPayload),
    Reuse("reuse", ::ChunkCacheReusePayload),
    Retire("retire", ::ChunkCacheRetirePayload),
    Offer("offer", ::ChunkCacheOfferPayload),
    Cancel("cancel", ::ChunkCacheCancelPayload),
    Result("result", ::ChunkCacheResultPayload),
    Warmup("warmup", ::ChunkCacheWarmupPayload),
    Absent("absent", ::ChunkCacheAbsentPayload),
    Paused("paused", ::ChunkCachePausedPayload);

    val id = ResourceLocation("rdi", "chunk_cache_v1_$path")

    fun decode(buffer: FriendlyByteBuf): ChunkCachePayload {
        val payload = read(buffer)
        require(!buffer.isReadable) { "Trailing bytes in chunk cache packet $id" }
        return payload
    }

    companion object {
        val clientbound = listOf(Context, Reuse, Retire, Warmup)
        val serverbound = listOf(Offer, Cancel, Result, Absent, Paused)

        fun channel(payload: ChunkCachePayload): FabricChunkCacheWire = when (payload) {
            is ChunkCacheContextPayload -> Context
            is ChunkCacheReusePayload -> Reuse
            is ChunkCacheRetirePayload -> Retire
            is ChunkCacheOfferPayload -> Offer
            is ChunkCacheCancelPayload -> Cancel
            is ChunkCacheResultPayload -> Result
            is ChunkCacheWarmupPayload -> Warmup
            is ChunkCacheAbsentPayload -> Absent
            is ChunkCachePausedPayload -> Paused
        }

        fun encode(payload: ChunkCachePayload): FriendlyByteBuf {
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            try {
                payload.write(buffer)
                return buffer
            } catch (error: Throwable) {
                buffer.release()
                throw error
            }
        }
    }
}
