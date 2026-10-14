package calebxzau.rdi.client.ui.comp

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Solid core with a breathing halo, centered in [modifier]'s bounds; the halo alpha is read only while drawing, so callers never recompose for it. */
@Composable
fun GlowingDot(
    color: Color,
    modifier: Modifier = Modifier,
    coreRadius: Dp = 3.dp,
) {
    val glowAlpha = rememberInfiniteTransition(label = "glowingDot").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "glowingDotHalo",
    )
    Canvas(modifier) {
        val glowRadius = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = glowAlpha.value), Color.Transparent),
                center = center,
                radius = glowRadius,
            ),
            radius = glowRadius,
        )
        drawCircle(color = color, radius = coreRadius.toPx())
    }
}
