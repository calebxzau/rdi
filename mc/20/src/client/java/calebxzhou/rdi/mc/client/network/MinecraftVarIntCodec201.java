package calebxzhou.rdi.mc.client.network;

import calebxzau.rdi.mc.zstdcodec.MinecraftVarIntCodec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;

public final class MinecraftVarIntCodec201 implements MinecraftVarIntCodec {
    public static final MinecraftVarIntCodec201 INSTANCE = new MinecraftVarIntCodec201();

    private MinecraftVarIntCodec201() {
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
