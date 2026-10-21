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

// The launcher uses a light color scheme; quest books are colored for the game's dark GUI.
// Bright codes are darkened for contrast and §f inherits the theme's text color.
internal val MINECRAFT_COLORS = listOf(
    0x000000, 0x0000AA, 0x006400, 0x006B6B, 0xAA0000, 0xAA00AA, 0x9A5C00, 0x6B6B6B,
    0x555555, 0x3B3BE0, 0x1A7A1A, 0x00757F, 0xD12F2F, 0xB82DB8, 0x8A6500, null,
).map { rgb -> rgb?.let { Color(0xFF000000 or it.toLong()) } ?: Color.Unspecified }

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
                    in '0'..'9', in 'a'..'f' -> SpanStyle(color = MINECRAFT_COLORS[code.digitToInt(16)])
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
