package calebxzau.rdi.client.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.chrisbanes.haze.HazeEffectDrawScope
import dev.chrisbanes.haze.HazeEffectFactory
import dev.chrisbanes.haze.HazeEffectRenderer
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSampling
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 液态玻璃外观参数，含义与liquid-glass.html的滑块一致。
 *
 * @property bevel 边缘斜面宽度，折射与高光只发生在斜面内
 * @property refractiveIndex 玻璃折射率，越大边缘偏折越强
 * @property frost 折射前对背景的模糊半径，0为完全通透
 * @property cornerRadius 圆角半径，未指定时为胶囊形（短边一半）
 * @property specularAngle 平行光方位角（度），高光出现在与其对齐及相反的两侧边缘
 * @property specularThickness 高光带宽度，越大越宽越柔和
 * @property tint 叠加在折射内容上的着色，用于保证前景文字对比度
 * @property hairline 1px内描边颜色，使低对比背景下轮廓仍清晰
 * @property pressedScale 按下时以按压点为中心收缩到的比例
 * @property pressedGlow 按下时按压点高光强度
 */
@Immutable
data class LiquidGlassStyle(
    val bevel: Dp = 13.dp,
    val refractiveIndex: Float = 1.5f,
    val frost: Dp = 3.dp,
    val cornerRadius: Dp = Dp.Unspecified,
    val specularAngle: Float = 60f,
    val specularIntensity: Float = 1f,
    val specularThickness: Float = 1f,
    val tint: Color = Color.Transparent,
    val hairline: Color = Color.White.copy(alpha = 0.1f),
    val hairlineWidth: Dp = 1.dp,
    val pressedScale: Float = 0.95f,
    val pressedGlow: Float = 0.5f,
)

/** 液态玻璃的按压状态，由[rememberLiquidGlassPressState]根据交互源驱动。 */
@Stable
class LiquidGlassPressState internal constructor() {
    internal val progress = Animatable(0f)
    internal var pivot by mutableStateOf(Offset.Unspecified)
}

private val PressInSpec = spring<Float>(stiffness = Spring.StiffnessHigh)
private val PressOutSpec = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

/**
 * 监听[interactionSource]的按压，按下时快速收缩、松开时带回弹恢复。
 * [interactionSource]的按压坐标需与玻璃自身坐标一致。
 */
@Composable
fun rememberLiquidGlassPressState(interactionSource: InteractionSource?): LiquidGlassPressState {
    val state = remember { LiquidGlassPressState() }
    LaunchedEffect(state, interactionSource) {
        val animationScope = this
        state.progress.snapTo(0f)
        val source = interactionSource ?: return@LaunchedEffect
        val presses = mutableListOf<PressInteraction.Press>()
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    presses += interaction
                    state.pivot = interaction.pressPosition
                }
                is PressInteraction.Release -> presses -= interaction.press
                is PressInteraction.Cancel -> presses -= interaction.press
                else -> return@collect
            }
            val pressed = presses.isNotEmpty()
            animationScope.launch {
                if (pressed) {
                    state.progress.animateTo(1f, PressInSpec)
                } else {
                    state.progress.animateTo(0f, PressOutSpec)
                }
            }
        }
    }
    return state
}

/**
 * 液态玻璃：折射并模糊[input]对应的`hazeSource`内容。
 *
 * 移植自liquid-glass.html：在[LiquidGlassStyle.bevel]斜面内用凸面squircle高度曲线求斜率，
 * 按斯涅尔定律算出朝中心的采样位移，并叠加对称的平行光镜面高光。
 * 背景捕获由haze完成，折射在本节点的图层RenderEffect中逐像素计算。
 */
fun Modifier.liquidGlass(
    input: HazeInput,
    style: LiquidGlassStyle,
    pressState: LiquidGlassPressState? = null,
): Modifier = this
    .then(LiquidGlassElement(style, pressState))
    .hazeEffect(
        factory = LiquidGlassInputFactory,
        input = input,
        style = Unit,
        sampling = HazeSampling.FullResolution,
        expandLayerBounds = false,
    )

/** 只画出haze捕获的背景，折射交给外层[LiquidGlassNode]。 */
private val LiquidGlassInputFactory = HazeEffectFactory {
    object : HazeEffectRenderer<Unit> {
        override fun HazeEffectDrawScope.draw(style: Unit) {
            drawInput()
        }
    }
}

private data class LiquidGlassElement(
    val style: LiquidGlassStyle,
    val pressState: LiquidGlassPressState?,
) : ModifierNodeElement<LiquidGlassNode>() {
    override fun create() = LiquidGlassNode(style, pressState)

    override fun update(node: LiquidGlassNode) {
        node.style = style
        node.pressState = pressState
        node.invalidateDraw()
    }
}

private class LiquidGlassNode(
    var style: LiquidGlassStyle,
    var pressState: LiquidGlassPressState?,
) : Modifier.Node(), DrawModifierNode {
    private var layer: GraphicsLayer? = null
    private var cachedUniforms: LiquidGlassUniforms? = null
    private var cachedEffect: RenderEffect? = null

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        cachedUniforms = null
        cachedEffect = null
    }

    override fun ContentDrawScope.draw() {
        val layer = layer
        if (layer == null || size.minDimension <= 0f) {
            drawContent()
            return
        }
        // 在draw中读取按压动画，变化时只重绘不重组
        val press = pressState
        val uniforms = style.resolveUniforms(
            size = size,
            density = this,
            pressProgress = press?.progress?.value ?: 0f,
            pressPivot = press?.pivot ?: Offset.Unspecified,
        )
        val effect = cachedEffect.takeIf { uniforms == cachedUniforms }
            ?: uniforms.toImageFilter().asComposeRenderEffect().also {
                cachedUniforms = uniforms
                cachedEffect = it
            }
        layer.renderEffect = effect
        layer.record { this@draw.drawContent() }
        drawLayer(layer)
    }
}

/** 解析为像素单位的shader参数；相等时复用已创建的RenderEffect。 */
internal data class LiquidGlassUniforms(
    val shapeRect: Rect,
    val cornerRadius: Float,
    val bevel: Float,
    val taper: Float,
    val refractiveIndex: Float,
    val frostSigma: Float,
    val lightDirection: Offset,
    val specularIntensity: Float,
    val shininess: Float,
    val tint: Color,
    val hairline: Color,
    val hairlineWidth: Float,
    val glowCenter: Offset,
    val glowRadius: Float,
    val glowIntensity: Float,
)

private val TAPER_MAX = 8.dp
private const val MIN_OPTICAL_PX = 0.001f
private const val GLOW_RADIUS_FRACTION = 0.8f

internal fun LiquidGlassStyle.resolveUniforms(
    size: Size,
    density: Density,
    pressProgress: Float,
    pressPivot: Offset,
): LiquidGlassUniforms = with(density) {
    // 弹簧回弹可能越界，形状只在[原尺寸, pressedScale]之间变化
    val progress = pressProgress.coerceIn(0f, 1f)
    val scale = 1f - (1f - pressedScale) * progress
    val pivot = if (pressPivot.isSpecified) {
        Offset(pressPivot.x.coerceIn(0f, size.width), pressPivot.y.coerceIn(0f, size.height))
    } else {
        size.center
    }
    val radius = if (cornerRadius.isSpecified) cornerRadius.toPx() else size.minDimension / 2f
    val bevelPx = max(bevel.toPx() * scale, MIN_OPTICAL_PX)
    val angle = Math.toRadians(specularAngle.toDouble())
    LiquidGlassUniforms(
        shapeRect = Rect(
            left = pivot.x * (1f - scale),
            top = pivot.y * (1f - scale),
            right = pivot.x + (size.width - pivot.x) * scale,
            bottom = pivot.y + (size.height - pivot.y) * scale,
        ),
        cornerRadius = radius * scale,
        bevel = bevelPx,
        taper = max(min(TAPER_MAX.toPx(), bevelPx * 0.45f), MIN_OPTICAL_PX),
        refractiveIndex = refractiveIndex,
        frostSigma = frost.toPx(),
        lightDirection = Offset(cos(angle).toFloat(), sin(angle).toFloat()),
        specularIntensity = specularIntensity,
        shininess = 6f / max(0.1f, specularThickness),
        tint = tint,
        hairline = hairline,
        hairlineWidth = hairlineWidth.toPx(),
        glowCenter = pivot,
        glowRadius = size.minDimension * GLOW_RADIUS_FRACTION,
        glowIntensity = pressedGlow * progress,
    )
}

private val liquidGlassEffect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(LIQUID_GLASS_SKSL) }

/** 先按[LiquidGlassUniforms.frostSigma]模糊背景，再执行液态玻璃shader。 */
internal fun LiquidGlassUniforms.toImageFilter(): ImageFilter {
    val builder = RuntimeShaderBuilder(liquidGlassEffect)
    builder.uniform("shapeRect", shapeRect.left, shapeRect.top, shapeRect.right, shapeRect.bottom)
    builder.uniform("cornerRadius", cornerRadius)
    builder.uniform("bevel", bevel)
    builder.uniform("taper", taper)
    builder.uniform("refractiveIndex", refractiveIndex)
    builder.uniform("lightDirection", lightDirection.x, lightDirection.y)
    builder.uniform("specularIntensity", specularIntensity)
    builder.uniform("shininess", shininess)
    builder.colorUniform("tint", tint)
    builder.colorUniform("hairline", hairline)
    builder.uniform("hairlineWidth", hairlineWidth)
    builder.uniform("glowCenter", glowCenter.x, glowCenter.y)
    builder.uniform("glowRadius", max(glowRadius, MIN_OPTICAL_PX))
    builder.uniform("glowIntensity", glowIntensity)
    val frostFilter = if (frostSigma > 0f) ImageFilter.makeBlur(frostSigma, frostSigma, FilterTileMode.CLAMP) else null
    return ImageFilter.makeRuntimeShader(builder, "content", frostFilter)
}

private fun RuntimeShaderBuilder.colorUniform(name: String, color: Color) {
    val srgb = color.convert(ColorSpaces.Srgb)
    uniform(name, srgb.red, srgb.green, srgb.blue, srgb.alpha)
}

/**
 * 坐标为图层局部像素。斜面内：
 * - 高度曲线h(t) = (1 - (1 - t)^4)^(1/4)，t为离边距离/斜面宽
 * - 斯涅尔定律求偏折角，位移 = tan(θ1 - θ2) × 斜面宽，方向朝中心（只采样玻璃内部，边缘无透明缝）
 * - 斜面内外两侧smoothstep渐隐，避免台阶
 * - 高光取|N·L|^shininess，对角两侧对称，另有随斜率变化的细亮边
 */
private const val LIQUID_GLASS_SKSL = """
uniform shader content;
uniform float4 shapeRect;
uniform float cornerRadius;
uniform float bevel;
uniform float taper;
uniform float refractiveIndex;
uniform float2 lightDirection;
uniform float specularIntensity;
uniform float shininess;
uniform float4 tint;
uniform float4 hairline;
uniform float hairlineWidth;
uniform float2 glowCenter;
uniform float glowRadius;
uniform float glowIntensity;

float sdRoundedRect(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - (halfSize - r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float2 outwardNormal(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - (halfSize - r);
    float2 n;
    if (q.x > 0.0 && q.y > 0.0) {
        n = normalize(q);
    } else if (q.x > q.y) {
        n = float2(1.0, 0.0);
    } else {
        n = float2(0.0, 1.0);
    }
    return float2(p.x < 0.0 ? -n.x : n.x, p.y < 0.0 ? -n.y : n.y);
}

float4 over(float4 src, float4 dst) {
    return src + dst * (1.0 - src.a);
}

half4 main(float2 coord) {
    float2 halfSize = (shapeRect.zw - shapeRect.xy) * 0.5;
    float2 p = coord - (shapeRect.xy + halfSize);
    float r = min(cornerRadius, min(halfSize.x, halfSize.y));
    float d = sdRoundedRect(p, halfSize, r);
    float coverage = clamp(0.5 - d, 0.0, 1.0);
    if (coverage <= 0.0) {
        return half4(0.0);
    }
    float dist = max(-d, 0.0);
    float2 displacement = float2(0.0);
    float specular = 0.0;
    if (dist < bevel) {
        float t = max(dist / bevel, 0.02);
        float u = 1.0 - t;
        float u3 = u * u * u;
        float slope = u3 / pow(1.0 - u3 * u, 0.75);
        float theta1 = atan(slope);
        float theta2 = asin(clamp(sin(theta1) / refractiveIndex, -1.0, 1.0));
        float fade = smoothstep(0.0, 1.0, (bevel - dist) / taper) * smoothstep(0.0, 1.0, dist);
        float2 n = outwardNormal(p, halfSize, r);
        displacement = -n * (tan(theta1 - theta2) * bevel * fade);
        float sinT = sin(theta1);
        float directional = pow(abs(dot(n * sinT, lightDirection)), shininess);
        float rim = max(directional, sinT * 0.06) * specularIntensity * min(1.0, sinT * 1.8);
        specular = clamp(rim * fade, 0.0, 1.0);
    }
    float4 color = float4(content.eval(coord + displacement));
    color = over(float4(tint.rgb * tint.a, tint.a), color);
    if (glowIntensity > 0.0) {
        float2 g = (coord - glowCenter) / glowRadius;
        float glow = clamp(glowIntensity * exp(-dot(g, g)), 0.0, 1.0);
        color = over(float4(glow), color);
    }
    color = over(float4(specular), color);
    float hair = hairline.a * clamp(hairlineWidth + 0.5 - dist, 0.0, 1.0);
    color = over(float4(hairline.rgb * hair, hair), color);
    return half4(color * coverage);
}
"""
