package calebxzau.rdi.mc.chunkcache

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.neoforged.neoforge.network.connection.ConnectionType
import java.io.IOException

/** Preserves a vanilla chunk packet's serialized non-terrain bytes around its section payload. */
object ChunkReuseCodec {
    private const val MAX_PACKET_BYTES = ChunkCacheLimits.MAX_METADATA_BYTES + ChunkCacheLimits.MAX_SECTION_BYTES + 64 * 1024

    @JvmStatic
    @Throws(IOException::class)
    fun metadata(packet: ClientboundLevelChunkWithLightPacket, registries: RegistryAccess): ByteArray {
        val encoded = Unpooled.buffer()
        val output = RegistryFriendlyByteBuf(encoded, registries, ConnectionType.NEOFORGE)
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(output, packet)
            if (encoded.readableBytes() > MAX_PACKET_BYTES) throw IOException("Chunk packet exceeds reuse limit")
            val bytes = ByteArray(encoded.readableBytes())
            encoded.getBytes(encoded.readerIndex(), bytes)
            val inputBytes = Unpooled.wrappedBuffer(bytes)
            val input = RegistryFriendlyByteBuf(inputBytes, registries, ConnectionType.NEOFORGE)
            try {
                input.readInt() // Chunk X
                input.readInt() // Chunk Z
                input.readNbt() ?: throw IOException("Missing chunk heightmaps")
                val sectionLengthOffset = input.readerIndex()
                val sectionLength = input.readVarInt()
                if (sectionLength !in 0..ChunkCacheLimits.MAX_SECTION_BYTES || sectionLength > input.readableBytes()) {
                    throw IOException("Invalid chunk section length")
                }
                val prefix = bytes.copyOfRange(0, sectionLengthOffset)
                input.skipBytes(sectionLength)
                val suffix = ByteArray(input.readableBytes())
                input.readBytes(suffix)
                val resultBuffer = Unpooled.buffer()
                try {
                    val result = RegistryFriendlyByteBuf(resultBuffer, registries, ConnectionType.NEOFORGE)
                    result.writeVarInt(prefix.size)
                    result.writeBytes(prefix)
                    result.writeBytes(suffix)
                    if (result.readableBytes() > ChunkCacheLimits.MAX_METADATA_BYTES) {
                        throw IOException("Chunk reuse metadata exceeds limit")
                    }
                    return ByteArray(result.readableBytes()).also { result.readBytes(it) }
                } finally {
                    resultBuffer.release()
                }
            } finally {
                inputBytes.release()
            }
        } catch (failure: RuntimeException) {
            throw IOException("Invalid serialized chunk packet", failure)
        } finally {
            encoded.release()
        }
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
