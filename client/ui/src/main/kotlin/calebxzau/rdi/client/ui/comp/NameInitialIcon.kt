package calebxzau.rdi.client.ui.comp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight

/** Stand-in icon for something without artwork: the name's first letter on a color derived from the name, so each item stays recognizable. */
@Composable
fun NameInitialIcon(name: String, modifier: Modifier = Modifier) {
    val initial = remember(name) { nameInitial(name) }
    val background = remember(name) { Color.hsl(nameHue(name), 0.45f, 0.5f) }
    BoxWithConstraints(modifier.background(background), contentAlignment = Alignment.Center) {
        val fontSize = with(LocalDensity.current) { (minOf(maxWidth, maxHeight) * 0.5f).toSp() }
        Text(text = initial, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

/** First letter or digit of [name] (skipping brackets and symbols such as "【"), upper-cased; "?" when there is none. */
internal fun nameInitial(name: String): String {
    val codePoint = name.codePoints().filter(Character::isLetterOrDigit).findFirst()
    if (!codePoint.isPresent) return "?"
    return String(Character.toChars(codePoint.asInt)).uppercase()
}

/** Hue in [0, 360) that is stable for the same [name] across runs ([String.hashCode] is fixed by the JDK spec). */
internal fun nameHue(name: String): Float = Math.floorMod(name.hashCode(), 360).toFloat()
