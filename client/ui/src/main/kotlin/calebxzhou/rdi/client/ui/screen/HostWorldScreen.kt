package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ContentBody
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.TitleRow
import calebxzau.rdi.client.ui.openFolder
import calebxzau.rdi.client.ui.viewmodel.HostWorldViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HostWorldScreen(
    hostId: String,
    onBack: () -> Unit,
    onImportSave: () -> Unit,
    onRollback: () -> Unit,
    viewModel: HostWorldViewModel = viewModel(key = "host-world-${hostId}") { HostWorldViewModel(hostId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showResetConfirm by remember { mutableStateOf(false) }
    val busy = state.busy != null

    state.host?.takeIf { showResetConfirm }?.let { host ->
        var resetName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置存档") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("这会删除房间存档且不可恢复。下次启动时将生成新存档。请输入房间名称确认。")
                    OutlinedTextField(
                        value = resetName,
                        onValueChange = { resetName = it },
                        label = { Text(host.name) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = resetName == host.name,
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetWorld()
                    },
                ) { Text("重置") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("取消") } },
        )
    }

    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.SMALL) {
            TitleRow(title = "房间存档管理", onBack = onBack) {
                if (state.canResetWorld) {
                    CircleIconButton(
                        icon = "\uDB85\uDC5E",
                        label = "重置存档",
                        bgColor = MaterialTheme.colorScheme.error,
                        iconColor = MaterialTheme.colorScheme.onError,
                        enabled = !busy,
                        onClick = { showResetConfirm = true },
                    )
                }
            }
            ContentBody(
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("需要使用/syncchunk指令设置“同步区块”才能自动备份/下载地图",style = MaterialTheme.typography.bodySmall,)
                state.busy?.let { message ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(message)
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (state.downloadRunId != null || state.savesDir != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {

                        state.savesDir?.let { saves ->
                            CircleIconButton(icon = "\uEAED", label = "打开单人存档文件夹") { openFolder(saves.toString()) }
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.canImportSave) {
                        CircleIconButton(
                            icon = "\uEAC3",
                            label = "上传自己的存档",
                            onClick = onImportSave,
                        )
                    }
                    if (state.canDownload) {
                        val download = state.download
                        CircleIconButton(
                            icon = "\uEAC2",
                            label = "下载房间存档",
                            enabled = !busy && download != null &&
                                (download.reusable || (download.blockedReason == null && download.remaining > 0)),
                            onClick = viewModel::downloadSave,
                        )
                    }
                    if (state.canRollback) {
                        CircleIconButton(
                            icon = "\uF1DA",
                            label = "回档",
                            onClick = onRollback,
                        )
                    }

                }
                state.download?.takeIf { state.canDownload }?.let { download ->
                    Text(
                        when {
                            download.reusable -> "存档已经打包好，这次下载不占次数"
                            download.blockedReason != null -> download.blockedReason!!
                            else -> "今天还能下载${download.remaining}次（所有成员共用${download.limit}次）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (download.today.isNotEmpty()) {
                        Text(
                            "今天已下载：" + download.today.joinToString("、") { record ->
                                "${record.playerName ?: "玩家"} ${TIME_FORMAT.format(Instant.ofEpochMilli(record.finishedAt))}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
