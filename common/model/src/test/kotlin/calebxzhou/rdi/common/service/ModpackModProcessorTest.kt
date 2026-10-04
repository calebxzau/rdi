package calebxzhou.rdi.common.service

import calebxzhou.rdi.common.model.Mod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModpackModProcessorTest {
    @Test
    fun removesKnownBadModsAndBackupMods() {
        val mods = listOf(
            mod("world-backups"),
            mod("spark"),
            mod("essential-mod"),
            mod("modern-ui")
        )

        val processed = ModpackModProcessor.processMods(mods)

        assertFalse(processed.any { it.slug == "world-backups" })
        assertFalse(processed.any { it.slug == "spark" })
        assertFalse(processed.any { it.slug == "essential-mod" })
        assertEquals(listOf("modern-ui"), processed.map(Mod::slug))
    }

    @Test
    fun overridesKnownModSides() {
        val mods = listOf(
            mod("loot-beams-refork", Mod.Side.CLIENT),
            mod("modern-ui", Mod.Side.BOTH)
        )

        val processed = ModpackModProcessor.processMods(mods)

        assertEquals(Mod.Side.BOTH, processed.first { it.slug == "loot-beams-refork" }.side)
        assertEquals(Mod.Side.CLIENT, processed.first { it.slug == "modern-ui" }.side)
    }

    @Test
    fun clientOverrideOriginKeepsKnownBothSideModClientOnly() {
        val override = mod("loot-beams-refork", Mod.Side.BOTH).copy(clientOnlyOverride = true)
        val processed = ModpackModProcessor.processMods(listOf(override)).single()
        assertEquals(Mod.Side.CLIENT, processed.side)
        assertEquals(true, processed.clientOnlyOverride)
    }

    @Test
    fun clientOverrideVersionReplacesCommonVersionOnClientAndKeepsServerVersion() {
        val common = mod("example")
        val client = common.copy(fileId = "client-file", hash = "client-hash", side = Mod.Side.CLIENT, clientOnlyOverride = true)
        val processed = ModpackModProcessor.processMods(listOf(common, client))
        assertEquals(Mod.Side.SERVER, processed.first { !it.clientOnlyOverride }.side)
        assertEquals(Mod.Side.CLIENT, processed.first { it.clientOnlyOverride }.side)
    }

    @Test
    fun rawClientReplacementKeepsKnownCommonModOnlyOnServerDespiteSideRules() {
        val common = mod("loot-beams-refork").copy(clientOverrideReplaced = true)
        assertEquals(Mod.Side.SERVER, ModpackModProcessor.processMods(listOf(common)).single().side)
        assertEquals(emptyList(), ModpackModProcessor.processMods(listOf(common.copy(side = Mod.Side.CLIENT))))
        assertEquals(Mod.Side.UNKNOWN, ModpackModProcessor.processMods(listOf(common.copy(side = Mod.Side.UNKNOWN))).single().side)
    }

    private fun mod(slug: String, side: Mod.Side = Mod.Side.BOTH): Mod = Mod(
        platform = "mr",
        projectId = slug,
        slug = slug,
        fileId = "file-$slug",
        hash = "hash-$slug",
        side = side
    )
}
