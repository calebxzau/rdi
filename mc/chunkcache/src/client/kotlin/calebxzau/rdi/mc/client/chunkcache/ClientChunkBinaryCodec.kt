package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkTerrainCodec
import calebxzau.rdi.mc.client.chunkcache.ClientChunkSnapshot.BlockEntityData
import calebxzau.rdi.mc.client.chunkcache.ClientChunkSnapshot.Snapshot
import io.netty.buffer.Unpooled
import net.minecraft.core.Registry
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceLocation
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Versioned RDI payload inside MCA/ID8. Numeric palettes require the original registry mapping. */
object ClientChunkBinaryCodec {
    const val MAGIC: Int = 0x52444348 // RDCH
    const val VERSION: Int = 1
    private const val MAX_SECTIONS = 256
    private const val MAX_NAME_BYTES = 2048

    @JvmStatic
    @Throws(IOException::class)
    fun write(output: DataOutput, snapshot: Snapshot) {
        validate(snapshot)
        val bounded = DataOutputStream(object : OutputStream() {
            private var written = 0L
            private fun reserve(length: Int) {
                written += length
                if (written > ClientChunkSnapshot.MAX_SNAPSHOT_BYTES) throw IOException("Binary chunk exceeds record limit")
            }
            override fun write(value: Int) {
                reserve(1)
                output.writeByte(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                reserve(length)
                output.write(bytes, offset, length)
            }
        })
        bounded.writeInt(MAGIC)
        bounded.writeShort(VERSION)
        writeName(bounded, snapshot.dimension().toString())
        bounded.writeInt(snapshot.x())
        bounded.writeInt(snapshot.z())
        bounded.writeInt(snapshot.minSection())
        bounded.writeInt(snapshot.sectionCount())
        bounded.writeLong(snapshot.gameTime())
        bounded.writeLong(snapshot.sequence())
        bounded.writeInt(snapshot.sections().size)
        bounded.write(snapshot.sections())
        NbtIo.write(snapshot.heightmaps(), bounded)
        bounded.writeInt(snapshot.blockEntities().size)
        for (entity in snapshot.blockEntities()) {
            bounded.writeInt(entity.x())
            bounded.writeInt(entity.y())
            bounded.writeInt(entity.z())
            writeName(bounded, entity.type())
            bounded.writeBoolean(entity.tag() != null)
            entity.tag()?.let { NbtIo.write(it, bounded) }
        }
        bounded.flush() // Does not close the owner-provided RegionFile/Zstd stream.
    }

    @JvmStatic
    @Throws(IOException::class)
    fun read(input: DataInput): Snapshot {
        val bounded = DataInputStream(object : InputStream() {
            private var remaining = ClientChunkSnapshot.MAX_SNAPSHOT_BYTES.toLong()
            private fun reserve(length: Int) {
                if (length.toLong() > remaining) throw IOException("Binary chunk exceeds record limit")
                remaining -= length
            }
            override fun read(): Int {
                reserve(1)
                return input.readUnsignedByte()
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                reserve(length)
                input.readFully(bytes, offset, length)
                return length
            }
        })
        try {
            if (bounded.readInt() != MAGIC) throw IOException("Not an RDI binary chunk (legacy NBT is unsupported)")
            if (bounded.readUnsignedShort() != VERSION) throw IOException("Unsupported RDI binary chunk version")
            val dimension = ResourceLocation.tryParse(readName(bounded)) ?: throw IOException("Invalid dimension ID")
            val x = bounded.readInt()
            val z = bounded.readInt()
            val minSection = bounded.readInt()
            val sectionCount = bounded.readInt()
            checkSections(minSection, sectionCount)
            val gameTime = bounded.readLong()
            val sequence = bounded.readLong()
            val length = bounded.readInt()
            if (length !in 0..ClientChunkSnapshot.MAX_SECTION_BYTES) throw IOException("Invalid section byte length")
            val sections = ByteArray(length)
            bounded.readFully(sections)
            // A shared accounter bounds the combined metadata, not just each individual tag.
            val accounter = ChunkCacheCompat.nbtAccounter(ClientChunkSnapshot.MAX_SNAPSHOT_BYTES.toLong(), 128)
            val heights = NbtIo.read(bounded, accounter)
            val count = bounded.readInt()
            if (count !in 0..ClientChunkSnapshot.MAX_BLOCK_ENTITIES) throw IOException("Invalid block entity count")
            val entities = ArrayList<BlockEntityData>(count)
            repeat(count) {
                val entityX = bounded.readInt()
                val entityY = bounded.readInt()
                val entityZ = bounded.readInt()
                val type = readName(bounded)
                if (ResourceLocation.tryParse(type) == null) throw IOException("Invalid block entity type")
                val tag: CompoundTag? = if (bounded.readBoolean()) NbtIo.read(bounded, accounter) else null
                entities.add(BlockEntityData(entityX, entityY, entityZ, type, tag))
            }
            return Snapshot(dimension, x, z, minSection, sectionCount, gameTime, sequence, sections, heights, entities)
                .also(::validate)
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid binary chunk", failure)
        } catch (failure: RuntimeException) {
            if (ChunkCacheCompat.isNbtLimitFailure(failure)) throw IOException("Binary chunk NBT exceeds limit", failure)
            throw failure
        }
    }

    /** Detached decoding only. Installing chunks and obtaining their current light is a separate operation. */
    @JvmStatic
    @Throws(IOException::class)
    fun decodeSections(snapshot: Snapshot, biomes: Registry<Biome>): Array<LevelChunkSection> {
        validate(snapshot)
        val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(snapshot.sections()))
        try {
            val sections = Array(snapshot.sectionCount()) { LevelChunkSection(biomes) }
            for (section in sections) section.read(buffer)
            ChunkTerrainCodec.checkTrailingPadding(buffer, snapshot.sectionCount())
            return sections
        } catch (failure: RuntimeException) {
            throw IOException("Invalid section palette data", failure)
        } finally {
            buffer.release()
        }
    }

    private fun validate(snapshot: Snapshot) {
        checkSections(snapshot.minSection(), snapshot.sectionCount())
        if (snapshot.sections().size > ClientChunkSnapshot.MAX_SECTION_BYTES ||
            snapshot.blockEntities().size > ClientChunkSnapshot.MAX_BLOCK_ENTITIES) {
            throw IOException("Binary chunk exceeds limits")
        }
        try {
            snapshot.estimatedBytes()
        } catch (failure: ClientChunkSnapshot.TooLargeException) {
            throw IOException("Binary chunk exceeds retained-data limit", failure)
        }
    }

    private fun checkSections(minSection: Int, count: Int) {
        if (count !in 1..MAX_SECTIONS || minSection.toLong() + count > Int.MAX_VALUE) {
            throw IOException("Invalid section range")
        }
    }

    private fun writeName(output: DataOutput, name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_NAME_BYTES) throw IOException("Binary chunk name exceeds limit")
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private fun readName(input: DataInput): String {
        val length = input.readUnsignedShort()
        if (length > MAX_NAME_BYTES) throw IOException("Binary chunk name exceeds limit")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
