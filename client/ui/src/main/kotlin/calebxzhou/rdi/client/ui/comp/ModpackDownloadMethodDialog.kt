package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.ui.screen.prepareRdiPack2ImportFromPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ModpackDownloadMethodDialog(
    packName: String,
    packVer: String,
    onDismiss: () -> Unit,
    onDirectDownload: (Boolean) -> Unit,
    onOpenTaskList: ((String) -> Unit)? = null,
    onImportMessage: (String) -> Unit = {},
    onImportError: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var showFriendTransferTodo by remember(packName, packVer) { mutableStateOf(false) }
    var includeClientExtras by remember(packName, packVer) { mutableStateOf(false) }
    val packTitle = listOf(packName, packVer).filter(String::isNotBlank).joinToString(" ")

    fun importRdiModpack() {
        scope.launch {
            val task = withContext(Dispatchers.IO) {
                runCatching {
                    onImportMessage("开始导入...")
                    prepareRdiPack2ImportFromPicker()
                }
            }.getOrElse {
                onImportError(it.message ?: "导入失败")
                return@launch
            }
            if (task == null) {
                onDismiss()
                return@launch
            }
            onDismiss()
            val runId = ClientTaskManager.submit(task.task, task.dedupeKey)
            if (onOpenTaskList != null) {
                onOpenTaskList(runId)
            } else {
                onImportMessage("已加入任务列表")
            }
        }
    }

    if (showFriendTransferTodo) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("让朋友发我") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1.让他打开整合包-我的整合包界面")
                    Text("2.点${packTitle.ifBlank { "这个整合包" }}")
                    Text("3.点分享")
                    Text("4.让他通过QQ等工具把包发给你")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(onClick = ::importRdiModpack) {
                    Text("5.导入朋友发来的整合包")
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择下载方式") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请选择${packTitle.ifBlank { "整合包" }}下载方式")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = includeClientExtras,
                            role = Role.Checkbox,
                            onValueChange = { includeClientExtras = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = includeClientExtras,
                        onCheckedChange = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("下载此包材质光影")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDirectDownload(includeClientExtras) }) {
                Text("2.直接下载(很慢)")
            }
        },
        dismissButton = {
            TextButton(onClick = { showFriendTransferTodo = true }) {
                Text("1.让朋友分享我(非常快)")
            }
        }
    )
}
