package calebxzau.rdi.mc.syncchunk.network

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

class RSyncChunksPayload(entries: List<Entry>) : CustomPacketPayload {
    constructor(buf: RegistryFriendlyByteBuf) : this(readEntries(buf))

    fun write(buf: RegistryFriendlyByteBuf) {
        require(entries.size <= MAX_ENTRY_COUNT) { "同步区块payload数量超出范围：${entries.size}" }
        buf.writeVarInt(entries.size)
        entries.forEach {
            buf.writeUtf(it.dimensionId, MAX_DIMENSION_ID_LENGTH)
            buf.writeInt(it.chunkX)
            buf.writeInt(0)
            buf.writeInt(it.chunkZ)
        }
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    data class Entry(val dimensionId: String, val chunkX: Int, val chunkZ: Int)

    val entries: List<Entry> = entries.toList()

    companion object {
        private const val MAX_DIMENSION_ID_LENGTH = 512
        private const val MAX_ENTRY_COUNT = 65_536

        val TYPE: CustomPacketPayload.Type<RSyncChunksPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath("rdi", "firm_sections")
        )
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RSyncChunksPayload> =
            CustomPacketPayload.codec(RSyncChunksPayload::write, ::RSyncChunksPayload)

        internal fun validateEntryCount(count: Int): Int {
            require(count in 0..MAX_ENTRY_COUNT) { "同步区块payload数量超出范围：$count" }
            return count
        }

        private fun readEntries(buf: RegistryFriendlyByteBuf): List<Entry> = List(validateEntryCount(buf.readVarInt())) {
            val dimensionId = buf.readUtf(MAX_DIMENSION_ID_LENGTH)
            val chunkX = buf.readInt()
            buf.readInt()
            val chunkZ = buf.readInt()
            Entry(dimensionId, chunkX, chunkZ)
        }
    }
}
