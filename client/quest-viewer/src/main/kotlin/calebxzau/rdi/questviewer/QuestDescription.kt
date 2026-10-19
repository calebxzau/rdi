package calebxzau.rdi.questviewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import kotlinx.serialization.json.*
import java.util.Locale

@Composable
internal fun QuestDescription(raw: String, data: QuestPreviewData, onNavigate: (PreviewTarget) -> Unit) {
    val styles = TextLinkStyles(style = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    ))
    val text = remember(raw, data, styles, onNavigate) { questDescriptionText(raw, data, styles, onNavigate) }
    Text(text)
}

/** Preserve task navigation before the plain-text representation discards click events. */
internal fun questDescriptionText(
    raw: String,
    data: QuestPreviewData,
    styles: TextLinkStyles = TextLinkStyles(),
    onNavigate: (PreviewTarget) -> Unit,
): AnnotatedString {
    val translated = data.assets.language[raw] ?: raw
    if (!translated.trimStart().startsWith('{') && !translated.trimStart().startsWith('[')) {
        return questAnnotatedText(translated)
    }
    val component = try {
        Json.parseToJsonElement(translated)
    } catch (_: IllegalArgumentException) {
        // Quest prose and FTB markup need not be JSON.
        return questAnnotatedText(translated)
    }
    return buildAnnotatedString {
        fun appendComponent(value: JsonElement, depth: Int) {
            if (depth > 32) {
                append(questAnnotatedText(data.text(value.toString())))
                return
            }
            when (value) {
                is JsonPrimitive -> append(questAnnotatedText(value.content))
                is JsonArray -> value.forEach { appendComponent(it, depth + 1) }
                is JsonObject -> {
                    val base = JsonObject(value - "extra")
                    val click = value["clickEvent"] as? JsonObject
                    val reference = (click?.get("value") as? JsonPrimitive)?.content?.trim().orEmpty()
                    if ((click?.get("action") as? JsonPrimitive)?.content == "change_page") {
                        val direct = data.resolve(reference)
                        val targets = if (direct.isNotEmpty()) direct else {
                            val canonical = reference.removePrefix("0x").removePrefix("0X")
                                .toULongOrNull(16)?.toString(16)?.uppercase(Locale.ROOT)?.padStart(16, '0')
                            canonical?.let(data::resolve).orEmpty()
                        }
                        val target = targets.singleOrNull()
                        if (target != null) {
                            withLink(LinkAnnotation.Clickable(target.id, styles) { onNavigate(target) }) {
                                append(questAnnotatedText(target.label))
                            }
                        } else {
                            val caption = data.text(base.toString()).takeUnless { it.isBlank() || it.trim() == "EDIT HERE" }
                            val notice = if (targets.isEmpty()) "任务链接不可用" else "任务链接不明确"
                            append(questAnnotatedText(caption?.let { "${it}（${notice}）" } ?: notice))
                        }
                    } else {
                        append(questAnnotatedText(data.text(base.toString())))
                    }
                    (value["extra"] as? JsonArray)?.forEach { appendComponent(it, depth + 1) }
                }
            }
        }
        appendComponent(component, 0)
    }
}
