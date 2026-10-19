package calebxzau.rdi.questviewer

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertTrue

class QuestPreviewSampleTest {
    @Test
    fun opensOptInRealModpacksAndBuildsEveryChapterGraph() {
        val directories = System.getenv("RDI_QUEST_VIEWER_SAMPLES").orEmpty()
            .split(File.pathSeparator).filter(String::isNotBlank)
        assumeTrue(directories.isNotEmpty(), "RDI_QUEST_VIEWER_SAMPLES is not set")
        directories.forEach { directory ->
            val data = QuestPreviewLoader.load(Path.of(directory)).getOrThrow()
            assertTrue(data.book.chapters.isNotEmpty(), directory)
            assertTrue(data.quests.isNotEmpty(), directory)
            val graphs = data.book.chapters.map(data::graph)
            assertTrue(graphs.any { it.nodes.isNotEmpty() }, directory)
            graphs.forEach { graph ->
                assertTrue(graph.edges.all { it.from in graph.nodes && it.to in graph.nodes }, directory)
            }
            println("$directory: chapters=${graphs.size}, quests=${data.quests.size}, nodes=${graphs.sumOf { it.nodes.size }}, edges=${graphs.sumOf { it.edges.size }}, issues=${data.issues.size}")
        }
    }
}
