package calebxzau.rdi.anvilrw.remap

import kotlin.test.Test
import kotlin.test.assertEquals

class SaveLoaderDetectionTest {
    @Test
    fun fmlIdentifiesForgeAndNeoForgeDespitePreviousBrands() {
        assertEquals(SaveLoaderDetection.Forge, detect(listOf("vanilla", "fabric"), listOf("minecraft", "forge")))
        assertEquals(SaveLoaderDetection.NeoForge, detect(emptyList(), listOf("minecraft", "neoforge")))
    }

    @Test
    fun fabricMatchesAnExactBrandToken() {
        assertEquals(SaveLoaderDetection.FabricHistory, detect(listOf("fabric")))
        assertEquals(SaveLoaderDetection.FabricHistory, detect(listOf("vanilla", "example,fabric")))
        assertEquals(SaveLoaderDetection.Unknown, detect(listOf("fabricated", "fabric-api")))
    }

    @Test
    fun mixedLoaderEvidenceIsRejected() {
        assertEquals(SaveLoaderDetection.Conflicting, detect(emptyList(), listOf("forge", "neoforge")))
        assertEquals(SaveLoaderDetection.Conflicting, detect(listOf("forge", "fabric")))
        assertEquals(SaveLoaderDetection.Conflicting, detect(listOf("neoforge", "fabric")))
        assertEquals(SaveLoaderDetection.Conflicting, detect(listOf("quilt,fabric")))
        assertEquals(SaveLoaderDetection.Conflicting, detect(listOf("fabric"), listOf("minecraft")))
    }

    @Test
    fun vanillaAndMissingEvidenceAreDistinct() {
        assertEquals(SaveLoaderDetection.Vanilla, detect(listOf("vanilla")))
        assertEquals(SaveLoaderDetection.Unknown, detect(emptyList()))
        assertEquals(SaveLoaderDetection.Unknown, detect(listOf("forge")))
        assertEquals(SaveLoaderDetection.Unknown, detect(listOf("vanilla"), listOf("minecraft")))
    }

    private fun detect(brands: List<String>, modIds: List<String>? = null): SaveLoaderDetection {
        val save = SyntheticSave().levelDat(modIds = modIds, brands = brands)
        return NbtMetadataReader.readLevelDat(save.root.resolve("level.dat")).getOrThrow().loaderDetection
    }
}
