package calebxzau.rdi.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import calebxzau.rdi.client.ui.comp.NameInitialIcon
import calebxzhou.rdi.client.ui.comp.CardHint
import calebxzhou.rdi.client.ui.comp.CardHintHost
import calebxzhou.rdi.client.ui.comp.IconTileCard
import calebxzhou.rdi.client.ui.comp.TILE_GRID_SPACING
import calebxzhou.rdi.client.ui.comp.TileGlass
import calebxzhou.rdi.client.ui.comp.TileGridGlassStyle
import calebxzhou.rdi.client.ui.comp.TileIconShape
import calebxzhou.rdi.client.ui.comp.iconTileSize

private const val DEMO_TILE_COUNT = 60
private const val DEMO_ROWS = 3
private val DemoTileSize = iconTileSize(110.dp)
private val DemoGridPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
private val DemoGridHeight = DemoTileSize.height * DEMO_ROWS + TILE_GRID_SPACING * (DEMO_ROWS - 1) + 32.dp

/**
 * 玻璃瓷砖网格演示：在动态背景上放[DEMO_TILE_COUNT]张瓷砖，两侧边缘悬停自动滚动，左上角显示帧率，
 * 可在“共享玻璃”（整张网格一个shader）与“逐卡玻璃”（每张瓷砖各自捕获、模糊背景）之间切换对比。
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "玻璃瓷砖网格演示",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        RTheme {
            AppBackgroundProvider {
                GlassTileGridDemoContent()
            }
        }
    }
}

@Composable
private fun GlassTileGridDemoContent() {
    val hazeState = LocalAppBackgroundHazeState.current
    val grid = rememberGlassTileGridState()
    val titles = remember { List(DEMO_TILE_COUNT) { "房间${it + 1}" } }
    var shared by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
        AppBackgroundImage(active = true, modifier = Modifier.fillMaxSize())
        CardHintHost(Modifier.fillMaxSize()) {
            EdgeAutoScrollBox(
                scrollState = grid.lazyGridState,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(DemoGridHeight),
            ) {
                if (shared) {
                    GlassTileGrid(
                        items = titles,
                        state = grid,
                        hazeState = hazeState,
                        tileSize = DemoTileSize,
                        modifier = Modifier.fillMaxWidth(),
                        rows = DEMO_ROWS,
                        spacing = TILE_GRID_SPACING,
                        contentPadding = DemoGridPadding,
                        style = TileGridGlassStyle,
                    ) { index, title ->
                        DemoTile(title, TileGlass.Shared(grid, index))
                    }
                } else {
                    PerTileGlassGrid(titles, grid, hazeState, Modifier.fillMaxWidth())
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FrameRateText()
            CircleIconButton(
                icon = "\uF0EC",
                label = if (shared) "共享玻璃（点击切换）" else "逐卡玻璃（点击切换）",
            ) { shared = !shared }
        }
    }
}

/** 对照组：同样的布局，每张瓷砖各自折射背景。 */
@Composable
private fun PerTileGlassGrid(
    titles: List<String>,
    grid: GlassTileGridState,
    hazeState: dev.chrisbanes.haze.HazeState,
    modifier: Modifier,
) {
    LazyHorizontalGrid(
        rows = GridCells.Fixed(DEMO_ROWS),
        modifier = modifier.height(DemoGridHeight),
        state = grid.lazyGridState,
        contentPadding = DemoGridPadding,
        horizontalArrangement = Arrangement.spacedBy(TILE_GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(TILE_GRID_SPACING),
    ) {
        itemsIndexed(titles) { _, title ->
            Box(Modifier.size(DemoTileSize)) { DemoTile(title, TileGlass.Backdrop(hazeState)) }
        }
    }
}

@Composable
private fun DemoTile(title: String, glass: TileGlass) {
    IconTileCard(
        title = title,
        hint = CardHint(title = title),
        glass = glass,
        onPrimaryClick = {},
    ) {
        NameInitialIcon(title, Modifier.fillMaxSize().clip(TileIconShape))
    }
}

/** 每秒统计一次实际出帧数。 */
@Composable
private fun FrameRateText() {
    var fps by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var frames = 0
        var windowStart = 0L
        while (true) {
            withFrameNanos { now ->
                if (windowStart == 0L) windowStart = now
                frames++
                if (now - windowStart >= 1_000_000_000L) {
                    fps = frames
                    frames = 0
                    windowStart = now
                }
            }
        }
    }
    Text(
        text = "${fps}fps",
        color = Color.White,
        style = MaterialTheme.typography.titleLarge,
    )
}
