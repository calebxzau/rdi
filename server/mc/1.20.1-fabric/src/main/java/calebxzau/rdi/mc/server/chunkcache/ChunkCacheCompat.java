package calebxzau.rdi.mc.server.chunkcache;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

/** Fabric's loader check for the shared vanilla chunk-send hooks. */
public final class ChunkCacheCompat {
    private static final boolean AVAILABLE = !FabricLoader.getInstance().isModLoaded("imm_ptl_core");

    static {
        if (!AVAILABLE) {
            LoggerFactory.getLogger("rdi").warn("Chunk cache disabled: Immersive Portals replaces vanilla chunk sending");
        }
    }

    private ChunkCacheCompat() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }
}
