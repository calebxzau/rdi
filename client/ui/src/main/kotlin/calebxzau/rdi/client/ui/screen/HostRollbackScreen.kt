package calebxzau.rdi.client.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.ScrollableContentBody
import calebxzau.rdi.client.ui.TitleRow
import calebxzau.rdi.client.ui.viewmodel.HostRollbackBanner
import calebxzau.rdi.client.ui.viewmodel.HostRollbackViewModel
import calebxzau.rdi.client.ui.viewmodel.groupRollbackPointsByDay
import calebxzau.rdi.client.ui.viewmodel.rollbackAgo
import calebxzau.rdi.client.ui.viewmodel.rollbackPointChanges
import calebxzau.rdi.client.ui.viewmodel.rollbackPointDateTime
import calebxzau.rdi.client.ui.viewmodel.rollbackPointName
import calebxzau.rdi.client.ui.viewmodel.rollbackPointTime
import calebxzau.rdi.client.ui.viewmodel.rollbackUsagePercent
import calebxzau.rdi.common.model.HostRollbackPointKind
import calebxzau.rdi.common.model.HostRollbackPointVo
import calebxzau.rdi.common.model.HostRollbackRequestState
import calebxzau.rdi.common.model.HostRollbackStatusVo

/** What the confirm dialog is about to do. */
private sealed interface RollbackConfirm {
    data class Rollback(val point: HostRollbackPointVo) : RollbackConfirm
    data class Undo(val pointId: String) : RollbackConfirm
    data object Cancel : RollbackConfirm
}

/** Rolls the host's sync chunks and player data back to a 回档点 (owner only, 1.20.1 Forge). */
@Composable
fun HostRollbackScreen(
    hostId: String,
    onBack: () -> Unit,
    viewModel: HostRollbackViewModel = viewModel(key = "host-rollback-${hostId}") { HostRollbackViewModel(hostId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<RollbackConfirm?>(null) }
    val busy = state.busy != null
    val status = state.status

    if (status != null) {
        confirm?.let { action ->
            RollbackConfirmDialog(
                status = status,
                action = action,
                onConfirm = {
                    confirm = null
                    when (action) {
                        is RollbackConfirm.Rollback -> viewModel.rollback(action.point.id)
                        is RollbackConfirm.Undo -> viewModel.rollback(action.pointId)
                        RollbackConfirm.Cancel -> viewModel.cancel()
                    }
                },
                onDismiss = { confirm = null },
            )
        }
    }

    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.MEDIUM) {
            TitleRow(title = "回档", onBack = onBack) {
                CircleIconButton(icon = "\uF021", label = "刷新", showText = false, enabled = !busy, onClick = viewModel::reload)
            }
            ScrollableContentBody(state = rememberScrollState(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.busy?.let { message ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(message)
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (status == null) return@ScrollableContentBody

                state.banner?.let { banner ->
                    RollbackBannerCard(status, banner, enabled = !busy, onCancel = { confirm = RollbackConfirm.Cancel }) { pointId ->
                        confirm = RollbackConfirm.Undo(pointId)
                    }
                }
                RollbackUsage(status)

                if (status.points.isEmpty()) {
                    Text("还没有回档点。房间运行时，每5分钟自动保存一个回档点。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@ScrollableContentBody
                }
                Text("选择要回档到的时间", fontWeight = FontWeight.Bold)
                groupRollbackPointsByDay(status.points).forEach { group ->
                    Text(group.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    group.points.forEach { point ->
                        RollbackPointRow(point, enabled = !busy) { confirm = RollbackConfirm.Rollback(point) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RollbackBannerCard(
    status: HostRollbackStatusVo,
    banner: HostRollbackBanner,
    enabled: Boolean,
    onCancel: () -> Unit,
    onUndo: (String) -> Unit,
) {
    val isError = banner is HostRollbackBanner.Failed
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (banner) {
                    is HostRollbackBanner.Pending -> {
                        Text("已设置回档到${rollbackPointName(status, banner.pointId)}", fontWeight = FontWeight.Bold)
                        Text("下次启动房间时生效。" + if (banner.canCancel) "" else "房间关闭后可以取消。")
                    }
                    is HostRollbackBanner.Unfinished -> {
                        Text("回档到${rollbackPointName(status, banner.pointId)}还没有完成", fontWeight = FontWeight.Bold)
                        Text("启动房间后会继续回档。" + if (banner.canCancel) "" else "房间关闭后可以取消。")
                    }
                    HostRollbackBanner.Abandoned -> {
                        Text("已取消回档", fontWeight = FontWeight.Bold)
                        Text("下次启动房间时，会恢复到回档前的状态。")
                    }
                    is HostRollbackBanner.Done -> {
                        Text("上次已回档到${rollbackPointName(status, banner.pointId)}", fontWeight = FontWeight.Bold)
                        if (banner.undoPointId != null) Text("回档前的状态已保存，可以撤销这次回档。")
                    }
                    is HostRollbackBanner.Failed -> {
                        Text("上次回档失败", fontWeight = FontWeight.Bold)
                        Text(banner.message)
                    }
                }
            }
            when (banner) {
                is HostRollbackBanner.Pending ->
                    CircleIconButton(icon = "\uF00D", label = "取消回档", enabled = enabled && banner.canCancel, onClick = onCancel)
                is HostRollbackBanner.Unfinished ->
                    CircleIconButton(icon = "\uF00D", label = "取消回档", enabled = enabled && banner.canCancel, onClick = onCancel)
                is HostRollbackBanner.Done -> banner.undoPointId?.let { undo ->
                    CircleIconButton(icon = "\uF0E2", label = "撤销这次回档", enabled = enabled) { onUndo(undo) }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun RollbackUsage(status: HostRollbackStatusVo) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "回档空间已用${rollbackUsagePercent(status.usedBytes, status.maxBytes)}%，24小时内每5分钟一个，7天内每小时一个",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { if (status.maxBytes > 0) (status.usedBytes.toFloat() / status.maxBytes).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RollbackPointRow(point: HostRollbackPointVo, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(rollbackPointTime(point.createdAt), style = MaterialTheme.typography.titleMedium)
            Text(rollbackAgo(point.createdAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (point.kind == HostRollbackPointKind.BeforeRollback) {
                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(
                        "回档前",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Text(
                rollbackPointChanges(point),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RollbackConfirmDialog(
    status: HostRollbackStatusVo,
    action: RollbackConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rollbackLines = buildList {
        add("房间里的区块和所有玩家的背包都会恢复到这个时间，之后的建造和物品会丢失。")
        if (status.hostRunning) add("房间正在运行，会先关闭房间，在线玩家会断开。")
        add("回档在下次启动房间时进行。")
        add("回档前会自动保存当前状态，之后可以撤销。")
    }
    val (title, lines) = when (action) {
        is RollbackConfirm.Rollback -> "回档到${rollbackPointDateTime(action.point.createdAt)}？" to rollbackLines
        is RollbackConfirm.Undo -> "撤销回档，恢复到${rollbackPointName(status, action.pointId)}？" to
            listOf(rollbackLines.first(), "回档之后在房间里玩的进度也会丢失。") + rollbackLines.drop(1)
        RollbackConfirm.Cancel -> "取消回档？" to listOf(
            if (status.pending?.state == HostRollbackRequestState.Applying) {
                "回档已经开始，取消后下次启动房间时会恢复到回档前的状态。"
            } else {
                "房间会保持现在的状态，不再回档。"
            }
        )
    }
    val isCancel = action == RollbackConfirm.Cancel
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { Text("· ${it}") }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    if (isCancel) "取消回档" else "回档",
                    color = if (isCancel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("返回") }
        },
    )
}
