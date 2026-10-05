package calebxzau.rdi.mc.syncchunk.network

import calebxzau.rdi.mc.syncchunk.SyncChunkList
import calebxzau.rdi.mc.syncchunk.SyncChunkListCodec
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/** 1.21 transport for the sync chunk list; a malformed list arrives as a failure instead of disconnecting. */
class RSyncChunksPayload(val list: Result<SyncChunkList>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<RSyncChunksPayload> = CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath("rdi", "sync_chunks")
        )
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RSyncChunksPayload> = CustomPacketPayload.codec(
            { payload, buf -> SyncChunkListCodec.write(payload.list.getOrThrow(), buf) },
            { buf -> RSyncChunksPayload(SyncChunkListCodec.read(buf)) },
        )

        fun of(list: SyncChunkList): RSyncChunksPayload = RSyncChunksPayload(Result.success(list))
    }
}
