package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ContentBody
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.TitleRow

@Composable
fun HostWorldScreen(onBack: () -> Unit) {
    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.SMALL) {
            TitleRow(title = "房间存档管理", onBack = onBack)
            ContentBody(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIconButton(
                        icon = "\ueac3",
                        label = "上传自己的存档",
                        onClick = {},
                    )
                    CircleIconButton(
                        icon = "\ueac2",
                        label = "下载房间存档",
                        onClick = {},
                    )
                }
            }
        }
    }
}
