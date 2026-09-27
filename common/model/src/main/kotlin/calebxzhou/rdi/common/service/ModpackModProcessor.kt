
package calebxzhou.rdi.common.service

import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
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
        "zstdnet",
        "mcwifipnp",
        //have video mod, no need
        "what-can-i-see",
        //rely on spark for tps
        "tab-list"
    )

    private val forgeOnlyRemovedSlugs = setOf(
        "skybox-loader-forge",
        "lazyyyyy",
    )

    fun removedSlugsFor(loader: ModLoader): Set<String> =
        if (loader == ModLoader.Fabric) removedSlugs - forgeOnlyRemovedSlugs else removedSlugs

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

    fun processMods(mods: List<Mod>, loader: ModLoader = ModLoader.forge): MutableList<Mod> = mods.mapNotNull { mod ->
        val slug = mod.normalizedSlug
        when {
            slug.contains("backup") || slug in removedSlugsFor(loader) -> null
            loader != ModLoader.Fabric && slug in clientSideSlugs -> mod.copyWithSide(Mod.Side.CLIENT)
            loader != ModLoader.Fabric && slug in bothSideSlugs -> mod.copyWithSide(Mod.Side.BOTH)
            else -> mod.copyWithSide(mod.side)
        }
    }.toMutableList()

    private fun Mod.copyWithSide(side: Mod.Side): Mod =
        copy(side = side, downloadUrls = downloadUrls.toList())
}
