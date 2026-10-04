package calebxzau.rdi.mc.client.chunkcache;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;

/** Copies and binds packet-owned base data. Transferred arrays/tags are detached and exclusively owned. */
public final class ClientChunkSnapshot {
    public static final int MAX_SECTION_BYTES = 2 * 1024 * 1024;
    public static final int MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_BLOCK_ENTITIES = 4096;

    public record BlockEntityData(int x, int y, int z, String type, CompoundTag tag) { }

    /** Packet-only data copied before vanilla schedules packet application on the client thread. */
    public record PacketData(int x, int z, byte[] sections, CompoundTag heightmaps,
                             List<BlockEntityData> blockEntities, long estimatedBytes) {
        public PacketData {
            blockEntities = List.copyOf(blockEntities);
        }
    }

    public record Snapshot(ResourceLocation dimension, int x, int z, int minSection, int sectionCount,
                           long gameTime, long sequence, byte[] sections, CompoundTag heightmaps,
                           List<BlockEntityData> blockEntities) {
        public Snapshot {
            blockEntities = List.copyOf(blockEntities);
        }

        public long estimatedBytes() {
            long size = 256L + sections.length + estimateTag(heightmaps);
            for (BlockEntityData entity : blockEntities) {
                size = checked(size + 64L + 2L * entity.type().length() + estimateTag(entity.tag()));
            }
            return checked(size);
        }
    }

    public static final class TooLargeException extends IllegalArgumentException {
        public TooLargeException(String message) { super(message); }
    }

    private ClientChunkSnapshot() { }

    /** Inspects existing packet storage before allocating any snapshot copies. */
    public static long estimatePacket(ClientboundLevelChunkPacketData data, int x, int z) {
        FriendlyByteBuf buffer = data.getReadBuffer();
        long size;
        try {
            int length = buffer.readableBytes();
            checkSections(length);
            size = checked(256L + length + estimateTag(data.getHeightmaps()));
        } finally {
            buffer.release();
        }
        long[] total = {size};
        int[] count = {0};
        data.getBlockEntitiesTagsConsumer(x, z).accept((pos, type, tag) -> {
            if (++count[0] > MAX_BLOCK_ENTITIES) throw new TooLargeException("Too many block entities");
            String id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString();
            total[0] = checked(total[0] + 64L + 2L * id.length() + estimateTag(tag));
        });
        return total[0];
    }

    /** Packet-only API for callers that do not need a bounded reservation before copying. */
    public static PacketData capturePacketData(int x, int z, ClientboundLevelChunkPacketData data) {
        return copyPacketData(x, z, data, estimatePacket(data, x, z));
    }

    /** Copies packet-owned data after caller has estimated and reserved its bounded memory. */
    public static PacketData copyPacketData(int x, int z, ClientboundLevelChunkPacketData data, long estimatedBytes) {
        FriendlyByteBuf buffer = data.getReadBuffer();
        byte[] sections;
        try {
            checkSections(buffer.readableBytes());
            sections = new byte[buffer.readableBytes()];
            buffer.readBytes(sections);
        } finally {
            buffer.release();
        }
        List<BlockEntityData> entities = new ArrayList<>();
        data.getBlockEntitiesTagsConsumer(x, z).accept((pos, type, tag) -> {
            // The packet callback reuses its MutableBlockPos; retain only primitive coordinates.
            entities.add(new BlockEntityData(pos.getX(), pos.getY(), pos.getZ(),
                    BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString(), tag == null ? null : tag.copy()));
        });
        return new PacketData(x, z, sections, data.getHeightmaps().copy(), entities, checked(estimatedBytes));
    }

    /** Binds level metadata on the main thread after packet order is established by vanilla. */
    public static Snapshot bindPacketData(PacketData packet, ResourceLocation dimension, int minSection,
                                          int sectionCount, long gameTime, long sequence) {
        return new Snapshot(dimension, packet.x(), packet.z(), minSection, sectionCount,
                gameTime, sequence, packet.sections(), packet.heightmaps(), packet.blockEntities());
    }

    /** Test utility for section byte encoding; runtime cache writes use packet-owned bytes. */
    public static byte[] encodeSections(LevelChunkSection[] sections) {
        long length = 0;
        for (LevelChunkSection section : sections) length += section.getSerializedSize();
        checkSections(length);
        FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer((int) length));
        try {
            for (LevelChunkSection section : sections) section.write(buffer);
            // 1.20.1 over-estimates single-value containers, so keep only the bytes actually written.
            if (buffer.writerIndex() > length) throw new IllegalStateException("Section encoding length changed");
            byte[] bytes = new byte[buffer.writerIndex()];
            buffer.getBytes(0, bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    /** Saturation checked, wide accounting instead of CompoundTag.sizeInBytes()'s int sums. */
    public static long estimateTag(Tag tag) { return estimateTag(tag, 0); }

    private static long estimateTag(Tag tag, int depth) {
        if (tag == null) return 0;
        if (depth > 128) throw new TooLargeException("NBT nesting exceeds cache limit");
        long size = 48;
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                size = checked(size + 64L + 2L * key.length() + estimateTag(compound.get(key), depth + 1));
            }
        } else if (tag instanceof ListTag list) {
            for (Tag value : list) size = checked(size + 8 + estimateTag(value, depth + 1));
        } else if (tag instanceof ByteArrayTag array) {
            size += array.getAsByteArray().length;
        } else if (tag instanceof IntArrayTag array) {
            size += 4L * array.getAsIntArray().length;
        } else if (tag instanceof LongArrayTag array) {
            size += 8L * array.getAsLongArray().length;
        } else if (tag instanceof StringTag string) {
            size += 2L * string.getAsString().length();
        } else {
            size += tag.sizeInBytes();
        }
        return checked(size);
    }

    private static long checked(long size) {
        if (size < 0 || size > MAX_SNAPSHOT_BYTES) throw new TooLargeException("Chunk snapshot exceeds cache limit");
        return size;
    }

    private static void checkSections(long length) {
        if (length < 0 || length > MAX_SECTION_BYTES) throw new TooLargeException("Section bytes exceed cache limit");
    }
}
