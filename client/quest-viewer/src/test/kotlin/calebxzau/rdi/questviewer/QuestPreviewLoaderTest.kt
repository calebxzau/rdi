package calebxzau.rdi.questviewer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class QuestPreviewLoaderTest {
    @TempDir
    lateinit var root: Path

    private fun write(relative: String, text: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun book() {
        write("config/ftbquests/quests/data.snbt", "{version: 13, default_quest_shape: 'diamond'}")
        write("config/ftbquests/quests/chapters/first.snbt", """
            {id:'10', title:'First', default_quest_size:2.0d, quests:[
                {id:'20', x:0.0d, y:0.0d, title:'Original', tags:['starter'],
                    tasks:[{id:'30', type:'item', item:'minecraft:stone', count:16L}]},
                {id:'21', x:2.0d, y:0.0d, dependencies:['30'], size:0.0d}
            ]}
        """.trimIndent())
        write("config/ftbquests/quests/chapters/second.snbt", """
            {id:'11', title:'Second', quests:[
                {id:'22', x:1.0d, y:1.0d, dependencies:['#starter'], tasks:[{id:'31',type:'checkmark'}]}
            ],quest_links:[{id:'40',linked_quest:'20',x:0.0d,y:1.0d}]}
        """.trimIndent())
        write("config/ftbquests/quests/lang/zh_cn.snbt", "{quest.0000000000000020.title:'起点'}")
    }

    private fun assets(manifest: PreviewManifest = PreviewManifest(
        1, 64, listOf(PreviewPage("texture_pages/0.png", 128, 64)),
        mapOf("minecraft:stone" to PreviewItem(0, 64, 0, "block.minecraft.stone")),
        languageFile = "lang/zh_cn.json",
    )) {
        write("rdi/preview/manifest.json", Json.encodeToString(manifest))
        write("rdi/preview/lang/zh_cn.json", """{"block.minecraft.stone":"石头","ftbquests.task.ftbquests.checkmark":"确认目标"}""")
    }

    @Test
    fun loadsLocalizedBookWithExportedItemNamesAndTaskReferences() {
        book()
        assets()
        val data = QuestPreviewLoader.load(root).getOrThrow()
        val first = data.quests.getValue("0000000000000020")
        assertEquals("起点", data.questTitle(first))
        assertEquals("石头", data.taskTitle(first.tasks.single()))
        assertEquals("确认目标", data.taskTitle(data.quests.getValue("0000000000000022").tasks.single()))
        val target = data.resolve("0000000000000030").single()
        assertEquals(first.id, target.questId)
        assertEquals("0000000000000010", target.chapterId)
        assertEquals(first.id, data.resolve("#starter").single().questId)
        val graph = data.graph(data.chapters.getValue("0000000000000010"))
        assertEquals("diamond", graph.nodes.first().shape)
        assertEquals(44f, graph.nodes.last().radius)
        assertEquals(first.id, graph.edges.single().from.questId)
        assertTrue(data.issues.isEmpty(), data.issues.joinToString())
    }

    @Test
    fun questLinkNavigatesAcrossChaptersAndRepresentsDependency() {
        book()
        val data = QuestPreviewLoader.load(root).getOrThrow()
        val graph = data.graph(data.chapters.getValue("0000000000000011"))
        val link = graph.nodes.single { it.link }
        assertEquals("0000000000000020", link.questId)
        assertEquals(link, graph.edges.single().from)
        assertEquals("0000000000000010", data.resolve(link.id).single().chapterId)
    }

    @Test
    fun missingExportDoesNotPreventReadingBook() {
        book()
        val data = QuestPreviewLoader.load(root).getOrThrow()
        assertEquals(3, data.quests.size)
        assertNull(data.assets.manifest)
        assertTrue(data.issues.any { it.contains("未找到物品预览素材") })
    }

    @Test
    fun brokenOptionalSnbtReportsDiagnosticAndKeepsOtherChapters() {
        book()
        write("config/ftbquests/quests/chapters/bad.snbt", "{broken:")
        val data = QuestPreviewLoader.load(root).getOrThrow()
        assertEquals(2, data.book.chapters.size)
        assertTrue(data.issues.any { it.contains("bad.snbt") })
    }

    @Test
    fun absentBookIsAnExplicitFailure() {
        assertTrue(QuestPreviewLoader.load(root).isFailure)
    }

    @Test
    fun malformedOrOutOfBoundsManifestPreservesBook() {
        book()
        assets(PreviewManifest(1, 64, listOf(PreviewPage("texture_pages/0.png", 64, 64)),
            mapOf("minecraft:stone" to PreviewItem(0, Int.MAX_VALUE, 0, "stone"))))
        val data = QuestPreviewLoader.load(root).getOrThrow()
        assertNull(data.assets.manifest)
        assertEquals(3, data.quests.size)
        assertTrue(data.issues.any { it.contains("素材无法读取") })
    }

    @Test
    fun missingLanguageFallsBackToItemIdAndKeepsAtlas() {
        book()
        assets(PreviewManifest(1, 64, listOf(PreviewPage("texture_pages/0.png", 64, 64)),
            mapOf("minecraft:stone" to PreviewItem(0, 0, 0, "stone")), "lang/missing.json"))
        val data = QuestPreviewLoader.load(root).getOrThrow()
        assertNotNull(data.assets.manifest)
        assertEquals("minecraft:stone", data.assets.itemName("minecraft:stone"))
        assertTrue(data.issues.any { it.contains("名称翻译无法读取") })
    }

    @Test
    fun rejectsUnsafeExportPathsButReadsLegacyGenerationPaths() {
        listOf("../other.png", "/tmp/other.png", "C:/other.png", "texture_pages/../../other.png", "texture_pages\\0.png").forEach {
            assertFailsWith<IllegalArgumentException>(it) { validatePreviewRelativePath(it) }
        }
        val relative = "texture_pages/019f0000-0000-7000-8000-000000000000/0.png"
        write("rdi/preview/$relative", "fixture")
        assertEquals(root.resolve("rdi/preview/$relative"), previewFile(root.resolve("rdi/preview"), relative))
    }
}
