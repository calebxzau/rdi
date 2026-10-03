package calebxzau.rdi.mc.chunkcache.network

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import java.util.UUID

object ChunkCachePayloads {
    const val VERSION = "chunk-cache-1"
    const val HASH_BYTES = 20
    private const val MAX_DIMENSION_CHARS = 256

    @JvmStatic
    fun writeEpoch(buffer: RegistryFriendlyByteBuf, epoch: UUID) {
        buffer.writeLong(epoch.mostSignificantBits)
        buffer.writeLong(epoch.leastSignificantBits)
    }

    @JvmStatic
    fun readEpoch(buffer: RegistryFriendlyByteBuf): UUID = UUID(buffer.readLong(), buffer.readLong())

    @JvmStatic
    fun writeDimension(buffer: RegistryFriendlyByteBuf, dimension: ResourceLocation) {
        val value = dimension.toString()
        require(value.length <= MAX_DIMENSION_CHARS) { "Dimension identifier exceeds limit" }
        buffer.writeUtf(value, MAX_DIMENSION_CHARS)
    }

    @JvmStatic
    fun readDimension(buffer: RegistryFriendlyByteBuf): ResourceLocation {
        val value = buffer.readUtf(MAX_DIMENSION_CHARS)
        return ResourceLocation.tryParse(value) ?: throw IllegalArgumentException("Invalid dimension identifier")
    }

    @JvmStatic
    fun readCount(buffer: RegistryFriendlyByteBuf, limit: Int): Int {
        val count = buffer.readVarInt()
        require(count in 0..limit) { "Payload list exceeds limit" }
        return count
    }

    @JvmStatic
    fun validateId(id: Long) {
        require(id > 0) { "Candidate ID must be positive" }
    }

    @JvmStatic
    fun validateHash(hash: ByteArray) {
        require(hash.size == HASH_BYTES) { "SHA-1 hash must be 20 bytes" }
    }

    @JvmStatic
    fun readHash(buffer: RegistryFriendlyByteBuf): ByteArray = ByteArray(HASH_BYTES).also(buffer::readBytes)

    @JvmStatic
    fun writeHash(buffer: RegistryFriendlyByteBuf, hash: ByteArray) {
        validateHash(hash)
        buffer.writeBytes(hash)
    }

}

data class ChunkCacheOffer(val id: Long, val x: Int, val z: Int, val hash: ByteArray) {
    init {
        ChunkCachePayloads.validateId(id)
        ChunkCachePayloads.validateHash(hash)
    }
}

data class ChunkCacheContextPayload(val epoch: UUID, val dimension: ResourceLocation) : CustomPacketPayload {
    constructor(buffer: RegistryFriendlyByteBuf) : this(
        ChunkCachePayloads.readEpoch(buffer), ChunkCachePayloads.readDimension(buffer),
    )

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    fun write(buffer: RegistryFriendlyByteBuf) {
        ChunkCachePayloads.writeEpoch(buffer, epoch)
        ChunkCachePayloads.writeDimension(buffer, dimension)
    }

    companion object {
        val TYPE = CustomPacketPayload.Type<ChunkCacheContextPayload>(ResourceLocation.fromNamespaceAndPath("rdi", "chunk_cache_v2_context"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ChunkCacheContextPayload> =
            CustomPacketPayload.codec(ChunkCacheContextPayload::write, ::ChunkCacheContextPayload)
    }
}

data class ChunkCacheOfferPayload(
    val epoch: UUID,
    val dimension: ResourceLocation,
    val entries: List<ChunkCacheOffer>,
) : CustomPacketPayload {
    init {
        require(entries.size <= ChunkCacheLimits.MAX_OFFER_BATCH) { "Offer batch exceeds limit" }
    }

    constructor(buffer: RegistryFriendlyByteBuf) : this(
        ChunkCachePayloads.readEpoch(buffer),
        ChunkCachePayloads.readDimension(buffer),
        List(ChunkCachePayloads.readCount(buffer, ChunkCacheLimits.MAX_OFFER_BATCH)) {
            ChunkCacheOffer(buffer.readLong(), buffer.readInt(), buffer.readInt(), ChunkCachePayloads.readHash(buffer))
        },
    )

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    fun write(buffer: RegistryFriendlyByteBuf) {
        ChunkCachePayloads.writeEpoch(buffer, epoch)
        ChunkCachePayloads.writeDimension(buffer, dimension)
        buffer.writeVarInt(entries.size)
        entries.forEach {
            buffer.writeLong(it.id)
            buffer.writeInt(it.x)
            buffer.writeInt(it.z)
            ChunkCachePayloads.writeHash(buffer, it.hash)
        }
    }

    companion object {
        val TYPE = CustomPacketPayload.Type<ChunkCacheOfferPayload>(ResourceLocation.fromNamespaceAndPath("rdi", "chunk_cache_v2_offer"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ChunkCacheOfferPayload> =
            CustomPacketPayload.codec(ChunkCacheOfferPayload::write, ::ChunkCacheOfferPayload)
    }
}

data class ChunkCacheRetirePayload(val epoch: UUID, val ids: List<Long>) : CustomPacketPayload {
    init { validateIds(ids) }
    constructor(buffer: RegistryFriendlyByteBuf) : this(
        ChunkCachePayloads.readEpoch(buffer), readIds(buffer),
    )
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    fun write(buffer: RegistryFriendlyByteBuf) = writeIds(buffer, epoch, ids)
    companion object {
        val TYPE = CustomPacketPayload.Type<ChunkCacheRetirePayload>(ResourceLocation.fromNamespaceAndPath("rdi", "chunk_cache_v2_retire"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ChunkCacheRetirePayload> =
            CustomPacketPayload.codec(ChunkCacheRetirePayload::write, ::ChunkCacheRetirePayload)
    }
}

data class ChunkCacheCancelPayload(val epoch: UUID, val ids: List<Long>) : CustomPacketPayload {
    init { validateIds(ids) }
    constructor(buffer: RegistryFriendlyByteBuf) : this(
        ChunkCachePayloads.readEpoch(buffer), readIds(buffer),
    )
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    fun write(buffer: RegistryFriendlyByteBuf) = writeIds(buffer, epoch, ids)
    companion object {
        val TYPE = CustomPacketPayload.Type<ChunkCacheCancelPayload>(ResourceLocation.fromNamespaceAndPath("rdi", "chunk_cache_v2_cancel"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ChunkCacheCancelPayload> =
            CustomPacketPayload.codec(ChunkCacheCancelPayload::write, ::ChunkCacheCancelPayload)
    }
}

data class ChunkCacheResultPayload(
    val epoch: UUID,
    val id: Long,
    val x: Int,
    val z: Int,
    val hash: ByteArray,
    val success: Boolean,
) : CustomPacketPayload {
    init { ChunkCachePayloads.validateId(id); ChunkCachePayloads.validateHash(hash) }
    constructor(buffer: RegistryFriendlyByteBuf) : this(
        ChunkCachePayloads.readEpoch(buffer), buffer.readLong(), buffer.readInt(), buffer.readInt(),
        ChunkCachePayloads.readHash(buffer), buffer.readBoolean(),
    )
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    fun write(buffer: RegistryFriendlyByteBuf) {
        ChunkCachePayloads.writeEpoch(buffer, epoch)
        buffer.writeLong(id)
        buffer.writeInt(x)
        buffer.writeInt(z)
        ChunkCachePayloads.writeHash(buffer, hash)
        buffer.writeBoolean(success)
    }
    companion object {
        val TYPE = CustomPacketPayload.Type<ChunkCacheResultPayload>(ResourceLocation.fromNamespaceAndPath("rdi", "chunk_cache_v2_result"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ChunkCacheResultPayload> =
            CustomPacketPayload.codec(ChunkCacheResultPayload::write, ::ChunkCacheResultPayload)
    }
}

data class ChunkCacheReusePayload(
    val epoch: UUID,
    val id: Long,
    val dimension: ResourceLocation,
    val x: Int,
    val z: Int,
    val hash: ByteArray,
    val metadata: ByteArray,
) : CustomPacketPayload {
    init {
        ChunkCachePayloads.validateId(id)
        ChunkCachePayloads.validateHash(hash)
        require(metadata.size <= ChunkCacheLimits.MAX_METADATA_BYTES) { "Chunk reuse metadata exceeds limit" }
    }

    constructor(buffer: RegistryFriendlyByteBuf) : this(
        ChunkCachePayloads.readEpoch(buffer), buffer.readLong(), ChunkCachePayloads.readDimension(buffer),
        buffer.readInt(), buffer.readInt(), ChunkCachePayloads.readHash(buffer), readMetadata(buffer),
    )

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
    fun write(buffer: RegistryFriendlyByteBuf) {
        ChunkCachePayloads.writeEpoch(buffer, epoch)
        buffer.writeLong(id)
        ChunkCachePayloads.writeDimension(buffer, dimension)
        buffer.writeInt(x)
        buffer.writeInt(z)
        ChunkCachePayloads.writeHash(buffer, hash)
        buffer.writeVarInt(metadata.size)
        buffer.writeBytes(metadata)
    }

    companion object {
        val TYPE = CustomPacketPayload.Type<ChunkCacheReusePayload>(ResourceLocation.fromNamespaceAndPath("rdi", "chunk_cache_v2_reuse"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ChunkCacheReusePayload> =
            CustomPacketPayload.codec(ChunkCacheReusePayload::write, ::ChunkCacheReusePayload)

        private fun readMetadata(buffer: RegistryFriendlyByteBuf): ByteArray {
            val length = buffer.readVarInt()
            require(length in 0..ChunkCacheLimits.MAX_METADATA_BYTES) { "Chunk reuse metadata exceeds limit" }
            require(length <= buffer.readableBytes()) { "Truncated chunk reuse metadata" }
            return ByteArray(length).also(buffer::readBytes)
        }
    }
}

private fun readIds(buffer: RegistryFriendlyByteBuf): List<Long> =
    List(ChunkCachePayloads.readCount(buffer, ChunkCacheLimits.MAX_OFFERS)) {
        buffer.readLong().also(ChunkCachePayloads::validateId)
    }

private fun writeIds(buffer: RegistryFriendlyByteBuf, epoch: UUID, ids: List<Long>) {
    validateIds(ids)
    ChunkCachePayloads.writeEpoch(buffer, epoch)
    buffer.writeVarInt(ids.size)
    ids.forEach { buffer.writeLong(it) }
}

private fun validateIds(ids: List<Long>) {
    require(ids.size <= ChunkCacheLimits.MAX_OFFERS) { "Candidate ID list exceeds limit" }
    ids.forEach(ChunkCachePayloads::validateId)
}
