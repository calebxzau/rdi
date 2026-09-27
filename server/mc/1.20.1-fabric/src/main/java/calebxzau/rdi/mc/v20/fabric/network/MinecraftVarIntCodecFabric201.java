package calebxzau.rdi.mc.v20.fabric.network;

import calebxzau.rdi.mc.zstdcodec.MinecraftVarIntCodec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;

public final class MinecraftVarIntCodecFabric201 implements MinecraftVarIntCodec {
    public static final MinecraftVarIntCodecFabric201 INSTANCE = new MinecraftVarIntCodecFabric201();

    private MinecraftVarIntCodecFabric201() {
    }

    @Override
    public int read(ByteBuf buffer) {
        return new FriendlyByteBuf(buffer).readVarInt();
    }

    @Override
    public void write(ByteBuf buffer, int value) {
        new FriendlyByteBuf(buffer).writeVarInt(value);
    }
}
