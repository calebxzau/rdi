package calebxzau.rdi.mc.v20.server.region;

import calebxzau.rdi.mc.regioncodec.RegionZstdStreams;
import net.minecraft.util.FastBufferedInputStream;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Minecraft 1.20.1 registration and buffering adapter for the shared ID8 codec. */
public final class RegionZstdCodec {
    public static final int COMPRESSION_ID = RegionZstdStreams.COMPRESSION_ID;
    public static final int COMPRESSION_LEVEL = RegionZstdStreams.COMPRESSION_LEVEL;
    private static volatile RegionFileVersion version;

    private RegionZstdCodec() {
    }

    public static RegionFileVersion register() {
        // Initialize the registry before locking: its <clinit> hook can reenter
        // this method, or another thread can already be initializing it.
        RegionFileVersion.fromId(COMPRESSION_ID);
        synchronized (RegionZstdCodec.class) {
            if (version != null) {
                return version;
            }
            RegionFileVersion existing = RegionFileVersion.fromId(COMPRESSION_ID);
            if (existing != null) {
                throw new IllegalStateException("Region compression ID " + COMPRESSION_ID + " is already registered");
            }
            version = RegionFileVersion.register(new RegionFileVersion(
                COMPRESSION_ID,
                RegionZstdCodec::wrapInput,
                RegionZstdCodec::wrapOutput
            ));
            return version;
        }
    }

    public static InputStream wrapInput(InputStream input) throws IOException {
        return RegionZstdStreams.wrapInput(input, FastBufferedInputStream::new);
    }

    public static OutputStream wrapOutput(OutputStream output) {
        return RegionZstdStreams.wrapOutput(output);
    }

    public static DataOutputStream wrapDataOutput(OutputStream wrapped, DataOutputStream output) {
        return RegionZstdStreams.wrapDataOutput(wrapped, output);
    }

    public static void abort(DataOutput output) {
        RegionZstdStreams.abort(output);
    }

    public static void closeAll() {
        RegionZstdStreams.closeAll();
    }
}
