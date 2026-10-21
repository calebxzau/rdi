package calebxzau.rdi.questviewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Test
import kotlin.test.*

class QuestTextTest {
    @Test
    fun resolvesJsonComponentsAndKeepsOrdinaryOrUnsupportedTextVisible() {
        val language = mapOf("quest.welcome" to "欢迎%s")
        assertEquals("欢迎玩家！", previewPlainText("""{"translate":"quest.welcome","with":[{"text":"玩家"}],"extra":[{"text":"！"}]}""", language))
        assertEquals("{unfinished text", previewPlainText("{unfinished text", language))
        val unsupported = """{"selector":"@p"}"""
        assertEquals(unsupported, previewPlainText(unsupported, language))
    }

    @Test
    fun colorAndStyleCodesAreRenderedAndSearchDoesNotContainCodes() {
        val text = "§a开始§r &l任务&r"
        val styled = questAnnotatedText(text)
        assertEquals("开始 任务", styled.text)
        assertTrue(styled.spanStyles.size >= 2)
        assertEquals(styled.text, searchableQuestText(text))
    }

    @Test
    fun whiteInheritsThemeTextColorAndBrightCodesStayReadableOnLightSurface() {
        assertEquals(Color.Unspecified, questAnnotatedText("§f白色").spanStyles.single().item.color)
        val surface = Color(0xFFF5FAFD)
        fun contrast(a: Color, b: Color): Float {
            val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
            return (light + 0.05f) / (dark + 0.05f)
        }
        MINECRAFT_COLORS.filter { it != Color.Unspecified }.forEach { color ->
            assertTrue(contrast(color, surface) >= 4.5f, "low contrast: $color")
        }
        assertEquals(16, MINECRAFT_COLORS.distinct().size)
    }
}
