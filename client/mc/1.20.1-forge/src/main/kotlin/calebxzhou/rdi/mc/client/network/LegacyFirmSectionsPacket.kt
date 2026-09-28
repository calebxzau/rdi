package calebxzhou.rdi.mc.client.network

import net.minecraft.network.FriendlyByteBuf

/** Decode-only compatibility for protocol-3 packet 1; no FirmSection state is retained. */
internal object LegacyFirmSectionsPacket {
    private const val MAX_DIMENSION_ID_LENGTH = 512
    private const val MAX_ENTRIES = 65536

    fun decode(buf: FriendlyByteBuf): LegacyFirmSectionsPacket {
        val count = buf.readVarInt()
        require(count in 0..MAX_ENTRIES) { "Invalid legacy firm section count: $count" }
        repeat(count) {
            buf.readUtf(MAX_DIMENSION_ID_LENGTH)
            buf.readInt() // chunkX
            buf.readInt() // sectionY
            buf.readInt() // chunkZ
        }
        return this
    }
}
