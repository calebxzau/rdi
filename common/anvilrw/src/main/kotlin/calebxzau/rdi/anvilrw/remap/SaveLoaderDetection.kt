package calebxzau.rdi.anvilrw.remap

enum class SaveLoaderDetection {
    Forge,
    NeoForge,
    /** Fabric appears in the world's brand history. The most recent loader is unknown. */
    FabricHistory,
    Vanilla,
    Unknown,
    Conflicting,
}

/** FML records a mod list on save. Minecraft's server brands accumulate across opens. */
val LevelMetadata.loaderDetection: SaveLoaderDetection
    get() {
        val fmlIds = fmlModIds.orEmpty()
        val forge = "forge" in fmlIds
        val neoForge = "neoforge" in fmlIds
        if (forge && neoForge) return SaveLoaderDetection.Conflicting
        if (forge) return SaveLoaderDetection.Forge
        if (neoForge) return SaveLoaderDetection.NeoForge

        val brands = serverBrands.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if ("fabric" in brands) {
            return if (fmlModIds != null || brands.any { it in incompatibleFabricBrands }) {
                SaveLoaderDetection.Conflicting
            } else {
                SaveLoaderDetection.FabricHistory
            }
        }
        if (fmlModIds == null && brands == setOf("vanilla")) return SaveLoaderDetection.Vanilla
        return SaveLoaderDetection.Unknown
    }

private val incompatibleFabricBrands = setOf("forge", "neoforge", "quilt")
