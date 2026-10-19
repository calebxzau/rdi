package calebxzau.rdi.questviewer

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

private val MINECRAFT_COLORS = listOf(
    0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
    0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0x8A6500, 0xFFFFFF,
)

internal fun questAnnotatedText(raw: String): AnnotatedString = buildAnnotatedString {
    var style = SpanStyle()
    var index = 0
    var segment = 0
    fun flush(end: Int) {
        if (end > segment) {
            pushStyle(style)
            append(raw.substring(segment, end))
            pop()
        }
    }
    while (index + 1 < raw.length) {
        if (raw[index] == '§' || raw[index] == '&') {
            val code = raw[index + 1].lowercaseChar()
            if (code in "0123456789abcdefklmnor") {
                flush(index)
                style = when (code) {
                    in '0'..'9', in 'a'..'f' -> SpanStyle(color = Color(0xFF000000 or MINECRAFT_COLORS[code.digitToInt(16)].toLong()))
                    'l' -> style.copy(fontWeight = FontWeight.Bold)
                    'o' -> style.copy(fontStyle = FontStyle.Italic)
                    'n' -> style.copy(textDecoration = TextDecoration.Underline)
                    'm' -> style.copy(textDecoration = TextDecoration.LineThrough)
                    'r' -> SpanStyle()
                    else -> style // Obfuscated text remains readable in a preview.
                }
                index += 2
                segment = index
                continue
            }
        }
        index++
    }
    flush(raw.length)
}

internal fun searchableQuestText(raw: String): String = raw.replace(Regex("[§&][0-9a-fk-or]", RegexOption.IGNORE_CASE), "")

@Composable
internal fun QuestText(text: String) {
    Text(remember(text) { questAnnotatedText(text) })
}
