package calebxzau.rdi.mc.server.chunkcache.mixin;

import calebxzau.rdi.mc.server.chunkcache.ChunkCacheCompat;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Skips the {@code playerLoadedChunk} hooks when another mod owns chunk sending; see {@link ChunkCacheCompat}. */
public final class ChunkCacheMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("rdi");
    private static final Set<String> SEND_PATH_MIXINS = Set.of(
            "calebxzau.rdi.mc.server.chunkcache.mixin.mChunkCacheSender",
            "calebxzau.rdi.mc.server.chunkcache.mixin.mChunkCacheWarmupHold"
    );

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!SEND_PATH_MIXINS.contains(mixinClassName) || ChunkCacheCompat.isAvailable()) return true;
        LOGGER.warn("Skipping chunk cache mixin {} on {}", mixinClassName, targetClassName);
        return false;
    }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
}
