package calebxzau.rdi.mc.client.chunkcache;

import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Objects;

/** Detached, main-thread-ordered input to the cache writer. No live chunks or block entities. */
public record ClientChunkCacheWrite(ClientChunkRegionSink.Key key, long sequence, long gameTime,
                                    ClientChunkSnapshot.Snapshot base, List<Update> updates, long estimatedBytes) {
    public ClientChunkCacheWrite {
        Objects.requireNonNull(key, "key");
        updates = List.copyOf(updates);
        if ((base == null) == updates.isEmpty()) throw new IllegalArgumentException("Expected one base or nonempty updates");
        if (sequence <= 0 || estimatedBytes <= 0 || estimatedBytes > ClientChunkSnapshot.MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("Invalid cache write bounds");
        }
    }

    public sealed interface Update permits BlockChange, BlockEntityChange, Biomes, Invalidate { }

    public record BlockChange(long position, int stateId, boolean hasBlockEntity) implements Update { }

    /**
     * Kept in packet order. The store keeps only the latest non-empty tag per position, the loaders' default
     * of a full reload; see {@code ClientChunkDeltaStore.compactEvents}.
     */
    public record BlockEntityChange(long position, String type, CompoundTag tag) implements Update { }

    /** All section biome palettes for this chunk, in vanilla packet order. */
    public record Biomes(byte[] data) implements Update { }

    /** Forgets this chunk, whose update was not captured; later updates are ignored until its next full chunk. */
    public record Invalidate() implements Update { }
}
