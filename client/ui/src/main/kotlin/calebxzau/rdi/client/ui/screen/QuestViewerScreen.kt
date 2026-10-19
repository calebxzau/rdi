package calebxzau.rdi.client.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import calebxzau.rdi.client.ui.ContentBody
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.TitleRow
import calebxzau.rdi.questviewer.QuestViewer
import java.nio.file.Path

@Composable
fun QuestViewerScreen(modpackPath: String, modpackName: String, onBack: () -> Unit) {
    val directory = remember(modpackPath) { Path.of(modpackPath) }
    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.FULL) {
            TitleRow(if (modpackName.isBlank()) "任务书" else "${modpackName}·任务书", onBack)
            ContentBody {
                QuestViewer(directory, Modifier.fillMaxSize())
            }
        }
    }
}
