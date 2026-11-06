package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** 创建多人房间的步骤说明，可直接前往整合广场。 */
@Composable
internal fun CreateRoomGuideDialog(onDismiss: () -> Unit, onOpenModpackPlaza: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建多人房间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1.前往整合广场")
                Text("2.选择喜欢玩的包")
                Text("3.在最新版本上点创建房间")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onOpenModpackPlaza()
                },
            ) { Text("立刻前往") }
        },
    )
}

