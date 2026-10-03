package calebxzau.rdi.mc.chunkcache

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.VarInt
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.neoforged.neoforge.network.connection.ConnectionType
import java.io.IOException
import java.util.BitSet

/** Preserves a vanilla chunk packet's serialized heightmaps and block entities around its section payload. */
object ChunkReuseCodec {
    private const val MAX_PACKET_BYTES = ChunkCacheLimits.MAX_METADATA_BYTES + ChunkCacheLimits.MAX_SECTION_BYTES + 64 * 1024

    /**
     * Light is written as an empty layer set: RDI clients compute light locally and discard server light arrays,
     * so carrying them would only cost metadata bytes and client-thread allocations.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun metadata(packet: ClientboundLevelChunkWithLightPacket, registries: RegistryAccess): ByteArray {
        val encoded = Unpooled.buffer()
        val buffer = RegistryFriendlyByteBuf(encoded, registries, ConnectionType.NEOFORGE)
        try {
            buffer.writeInt(packet.x)
            buffer.writeInt(packet.z)
            packet.chunkData.write(buffer)
            writeEmptyLight(buffer)
            if (buffer.readableBytes() > MAX_PACKET_BYTES) throw IOException("Chunk packet exceeds reuse limit")
            buffer.readInt() // Chunk X
            buffer.readInt() // Chunk Z
            buffer.readNbt() ?: throw IOException("Missing chunk heightmaps")
            val prefixLength = buffer.readerIndex()
            val sectionLength = buffer.readVarInt()
            if (sectionLength !in 0..ChunkCacheLimits.MAX_SECTION_BYTES || sectionLength > buffer.readableBytes()) {
                throw IOException("Invalid chunk section length")
            }
            buffer.skipBytes(sectionLength)
            val suffixStart = buffer.readerIndex()
            val suffixLength = buffer.readableBytes()
            val size = VarInt.getByteSize(prefixLength).toLong() + prefixLength + suffixLength
            if (size > ChunkCacheLimits.MAX_METADATA_BYTES) throw IOException("Chunk reuse metadata exceeds limit")
            val metadata = ByteArray(size.toInt())
            val output = Unpooled.wrappedBuffer(metadata).writerIndex(0)
            VarInt.write(output, prefixLength)
            output.writeBytes(encoded, 0, prefixLength)
            output.writeBytes(encoded, suffixStart, suffixLength)
            if (output.isWritable) throw IOException("Chunk reuse metadata length changed")
            return metadata
        } catch (failure: RuntimeException) {
            throw IOException("Invalid serialized chunk packet", failure)
        } finally {
            encoded.release()
        }
    }

    /** The wire form of a [net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData] with no layers. */
    private fun writeEmptyLight(buffer: RegistryFriendlyByteBuf) {
        val empty = BitSet()
        buffer.writeBitSet(empty) // Sky mask
        buffer.writeBitSet(empty) // Block mask
        buffer.writeBitSet(empty) // Empty sky mask
        buffer.writeBitSet(empty) // Empty block mask
        buffer.writeVarInt(0) // Sky updates
        buffer.writeVarInt(0) // Block updates
    }

    @JvmStatic
    @Throws(IOException::class)
    fun restore(
        metadata: ByteArray,
        sectionBytes: ByteArray,
        registries: RegistryAccess,
    ): ClientboundLevelChunkWithLightPacket {
        if (metadata.size > ChunkCacheLimits.MAX_METADATA_BYTES) throw IOException("Chunk reuse metadata exceeds limit")
        if (sectionBytes.size > ChunkCacheLimits.MAX_SECTION_BYTES) throw IOException("Chunk section data exceeds limit")
        val metadataInputBytes = Unpooled.wrappedBuffer(metadata)
        val metadataInput = RegistryFriendlyByteBuf(metadataInputBytes, registries, ConnectionType.NEOFORGE)
        try {
            val prefixLength = metadataInput.readVarInt()
            if (prefixLength < 0 || prefixLength > metadataInput.readableBytes()) throw IOException("Invalid chunk metadata prefix")
            val prefix = ByteArray(prefixLength)
            metadataInput.readBytes(prefix)
            val suffix = ByteArray(metadataInput.readableBytes())
            metadataInput.readBytes(suffix)
            val packetBuffer = Unpooled.buffer(prefix.size + sectionBytes.size + suffix.size + 5)
            try {
                val packetInput = RegistryFriendlyByteBuf(packetBuffer, registries, ConnectionType.NEOFORGE)
                packetInput.writeBytes(prefix)
                packetInput.writeVarInt(sectionBytes.size)
                packetInput.writeBytes(sectionBytes)
                packetInput.writeBytes(suffix)
                val packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(packetInput)
                if (packetInput.isReadable) throw IOException("Trailing restored chunk packet data")
                return packet
            } finally {
                packetBuffer.release()
            }
        } catch (failure: RuntimeException) {
            throw IOException("Invalid chunk reuse metadata", failure)
        } finally {
            metadataInputBytes.release()
        }
    }
}
