
package calebxzhou.rdi.common.service

import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.normalizedSlug

object ModpackModProcessor {
    val removedSlugs: Set<String> = setOf(
        "powerful-dummy",
        "spark",
        "essential-mod",
        "default-server-properties",
        "skybox-loader-forge",
        "customskinloader",
        "chunky",
        //makes game no error log, f
        "log-deduplicator",
        //not working for 47.4 mc20forge
        "lazyyyyy",
        //rdi already have
        "zstd-net",
    )

    private val clientSideSlugs = setOf(
        "status-effect-bars-reforged",
        "mafglib",
        "flighthud-reborn",
        "i18nupdatemod",
        "modern-ui",
        "controllable",
        "mekalus-oculus-fork-with-fixed-mekanism-mekasuit"
    )

    private val bothSideSlugs = setOf(
        "loot-beams-refork",
        "particular-reforged",
        "inventory-profiles-next",
        "inventory-tweaks-refoxed",
        "just-enough-resources-jer",
        "radiant-gear",
        "fusion-connected-textures",
        "oh-the-trees-youll-grow",
        "smartbrainlib"
    )

    fun processMods(mods: List<Mod>): MutableList<Mod> = mods.mapNotNull { mod ->
        val slug = mod.normalizedSlug
        when {
            slug.contains("backup") || slug in removedSlugs -> null
            slug in clientSideSlugs -> mod.copyWithSide(Mod.Side.CLIENT)
            slug in bothSideSlugs -> mod.copyWithSide(Mod.Side.BOTH)
            else -> mod.copyWithSide(mod.side)
        }
    }.toMutableList()

    private fun Mod.copyWithSide(side: Mod.Side): Mod =
        copy(side = side, downloadUrls = downloadUrls.toList())
}
