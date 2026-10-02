package calebxzau.rdi.mc.client.chunkcache;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static calebxzhou.rdi.mc.common.RDI.HOST_ID;

/** Captures detached server packet data in network order and binds cache metadata on the game thread. */
public final class RdiChunkCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_PACKET_HANDOFF_ENTRIES = 4096;
    private static final long MAX_PACKET_HANDOFF_BYTES = 256L * 1024 * 1024;
    private static final long LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final String SHARED_HOST_ID = "shared";
    private static final ChunkCacheWriterLifecycle LIFECYCLE = new ChunkCacheWriterLifecycle();
    private static volatile Session current;

    private static final class Session {
        final String hostId;
        final AtomicBoolean failed = new AtomicBoolean();
        volatile PacketCaptureContext packetCaptureContext;
        final LongAdder networkCaptureNanos = new LongAdder();
        final LongAdder networkCaptures = new LongAdder();
        final AtomicLong networkMaxNanos = new AtomicLong();
        long mainBindNanos;
        long baseCaptures;
        long deltaWrites;
        long lastLogNanos = System.nanoTime();
        long sequence;
        volatile boolean closed;
        volatile ClientChunkDeltaWriter writer;

        Session(String hostId) { this.hostId = hostId; }
    }

    /** Opaque context bound to one ClientPacketListener and its owning connection session. */
    public static final class PacketCaptureContext {
        private final Session owner;
        private final ClientChunkPacketHandoff bases = new ClientChunkPacketHandoff(
                MAX_PACKET_HANDOFF_ENTRIES, MAX_PACKET_HANDOFF_BYTES);
        private final ClientChunkDeltaPacketHandoff deltas = new ClientChunkDeltaPacketHandoff(
                MAX_PACKET_HANDOFF_ENTRIES, MAX_PACKET_HANDOFF_BYTES);

        private PacketCaptureContext(Session owner) { this.owner = owner; }

        public void close() {
            bases.close();
            deltas.close();
            if (owner.packetCaptureContext == this) owner.packetCaptureContext = null;
        }
    }

    private RdiChunkCache() { }

    /** All multiplayer connections share one cache namespace, independent of the join UI. */
    public static void beginConnection() {
        endConnection();
        Session session = new Session(HOST_ID);
        current = session;
        Path root = Path.of("rdi").resolve("hosts").resolve(HOST_ID).resolve("world");
        root.toFile().mkdirs();
        ClientChunkDeltaWriter writer = new ClientChunkDeltaWriter(root,
                failure -> fail(session, failure), 4096, 256L * 1024 * 1024, false);
        session.writer = writer;
        LIFECYCLE.open(() -> session.closed || session.failed.get(),
                () -> { writer.start(); return writer; },
                ignored -> { }, failure -> fail(session, failure));
    }

    /** Returns a listener-bound packet context; old listeners never consult a newer session. */
    public static PacketCaptureContext createPacketCaptureContext() {
        Session session = current;
        if (session == null) return null;
        PacketCaptureContext context = new PacketCaptureContext(session);
        PacketCaptureContext previous = session.packetCaptureContext;
        session.packetCaptureContext = context;
        if (previous != null) previous.close();
        if (current != session || session.closed || session.failed.get()) {
            session.packetCaptureContext = null;
            context.close();
            return null;
        }
        return context;
    }

    /** Called before vanilla's same-thread guard. Only detached packet data is copied here. */
    public static void captureBase(PacketCaptureContext context, int x, int z,
                                   ClientboundLevelChunkPacketData data) {
        capture(context, () -> context.bases.capture(data, x, z) ? 1 : -1);
    }

    public static void captureDelta(PacketCaptureContext context, Packet<?> packet) {
        captureDelta(context, packet, ClientChunkSnapshot.MAX_SNAPSHOT_BYTES);
    }

    public static boolean isCacheActive(PacketCaptureContext context) {
        return context != null && isCurrent(context.owner) && context.owner.packetCaptureContext == context;
    }

    /** Queued on the existing file owner after all earlier writes, never a second RegionFile reader. */
    public static CompletableFuture<ClientChunkDeltaStore.Combined> readCached(
            PacketCaptureContext context, ClientChunkRegionSink.Key key) {
        if (!isCacheActive(context) || context.owner.writer == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Chunk cache session is not active"));
        }
        return context.owner.writer.read(key);
    }

    /** Terrain-only projection queued on the same ordered file owner as writes and full reads. */
    public static CompletableFuture<ClientChunkDeltaStore.Terrain> readCachedTerrain(
            PacketCaptureContext context, ClientChunkRegionSink.Key key) {
        if (!isCacheActive(context) || context.owner.writer == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Chunk cache session is not active"));
        }
        return context.owner.writer.readTerrain(key);
    }

    public static void discardDelta(PacketCaptureContext context, Packet<?> packet) {
        if (context != null) context.deltas.consume(packet);
    }

    /** Returns copied bytes, zero for unrelated bundle members and -1 on a cache failure. */
    public static long captureDelta(PacketCaptureContext context, Packet<?> packet, long packetLimit) {
        return capture(context, () -> context.deltas.capture(packet, packetLimit));
    }

    private static long capture(PacketCaptureContext context, Capture action) {
        if (context == null) return 0;
        Session session = context.owner;
        if (session.closed || session.failed.get() || session.packetCaptureContext != context) return 0;
        long started = System.nanoTime();
        try {
            long copied = action.capture();
            if (copied < 0) fail(session, new IllegalStateException("Chunk packet handoff capacity exceeded"));
            return copied;
        } catch (Throwable error) {
            fail(session, error);
            return -1;
        } finally {
            long elapsed = System.nanoTime() - started;
            session.networkCaptureNanos.add(elapsed);
            session.networkCaptures.increment();
            session.networkMaxNanos.accumulateAndGet(elapsed, Math::max);
        }
    }

    @FunctionalInterface
    private interface Capture { long capture(); }

    /** Called during vanilla's ordered game-thread application, before packet-owned tags are applied. */
    public static void receivedBase(ClientLevel level, int x, int z, ClientboundLevelChunkPacketData data,
                                    PacketCaptureContext context) {
        if (context == null) return;
        Session session = context.owner;
        if (!isActiveLevel(session, level) || session.packetCaptureContext != context) return;
        ClientChunkSnapshot.PacketData packet = context.bases.consume(data);
        if (packet == null) {
            fail(session, new IllegalStateException("Full chunk packet reached client thread without a detached network capture"));
            return;
        }
        long started = System.nanoTime();
        try {
            ResourceLocation dimension = level.dimension().location();
            long sequence = ++session.sequence;
            ClientChunkSnapshot.Snapshot base = ClientChunkSnapshot.bindPacketData(packet, dimension,
                    level.getMinSection(), level.getSectionsCount(), level.getGameTime(), sequence);
            ClientChunkCacheWrite write = new ClientChunkCacheWrite(
                    new ClientChunkRegionSink.Key(dimension, x, z), sequence, level.getGameTime(),
                    base, List.of(), packet.estimatedBytes());
            submit(session, write);
            session.baseCaptures++;
        } catch (Throwable error) {
            fail(session, error);
        } finally {
            session.mainBindNanos += System.nanoTime() - started;
        }
    }

    /** Called from packet-handler tails after vanilla has applied each update on the game thread. */
    public static void receivedDelta(ClientLevel level, Packet<?> packet, PacketCaptureContext context) {
        if (context == null) return;
        Session session = context.owner;
        if (!isActiveLevel(session, level) || session.packetCaptureContext != context) return;
        ClientChunkDeltaPacketHandoff.PacketUpdates detached = context.deltas.consume(packet);
        if (detached == null) {
            fail(session, new IllegalStateException("Chunk update reached client thread without a detached network capture"));
            return;
        }
        if (detached.updates().isEmpty()) return;
        long started = System.nanoTime();
        try {
            ResourceLocation dimension = level.dimension().location();
            for (var update : detached.updates()) {
                int x = update.x();
                int z = update.z();
                if (ChunkCacheClient.waitingForRepair(level, x, z)) continue;
                long estimatedBytes = 128 + update.estimatedBytes();
                if (estimatedBytes > ClientChunkSnapshot.MAX_SNAPSHOT_BYTES) {
                    throw new ClientChunkSnapshot.TooLargeException("Merged chunk delta exceeds cache limit");
                }
                long sequence = ++session.sequence;
                submit(session, new ClientChunkCacheWrite(new ClientChunkRegionSink.Key(dimension, x, z),
                        sequence, level.getGameTime(), null, update.updates(), estimatedBytes));
                session.deltaWrites++;
                if (session.failed.get()) return;
            }
        } catch (Throwable error) {
            fail(session, error);
        } finally {
            session.mainBindNanos += System.nanoTime() - started;
        }
    }

    private static void submit(Session session, ClientChunkCacheWrite write) {
        ClientChunkDeltaWriter writer = session.writer;
        if (writer == null) {
            fail(session, new IllegalStateException("Chunk cache writer is not ready"));
            return;
        }
        if (!writer.submit(write)) fail(session, new IllegalStateException("Chunk cache writer rejected an ordered update"));
    }

    private static boolean isCurrent(Session session) {
        return current == session && !session.closed && !session.failed.get();
    }

    private static boolean isActiveLevel(Session session, ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        return isCurrent(session) && level != null && minecraft.level == level && !minecraft.hasSingleplayerServer();
    }

    /** Periodic observability only; no chunk or level data is read. */
    public static void tickStats() {
        Session session = current;
        if (session != null && !session.closed && System.nanoTime() - session.lastLogNanos >= LOG_INTERVAL_NANOS) {
            logStats(session);
        }
    }

    private static void fail(Session session, Throwable error) {
        if (!session.failed.compareAndSet(false, true)) return;
        LOGGER.error("Client chunk saving paused for room {}", session.hostId, error);
        PacketCaptureContext context = session.packetCaptureContext;
        if (context != null) context.close();
        ClientChunkDeltaWriter writer = session.writer;
        if (writer != null) {
            writer.discardPending();
            writer.closeAsync();
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (current == session) {
                minecraft.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(
                        "区块缓存保存失败，本次连接已暂停保存，请检查游戏日志"));
            }
        });
    }

    private static void logStats(Session session) {
        session.lastLogNanos = System.nanoTime();
        LOGGER.info("CHUNK_CACHE room={} base_captures={} delta_writes={} network_captures={} network_copy_ms={} network_max_ms={} main_bind_ms={} writer={}",
                session.hostId, session.baseCaptures, session.deltaWrites, session.networkCaptures.sum(),
                session.networkCaptureNanos.sum() / 1_000_000.0, session.networkMaxNanos.get() / 1_000_000.0,
                session.mainBindNanos / 1_000_000.0, session.writer == null ? "starting" : session.writer.stats());
    }

    public static void endConnection() {
        Session session = current;
        current = null;
        if (session == null) return;
        session.closed = true;
        PacketCaptureContext context = session.packetCaptureContext;
        if (context != null) context.close();
        if (session.writer != null) session.writer.closeAsync();
        logStats(session);
    }

    public static void shutdown() {
        endConnection();
        try {
            if (!LIFECYCLE.shutdown(2_000)) {
                LOGGER.warn("Client chunk cache shutdown exceeded 2 seconds; remaining cache writes may be lost");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException error) {
            LOGGER.error("Failed to close client chunk cache", error.getCause());
        }
    }
}
