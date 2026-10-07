package calebxzau.rdi.anvilrw.remap

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/** Builds region files the way Minecraft lays them out, with optional corruption. */
internal class TestRegion(private val dir: Path, private val rx: Int, private val rz: Int) {
    private class Spec(
        val index: Int,
        val type: Int,
        val payload: ByteArray,
        val timestamp: Int,
        val external: Boolean,
        val lengthOverride: Int?,
        val locationOverride: Int?,
    )

    private val specs = ArrayList<Spec>()

    fun chunk(
        index: Int,
        type: Int,
        nbt: ByteArray,
        timestamp: Int = 1_700_000_000,
        external: Boolean = false,
        lengthOverride: Int? = null,
        locationOverride: Int? = null,
        rawPayload: ByteArray? = null,
    ): TestRegion {
        val payload = rawPayload ?: BoundedIo.compressChunk(type, nbt)
        specs += Spec(index, type, payload, timestamp, external, lengthOverride, locationOverride)
        return this
    }

    fun write(): Path {
        val header = ByteBuffer.allocate(8192)
        val body = java.io.ByteArrayOutputStream()
        var sector = 2
        for (spec in specs) {
            val record = if (spec.external) {
                val x = rx * 32 + (spec.index and 31)
                val z = rz * 32 + (spec.index shr 5)
                Files.write(dir.resolve("c.${x}.${z}.mcc"), spec.payload)
                ByteBuffer.allocate(5).putInt(1).put((spec.type or 0x80).toByte()).array()
            } else {
                ByteBuffer.allocate(spec.payload.size + 5)
                    .putInt(spec.lengthOverride ?: (spec.payload.size + 1))
                    .put(spec.type.toByte())
                    .put(spec.payload)
                    .array()
            }
            val sectors = (record.size + 4095) / 4096
            body.write(record)
            body.write(ByteArray(sectors * 4096 - record.size))
            header.putInt(spec.index * 4, spec.locationOverride ?: ((sector shl 8) or sectors))
            header.putInt(4096 + spec.index * 4, spec.timestamp)
            sector += sectors
        }
        val path = dir.resolve("r.${rx}.${rz}.mca")
        Files.write(path, header.array() + body.toByteArray())
        return path
    }
}
