package calebxzau.rdi.anvilrw.remap

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DimensionStorageFolderTest {
    private val root = Path.of("saves", "world").toAbsolutePath()

    @Test
    fun vanillaDimensions() {
        assertEquals(root, dimensionStorageFolder(root, "minecraft:overworld").getOrThrow())
        assertEquals(root, dimensionStorageFolder(root, "overworld").getOrThrow())
        assertEquals(root.resolve("DIM-1"), dimensionStorageFolder(root, "minecraft:the_nether").getOrThrow())
        assertEquals(root.resolve("DIM1"), dimensionStorageFolder(root, "minecraft:the_end").getOrThrow())
        assertEquals(root.resolve("DIM1"), dimensionStorageFolder(root, ":the_end").getOrThrow())
    }

    @Test
    fun customDimensions() {
        assertEquals(
            root.resolve("dimensions").resolve("twilightforest").resolve("twilight_forest"),
            dimensionStorageFolder(root, "twilightforest:twilight_forest").getOrThrow(),
        )
        assertEquals(
            root.resolve("dimensions").resolve("my.mod-1").resolve("a").resolve("b_2.x"),
            dimensionStorageFolder(root, "my.mod-1:a/b_2.x").getOrThrow(),
        )
        assertEquals(
            root.resolve("dimensions").resolve("minecraft").resolve("custom"),
            dimensionStorageFolder(root, "custom").getOrThrow(),
        )
    }

    @Test
    fun unsafeOrInvalidIdsAreRejected() {
        val ids = listOf(
            "",
            "demo:",
            "demo:../../../outside",
            "demo:..",
            "demo:.",
            "demo:a/../../b",
            "demo:/tmp",
            "demo:a//b",
            "demo:a/",
            "..:x",
            ".:x",
            "demo:a.",
            "demo:con",
            "demo:nul.txt",
            "lpt1:x",
            "Demo:x",
            "demo:X",
            "demo:a\\b",
            "demo:a:b",
            "demo:a b",
            "demo:${"a".repeat(600)}",
        )
        for (id in ids) {
            assertIs<InvalidDimensionIdException>(dimensionStorageFolder(root, id).exceptionOrNull(), "\"${id}\" must be rejected")
        }
    }
}
