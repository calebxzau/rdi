package calebxzau.rdi.mc.zstdcodec

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.InflaterInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZlibTailTest {
    @Test
    fun `a mod snapshot round trips to the same content`() {
        for ((prefix, suffix) in listOf(ByteArray(0) to ByteArray(0), modString("signs") to ByteArray(0), modString("signs") to byteArrayOf(7, 8, 9))) {
            val json = snapshotJson(40)
            val packet = modPacket(CHANNEL, prefix, json, suffix)

            val tail = assertNotNull(ZlibTail.unwrap(packet))
            val header = channelHeader(CHANNEL)
            assertEquals(header.size + prefix.size, tail.tailOffset)
            assertTrue(tail.unwrapped.size > packet.size, "unwrapped=${tail.unwrapped.size} packet=${packet.size}")

            val restored = ZlibTail.rewrap(tail.unwrapped, tail.tailOffset).getOrThrow()
            val read = readModPacket(restored, header.size + prefix.size)
            assertContentEquals(json, read.json)
            assertContentEquals(suffix, read.suffix)
            assertContentEquals(restored.copyOfRange(0, header.size + prefix.size), packet.copyOfRange(0, header.size + prefix.size))
        }
    }

    @Test
    fun `packets that break the layout stay unchanged`() {
        val json = snapshotJson(40)
        val good = modPacket(CHANNEL, ByteArray(0), json, ByteArray(0))
        val header = channelHeader(CHANNEL).size

        assertNull(ZlibTail.unwrap(good.copyOf(ZlibTailFormat.MINIMUM_PACKET_BYTES - 1)), "short packet")
        assertNull(ZlibTail.unwrap(ByteArray(4096) { (it * 131 + 7).toByte() }), "no zlib field")
        assertNull(ZlibTail.unwrap(good.copyOf(good.size - 1)), "truncated zlib")
        assertNull(ZlibTail.unwrap(modPacket(CHANNEL, ByteArray(0), json, ByteArray(0), declaredRaw = json.size + 1)), "raw length too big")
        assertNull(ZlibTail.unwrap(modPacket(CHANNEL, ByteArray(0), json, ByteArray(0), declaredRaw = json.size - 1)), "raw length too small")
        assertNull(ZlibTail.unwrap(good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }), "bad checksum")
        assertNull(ZlibTail.unwrap(modPacket(CHANNEL, ByteArray(0), ByteArray(ZlibTailFormat.MAXIMUM_RAW_BYTES + 1) { 'a'.code.toByte() }, ByteArray(0))), "raw too big")
        val deepPrefix = ByteArray(ZlibTailFormat.SEARCH_BYTES) { 1 }
        assertNull(ZlibTail.unwrap(modPacket(CHANNEL, deepPrefix, json, ByteArray(0))), "field starts beyond the search window")
        assertNotNull(ZlibTail.unwrap(good)).also { assertEquals(header, it.tailOffset) }
    }

    @Test
    fun `malformed unwrapped packets fail to rewrap`() {
        val tail = assertNotNull(ZlibTail.unwrap(modPacket(CHANNEL, ByteArray(0), snapshotJson(40), ByteArray(0))))
        assertTrue(ZlibTail.rewrap(tail.unwrapped, tail.unwrapped.size).isFailure)
        assertTrue(ZlibTail.rewrap(tail.unwrapped, -1).isFailure)
        assertTrue(ZlibTail.rewrap(tail.unwrapped.copyOf(tail.unwrapped.size - 1), tail.tailOffset).isFailure)
        assertTrue(ZlibTail.rewrap(byteArrayOf(0x80.toByte(), 0x80.toByte()), 0).isFailure)
    }

    class ModRead(val json: ByteArray, val suffix: ByteArray)

    companion object {
        const val CHANNEL = "wonder_shop_story_core:shop_reply"

        /** Like a shop snapshot, a new revision changes the revision and one item. */
        fun snapshotJson(entries: Int, seed: Int = 0): ByteArray = buildString {
            append("{\"shop\":\"bakery\",\"revision\":").append(seed).append(",\"items\":[")
            repeat(entries) { index ->
                if (index > 0) append(',')
                val changed = if (index == seed % entries) seed * 3 else 0
                append("{\"id\":\"item_").append(index).append("\",\"price\":").append(index * 7 + changed)
                append(",\"stock\":").append((index * 13) % 97).append(",\"name\":\"Fresh bread ").append(index).append("\"}")
            }
            append("]}")
        }.toByteArray(Charsets.UTF_8)

        /** Packet id 0x19, then the channel string, as Minecraft encodes a custom payload. */
        fun channelHeader(channel: String): ByteArray {
            val output = ByteArrayOutputStream()
            output.write(0x19)
            output.write(modString(channel))
            return output.toByteArray()
        }

        fun modString(value: String): ByteArray {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return ByteArrayOutputStream().also { writeVarInt(it, bytes.size); it.write(bytes) }.toByteArray()
        }

        /** Mirrors ShopStateWire.write: VarInt raw length, then a byte array of level 1 zlib. */
        fun modPacket(channel: String, prefix: ByteArray, json: ByteArray, suffix: ByteArray, declaredRaw: Int = json.size): ByteArray {
            val deflater = Deflater(1)
            val zlib = try {
                deflater.setInput(json)
                deflater.finish()
                val buffer = ByteArray(json.size + 1024)
                val output = ByteArrayOutputStream()
                while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
                output.toByteArray()
            } finally {
                deflater.end()
            }
            val output = ByteArrayOutputStream()
            output.write(channelHeader(channel))
            output.write(prefix)
            writeVarInt(output, declaredRaw)
            writeVarInt(output, zlib.size)
            output.write(zlib)
            output.write(suffix)
            return output.toByteArray()
        }

        /** Mirrors ShopStateWire.read from [fieldStart]; whatever follows the field is the suffix. */
        fun readModPacket(packet: ByteArray, fieldStart: Int): ModRead {
            var index = fieldStart
            fun varInt(): Int {
                var value = 0
                var shift = 0
                while (true) {
                    val current = packet[index++].toInt() and 0xFF
                    value = value or ((current and 0x7F) shl shift)
                    if (current and 0x80 == 0) return value
                    shift += 7
                }
            }
            val rawLength = varInt()
            val zlibLength = varInt()
            assertTrue(zlibLength <= 1_000_000)
            val zlib = packet.copyOfRange(index, index + zlibLength)
            val json = InflaterInputStream(zlib.inputStream()).use { it.readNBytes(rawLength + 1) }
            assertEquals(rawLength, json.size)
            return ModRead(json, packet.copyOfRange(index + zlibLength, packet.size))
        }

        private fun writeVarInt(output: ByteArrayOutputStream, value: Int) {
            var current = value
            while (current and -0x80 != 0) {
                output.write((current and 0x7F) or 0x80)
                current = current ushr 7
            }
            output.write(current)
        }
    }
}
