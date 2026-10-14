package calebxzhou.rdi.client.ui.comp

import calebxzhou.rdi.client.model.UiMod
import calebxzhou.rdi.client.service.ModIconColorBand
import calebxzhou.rdi.client.service.ModIconColorCategory
import calebxzhou.rdi.client.service.ModIconColorSortKey
import calebxzhou.rdi.common.model.Mod
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentGridSortTest {
    private val alpha = modItem("alpha")
    private val beta = modItem("beta")
    private val gamma = modItem("gamma")
    private val shader = contentItem("aaa-shader", ContentGridType.SHADER_PACK)

    @Test
    fun `mod-only grid offers name and color sorts without type`() {
        assertEquals(
            listOf(ContentGridSort.NameAsc, ContentGridSort.NameDesc, ContentGridSort.IconColor),
            contentGridSortOptions(listOf(beta, alpha)),
        )
    }

    @Test
    fun `mixed grid defaults to type and keeps color sort for its mods`() {
        assertEquals(
            listOf(
                ContentGridSort.Type,
                ContentGridSort.NameAsc,
                ContentGridSort.NameDesc,
                ContentGridSort.IconColor,
            ),
            contentGridSortOptions(listOf(shader, alpha)),
        )
    }

    @Test
    fun `grid without mods has no color sort`() {
        assertEquals(
            listOf(ContentGridSort.NameAsc, ContentGridSort.NameDesc),
            contentGridSortOptions(listOf(shader)),
        )
    }

    @Test
    fun `implicit sort uses color only in icon mode`() {
        val options = contentGridSortOptions(listOf(alpha, beta))
        assertEquals(ContentGridSort.IconColor, implicitContentGridSort(iconOnly = true, options))
        assertEquals(ContentGridSort.NameAsc, implicitContentGridSort(iconOnly = false, options))
        val noMods = contentGridSortOptions(listOf(shader))
        assertEquals(ContentGridSort.NameAsc, implicitContentGridSort(iconOnly = true, noMods))
    }

    @Test
    fun `name sorts in both directions`() {
        val items = listOf(beta, gamma, alpha)
        assertEquals(listOf(alpha, beta, gamma), sortContentGridItems(items, ContentGridSort.NameAsc))
        assertEquals(listOf(gamma, beta, alpha), sortContentGridItems(items, ContentGridSort.NameDesc))
    }

    @Test
    fun `type sort groups mods before other content`() {
        assertEquals(
            listOf(alpha, beta, shader),
            sortContentGridItems(listOf(shader, beta, alpha), ContentGridSort.Type),
        )
    }

    @Test
    fun `color sort keeps name order until colors are analysed`() {
        assertEquals(
            listOf(alpha, beta, gamma),
            sortContentGridItems(listOf(gamma, alpha, beta), ContentGridSort.IconColor, modColors = null),
        )
    }

    @Test
    fun `color sort orders mods by color and appends other content`() {
        val colors = mapOf(
            alpha.mod.key to colorKey(ModIconColorBand.BLUE),
            beta.mod.key to colorKey(ModIconColorBand.RED),
            gamma.mod.key to colorKey(ModIconColorBand.GREEN),
        )
        assertEquals(
            listOf(beta, gamma, alpha, shader),
            sortContentGridItems(listOf(shader, alpha, beta, gamma), ContentGridSort.IconColor, colors),
        )
    }

    private fun colorKey(band: ModIconColorBand) = ModIconColorSortKey(
        category = ModIconColorCategory.CHROMATIC,
        band = band,
        hue = 0f,
        saturation = 1f,
        value = 1f,
    )

    private fun modItem(id: String) = ContentGridItem.ModItem(
        UiMod(
            Mod(
                platform = "mr",
                projectId = id,
                slug = id,
                fileId = "${id}-file",
                hash = "${id}-hash",
            )
        )
    )

    private fun contentItem(name: String, type: ContentGridType) = ContentGridItem.ContentItem(
        key = name,
        type = type,
        name = name,
        fileName = "${name}.zip",
        targetPath = "shaderpacks/${name}.zip",
        required = false,
    )
}
