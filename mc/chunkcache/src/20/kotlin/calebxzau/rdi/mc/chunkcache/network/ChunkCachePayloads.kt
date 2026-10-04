package calebxzau.rdi.mc.chunkcache.network

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/** Minecraft 1.20.1 messages for protocol `chunk-cache-1`; field layout matches the 1.21 payloads. */
object ChunkCachePayloads {
    const val VERSION = "chunk-cache-1"
    const val HASH_BYTES = 20
    private const val MAX_DIMENSION_CHARS = 256

    @JvmStatic
    fun writeEpoch(buffer: FriendlyByteBuf, epoch: UUID) {
        buffer.writeLong(epoch.mostSignificantBits)
        buffer.writeLong(epoch.leastSignificantBits)
    }

    @JvmStatic
    fun readEpoch(buffer: FriendlyByteBuf): UUID = UUID(buffer.readLong(), buffer.readLong())

    @JvmStatic
    fun writeDimension(buffer: FriendlyByteBuf, dimension: ResourceLocation) {
        val value = dimension.toString()
        require(value.length <= MAX_DIMENSION_CHARS) { "Dimension identifier exceeds limit" }
        buffer.writeUtf(value, MAX_DIMENSION_CHARS)
    }

    @JvmStatic
    fun readDimension(buffer: FriendlyByteBuf): ResourceLocation {
        val value = buffer.readUtf(MAX_DIMENSION_CHARS)
        return ResourceLocation.tryParse(value) ?: throw IllegalArgumentException("Invalid dimension identifier")
    }

    @JvmStatic
    fun readCount(buffer: FriendlyByteBuf, limit: Int): Int {
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
    fun readHash(buffer: FriendlyByteBuf): ByteArray = ByteArray(HASH_BYTES).also(buffer::readBytes)

    @JvmStatic
    fun writeHash(buffer: FriendlyByteBuf, hash: ByteArray) {
        validateHash(hash)
        buffer.writeBytes(hash)
    }
}

/** Every chunk cache message; the Forge channel registers one index per subtype. */
sealed interface ChunkCachePayload {
    fun write(buffer: FriendlyByteBuf)
}

data class ChunkCacheOffer(val id: Long, val x: Int, val z: Int, val hash: ByteArray) {
    init {
        ChunkCachePayloads.validateId(id)
        ChunkCachePayloads.validateHash(hash)
    }
}

data class ChunkCacheContextPayload(val epoch: UUID, val dimension: ResourceLocation) : ChunkCachePayload {
    constructor(buffer: FriendlyByteBuf) : this(
        ChunkCachePayloads.readEpoch(buffer), ChunkCachePayloads.readDimension(buffer),
    )

    override fun write(buffer: FriendlyByteBuf) {
        ChunkCachePayloads.writeEpoch(buffer, epoch)
        ChunkCachePayloads.writeDimension(buffer, dimension)
    }
}

data class ChunkCacheOfferPayload(
    val epoch: UUID,
    val dimension: ResourceLocation,
    val entries: List<ChunkCacheOffer>,
) : ChunkCachePayload {
    init {
        require(entries.size <= ChunkCacheLimits.MAX_OFFER_BATCH) { "Offer batch exceeds limit" }
    }

    constructor(buffer: FriendlyByteBuf) : this(
        ChunkCachePayloads.readEpoch(buffer),
        ChunkCachePayloads.readDimension(buffer),
        List(ChunkCachePayloads.readCount(buffer, ChunkCacheLimits.MAX_OFFER_BATCH)) {
            ChunkCacheOffer(buffer.readLong(), buffer.readInt(), buffer.readInt(), ChunkCachePayloads.readHash(buffer))
        },
    )

    override fun write(buffer: FriendlyByteBuf) {
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
}

data class ChunkCacheRetirePayload(val epoch: UUID, val ids: List<Long>) : ChunkCachePayload {
    init { validateIds(ids) }
    constructor(buffer: FriendlyByteBuf) : this(ChunkCachePayloads.readEpoch(buffer), readIds(buffer))
    override fun write(buffer: FriendlyByteBuf) = writeIds(buffer, epoch, ids)
}

data class ChunkCacheCancelPayload(val epoch: UUID, val ids: List<Long>) : ChunkCachePayload {
    init { validateIds(ids) }
    constructor(buffer: FriendlyByteBuf) : this(ChunkCachePayloads.readEpoch(buffer), readIds(buffer))
    override fun write(buffer: FriendlyByteBuf) = writeIds(buffer, epoch, ids)
}

data class ChunkCacheResultPayload(
    val epoch: UUID,
    val id: Long,
    val x: Int,
    val z: Int,
    val hash: ByteArray,
    val success: Boolean,
) : ChunkCachePayload {
    init { ChunkCachePayloads.validateId(id); ChunkCachePayloads.validateHash(hash) }
    constructor(buffer: FriendlyByteBuf) : this(
        ChunkCachePayloads.readEpoch(buffer), buffer.readLong(), buffer.readInt(), buffer.readInt(),
        ChunkCachePayloads.readHash(buffer), buffer.readBoolean(),
    )

    override fun write(buffer: FriendlyByteBuf) {
        ChunkCachePayloads.writeEpoch(buffer, epoch)
        buffer.writeLong(id)
        buffer.writeInt(x)
        buffer.writeInt(z)
        ChunkCachePayloads.writeHash(buffer, hash)
        buffer.writeBoolean(success)
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
) : ChunkCachePayload {
    init {
        ChunkCachePayloads.validateId(id)
        ChunkCachePayloads.validateHash(hash)
        require(metadata.size <= ChunkCacheLimits.MAX_METADATA_BYTES) { "Chunk reuse metadata exceeds limit" }
    }

    constructor(buffer: FriendlyByteBuf) : this(
        ChunkCachePayloads.readEpoch(buffer), buffer.readLong(), ChunkCachePayloads.readDimension(buffer),
        buffer.readInt(), buffer.readInt(), ChunkCachePayloads.readHash(buffer), readMetadata(buffer),
    )

    override fun write(buffer: FriendlyByteBuf) {
        ChunkCachePayloads.writeEpoch(buffer, epoch)
        buffer.writeLong(id)
        ChunkCachePayloads.writeDimension(buffer, dimension)
        buffer.writeInt(x)
        buffer.writeInt(z)
        ChunkCachePayloads.writeHash(buffer, hash)
        buffer.writeVarInt(metadata.size)
        buffer.writeBytes(metadata)
    }

    private companion object {
        fun readMetadata(buffer: FriendlyByteBuf): ByteArray {
            val length = buffer.readVarInt()
            require(length in 0..ChunkCacheLimits.MAX_METADATA_BYTES) { "Chunk reuse metadata exceeds limit" }
            require(length <= buffer.readableBytes()) { "Truncated chunk reuse metadata" }
            return ByteArray(length).also(buffer::readBytes)
        }
    }
}

private fun readIds(buffer: FriendlyByteBuf): List<Long> =
    List(ChunkCachePayloads.readCount(buffer, ChunkCacheLimits.MAX_OFFERS)) {
        buffer.readLong().also(ChunkCachePayloads::validateId)
    }

private fun writeIds(buffer: FriendlyByteBuf, epoch: UUID, ids: List<Long>) {
    validateIds(ids)
    ChunkCachePayloads.writeEpoch(buffer, epoch)
    buffer.writeVarInt(ids.size)
    ids.forEach { buffer.writeLong(it) }
}

private fun validateIds(ids: List<Long>) {
    require(ids.size <= ChunkCacheLimits.MAX_OFFERS) { "Candidate ID list exceeds limit" }
    ids.forEach(ChunkCachePayloads::validateId)
}
