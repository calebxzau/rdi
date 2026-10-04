package calebxzau.rdi.mc.client.chunkcache;

import calebxzau.rdi.mc.regioncodec.RegionZstdStreams;
import net.minecraft.util.FastBufferedInputStream;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/** Version adapter for the same ID8 stream used by the RDI servers. */
public final class ClientRegionZstd {
    private static RegionFileVersion version;

    private ClientRegionZstd() {
    }

    public static synchronized RegionFileVersion version() {
        if (version == null) {
            if (RegionFileVersion.fromId(RegionZstdStreams.COMPRESSION_ID) != null) {
                throw new IllegalStateException("Region compression ID8 is already registered");
            }
            version = RegionFileVersion.register(new RegionFileVersion(
                    RegionZstdStreams.COMPRESSION_ID,
                    input -> RegionZstdStreams.wrapInput(input, FastBufferedInputStream::new),
                    RegionZstdStreams::wrapOutput));
        }
        return version;
    }
}
