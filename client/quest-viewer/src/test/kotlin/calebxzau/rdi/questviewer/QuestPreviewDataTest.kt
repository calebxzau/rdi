package calebxzau.rdi.questviewer

import calebxzau.rdi.quests.ftb.model.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class QuestPreviewDataTest {
    private val book = "0000000000000001"

    private fun data(book: FtbQuestBook, language: Map<String, String> = emptyMap()) =
        QuestPreviewData(book, PreviewAssets(Path.of("."), language = language), emptyList())

    @Test
    fun bookAndChapterGroupDependenciesAreResolvableTargets() {
        val group = FtbChapterGroup("g", title = "主线", icon = FtbItemStack("minecraft:book"))
        val data = data(FtbQuestBook(
            dataVersion = 13, title = "测试任务书", chapterGroups = listOf(group, FtbChapterGroup("empty")),
            chapters = listOf(FtbChapter("c", "chapters/c.snbt", groupId = "g", quests = listOf(FtbQuest("a")))),
        ))
        val groupTarget = data.resolve("g").single()
        assertEquals("c", groupTarget.chapterId)
        assertNull(groupTarget.questId)
        assertEquals("主线", groupTarget.label)
        assertEquals(PreviewIcon.Item("minecraft:book"), data.targetIcon(groupTarget))
        assertNull(data.resolve("empty").single().chapterId)
        val bookTarget = data.resolve(book).single()
        assertNull(bookTarget.chapterId)
        assertEquals("测试任务书", bookTarget.label)
    }

    @Test
    fun chapterSectionsListEachGroupOnceEvenWhenChaptersInterleave() {
        val chapters = listOf(
            FtbChapter("a", "chapters/a.snbt"),
            FtbChapter("b", "chapters/b.snbt", groupId = "missing1"),
            FtbChapter("c", "chapters/c.snbt", groupId = "missing2"),
            FtbChapter("d", "chapters/d.snbt", groupId = "missing1"),
        )
        val sections = data(FtbQuestBook(dataVersion = 13, chapters = chapters)).chapterSections
        assertEquals(listOf(null, "missing1", "missing2"), sections.map { it.groupId })
        assertEquals(listOf("b", "d"), sections[1].chapters.map { it.id })
        assertTrue(sections.all { it.group == null })
    }

    @Test
    fun searchUsesTranslatedTextWithoutFormatCodesAndIgnoresCase() {
        val chapter = FtbChapter("c", "chapters/c.snbt", quests = listOf(
            FtbQuest("a", title = "§a铁§l镐", subtitle = """{"translate":"quest.sub"}"""),
            FtbQuest("b", title = "钻石", tasks = listOf(FtbTask("t", "item", item = FtbItemStack("minecraft:diamond")))),
        ))
        val data = data(FtbQuestBook(dataVersion = 13, chapters = listOf(chapter)), mapOf("quest.sub" to "Mining Basics"))
        assertEquals(listOf("a"), data.search("  mining BASICS ").map { it.id })
        assertEquals(listOf("a"), data.search("铁镐").map { it.id })
        assertEquals(listOf("b"), data.search("MINECRAFT:DIAMOND").map { it.id })
        assertTrue(data.search("   ").isEmpty())
    }
}
