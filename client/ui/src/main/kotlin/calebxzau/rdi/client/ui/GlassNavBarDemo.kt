package calebxzau.rdi.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val DemoNavItems = listOf(
    GlassNavItem("\uF015", "主页"),
    GlassNavItem("\uF1B2", "整合包"),
    GlassNavItem("\uF0C0", "多人房间"),
    GlassNavItem("\uF007", "衣柜"),
)

private val DemoTileColors = listOf(
    Color(0xFFE91E63) to Color(0xFFFF9800),
    Color(0xFF3F51B5) to Color(0xFF00BCD4),
    Color(0xFF4CAF50) to Color(0xFFCDDC39),
    Color(0xFF9C27B0) to Color(0xFFF44336),
    Color(0xFF212121) to Color(0xFF607D8B),
)

/**
 * 玻璃导航栏演示：在IDE中直接运行此main，滚动背后的色块查看模糊和边缘折射效果。
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "GlassNavBar演示",
        state = rememberWindowState(size = DpSize(960.dp, 640.dp)),
    ) {
        RTheme {
            val hazeState = rememberHazeState()
            var selectedIndex by remember { mutableIntStateOf(0) }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(60) { index ->
                        val (start, end) = DemoTileColors[index % DemoTileColors.size]
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.linearGradient(listOf(start, end))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("房间${index + 1}", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                GlassNavBar(
                    hazeState = hazeState,
                    items = DemoNavItems,
                    selectedIndex = selectedIndex,
                    onSelect = { selectedIndex = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp),
                )
            }
        }
    }
}
