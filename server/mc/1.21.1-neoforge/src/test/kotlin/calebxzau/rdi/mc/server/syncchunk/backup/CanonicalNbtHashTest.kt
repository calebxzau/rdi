package calebxzau.rdi.mc.server.syncchunk.backup

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.StringTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CanonicalNbtHashTest {
    @Test
    fun keyOrderDoesNotChangeTheHash() {
        val keys = (0 until 64).map { "key${it}" }
        val forward = CompoundTag().apply { keys.forEach { putInt(it, it.length) } }
        val backward = CompoundTag().apply { keys.reversed().forEach { putInt(it, it.length) } }
        val nestedForward = CompoundTag().apply { put("a", forward); putString("b", "x") }
        val nestedBackward = CompoundTag().apply { putString("b", "x"); put("a", backward) }

        assertEquals(CanonicalNbtHash.sha1(forward), CanonicalNbtHash.sha1(backward))
        assertEquals(CanonicalNbtHash.sha1(nestedForward), CanonicalNbtHash.sha1(nestedBackward))
    }

    @Test
    fun ignoredRootKeysAreLeftOutAtTheTopLevelOnly() {
        val chunk = terrain(lastUpdate = 100, inhabited = 5)
        val laterSave = terrain(lastUpdate = 6100, inhabited = 900)
        val ignored = CanonicalNbtHash.VOLATILE_TERRAIN_KEYS

        assertEquals(CanonicalNbtHash.sha1(chunk, ignored), CanonicalNbtHash.sha1(laterSave, ignored))
        assertNotEquals(CanonicalNbtHash.sha1(chunk), CanonicalNbtHash.sha1(laterSave))

        val nested = CompoundTag().apply { put("Level", terrain(1, 1)) }
        val nestedLater = CompoundTag().apply { put("Level", terrain(2, 1)) }
        assertNotEquals(CanonicalNbtHash.sha1(nested, ignored), CanonicalNbtHash.sha1(nestedLater, ignored))
    }

    @Test
    fun contentTypesAndListOrderChangeTheHash() {
        val base = CompoundTag().apply { putInt("v", 1) }
        assertNotEquals(CanonicalNbtHash.sha1(base), CanonicalNbtHash.sha1(CompoundTag().apply { putInt("v", 2) }))
        assertNotEquals(CanonicalNbtHash.sha1(base), CanonicalNbtHash.sha1(CompoundTag().apply { putLong("v", 1) }))
        assertNotEquals(CanonicalNbtHash.sha1(base), CanonicalNbtHash.sha1(CompoundTag().apply { putInt("w", 1) }))

        val ab = CompoundTag().apply { put("list", ListTag().apply { add(StringTag.valueOf("a")); add(StringTag.valueOf("b")) }) }
        val ba = CompoundTag().apply { put("list", ListTag().apply { add(StringTag.valueOf("b")); add(StringTag.valueOf("a")) }) }
        assertNotEquals(CanonicalNbtHash.sha1(ab), CanonicalNbtHash.sha1(ba))

        val split = CompoundTag().apply { putString("ab", "c") }
        val joined = CompoundTag().apply { putString("a", "bc") }
        assertNotEquals(CanonicalNbtHash.sha1(split), CanonicalNbtHash.sha1(joined))
    }

    @Test
    fun hashIsLowercaseSha1Hex() {
        assertEquals(Regex("[0-9a-f]{40}").matches(CanonicalNbtHash.sha1(CompoundTag())), true)
    }

    private fun terrain(lastUpdate: Long, inhabited: Long) = CompoundTag().apply {
        put("LastUpdate", LongTag.valueOf(lastUpdate))
        put("InhabitedTime", LongTag.valueOf(inhabited))
        put("xPos", IntTag.valueOf(3))
        putString("Status", "minecraft:full")
    }
}
