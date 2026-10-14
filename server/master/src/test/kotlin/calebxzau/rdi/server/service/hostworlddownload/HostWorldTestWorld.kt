package calebxzau.rdi.server.service.hostworlddownload

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/** Builds small worlds on disk for the save download tests. */
internal object HostWorldTestWorld {
    /** Writes `r.<rx>.<rz>.mca` holding [chunks] (absolute chunk coordinates), zlib compressed. */
    fun region(file: Path, chunks: List<Pair<Int, Int>>) {
        Files.createDirectories(file.parent)
        val header = ByteBuffer.allocate(8192)
        val body = ByteArrayOutputStream()
        var sector = 2
        for ((x, z) in chunks) {
            val payload = deflate("chunk ${x},${z}".toByteArray())
            val record = ByteBuffer.allocate(5 + payload.size).putInt(payload.size + 1).put(2).put(payload).array()
            val sectors = (record.size + 4095) / 4096
            header.putInt(((x and 31) + (z and 31) * 32) * 4, (sector shl 8) or sectors)
            body.write(record.copyOf(sectors * 4096))
            sector += sectors
        }
        Files.write(file, header.array() + body.toByteArray())
    }

    /** `data/rdi_sync_chunks.dat` listing [chunks] as (dimension, x, z). */
    fun syncList(worldDir: Path, chunks: List<Triple<String, Int, Int>>) {
        val nbt = ByteArrayOutputStream()
        DataOutputStream(nbt).use { out ->
            out.writeByte(10); out.writeUTF("")
            out.writeByte(10); out.writeUTF("data")
            out.writeByte(9); out.writeUTF("chunks"); out.writeByte(10); out.writeInt(chunks.size)
            for ((dimension, x, z) in chunks) {
                out.writeByte(8); out.writeUTF("dimension"); out.writeUTF(dimension)
                out.writeByte(3); out.writeUTF("x"); out.writeInt(x)
                out.writeByte(3); out.writeUTF("z"); out.writeInt(z)
                out.writeByte(8); out.writeUTF("owner"); out.writeUTF(UUID(1, 2).toString())
                out.writeByte(0)
            }
            out.writeByte(0)
            out.writeByte(0)
        }
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(nbt.toByteArray()) } }.toByteArray()
        file(worldDir, "data/rdi_sync_chunks.dat", gzipped)
    }

    fun file(worldDir: Path, relative: String, bytes: ByteArray = relative.toByteArray()) {
        val target = worldDir.resolve(relative)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }

    /**
     * A world with sync chunks (0,0) and (1,0) in the overworld and (0,0) in the nether; the region
     * files also hold chunks that are not synced.
     */
    fun standard(worldDir: Path) {
        file(worldDir, "level.dat")
        syncList(worldDir, listOf(Triple("minecraft:overworld", 0, 0), Triple("minecraft:overworld", 1, 0), Triple("minecraft:the_nether", 0, 0)))
        region(worldDir.resolve("region/r.0.0.mca"), listOf(0 to 0, 1 to 0, 5 to 5))
        region(worldDir.resolve("entities/r.0.0.mca"), listOf(0 to 0, 5 to 5))
        region(worldDir.resolve("region/r.1.0.mca"), listOf(40 to 0))
        region(worldDir.resolve("DIM-1/region/r.0.0.mca"), listOf(0 to 0, 2 to 2))
        file(worldDir, "playerdata/00000000-0000-0000-0000-000000000001.dat")
        file(worldDir, "data/sophisticatedbackpacks.dat")
        file(worldDir, "data/rdi_backup_token.txt")
        file(worldDir, "serverconfig/ftbchunks-world.snbt")
        file(worldDir, "session.lock")
        file(worldDir, "level.dat_old")
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }
}
