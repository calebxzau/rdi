package calebxzau.rdi.mc.server.syncchunk.backup

import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupIds
import net.minecraft.nbt.ByteArrayTag
import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.EndTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.ShortTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import java.security.MessageDigest

/**
 * SHA-1 of an NBT tree that ignores compound key order: `CompoundTag` is a `HashMap`, so equal
 * content may serialize in a different order. [ignoredRootKeys] are left out at the top level only.
 */
object CanonicalNbtHash {
    /** Chunk fields that change on every save without changing the chunk (`LastUpdate` is never read back). */
    val VOLATILE_TERRAIN_KEYS = setOf("LastUpdate", "InhabitedTime")

    fun sha1(tag: CompoundTag, ignoredRootKeys: Set<String> = emptySet()): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val writer = Writer(digest)
        writer.byte(Tag.TAG_COMPOUND.toInt())
        writer.compound(tag, ignoredRootKeys)
        return SyncChunkBackupIds.hex(digest.digest())
    }

    private class Writer(private val digest: MessageDigest) {
        private val buffer = ByteArray(8)

        fun byte(value: Int) = digest.update(value.toByte())

        fun int(value: Int) {
            for (index in 0..3) buffer[index] = (value ushr (24 - index * 8)).toByte()
            digest.update(buffer, 0, 4)
        }

        fun long(value: Long) {
            for (index in 0..7) buffer[index] = (value ushr (56 - index * 8)).toByte()
            digest.update(buffer, 0, 8)
        }

        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            int(bytes.size)
            digest.update(bytes)
        }

        fun compound(tag: CompoundTag, ignoredKeys: Set<String>) {
            val keys = tag.allKeys.filterNot { it in ignoredKeys }.sorted()
            int(keys.size)
            keys.forEach { key ->
                val child = tag.get(key) ?: return@forEach
                byte(child.id.toInt())
                string(key)
                value(child)
            }
        }

        fun value(tag: Tag) {
            when (tag) {
                is CompoundTag -> compound(tag, emptySet())
                is ListTag -> {
                    byte(tag.elementType.toInt())
                    int(tag.size)
                    for (index in 0 until tag.size) value(tag[index])
                }
                is ByteTag -> byte(tag.asByte.toInt())
                is ShortTag -> int(tag.asShort.toInt())
                is IntTag -> int(tag.asInt)
                is LongTag -> long(tag.asLong)
                is FloatTag -> int(java.lang.Float.floatToIntBits(tag.asFloat))
                is DoubleTag -> long(java.lang.Double.doubleToLongBits(tag.asDouble))
                is StringTag -> string(tag.asString)
                is ByteArrayTag -> tag.asByteArray.let { bytes ->
                    int(bytes.size)
                    digest.update(bytes)
                }
                is IntArrayTag -> tag.asIntArray.let { values ->
                    int(values.size)
                    values.forEach(::int)
                }
                is LongArrayTag -> tag.asLongArray.let { values ->
                    int(values.size)
                    values.forEach(::long)
                }
                is EndTag -> Unit
                else -> throw IllegalArgumentException("未知的NBT类型：${tag.javaClass.name}")
            }
        }
    }
}
