package calebxzau.rdi.mc.v20.l2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID

object L2NamePayload {
    /** The decoded L2Tabs 0.3.3 ID 1 payload, without its Minecraft packet ID or channel. */
    data class Decoded(val entityId: Int, val names: AttributeNames)

    const val MAX_PAYLOAD_BYTES = 1 shl 20
    private const val MAX_COUNT = 32_768
    private const val MAX_STRING_CHARS = 32_767
    private const val MAX_STRING_BYTES = MAX_STRING_CHARS * 3

    /** Encodes only the L2 ID 1 body. Invalid or oversized input throws [IllegalArgumentException]. */
    fun encode(entityId: Int, names: AttributeNames): ByteArray {
        val writer = Writer()
        writer.marker(1) // L2 message discriminator
        writer.marker(1) // non-null entity
        writer.marker(1) // non-null entity ID
        writer.int(entityId)
        writer.marker(1) // non-null attribute list
        writer.count(names.size)

        for ((attribute, modifiers) in names) {
            writer.marker(1)
            writer.marker(0)
            writer.marker(1)
            writer.string(attribute)
            writer.marker(1) // non-null modifier list
            writer.count(modifiers.size)

            for ((uuid, name) in modifiers) {
                writer.marker(1)
                writer.marker(0)
                writer.marker(1)
                writer.string(uuid.toString())
                writer.marker(1) // non-null name
                writer.string(name)
            }
        }
        return writer.toByteArray()
    }

    /** Decodes only the L2 ID 1 body and rejects malformed or trailing data. */
    fun decode(payload: ByteArray): Result<Decoded> = runCatching {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "L2 name payload exceeds 1 MiB" }
        val reader = Reader(payload)
        reader.expectMarker(1) // L2 message discriminator
        reader.expectMarker(1) // non-null entity
        reader.expectMarker(1) // non-null entity ID
        val entityId = reader.int()
        reader.expectMarker(1) // non-null attribute list
        val attributeCount = reader.count()
        val names = LinkedHashMap<String, Map<UUID, String>>(attributeCount)

        repeat(attributeCount) {
            reader.expectMarker(1)
            reader.expectMarker(0)
            reader.expectMarker(1)
            val attribute = reader.string()
            require(!names.containsKey(attribute)) { "Duplicate attribute name" }
            reader.expectMarker(1)
            val modifierCount = reader.count()
            val modifiers = LinkedHashMap<UUID, String>(modifierCount)

            repeat(modifierCount) {
                reader.expectMarker(1)
                reader.expectMarker(0)
                reader.expectMarker(1)
                val uuidText = reader.string()
                val uuid = try {
                    UUID.fromString(uuidText).also {
                        require(it.toString() == uuidText) { "Modifier UUID is not canonical" }
                    }
                } catch (exception: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid modifier UUID", exception)
                }
                reader.expectMarker(1)
                val name = reader.string()
                require(!modifiers.containsKey(uuid)) { "Duplicate modifier UUID" }
                modifiers[uuid] = name
            }
            names[attribute] = Collections.unmodifiableMap(modifiers)
        }
        reader.expectEnd()
        Decoded(entityId, Collections.unmodifiableMap(names))
    }

    private class Writer {
        private val output = ByteArrayOutputStream()

        fun marker(value: Int) = byte(value)

        fun byte(value: Int) {
            ensureCapacity(1)
            output.write(value)
        }

        fun int(value: Int) {
            ensureCapacity(4)
            output.write(value ushr 24)
            output.write(value ushr 16)
            output.write(value ushr 8)
            output.write(value)
        }

        fun count(value: Int) {
            require(value in 0..MAX_COUNT) { "Collection count exceeds $MAX_COUNT" }
            int(value)
        }

        fun string(value: String) {
            require(value.length <= MAX_STRING_CHARS) { "String exceeds $MAX_STRING_CHARS UTF-16 characters" }
            val encoded = try {
                val buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value))
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } catch (exception: CharacterCodingException) {
                throw IllegalArgumentException("String is not valid Unicode", exception)
            }
            require(encoded.size <= MAX_STRING_BYTES) { "UTF-8 string exceeds $MAX_STRING_BYTES bytes" }
            varInt(encoded.size)
            ensureCapacity(encoded.size)
            output.write(encoded)
        }

        private fun varInt(value: Int) {
            var remaining = value
            while (remaining and 0x7f.inv() != 0) {
                byte((remaining and 0x7f) or 0x80)
                remaining = remaining ushr 7
            }
            byte(remaining)
        }

        private fun ensureCapacity(additional: Int) {
            require(additional >= 0 && output.size() <= MAX_PAYLOAD_BYTES - additional) {
                "L2 name payload exceeds 1 MiB"
            }
        }

        fun toByteArray(): ByteArray = output.toByteArray()
    }

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0

        fun expectMarker(expected: Int) {
            val actual = unsignedByte()
            require(actual == expected) { "Expected marker $expected at byte ${offset - 1}, got $actual" }
        }

        fun int(): Int {
            requireRemaining(4)
            return (unsignedByte() shl 24) or
                (unsignedByte() shl 16) or
                (unsignedByte() shl 8) or
                unsignedByte()
        }

        fun count(): Int {
            val value = int()
            require(value in 0..MAX_COUNT) { "Invalid collection count $value" }
            // Each item requires at least one marker byte. This avoids iterating claimed counts
            // that cannot possibly fit in the remaining payload.
            require(value <= bytes.size - offset) { "Collection count exceeds remaining payload" }
            return value
        }

        fun string(): String {
            val byteCount = varInt()
            require(byteCount in 0..MAX_STRING_BYTES) { "Invalid UTF-8 string byte length $byteCount" }
            requireRemaining(byteCount)
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val decoded = try {
                decoder.decode(ByteBuffer.wrap(bytes, offset, byteCount)).toString()
            } catch (exception: CharacterCodingException) {
                throw IllegalArgumentException("Invalid UTF-8 string", exception)
            }
            offset += byteCount
            require(decoded.length <= MAX_STRING_CHARS) { "String exceeds $MAX_STRING_CHARS UTF-16 characters" }
            return decoded
        }

        private fun varInt(): Int {
            var result = 0
            for (index in 0 until 5) {
                val next = unsignedByte()
                if (index == 4) require(next and 0xf0 == 0) { "Invalid VarInt" }
                result = result or ((next and 0x7f) shl (index * 7))
                if (next and 0x80 == 0) {
                    require(index == 0 || next != 0) { "Non-canonical VarInt" }
                    return result
                }
            }
            throw IllegalArgumentException("VarInt is too long")
        }

        private fun unsignedByte(): Int {
            requireRemaining(1)
            return bytes[offset++].toInt() and 0xff
        }

        private fun requireRemaining(length: Int) {
            require(length >= 0 && length <= bytes.size - offset) { "Truncated L2 name payload" }
        }

        fun expectEnd() {
            require(offset == bytes.size) { "Trailing bytes in L2 name payload" }
        }
    }
}
