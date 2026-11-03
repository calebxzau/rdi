package calebxzau.rdi.mc.client.dm

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DmModFingerprintTest {
    private fun fingerprint(vararg entries: Pair<String, String>, world: DmModFingerprint.Part? = null, labels: Map<String, String> = emptyMap()) =
        DmModFingerprint(DmModFingerprint.part(entries.toList()), world, labels)

    @Test
    fun hashesFollowTheMasterContract() {
        // The same vectors are checked in dm-master mods.rs; both sides must agree byte for byte.
        val namespaces = mapOf("minecraft" to "b".repeat(40), "create" to "a".repeat(40))
        assertEquals("d8369fde1e260371d827cc92657b12e98defadfa", DmModFingerprint.overallOf(namespaces))
        val part = DmModFingerprint.part(listOf("minecraft:item" to "minecraft:stone", "minecraft:block" to "minecraft:stone"))
        assertEquals(mapOf("minecraft" to "22b7ab9586de326ba97a4f9c6213e715b702ef02"), part.namespaces)
        assertEquals(DmModFingerprint.overallOf(part.namespaces), part.overall)
    }

    @Test
    fun entryOrderDoesNotMatterButContentsDo() {
        val a = DmModFingerprint.part(listOf("minecraft:block" to "create:cog", "minecraft:item" to "create:cog", "minecraft:block" to "minecraft:stone"))
        val b = DmModFingerprint.part(listOf("minecraft:block" to "minecraft:stone", "minecraft:item" to "create:cog", "minecraft:block" to "create:cog"))
        assertEquals(a, b)
        val c = DmModFingerprint.part(listOf("minecraft:block" to "minecraft:stone", "minecraft:block" to "create:cog"))
        assertEquals(a.namespaces["minecraft"], c.namespaces["minecraft"])
        assertNotEquals(a.namespaces["create"], c.namespaces["create"])
    }

    @Test
    fun diffSortsNamespacesIntoMissingExtraAndChanged() {
        val room = fingerprint("minecraft:block" to "minecraft:stone", "minecraft:block" to "create:cog", "minecraft:block" to "farmersdelight:pan")
        val local = fingerprint("minecraft:block" to "minecraft:stone", "minecraft:block" to "create:shaft", "minecraft:block" to "jei:none")
        val difference = DmModFingerprint.diff(room, local)
        assertEquals(listOf("farmersdelight"), difference.missing)
        assertEquals(listOf("jei"), difference.extra)
        assertEquals(listOf("create"), difference.changed)
        assertTrue(DmModFingerprint.diff(room, room).isEmpty)
    }

    @Test
    fun worldRegistriesCountOnlyWhenBothSidesHaveThem() {
        val biomes = DmModFingerprint.part(listOf("minecraft:worldgen/biome" to "terralith:moonlight_grove"))
        val room = fingerprint("minecraft:block" to "minecraft:stone", world = biomes)
        val titleScreen = fingerprint("minecraft:block" to "minecraft:stone")
        assertTrue(DmModFingerprint.diff(room, titleScreen).isEmpty)
        val worldOpen = fingerprint("minecraft:block" to "minecraft:stone", world = DmModFingerprint.part(emptyList()))
        assertEquals(listOf("terralith"), DmModFingerprint.diff(room, worldOpen).missing)
    }

    @Test
    fun describeNamesModsByLabelAndShortensLongLists() {
        val room = fingerprint(*(1..8).map { "minecraft:block" to "mod$it:block" }.toTypedArray(), labels = mapOf("mod1" to "Mod One 1.0"))
        val local = fingerprint("minecraft:block" to "extra:block", labels = mapOf("extra" to "Extra 2.0"))
        val text = DmModFingerprint.describe(DmModFingerprint.diff(room, local), room, local)
        assertEquals("缺少：Mod One 1.0、mod2、mod3、mod4、mod5、mod6等8个；多出：Extra 2.0", text)
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val original = fingerprint(
            "minecraft:block" to "create:cog",
            world = DmModFingerprint.part(listOf("minecraft:dimension" to "minecraft:overworld")),
            labels = mapOf("create" to "Create 6.0.4"),
        )
        val parsed = DmModFingerprint.fromJson(JsonParser.parseString(original.toJson().toString()).asJsonObject)
        assertEquals(original, parsed)
        assertEquals(1, original.toJson().get("version").asInt)
    }
}
