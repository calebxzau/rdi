package calebxzau.rdi.client.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSampling
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 共享玻璃网格的外观：玻璃光学沿用[LiquidGlassStyle]，另有悬停、按压和投影参数，
 * 默认值与单张玻璃瓷砖一致。
 *
 * @property hoverSpecularBoost 悬停时高光增强的比例
 * @property shadowRadius 投影模糊半径；悬停时过渡到[hoverShadowRadius]，像被抬起
 * @property shadowOffsetY 投影下移距离；悬停时过渡到[hoverShadowOffsetY]
 * @property edgeShadowRadius 紧贴边缘的细投影，勾出玻璃轮廓
 * @property pressedScale 按下时瓷砖与玻璃一起缩放到的比例
 */
@Immutable
data class GlassTileGridStyle(
    val glass: LiquidGlassStyle = LiquidGlassStyle(
        bevel = 9.dp,
        frost = 8.dp,
        cornerRadius = 22.dp,
        tint = Color.White.copy(alpha = 0.4f),
        hairline = Color.White.copy(alpha = 0.7f),
    ),
    val hoverSpecularBoost: Float = 0.8f,
    val shadowRadius: Dp = 10.dp,
    val hoverShadowRadius: Dp = 16.dp,
    val shadowOffsetY: Dp = 3.dp,
    val hoverShadowOffsetY: Dp = 6.dp,
    val shadowAlpha: Float = 0.18f,
    val edgeShadowRadius: Dp = 1.5.dp,
    val edgeShadowAlpha: Float = 0.16f,
    val pressedScale: Float = 0.96f,
)

/** 不指向任何瓷砖的序号。 */
internal const val NO_TILE = -1

private val HoverSpec = spring<Float>()
private val PressInSpec = spring<Float>(stiffness = Spring.StiffnessHigh)
private val PressOutSpec = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

/**
 * [GlassTileGrid]的状态：滚动位置，以及当前悬停、按下的瓷砖。
 * 瓷砖通过[TrackTileInteractions]上报交互，网格在同一个shader里画出对应的高光和缩放。
 */
@Stable
class GlassTileGridState internal constructor(
    val lazyGridState: LazyGridState,
    private val scope: CoroutineScope,
) {
    internal var hoveredIndex by mutableIntStateOf(NO_TILE)
        private set
    internal val hoverProgress = Animatable(0f)
    internal var pressedIndex by mutableIntStateOf(NO_TILE)
        private set
    internal val pressProgress = Animatable(0f)
    internal var pressedScale = 0.96f

    /** [index]号瓷砖当前的缩放；在绘制阶段读取，按压动画只触发重绘。 */
    fun tileScale(index: Int): Float =
        if (index == pressedIndex) 1f - (1f - pressedScale) * pressProgress.value else 1f

    internal fun setHovered(index: Int, hovered: Boolean) {
        if (hovered) {
            if (hoveredIndex == index) return
            hoveredIndex = index
            scope.launch {
                hoverProgress.snapTo(0f)
                hoverProgress.animateTo(1f, HoverSpec)
            }
        } else if (hoveredIndex == index) {
            scope.launch {
                hoverProgress.animateTo(0f, HoverSpec)
                // 渐隐途中悬停到别的瓷砖会取消本协程，不会清掉新的序号
                hoveredIndex = NO_TILE
            }
        }
    }

    internal fun setPressed(index: Int, pressed: Boolean) {
        if (pressed) {
            pressedIndex = index
            scope.launch { pressProgress.animateTo(1f, PressInSpec) }
        } else if (pressedIndex == index) {
            scope.launch {
                pressProgress.animateTo(0f, PressOutSpec)
                pressedIndex = NO_TILE
            }
        }
    }

}

@Composable
fun rememberGlassTileGridState(lazyGridState: LazyGridState = rememberLazyGridState()): GlassTileGridState {
    val scope = rememberCoroutineScope()
    return remember(lazyGridState, scope) { GlassTileGridState(lazyGridState, scope) }
}

/** 把瓷砖的悬停与按压上报给网格；瓷砖离开组合时清掉自己的状态。 */
@Composable
fun GlassTileGridState.TrackTileInteractions(index: Int, interactionSource: InteractionSource) {
    val state = this
    LaunchedEffect(state, index, interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is HoverInteraction.Enter -> state.setHovered(index, true)
                is HoverInteraction.Exit -> state.setHovered(index, false)
                is PressInteraction.Press -> state.setPressed(index, true)
                is PressInteraction.Release -> state.setPressed(index, false)
                is PressInteraction.Cancel -> state.setPressed(index, false)
            }
        }
    }
    DisposableEffect(state, index) {
        onDispose {
            state.setHovered(index, false)
            state.setPressed(index, false)
        }
    }
}

/**
 * 横向滚动的玻璃瓷砖网格，所有瓷砖的玻璃与投影由一次背景捕获、一次模糊和一个shader统一绘制，
 * 开销与瓷砖数量无关。瓷砖只画内容，并通过[TrackTileInteractions]上报交互。
 *
 * 瓷砖大小统一为[tileSize]，按列优先排成[rows]行；高度由行数、瓷砖高度、间距和上下内边距决定。
 */
@Composable
fun <T> GlassTileGrid(
    items: List<T>,
    state: GlassTileGridState,
    hazeState: HazeState,
    tileSize: DpSize,
    modifier: Modifier = Modifier,
    rows: Int = 3,
    spacing: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    style: GlassTileGridStyle = GlassTileGridStyle(),
    key: ((T) -> Any)? = null,
    tile: @Composable (index: Int, item: T) -> Unit,
) {
    SideEffect { state.pressedScale = style.pressedScale }
    val input = remember(hazeState) { HazeInput.Sources(hazeState) }
    val height = tileSize.height * rows + spacing * (rows - 1) +
        contentPadding.calculateTopPadding() + contentPadding.calculateBottomPadding()
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val fallbackPitch = with(density) {
        Offset((tileSize.width + spacing).toPx(), (tileSize.height + spacing).toPx())
    }
    // LazyGrid报告的瓷砖位置从内边距之后算起，换算到网格容器坐标需加上起始内边距
    val contentOffset = with(density) {
        Offset(
            contentPadding.calculateStartPadding(layoutDirection).toPx(),
            contentPadding.calculateTopPadding().toPx(),
        )
    }
    Box(modifier.height(height)) {
        Box(
            Modifier
                .matchParentSize()
                // shader按格子取模会画出视口外的瓷砖，必须裁到网格自身范围
                .clipToBounds()
                .then(GlassTileGridElement(state, items.size, rows, contentOffset, fallbackPitch, style))
                .hazeEffect(
                    factory = LiquidGlassInputFactory,
                    input = input,
                    style = Unit,
                    sampling = HazeSampling.FullResolution,
                    expandLayerBounds = false,
                )
        )
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            modifier = Modifier.fillMaxSize(),
            state = state.lazyGridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            itemsIndexed(items, key = key?.let { itemKey -> { _, item -> itemKey(item) } }) { index, item ->
                Box(Modifier.size(tileSize)) { tile(index, item) }
            }
        }
    }
}

/** 网格可见瓷砖的位置，取自LazyGrid的布局信息。 */
internal data class GlassTileSlot(val index: Int, val offset: IntOffset, val size: IntSize)

/** 第0列第0行瓷砖的左上角（可在可见区之外）、行列间距与瓷砖大小，均为像素。 */
internal data class GlassTileLayout(val origin: Offset, val pitch: Offset, val tileSize: Size)

/**
 * 从可见瓷砖推出整张网格的几何。列优先排列时第i项位于第i/rows列、第i%rows行；
 * 间距取可见范围内相距最远的同行（同列）瓷砖求平均，减少像素取整误差。
 */
internal fun resolveGlassTileLayout(slots: List<GlassTileSlot>, rows: Int, fallbackPitch: Offset): GlassTileLayout? {
    val anchor = slots.minByOrNull { it.index } ?: return null
    val anchorColumn = anchor.index / rows
    val anchorRow = anchor.index % rows
    val pitchX = slots
        .filter { it.index % rows == anchorRow && it.index / rows != anchorColumn }
        .maxByOrNull { abs(it.index / rows - anchorColumn) }
        ?.let { (it.offset.x - anchor.offset.x).toFloat() / (it.index / rows - anchorColumn) }
        ?: fallbackPitch.x
    val pitchY = slots
        .filter { it.index / rows == anchorColumn && it.index % rows != anchorRow }
        .maxByOrNull { abs(it.index % rows - anchorRow) }
        ?.let { (it.offset.y - anchor.offset.y).toFloat() / (it.index % rows - anchorRow) }
        ?: fallbackPitch.y
    return GlassTileLayout(
        origin = Offset(anchor.offset.x - anchorColumn * pitchX, anchor.offset.y - anchorRow * pitchY),
        pitch = Offset(pitchX, pitchY),
        tileSize = Size(anchor.size.width.toFloat(), anchor.size.height.toFloat()),
    )
}

private data class GlassTileGridElement(
    val state: GlassTileGridState,
    val count: Int,
    val rows: Int,
    val contentOffset: Offset,
    val fallbackPitch: Offset,
    val style: GlassTileGridStyle,
) : ModifierNodeElement<GlassTileGridNode>() {
    override fun create() = GlassTileGridNode(state, count, rows, contentOffset, fallbackPitch, style)

    override fun update(node: GlassTileGridNode) {
        node.state = state
        node.count = count
        node.rows = rows
        node.contentOffset = contentOffset
        node.fallbackPitch = fallbackPitch
        node.style = style
        node.invalidateDraw()
    }
}

private class GlassTileGridNode(
    var state: GlassTileGridState,
    var count: Int,
    var rows: Int,
    var contentOffset: Offset,
    var fallbackPitch: Offset,
    var style: GlassTileGridStyle,
) : Modifier.Node(), DrawModifierNode {
    private var layer: GraphicsLayer? = null
    private var cachedUniforms: GlassTileGridUniforms? = null
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
        val layer = layer ?: return
        if (count <= 0 || size.minDimension <= 0f) return
        // 在draw中读取滚动与交互状态，滚动和动画只触发重绘
        val slots = state.lazyGridState.layoutInfo.visibleItemsInfo
            .filter { it.index < count }
            .map { GlassTileSlot(it.index, it.offset, it.size) }
        val layout = resolveGlassTileLayout(slots, rows, fallbackPitch)
            ?.let { it.copy(origin = it.origin + contentOffset) }
            ?: return
        val pressedIndex = state.pressedIndex
        val uniforms = style.resolveUniforms(
            layout = layout,
            count = count,
            rows = rows,
            density = this,
            hoverIndex = state.hoveredIndex,
            hoverProgress = state.hoverProgress.value,
            pressIndex = pressedIndex,
            pressScale = state.tileScale(pressedIndex),
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

/** 解析为像素单位的网格shader参数。 */
internal data class GlassTileGridUniforms(
    val origin: Offset,
    val pitch: Offset,
    val tileSize: Size,
    val rows: Int,
    val count: Int,
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
    val hoverIndex: Int,
    val hoverProgress: Float,
    val hoverSpecularBoost: Float,
    val pressIndex: Int,
    val pressScale: Float,
    val shadowSigma: Float,
    val hoverShadowSigma: Float,
    val shadowOffsetY: Float,
    val hoverShadowOffsetY: Float,
    val shadowAlpha: Float,
    val edgeShadowSigma: Float,
    val edgeShadowAlpha: Float,
)

internal fun GlassTileGridStyle.resolveUniforms(
    layout: GlassTileLayout,
    count: Int,
    rows: Int,
    density: Density,
    hoverIndex: Int,
    hoverProgress: Float,
    pressIndex: Int,
    pressScale: Float,
): GlassTileGridUniforms = with(density) {
    val bevelPx = max(glass.bevel.toPx(), MIN_OPTICAL_PX)
    val angle = Math.toRadians(glass.specularAngle.toDouble())
    val radius = if (glass.cornerRadius.isSpecified) glass.cornerRadius.toPx() else layout.tileSize.minDimension / 2f
    GlassTileGridUniforms(
        origin = layout.origin,
        pitch = layout.pitch,
        tileSize = layout.tileSize,
        rows = rows,
        count = count,
        cornerRadius = radius,
        bevel = bevelPx,
        taper = max(min(TAPER_MAX.toPx(), bevelPx * 0.45f), MIN_OPTICAL_PX),
        refractiveIndex = glass.refractiveIndex,
        frostSigma = glass.frost.toPx(),
        lightDirection = Offset(cos(angle).toFloat(), sin(angle).toFloat()),
        specularIntensity = glass.specularIntensity,
        shininess = 6f / max(0.1f, glass.specularThickness),
        tint = glass.tint,
        hairline = glass.hairline,
        hairlineWidth = glass.hairlineWidth.toPx(),
        hoverIndex = hoverIndex,
        hoverProgress = hoverProgress.coerceIn(0f, 1f),
        hoverSpecularBoost = hoverSpecularBoost,
        pressIndex = pressIndex,
        pressScale = pressScale,
        shadowSigma = shadowRadius.toBlurSigma(this),
        hoverShadowSigma = hoverShadowRadius.toBlurSigma(this),
        shadowOffsetY = shadowOffsetY.toPx(),
        hoverShadowOffsetY = hoverShadowOffsetY.toPx(),
        shadowAlpha = shadowAlpha,
        edgeShadowSigma = edgeShadowRadius.toBlurSigma(this),
        edgeShadowAlpha = edgeShadowAlpha,
    )
}

/** 与常见模糊半径约定一致：sigma ≈ 0.57735 × 半径 + 0.5。 */
private fun Dp.toBlurSigma(density: Density): Float = with(density) { max(toPx() * 0.57735f + 0.5f, MIN_OPTICAL_PX) }

private val glassTileGridEffect: RuntimeEffect by lazy { RuntimeEffect.makeForShader(GLASS_TILE_GRID_SKSL) }

/** 先按[GlassTileGridUniforms.frostSigma]模糊整片背景，再执行网格玻璃shader。 */
internal fun GlassTileGridUniforms.toImageFilter(): ImageFilter {
    val builder = RuntimeShaderBuilder(glassTileGridEffect)
    builder.uniform("origin", origin.x, origin.y)
    builder.uniform("pitch", pitch.x, pitch.y)
    builder.uniform("tileSize", tileSize.width, tileSize.height)
    builder.uniform("rows", rows.toFloat())
    builder.uniform("count", count.toFloat())
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
    builder.uniform("hoverIndex", hoverIndex.toFloat())
    builder.uniform("hoverProgress", hoverProgress)
    builder.uniform("hoverSpecularBoost", hoverSpecularBoost)
    builder.uniform("pressIndex", pressIndex.toFloat())
    builder.uniform("pressScale", pressScale)
    builder.uniform("shadowSigma", shadowSigma, hoverShadowSigma)
    builder.uniform("shadowOffsetY", shadowOffsetY, hoverShadowOffsetY)
    builder.uniform("shadowAlpha", shadowAlpha)
    builder.uniform("edgeShadowSigma", edgeShadowSigma)
    builder.uniform("edgeShadowAlpha", edgeShadowAlpha)
    val frostFilter = if (frostSigma > 0f) ImageFilter.makeBlur(frostSigma, frostSigma, FilterTileMode.CLAMP) else null
    return ImageFilter.makeRuntimeShader(builder, "content", frostFilter)
}

/**
 * 坐标为图层局部像素。按行列间距取模得到当前格，只需检查当前格及右、下、右下三个邻格：
 * 像素要么落在当前格的瓷砖里，要么落在它与右/下邻格之间的缝里，投影只可能来自这四张瓷砖。
 * 投影用logistic近似高斯模糊后的边缘（d=0处为0.5），玻璃本身不透明，会盖住自己下方的投影。
 */
private const val GLASS_TILE_GRID_SKSL = """
uniform shader content;
uniform float2 origin;
uniform float2 pitch;
uniform float2 tileSize;
uniform float rows;
uniform float count;
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
uniform float hoverIndex;
uniform float hoverProgress;
uniform float hoverSpecularBoost;
uniform float pressIndex;
uniform float pressScale;
uniform float2 shadowSigma;
uniform float2 shadowOffsetY;
uniform float shadowAlpha;
uniform float edgeShadowSigma;
uniform float edgeShadowAlpha;
""" + LIQUID_GLASS_SKSL_FUNCTIONS + """
float blurredEdge(float d, float sigma) {
    return 1.0 / (1.0 + exp(min(1.702 * d / sigma, 40.0)));
}

half4 main(float2 coord) {
    float2 cell0 = floor((coord - origin) / pitch);
    float4 glass = float4(0.0);
    float glassCoverage = 0.0;
    float ambient = 0.0;
    float edge = 0.0;
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float2 cell = cell0 + float2(mod(fi, 2.0), floor(fi * 0.5));
        float index = cell.x * rows + cell.y;
        if (cell.x < 0.0 || cell.y < 0.0 || cell.y >= rows || index >= count) {
            continue;
        }
        float scale = index == pressIndex ? pressScale : 1.0;
        float hover = index == hoverIndex ? hoverProgress : 0.0;
        float2 halfSize = tileSize * 0.5 * scale;
        float r = min(cornerRadius * scale, min(halfSize.x, halfSize.y));
        float2 p = coord - (origin + cell * pitch + tileSize * 0.5);
        float d = sdRoundedRect(p, halfSize, r);
        float lift = mix(shadowOffsetY.x, shadowOffsetY.y, hover);
        float sigma = mix(shadowSigma.x, shadowSigma.y, hover);
        ambient = max(ambient, blurredEdge(sdRoundedRect(p - float2(0.0, lift), halfSize, r), sigma));
        edge = max(edge, blurredEdge(d, edgeShadowSigma));
        float coverage = clamp(0.5 - d, 0.0, 1.0);
        if (coverage > 0.0) {
            float dist = max(-d, 0.0);
            float3 optics = bevelOptics(
                dist, outwardNormal(p, halfSize, r), bevel * scale, taper * scale, refractiveIndex,
                lightDirection, specularIntensity * (1.0 + hoverSpecularBoost * hover), shininess
            );
            float4 color = float4(content.eval(coord + optics.xy));
            color = over(float4(tint.rgb * tint.a, tint.a), color);
            color = over(float4(optics.z), color);
            glass = withHairline(color, dist, hairline, hairlineWidth);
            glassCoverage = coverage;
        }
    }
    float shadow = 1.0 - (1.0 - ambient * shadowAlpha) * (1.0 - edge * edgeShadowAlpha);
    return half4(glass * glassCoverage + float4(0.0, 0.0, 0.0, shadow) * (1.0 - glassCoverage));
}
"""
