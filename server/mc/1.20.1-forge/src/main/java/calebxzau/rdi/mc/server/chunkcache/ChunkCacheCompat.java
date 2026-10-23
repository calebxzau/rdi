package calebxzau.rdi.mc.server.chunkcache;

import net.minecraftforge.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether cached-terrain reuse can run on this server. Immersive Portals overwrites
 * {@code ChunkMap.playerLoadedChunk} with an empty method and sends chunks through its own tracker,
 * so the vanilla send path the chunk cache hooks never runs.
 */
public final class ChunkCacheCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("rdi");
    private static final String IMMERSIVE_PORTALS_CORE = "imm_ptl_core";
    private static volatile Boolean available;

    private ChunkCacheCompat() {}

    /** Also read from the mixin plugin, so it relies only on the loading mod list. */
    public static boolean isAvailable() {
        Boolean known = available;
        if (known != null) return known;
        LoadingModList mods = LoadingModList.get();
        // Without a mod list nothing can conflict yet; do not cache that answer.
        if (mods == null) return true;
        boolean result = mods.getModFileById(IMMERSIVE_PORTALS_CORE) == null;
        if (!result) {
            LOGGER.warn("Chunk cache disabled: Immersive Portals ({}) replaces vanilla chunk sending", IMMERSIVE_PORTALS_CORE);
        }
        available = result;
        return result;
    }
}
