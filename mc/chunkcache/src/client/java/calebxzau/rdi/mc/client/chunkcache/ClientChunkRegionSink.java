package calebxzau.rdi.mc.client.chunkcache;

import calebxzau.rdi.mc.regioncodec.RegionZstdStreams;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Owned exclusively by one queue worker. Files use vanilla sector/header/sidecar handling. */
public final class ClientChunkRegionSink implements CoalescingWriteQueue.Sink<ClientChunkRegionSink.Key, ClientChunkSnapshot.Snapshot> {
    public record Key(ResourceLocation dimension, int x, int z) {
    }

    private final Path worldRoot;
    private final LinkedHashMap<Path, RegionFile> regions = new LinkedHashMap<>(16, 0.75f, true);

    public ClientChunkRegionSink(Path worldRoot) {
        this.worldRoot = worldRoot.toAbsolutePath().normalize();
    }

    public Path directory(ResourceLocation dimension) throws IOException {
        Path directory = worldRoot.resolve("dimensions");
        String[] components = (dimension.getNamespace() + "/" + dimension.getPath()).split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals("..") || component.endsWith(".")) {
                throw new IOException("Invalid dimension directory component: " + dimension);
            }
            directory = directory.resolve(component);
        }
        return directory;
    }

    @Override
    public void write(Key key, ClientChunkSnapshot.Snapshot snapshot) throws IOException {
        if (key == null || snapshot == null) throw new IOException("Chunk key and snapshot are required");
        if (!key.dimension().equals(snapshot.dimension()) || key.x() != snapshot.x() || key.z() != snapshot.z()) {
            throw new IOException("Chunk key does not match snapshot coordinates");
        }

        // Finish bounded record encoding before opening a RegionFile output stream. This preserves
        // an existing record even when the optional runtime abort mixin is not active.
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        ClientChunkBinaryCodec.write(new DataOutputStream(record), snapshot);
        writeEncoded(key, record.toByteArray());
    }

    /** Writes a fully encoded bounded record, preserving the prior chunk if encoding failed. */
    public void writeEncoded(Key key, byte[] record) throws IOException {
        if (key == null || record == null || record.length == 0 || record.length > ClientChunkSnapshot.MAX_SNAPSHOT_BYTES + 64L) {
            throw new IOException("Invalid encoded chunk record");
        }

        Path directory = directory(key.dimension());
        Path path = directory.resolve("r." + (key.x() >> 5) + "." + (key.z() >> 5) + ".mca");
        RegionFile region = regions.get(path);
        if (region == null) {
            if (regions.size() >= 32) {
                var iterator = regions.entrySet().iterator();
                var oldest = iterator.next();
                oldest.getValue().close();
                iterator.remove();
            }
            Files.createDirectories(directory);
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, key.dimension());
            region = ChunkCacheCompat.openRegion("rdi-client-chunks", dimension, path, directory);
            regions.put(path, region);
        }
        try (var output = region.getChunkDataOutputStream(new ChunkPos(key.x(), key.z()))) {
            try {
                output.write(record);
            } catch (Throwable failure) {
                RegionZstdStreams.abort(output);
                throw failure;
            }
        }
    }

    /** Reads a bounded record from an existing region. The returned array is detached. */
    public byte[] readEncoded(Key key) throws IOException {
        if (key == null) throw new IOException("Chunk key is required");
        Path directory = directory(key.dimension());
        Path path = directory.resolve("r." + (key.x() >> 5) + "." + (key.z() >> 5) + ".mca");
        if (!Files.isRegularFile(path)) return null;
        RegionFile region = regions.get(path);
        if (region == null) {
            if (regions.size() >= 32) {
                var iterator = regions.entrySet().iterator();
                var oldest = iterator.next();
                oldest.getValue().close();
                iterator.remove();
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, key.dimension());
            region = ChunkCacheCompat.openRegion("rdi-client-chunks", dimension, path, directory);
            regions.put(path, region);
        }
        try (InputStream input = region.getChunkDataInputStream(new ChunkPos(key.x(), key.z()))) {
            if (input == null) return null;
            ByteArrayOutputStream record = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (record.size() + read > ClientChunkSnapshot.MAX_SNAPSHOT_BYTES + 64L) {
                    throw new IOException("Encoded chunk record exceeds limit");
                }
                record.write(buffer, 0, read);
            }
            return record.toByteArray();
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (RegionFile region : regions.values()) {
            try {
                region.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        regions.clear();
        if (failure != null) throw failure;
    }
}
