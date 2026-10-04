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

    public sealed interface Update permits BlockChange, BlockEntityChange, Biomes { }

    public record BlockChange(long position, int stateId, boolean hasBlockEntity) implements Update { }

    /** Keep packet order: mod block entity tags are not necessarily complete snapshots. */
    public record BlockEntityChange(long position, String type, CompoundTag tag) implements Update { }

    /** All section biome palettes for this chunk, in vanilla packet order. */
    public record Biomes(byte[] data) implements Update { }
}
