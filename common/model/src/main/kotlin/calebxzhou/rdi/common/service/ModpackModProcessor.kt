
package calebxzhou.rdi.common.service

import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.normalizedSlug
import calebxzhou.rdi.common.model.sameMod

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

    fun processMods(mods: List<Mod>, loader: ModLoader = ModLoader.forge): MutableList<Mod> {
        val processed = mods.mapNotNull { mod ->
            val slug = mod.normalizedSlug
            when {
                slug.contains("backup") || slug in removedSlugsFor(loader) -> null
                mod.clientOnlyOverride -> mod.copyWithSide(Mod.Side.CLIENT)
                mod.clientOverrideReplaced && mod.side == Mod.Side.CLIENT -> null
                mod.clientOverrideReplaced -> mod.copyWithSide(if (mod.side == Mod.Side.BOTH) Mod.Side.SERVER else mod.side)
                loader != ModLoader.Fabric && slug in clientSideSlugs -> mod.copyWithSide(Mod.Side.CLIENT)
                loader != ModLoader.Fabric && slug in bothSideSlugs -> mod.copyWithSide(Mod.Side.BOTH)
                else -> mod.copyWithSide(mod.side)
            }
        }
        val clientOverrides = processed.filter(Mod::clientOnlyOverride)
        return processed.mapNotNull { mod ->
            if (!mod.clientOnlyOverride && clientOverrides.any {
                    sameMod(it, mod) && !it.hash.equals(mod.hash, ignoreCase = true)
                }) {
                when (mod.side) {
                    Mod.Side.BOTH -> mod.copyWithSide(Mod.Side.SERVER)
                    Mod.Side.CLIENT -> null
                    else -> mod
                }
            } else mod
        }.toMutableList()
    }

    private fun Mod.copyWithSide(side: Mod.Side): Mod =
        copy(side = side, downloadUrls = downloadUrls.toList())
}
