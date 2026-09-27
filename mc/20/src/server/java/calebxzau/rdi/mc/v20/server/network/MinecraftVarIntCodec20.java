package calebxzau.rdi.mc.v20.server.network;

import calebxzau.rdi.mc.zstdcodec.MinecraftVarIntCodec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;

public final class MinecraftVarIntCodec20 implements MinecraftVarIntCodec {
    public static final MinecraftVarIntCodec20 INSTANCE = new MinecraftVarIntCodec20();

    private MinecraftVarIntCodec20() {
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
