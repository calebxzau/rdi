package calebxzau.rdi.client.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.RefractionProfile
import dev.chrisbanes.haze.glass.hazeGlass
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class GlassNavItem(
    val icon: String,
    val label: String,
)

private val GlassNavPillShape = RoundedCornerShape(percent = 50)

/**
 * 玻璃质感导航栏：模糊并折射[hazeState]对应的`hazeSource`内容，图标在左、文字在右。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun GlassNavBar(
    hazeState: HazeState,
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val glassStyle = remember(surface) {
        GlassStyle.regular.then {
            shape(GlassNavPillShape)
            tint(surface.copy(alpha = 0.35f))
            // regular在短边<64dp时depth<1会保留背景细节；固定depth=1完全漫射，其余沿用GlassDefaults.optics
            optics(
                blurRadius = 20.dp,
                depth = 1f,
                refractionDisplacement = 48.dp,
                refractionProfile = RefractionProfile.Edge(20.dp),
                refractionHeightFraction = 0.15f,
                refractionDetailIntensity = 0f,
            )
        }
    }
    // 选中胶囊用液态玻璃透镜：斜面折射+对称高光，着色保证选中文字对比度
    val indicatorStyle = remember(surface) { LiquidGlassStyle(tint = surface.copy(alpha = 0.35f)) }
    val input = remember(hazeState) { HazeInput.Sources(hazeState) }
    // 每个入口相对Row的x坐标与宽度(px)，用于定位选中胶囊
    val itemBounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    // 每个入口的交互源；选中项的交互源同时驱动胶囊，使按压反馈作用在玻璃上
    val interactionSources = remember(items.size) { List(items.size) { MutableInteractionSource() } }
    val indicatorPress = rememberLiquidGlassPressState(interactionSources.getOrNull(selectedIndex))
    val indicatorX = remember { Animatable(0f) }
    val indicatorWidth = remember { Animatable(0f) }
    val target = itemBounds[selectedIndex]

    LaunchedEffect(target) {
        val (x, width) = target ?: return@LaunchedEffect
        if (indicatorWidth.value == 0f) {
            indicatorX.snapTo(x)
            indicatorWidth.snapTo(width)
        } else {
            val spec = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
            coroutineScope {
                launch { indicatorX.animateTo(x, spec) }
                launch { indicatorWidth.animateTo(width, spec) }
            }
        }
    }

    Box(
        modifier = modifier
            .height(56.dp)
            .hazeGlass(input = input, style = glassStyle)
            .padding(6.dp)
    ) {
        if (target != null) {
            val density = LocalDensity.current
            Box(
                Modifier
                    .offset { IntOffset(indicatorX.value.roundToInt(), 0) }
                    .width(with(density) { indicatorWidth.value.toDp() })
                    .fillMaxHeight()
                    .liquidGlass(input = input, style = indicatorStyle, pressState = indicatorPress)
            )
        }
        Row(
            modifier = Modifier.fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                GlassNavBarItem(
                    item = item,
                    selected = index == selectedIndex,
                    interactionSource = interactionSources[index],
                    onClick = { onSelect(index) },
                    modifier = Modifier.onPlaced { coordinates ->
                        itemBounds[index] = coordinates.positionInParent().x to coordinates.size.width.toFloat()
                    }
                )
            }
        }
    }
}

@Composable
private fun GlassNavBarItem(
    item: GlassNavItem,
    selected: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    )
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(GlassNavPillShape)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                // 选中项的按压反馈由胶囊玻璃呈现，不再叠加波纹
                indication = if (selected) null else LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.icon,
            fontFamily = IconFontFamily,
            fontSize = 18.sp,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

