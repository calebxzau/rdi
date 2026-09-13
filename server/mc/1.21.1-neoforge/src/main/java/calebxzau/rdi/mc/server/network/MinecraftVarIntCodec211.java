package calebxzau.rdi.mc.server.network;

import calebxzau.rdi.mc.zstdcodec.MinecraftVarIntCodec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;

public final class MinecraftVarIntCodec211 implements MinecraftVarIntCodec {
    public static final MinecraftVarIntCodec211 INSTANCE = new MinecraftVarIntCodec211();

    private MinecraftVarIntCodec211() {
    }

    @Override
    public int read(ByteBuf buffer) {
        return VarInt.read(buffer);
    }

    @Override
    public void write(ByteBuf buffer, int value) {
        VarInt.write(buffer, value);
    }
}
