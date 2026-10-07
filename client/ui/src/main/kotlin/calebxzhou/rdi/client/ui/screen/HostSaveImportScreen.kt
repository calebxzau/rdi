package calebxzau.rdi.client.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ConfirmDialog
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.ScrollableContentBody
import calebxzau.rdi.client.ui.TitleRow
import calebxzau.rdi.client.ui.comp.QqPlayerSearchDialog
import calebxzau.rdi.client.ui.comp.SavePlayerMappingCard
import calebxzau.rdi.client.ui.viewmodel.HostSaveImportViewModel
import calebxzau.rdi.client.ui.viewmodel.SaveImportStep
import calebxzhou.rdi.client.ui.McPlayArgs
import java.util.UUID

private const val MARKING_NOTICE =
    "1.点击开始按钮启动此整合包\n2.输入/syncchunk show显示你当前所在区块\n3.挨个找你想传的区块，站在里面，输入/syncchunk add，最多256个\n4.弄完了保存并退出"

/** Imports one of the owner's singleplayer saves into the host (plan §4). */
@Composable
fun HostSaveImportScreen(
    hostId: String,
    onBack: () -> Unit,
    onOpenMcPlay: (McPlayArgs) -> Unit,
    onOpenTask: (String) -> Unit,
    viewModel: HostSaveImportViewModel = viewModel(key = "host-save-import-${hostId}") { HostSaveImportViewModel(hostId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launch by viewModel.launch.collectAsStateWithLifecycle()
    var qqSearchFor by remember { mutableStateOf<UUID?>(null) }
    var confirmCleanup by remember { mutableStateOf(false) }
    val busy = state.busy != null

    LaunchedEffect(launch) {
        launch?.let { args ->
            viewModel.onLaunchHandled()
            onOpenMcPlay(args)
        }
    }

    qqSearchFor?.let { uuid ->
        QqPlayerSearchDialog(
            onSearch = viewModel::searchQq,
            onPick = { target ->
                viewModel.setTarget(uuid, target)
                qqSearchFor = null
            },
            onDismiss = { qqSearchFor = null },
        )
    }
    if (confirmCleanup) {
        ConfirmDialog(
            title = "清理导入副本",
            message = "将删除为导入创建的存档副本和其中的区块标记，原存档不受影响。",
            onConfirm = {
                confirmCleanup = false
                viewModel.cleanupCopy()
            },
            onDismiss = { confirmCleanup = false },
        )
    }

    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.SMALL) {
            TitleRow(title = "导入单人存档" + (state.host?.name?.let { " · ${it}" } ?: ""), onBack = onBack)
            ScrollableContentBody(state = rememberScrollState(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.busy?.let { message ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(message)
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                when (state.step) {
                    SaveImportStep.PickSave, SaveImportStep.SelectPlayer -> {
                        Text("把房间里的存档换成自己的")
                        CircleIconButton(icon = "", label = "选择存档文件夹", enabled = !busy && state.host != null, onClick = viewModel::pickSave)
                        state.source?.let { source ->
                            Text("已选择：${source}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("用哪个角色进入存档？", fontWeight = FontWeight.Bold)
                            if (state.markingPlayers.isEmpty()) {
                                Text("使用我的RDI角色，从世界出生点开始")
                            } else {
                                state.markingPlayers.forEach { role ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = state.selectedPlayer == role.player.uuid, enabled = !busy,
                                            onClick = { viewModel.selectMarkingPlayer(role.player.uuid) })
                                        Column {
                                            Text(role.displayName + if (role.player.isSingleplayerHost && role.displayName != "单机存档主人") "（单机存档主人）" else "")
                                            Text("角色编号：${role.player.uuid.toString().take(8)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            role.position?.let { pos ->
                                                val dimensionName = when (pos.dimension) {
                                                    "minecraft:overworld" -> "主世界"
                                                    "minecraft:the_nether" -> "下界"
                                                    "minecraft:the_end" -> "末地"
                                                    else -> pos.dimension
                                                }
                                                Text("${dimensionName} · ${kotlin.math.floor(pos.x).toInt()}, ${kotlin.math.floor(pos.y).toInt()}, ${kotlin.math.floor(pos.z).toInt()}")
                                            } ?: Text("最后位置未知")
                                            role.player.lastPlayed?.let {
                                                Text("最近保存：${java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(it)}")
                                            }
                                        }
                                    }
                                }
                            }
                            Text("此次选择用于寻找和标记区块，导入时再确认角色对应的RDI账号。")
                            Text(MARKING_NOTICE)
                            if (state.reusableRecord != null) {
                                Text("上次标记角色：${state.reusableRecord!!.selectedPlayerName ?: state.reusableRecord!!.launchUuid.take(8)}。继续标记会使用该角色；更换角色会创建新副本，保留旧副本及标记。")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircleIconButton(icon = "", label = "继续使用上次标记的副本", enabled = !busy) { viewModel.startMarking(reuse = true) }
                                    CircleIconButton(icon = "", label = "用所选角色创建新副本", enabled = !busy) { viewModel.startMarking(reuse = false) }
                                }
                            } else {
                                CircleIconButton(icon = "", label = "用所选角色开始标记", enabled = !busy) { viewModel.startMarking(reuse = false) }
                            }
                        }
                    }

                    SaveImportStep.Marking -> {
                        Text("游戏中正在标记要保留的区块。", fontWeight = FontWeight.Bold)
                        Text(MARKING_NOTICE)
                        CircleIconButton(icon = "", label = "我已退出游戏", enabled = !busy, onClick = viewModel::onGameExited)
                    }

                    SaveImportStep.Summary -> {
                        val summary = state.summary
                        if (summary == null || summary.marked.isEmpty()) {
                            Text("还没有标记任何区块。请至少标记一个要保留的区块。", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("已标记${summary.marked.size}个区块，其中${summary.presentCount}个在存档中已经生成。", fontWeight = FontWeight.Bold)
                            summary.markedByDimension.forEach { (dimension, count) -> Text("${dimension}：${count}个区块") }
                            if (summary.playersOutside.isNotEmpty()) {
                                Text(
                                    "有${summary.playersOutside.size}名玩家最后所在的位置不在保留的区块内，进入房间后可能位于重新生成的地形中。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircleIconButton(icon = "", label = "重新进入游戏调整", enabled = !busy, onClick = viewModel::relaunch)
                            CircleIconButton(
                                icon = "",
                                label = "下一步",
                                enabled = !busy && summary?.marked?.isNotEmpty() == true,
                                onClick = viewModel::continueToMapping,
                            )
                            CircleIconButton(icon = "", label = "清理导入副本", enabled = !busy) { confirmCleanup = true }
                        }
                    }

                    SaveImportStep.Mapping -> {
                        Text("存档中的每名玩家对应到哪个RDI账号？不迁移的玩家会原样保留。", fontWeight = FontWeight.Bold)
                        val duals = state.dualIdentities.associateBy { it.other }
                        state.rows.forEach { row ->
                            SavePlayerMappingCard(
                                row = row,
                                dual = duals[row.player.uuid],
                                dualKeep = state.dualChoices[row.player.uuid],
                                enabled = !busy,
                                onClear = { viewModel.setTarget(row.player.uuid, null) },
                                onAssignSelf = { viewModel.setTarget(row.player.uuid, viewModel.selfTarget()) },
                                onSearchQq = { qqSearchFor = row.player.uuid },
                                onDualKeep = { keep -> viewModel.setDualKeep(row.player.uuid, keep) },
                            )
                        }
                        CircleIconButton(icon = "", label = "下一步", enabled = !busy, onClick = viewModel::review)
                    }

                    SaveImportStep.Confirm -> {
                        Text("确认导入", fontWeight = FontWeight.Bold)
                        state.confirmation?.messages?.forEach { Text("· ${it}") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircleIconButton(icon = "", label = "返回修改", enabled = !busy, onClick = viewModel::backToMapping)
                            CircleIconButton(icon = "", label = "开始导入", enabled = !busy, onClick = viewModel::confirm)
                        }
                    }

                    SaveImportStep.Submitted -> {
                        Text("已开始导入。处理和上传需要几分钟，可以在任务列表查看进度，完成后会收到邮件。")
                        state.runId?.let { runId ->
                            CircleIconButton(icon = "", label = "查看进度", onClick = { onOpenTask(runId) })
                        }
                    }
                }
            }
        }
    }
}
