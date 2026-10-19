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
