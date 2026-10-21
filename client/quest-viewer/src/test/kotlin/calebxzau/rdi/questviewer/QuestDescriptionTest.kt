package calebxzau.rdi.questviewer

import androidx.compose.ui.text.LinkAnnotation
import calebxzau.rdi.quests.ftb.model.FtbChapter
import calebxzau.rdi.quests.ftb.model.FtbQuest
import calebxzau.rdi.quests.ftb.model.FtbQuestBook
import calebxzau.rdi.quests.ftb.snbt.FtbSnbt
import calebxzau.rdi.quests.ftb.snbt.FtbSnbtData
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class QuestDescriptionTest {
    private val questId = "315964E756BC5D6D"
    private val chapterId = "1668FC63F8762C7E"
    private val secondQuestId = "00000000000000AB"
    private val tags = FtbSnbtData(FtbSnbt.parse("{tags:['shared']}").getOrThrow())
    private val data = QuestPreviewData(
        FtbQuestBook(dataVersion = 13, chapters = listOf(
            FtbChapter(chapterId, "chapters/target.snbt", title = "世界的枷锁", quests = listOf(
                FtbQuest(questId, title = "&d灾厄之册", data = tags),
            )),
            FtbChapter("50414E56188AC452", "chapters/other.snbt", quests = listOf(
                FtbQuest(secondQuestId, title = "注意事项", data = tags),
            )),
        )),
        PreviewAssets(Path.of(".")),
        emptyList(),
    )

    private fun link(reference: String, caption: String = "EDIT HERE") =
        """{"text":"${caption}","underlined":"true","clickEvent":{"action":"change_page","value":"${reference}"}}"""

    @Test
    fun replacesPlaceholderWithTargetTitleAndClickNavigatesToItsChapterAndQuest() {
        var selected: PreviewTarget? = null
        val text = questDescriptionText(link(questId), data) { selected = it }
        assertEquals("灾厄之册", text.text)
        assertTrue(text.spanStyles.any { it.item.color == MINECRAFT_COLORS[0xd] })
        val annotation = text.getLinkAnnotations(0, text.length).single()
        assertEquals(0, annotation.start)
        assertEquals(text.length, annotation.end)
        val clickable = assertIs<LinkAnnotation.Clickable>(annotation.item)
        assertEquals(questId, clickable.tag)
        assertNotNull(clickable.linkInteractionListener).onClick(clickable)
        assertEquals(chapterId, selected?.chapterId)
        assertEquals(questId, selected?.questId)
    }

    @Test
    fun normalizesLowercasePrefixedAndShortHexReferences() {
        for (reference in listOf(questId.lowercase(), " 0x${questId.lowercase()} ", "ab")) {
            val text = questDescriptionText(link(reference), data) {}
            val expected = if (reference == "ab") secondQuestId else questId
            assertEquals(expected, assertIs<LinkAnnotation.Clickable>(text.getLinkAnnotations(0, text.length).single().item).tag)
        }
    }

    @Test
    fun preservesMixedProseAndExtraComponentsAndMakesOnlyTargetsClickable() {
        val raw = """{"text":"前往","extra":[${link(questId)},"，然后查看",${link(secondQuestId)},"。"]}"""
        val text = questDescriptionText(raw, data) {}
        assertEquals("前往灾厄之册，然后查看注意事项。", text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("灾厄之册", "注意事项"), links.map { text.text.substring(it.start, it.end) })
    }

    @Test
    fun missingAndAmbiguousReferencesRemainVisibleWithoutInventingNavigation() {
        for ((reference, notice) in listOf("missing" to "任务链接不可用", "#shared" to "任务链接不明确", "" to "任务链接不可用")) {
            val text = questDescriptionText(link(reference), data) { fail("Unresolved links must not navigate") }
            assertEquals(notice, text.text)
            assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
        }
        assertEquals("查看教程（任务链接不可用）", questDescriptionText(link("missing", "查看教程"), data) {}.text)
    }

    @Test
    fun chapterLinksNavigateWithoutSelectingAQuest() {
        var selected: PreviewTarget? = null
        val text = questDescriptionText(link(chapterId), data) { selected = it }
        assertEquals("世界的枷锁", text.text)
        val clickable = text.getLinkAnnotations(0, text.length).single().item
        assertNotNull(clickable.linkInteractionListener).onClick(clickable)
        assertEquals(chapterId, selected?.chapterId)
        assertNull(selected?.questId)
    }

    @Test
    fun ordinaryMalformedAndUnsupportedTextStaysReadableWithoutExecutingOtherActions() {
        val command = """{"text":"保留文字","clickEvent":{"action":"run_command","value":"/give @p diamond"}}"""
        for ((raw, expected) in listOf(
            "&e正文" to "正文",
            "EDIT HERE" to "EDIT HERE",
            "{unfinished text" to "{unfinished text",
            """{"selector":"@p"}""" to """{"selector":"@p"}""",
            command to "保留文字",
        )) {
            val text = questDescriptionText(raw, data) { fail("Non-navigation text must not navigate") }
            assertEquals(expected, text.text)
            assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
        }
    }
}
