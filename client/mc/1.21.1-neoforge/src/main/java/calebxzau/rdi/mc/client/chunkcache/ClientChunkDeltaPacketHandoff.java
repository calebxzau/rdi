package calebxzau.rdi.mc.client.chunkcache;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Copies mutable update-packet data before vanilla schedules its ordered client-thread application. */
public final class ClientChunkDeltaPacketHandoff {
    public record ChunkUpdate(int x, int z, List<ClientChunkCacheWrite.Update> updates, long estimatedBytes) {
        public ChunkUpdate {
            updates = List.copyOf(updates);
        }
    }

    public record PacketUpdates(List<ChunkUpdate> updates, long estimatedBytes) {
        public PacketUpdates {
            updates = List.copyOf(updates);
        }
    }

    private static final class Entry {
        final long bytes;
        PacketUpdates updates;

        Entry(long bytes) { this.bytes = bytes; }
    }

    private final int maxEntries;
    private final long maxBytes;
    private final Map<Packet<?>, Entry> entries = new IdentityHashMap<>();
    private long pendingBytes;
    private boolean closed;

    public ClientChunkDeltaPacketHandoff(int maxEntries, long maxBytes) {
        if (maxEntries <= 0 || maxBytes <= 0) throw new IllegalArgumentException("Handoff bounds must be positive");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    /** Returns copied bytes, -1 when rejected, and 0 for packet types outside the delta contract. */
    public long capture(Packet<?> packet, long maxPacketBytes) {
        if (!(packet instanceof ClientboundBlockUpdatePacket)
                && !(packet instanceof ClientboundSectionBlocksUpdatePacket)
                && !(packet instanceof ClientboundBlockEntityDataPacket)
                && !(packet instanceof ClientboundChunksBiomesPacket)) return 0;
        if (maxPacketBytes <= 0) return -1;
        if (packet instanceof ClientboundBlockUpdatePacket value) {
            var pos = value.getPos();
            BlockState state = value.getBlockState();
            long size = 96;
            return capture(packet, size, maxPacketBytes, () -> List.of(new ChunkUpdate(pos.getX() >> 4, pos.getZ() >> 4,
                    List.of(new ClientChunkCacheWrite.BlockChange(pos.asLong(), Block.getId(state), state.hasBlockEntity())), size)));
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket value) {
            int[] count = {0};
            value.runUpdates((pos, state) -> count[0]++);
            long size = add(64, 96L * count[0]);
            return capture(packet, Math.max(1, size), maxPacketBytes, () -> {
                List<ClientChunkCacheWrite.Update> updates = new ArrayList<>(count[0]);
                int[] chunk = {0, 0};
                boolean[] first = {true};
                value.runUpdates((pos, state) -> {
                    if (first[0]) { chunk[0] = pos.getX() >> 4; chunk[1] = pos.getZ() >> 4; first[0] = false; }
                    updates.add(new ClientChunkCacheWrite.BlockChange(pos.asLong(), Block.getId(state), state.hasBlockEntity()));
                });
                return updates.isEmpty() ? List.of() : List.of(new ChunkUpdate(chunk[0], chunk[1], updates, size));
            });
        }
        if (packet instanceof ClientboundBlockEntityDataPacket value) {
            var pos = value.getPos();
            String type = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(value.getType()).toString();
            var sourceTag = value.getTag();
            long size = add(128L + 2L * type.length(), ClientChunkSnapshot.estimateTag(sourceTag));
            return capture(packet, size, maxPacketBytes, () -> List.of(new ChunkUpdate(pos.getX() >> 4, pos.getZ() >> 4,
                    List.of(new ClientChunkCacheWrite.BlockEntityChange(pos.asLong(), type,
                            sourceTag == null ? null : sourceTag.copy())), size)));
        }
        if (packet instanceof ClientboundChunksBiomesPacket value) {
            long total = 64;
            for (var data : value.chunkBiomeData()) total = add(total, 96L + data.buffer().length);
            long reserved = Math.max(1, total);
            return capture(packet, reserved, maxPacketBytes, () -> {
                Map<Long, List<ClientChunkCacheWrite.Update>> grouped = new LinkedHashMap<>();
                Map<Long, Long> bytesByChunk = new LinkedHashMap<>();
                for (var data : value.chunkBiomeData()) {
                    byte[] source = data.buffer();
                    ChunkPos pos = data.pos();
                    long key = ChunkPos.asLong(pos.x, pos.z);
                    grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new ClientChunkCacheWrite.Biomes(source.clone()));
                    bytesByChunk.merge(key, add(96, source.length), ClientChunkDeltaPacketHandoff::add);
                }
                List<ChunkUpdate> updates = new ArrayList<>(grouped.size());
                grouped.forEach((key, chunkUpdates) -> updates.add(new ChunkUpdate(ChunkPos.getX(key),
                        ChunkPos.getZ(key), chunkUpdates, bytesByChunk.get(key))));
                return updates;
            });
        }
        return 0;
    }

    private long capture(Packet<?> packet, long bytes, long packetLimit, Supplier<List<ChunkUpdate>> copier) {
        if (bytes <= 0 || bytes > packetLimit || bytes > ClientChunkSnapshot.MAX_SNAPSHOT_BYTES) return -1;
        Entry reservation = new Entry(bytes);
        synchronized (this) {
            if (closed || entries.containsKey(packet) || entries.size() >= maxEntries || bytes <= 0
                    || bytes > maxBytes - pendingBytes) return -1;
            entries.put(packet, reservation);
            pendingBytes += bytes;
        }
        try {
            PacketUpdates detached = new PacketUpdates(copier.get(), bytes);
            synchronized (this) {
                if (closed || entries.get(packet) != reservation) {
                    removeIfCurrent(packet, reservation);
                    return -1;
                }
                reservation.updates = detached;
                return bytes;
            }
        } catch (RuntimeException | Error error) {
            synchronized (this) { removeIfCurrent(packet, reservation); }
            throw error;
        }
    }

    public synchronized PacketUpdates consume(Packet<?> packet) {
        Entry entry = entries.remove(packet);
        if (entry == null) return null;
        pendingBytes -= entry.bytes;
        return entry.updates;
    }

    public synchronized void close() {
        closed = true;
        entries.clear();
        pendingBytes = 0;
    }

    public synchronized int pendingEntries() { return entries.size(); }

    public synchronized long pendingBytes() { return pendingBytes; }

    public synchronized boolean isClosed() { return closed; }

    private void removeIfCurrent(Packet<?> packet, Entry entry) {
        if (entries.remove(packet, entry)) pendingBytes -= entry.bytes;
    }

    private static long add(long left, long right) {
        long result = left + right;
        if (result < left) throw new ClientChunkSnapshot.TooLargeException("Chunk update size overflow");
        return result;
    }

}
