package calebxzau.rdi.mc.client.chunkcache;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;

import java.util.IdentityHashMap;
import java.util.Map;

/** Bounded per-listener bridge from packet ingress to vanilla's ordered main-thread application. */
public final class ClientChunkPacketHandoff {
    private static final class Entry {
        final long reservedBytes;
        ClientChunkSnapshot.PacketData packetData;

        Entry(long reservedBytes) { this.reservedBytes = reservedBytes; }
    }

    private final int maxEntries;
    private final long maxBytes;
    private final Map<ClientboundLevelChunkPacketData, Entry> entries = new IdentityHashMap<>();
    private long pendingBytes;
    private long captureNanos;
    private long captures;
    private long skipped;
    private boolean closed;

    public ClientChunkPacketHandoff(int maxEntries, long maxBytes) {
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    /** Estimates and reserves before allocating copies; concurrent in-flight copies count against both limits. */
    public boolean capture(ClientboundLevelChunkPacketData data, int x, int z) {
        long started = System.nanoTime();
        boolean captured = false;
        try {
            captured = captureInternal(data, x, z);
            return captured;
        } finally {
            synchronized (this) {
                captureNanos += System.nanoTime() - started;
                if (captured) captures++;
                else skipped++;
            }
        }
    }

    private boolean captureInternal(ClientboundLevelChunkPacketData data, int x, int z) {
        synchronized (this) {
            if (closed || entries.containsKey(data) || entries.size() >= maxEntries || pendingBytes >= maxBytes) return false;
        }

        final long estimate;
        try {
            estimate = ClientChunkSnapshot.estimatePacket(data, x, z);
        } catch (ClientChunkSnapshot.TooLargeException error) {
            return false;
        }

        Entry reservation = new Entry(estimate);
        synchronized (this) {
            if (closed || entries.containsKey(data) || entries.size() >= maxEntries
                    || estimate <= 0 || estimate > maxBytes - pendingBytes) return false;
            entries.put(data, reservation);
            pendingBytes += estimate;
        }

        ClientChunkSnapshot.PacketData captured;
        try {
            captured = ClientChunkSnapshot.copyPacketData(x, z, data, estimate);
        } catch (RuntimeException | Error error) {
            synchronized (this) {
                removeIfCurrent(data, reservation);
            }
            throw error;
        }

        synchronized (this) {
            if (closed || entries.get(data) != reservation) {
                removeIfCurrent(data, reservation);
                return false;
            }
            reservation.packetData = captured;
            return true;
        }
    }

    /** Consumes only completed data. The packet stays unmodified for vanilla application. */
    public synchronized ClientChunkSnapshot.PacketData consume(ClientboundLevelChunkPacketData data) {
        Entry entry = entries.remove(data);
        if (entry == null) return null;
        pendingBytes -= entry.reservedBytes;
        return entry.packetData;
    }

    public synchronized void close() {
        closed = true;
        entries.clear();
        pendingBytes = 0;
    }

    public synchronized int pendingEntries() { return entries.size(); }

    public synchronized long pendingBytes() { return pendingBytes; }

    public synchronized boolean isClosed() { return closed; }

    public synchronized long captureNanos() { return captureNanos; }

    public synchronized long captures() { return captures; }

    public synchronized long skipped() { return skipped; }

    private void removeIfCurrent(ClientboundLevelChunkPacketData data, Entry entry) {
        if (entries.remove(data, entry)) pendingBytes -= entry.reservedBytes;
    }
}
