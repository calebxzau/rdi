package calebxzau.rdi.questviewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import calebxzau.rdi.quests.ftb.model.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class QuestGraphTest {
    private fun node(id: String, center: Offset, shape: String = "circle") = QuestGraphNode(id, id, center, 22f, shape, id, null)

    @Test
    fun labelWidthsKeepDenseGridLabelsApartWithoutNarrowingIsolatedRows() {
        val grid = (0..2).flatMap { row ->
            (0..4).map { column -> node("${row}:${column}", Offset(column * 72f, row * 72f)) }
        }
        val isolated = node("isolated", Offset(0f, 720f))
        val widths = questLabelWidths(grid + isolated, 16f)
        grid.forEach { assertEquals(64f, widths.getValue(it.id)) }
        assertEquals(150f, widths.getValue(isolated.id))
        assertTrue(questLabelWidths(emptyList(), 16f).isEmpty())
    }

    @Test
    fun labelWidthsHandleBandBoundariesNegativeCoordinatesAndDifferentNodeSizes() {
        for (top in listOf(-33f, -17f, -1f, 15f, 31f)) {
            val left = node("left", Offset(-72f, top - 22f))
            val right = node("right", Offset(0f, top + 2f - 44f)).copy(radius = 44f)
            val widths = questLabelWidths(listOf(left, right), 16f)
            assertEquals(64f, widths.getValue("left"))
            assertEquals(64f, widths.getValue("right"))
        }
    }

    @Test
    fun labelWidthsDoNotOverlapInStaggeredRowsOrFailOnCoincidentNodes() {
        val nodes = listOf(
            node("a", Offset(-100f, -10f)), node("b", Offset(-35f, 3f)),
            node("c", Offset(0f, 17f)), node("d", Offset(80f, 25f)),
            node("e", Offset(150f, 38f)),
        )
        val widths = questLabelWidths(nodes, 16f)
        nodes.forEachIndexed { index, left ->
            nodes.drop(index + 1).forEach { right ->
                if (kotlin.math.abs(left.center.y - right.center.y) < 16f) {
                    val gap = right.center.x - left.center.x - (widths.getValue(left.id) + widths.getValue(right.id)) / 2
                    assertTrue(gap >= 8f, "${left.id} and ${right.id} overlap")
                }
            }
        }
        val coincident = questLabelWidths(listOf(node("a", Offset.Zero), node("b", Offset.Zero)), 16f)
        assertTrue(coincident.values.all { it > 0f && it.isFinite() })
    }

    @Test
    fun zoomPreservesWorldPointUnderCursorAndHitTestingUsesSameTransform() {
        val nodes = listOf(node("a", Offset(-144f, 72f)), node("b", Offset(72f, -144f)))
        val before = QuestViewport(1.5f, Offset(300f, 210f))
        val anchor = Offset(125f, 260f)
        val after = before.zoom(2f, anchor)
        assertEquals(before.world(anchor), after.world(anchor))
        assertEquals("a", after.hit(nodes, after.screen(nodes.first().center))?.id)
        assertNull(after.hit(nodes, Offset(-500f, -500f)))
    }

    @Test
    fun fitHandlesNegativeCoordinatesAndNodeExtents() {
        val nodes = listOf(node("a", Offset(-240f, -100f)), node("b", Offset(480f, 200f)))
        val size = IntSize(600, 360)
        val viewport = fitQuestGraph(nodes, size, 1.5f)
        nodes.forEach {
            val center = viewport.screen(it.center)
            val radius = it.radius * viewport.scale
            assertTrue(center.x - radius >= 0 && center.x + radius <= size.width)
            assertTrue(center.y - radius >= 0 && center.y + radius <= size.height)
        }
    }

    @Test
    fun diamondCornersAreNotClickableAndTopmostOverlappingNodeWins() {
        val diamond = node("a", Offset.Zero, "diamond")
        val viewport = QuestViewport()
        assertNull(viewport.hit(listOf(diamond), Offset(20f, 20f)))
        val square = node("b", Offset.Zero, "square")
        assertEquals("b", viewport.hit(listOf(diamond, square), Offset(20f, 20f))?.id)
    }

    @Test
    fun ambiguousTagsRemainMultipleTargetsWithoutInventingDependencyEdges() {
        val raw = calebxzau.rdi.quests.ftb.snbt.FtbSnbtData(
            calebxzau.rdi.quests.ftb.snbt.FtbSnbt.parse("{tags:['shared']}").getOrThrow(),
        )
        val chapter = FtbChapter("c", "chapters/c.snbt", quests = listOf(
            FtbQuest("a", data = raw), FtbQuest("b", x = 2.0, data = raw),
            FtbQuest("d", x = 4.0, dependencies = listOf("#shared")),
        ))
        val data = QuestPreviewData(FtbQuestBook(dataVersion = 13, chapters = listOf(chapter)), PreviewAssets(Path.of(".")), emptyList())
        assertEquals(2, data.resolve("#shared").size)
        assertTrue(data.graph(chapter).edges.isEmpty())
    }

    @Test
    fun chapterDefaultsRespectExplicitFalseAndQuestLinkHasIndependentDefaultSize() {
        fun raw(text: String) = calebxzau.rdi.quests.ftb.snbt.FtbSnbtData(
            calebxzau.rdi.quests.ftb.snbt.FtbSnbt.parse(text).getOrThrow(),
        )
        val chapter = FtbChapter("c", "chapters/c.snbt", quests = listOf(
            FtbQuest("a"),
            FtbQuest("b", x = 2.0, dependencies = listOf("a"), data = raw("{hide_dependency_lines:false}"), repeatable = false),
            FtbQuest("d", x = 4.0, dependencies = listOf("b")),
        ), links = listOf(FtbQuestLink("link", "a", y = 2.0)),
            data = raw("{default_quest_size:2.0d,default_hide_dependency_lines:true,default_repeatable_quest:true}"))
        val data = QuestPreviewData(FtbQuestBook(dataVersion = 13, chapters = listOf(chapter)), PreviewAssets(Path.of(".")), emptyList())
        val graph = data.graph(chapter)
        assertEquals("b", graph.edges.single().to.questId)
        assertEquals(22f, graph.nodes.single { it.link }.radius)
        assertEquals(44f, graph.nodes.first().radius)
        assertTrue(data.repeatable(data.quests.getValue("a")))
        assertFalse(data.repeatable(data.quests.getValue("b")))
    }
}
