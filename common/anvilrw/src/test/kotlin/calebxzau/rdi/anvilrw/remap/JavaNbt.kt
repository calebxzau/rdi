package calebxzau.rdi.anvilrw.remap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.util.UUID
import kotlin.test.assertEquals

/** A small NBT tree used to build fixtures and to check output with Minecraft's semantics. */
sealed interface Tag
data class TByte(val value: Byte) : Tag
data class TShort(val value: Short) : Tag
data class TInt(val value: Int) : Tag
data class TLong(val value: Long) : Tag
data class TFloat(val value: Float) : Tag
data class TDouble(val value: Double) : Tag
data class TByteArray(val value: List<Byte>) : Tag
data class TString(val value: String) : Tag
data class TList(val elementType: Int, val items: List<Tag>) : Tag
data class TCompound(val entries: List<Pair<String, Tag>>) : Tag
data class TIntArray(val value: List<Int>) : Tag
data class TLongArray(val value: List<Long>) : Tag

fun compound(vararg entries: Pair<String, Tag>): TCompound = TCompound(entries.toList())

fun uuidInts(uuid: UUID): TIntArray = TIntArray(
    listOf(
        (uuid.mostSignificantBits ushr 32).toInt(),
        uuid.mostSignificantBits.toInt(),
        (uuid.leastSignificantBits ushr 32).toInt(),
        uuid.leastSignificantBits.toInt(),
    ),
)

/**
 * Writes and reads NBT exactly like Minecraft does, through `DataOutput.writeUTF` and
 * `DataInput.readUTF` (Java modified UTF-8). Reading a malformed string fails the same way it would
 * in Minecraft, so a successful [read] means Minecraft could read the bytes too.
 */
object JavaNbt {
    fun write(root: TCompound, rootName: String = ""): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(NbtTag.COMPOUND)
            output.writeUTF(rootName)
            writePayload(output, root)
        }
        return bytes.toByteArray()
    }

    fun payload(tag: Tag): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { writePayload(it, tag) }
        return bytes.toByteArray()
    }

    fun read(bytes: ByteArray): TCompound {
        val input = ByteArrayInputStream(bytes)
        val data = DataInputStream(input)
        val type = data.readByte().toInt()
        check(type == NbtTag.COMPOUND) { "Root tag must be a compound" }
        data.readUTF()
        val root = readPayload(data, NbtTag.COMPOUND) as TCompound
        assertEquals(0, input.available(), "Trailing bytes after the root tag")
        return root
    }

    fun typeOf(tag: Tag): Int = when (tag) {
        is TByte -> NbtTag.BYTE
        is TShort -> NbtTag.SHORT
        is TInt -> NbtTag.INT
        is TLong -> NbtTag.LONG
        is TFloat -> NbtTag.FLOAT
        is TDouble -> NbtTag.DOUBLE
        is TByteArray -> NbtTag.BYTE_ARRAY
        is TString -> NbtTag.STRING
        is TList -> NbtTag.LIST
        is TCompound -> NbtTag.COMPOUND
        is TIntArray -> NbtTag.INT_ARRAY
        is TLongArray -> NbtTag.LONG_ARRAY
    }

    private fun writePayload(output: DataOutput, tag: Tag) {
        when (tag) {
            is TByte -> output.writeByte(tag.value.toInt())
            is TShort -> output.writeShort(tag.value.toInt())
            is TInt -> output.writeInt(tag.value)
            is TLong -> output.writeLong(tag.value)
            is TFloat -> output.writeFloat(tag.value)
            is TDouble -> output.writeDouble(tag.value)
            is TByteArray -> {
                output.writeInt(tag.value.size)
                output.write(tag.value.toByteArray())
            }
            is TString -> output.writeUTF(tag.value)
            is TList -> {
                output.writeByte(tag.elementType)
                output.writeInt(tag.items.size)
                tag.items.forEach { writePayload(output, it) }
            }
            is TCompound -> {
                tag.entries.forEach { (name, value) ->
                    output.writeByte(typeOf(value))
                    output.writeUTF(name)
                    writePayload(output, value)
                }
                output.writeByte(NbtTag.END)
            }
            is TIntArray -> {
                output.writeInt(tag.value.size)
                tag.value.forEach { output.writeInt(it) }
            }
            is TLongArray -> {
                output.writeInt(tag.value.size)
                tag.value.forEach { output.writeLong(it) }
            }
        }
    }

    private fun readPayload(input: DataInput, type: Int): Tag = when (type) {
        NbtTag.BYTE -> TByte(input.readByte())
        NbtTag.SHORT -> TShort(input.readShort())
        NbtTag.INT -> TInt(input.readInt())
        NbtTag.LONG -> TLong(input.readLong())
        NbtTag.FLOAT -> TFloat(input.readFloat())
        NbtTag.DOUBLE -> TDouble(input.readDouble())
        NbtTag.BYTE_ARRAY -> TByteArray(ByteArray(input.readInt()).also { input.readFully(it) }.toList())
        NbtTag.STRING -> TString(input.readUTF())
        NbtTag.LIST -> {
            val elementType = input.readByte().toInt()
            val count = input.readInt()
            TList(elementType, List(count) { readPayload(input, elementType) })
        }
        NbtTag.COMPOUND -> {
            val entries = ArrayList<Pair<String, Tag>>()
            while (true) {
                val entryType = input.readByte().toInt()
                if (entryType == NbtTag.END) break
                val name = input.readUTF()
                entries += name to readPayload(input, entryType)
            }
            TCompound(entries)
        }
        NbtTag.INT_ARRAY -> TIntArray(List(input.readInt()) { input.readInt() })
        NbtTag.LONG_ARRAY -> TLongArray(List(input.readInt()) { input.readLong() })
        else -> error("Unknown tag type ${type}")
    }
}
