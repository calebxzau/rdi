package calebxzau.rdi.client.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ContentBody
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.TitleRow
import calebxzau.rdi.client.ui.themeNow
import calebxzau.rdi.questviewer.QuestViewer
import java.nio.file.Path

@Composable
fun QuestViewerScreen(modpackPath: String, modpackName: String, onBack: () -> Unit) {
    val directory = remember(modpackPath) { Path.of(modpackPath) }
    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.FULL) {
            TitleRow("任务书预览", onBack)
            ContentBody {
                QuestViewer(directory, Modifier.fillMaxSize()) { expanded, onClick ->
                    CircleIconButton(
                        icon = if (expanded) "\uDB85\uDF8D" else "\uDB80\uDEFD",
                        label = if (expanded) "收起详细字段" else "查看详细字段",
                        showText = false,
                        size = 26.dp,
                        bgColor = themeNow.onPrimary,
                        iconColor = themeNow.primary,
                        iconFontScale = 0.8f,
                        onClick = onClick,
                    )
                }
            }
        }
    }
}
