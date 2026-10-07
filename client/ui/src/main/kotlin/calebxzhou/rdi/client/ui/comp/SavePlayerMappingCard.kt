package calebxzau.rdi.client.ui.comp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import calebxzau.rdi.anvilrw.remap.DualIdentity
import calebxzau.rdi.anvilrw.remap.DualIdentityKeep
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.viewmodel.MappingTargetSource
import calebxzau.rdi.client.ui.viewmodel.SavePlayerRow
import calebxzhou.rdi.client.ui.comp.HeadButton
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LAST_PLAYED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** Formats game ticks as hours, e.g. `12.5小时`. */
fun playTimeText(ticks: Long?): String? = ticks?.let { "%.1f小时".format(it / 72_000.0) }

/** One player of the imported save and the RDI account they become (plan §7). */
@Composable
fun SavePlayerMappingCard(
    row: SavePlayerRow,
    dual: DualIdentity?,
    dualKeep: DualIdentityKeep?,
    enabled: Boolean,
    onClear: () -> Unit,
    onAssignSelf: () -> Unit,
    onSearchQq: () -> Unit,
    onDualKeep: (DualIdentityKeep) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.displayName, fontWeight = FontWeight.Bold)
                AssistChip(onClick = {}, label = { Text(row.badge) })
                if (row.player.isSingleplayerHost) AssistChip(onClick = {}, label = { Text("房主") })
            }
            val details = listOfNotNull(
                row.player.lastPlayed?.let { "最后游玩${LAST_PLAYED_FORMAT.format(it)}" },
                playTimeText(row.player.playTimeTicks)?.let { "游戏时长${it}" },
            )
            if (details.isNotEmpty()) Text(details.joinToString("，"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                row.readOnly && row.accountMissing -> Text("账号已不存在，将保持原样", color = MaterialTheme.colorScheme.error)
                row.readOnly -> Text("已是RDI玩家，将保持原样并加入房间", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val target = row.target
                        if (target == null) {
                            Text("不迁移", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("对应到")
                            HeadButton(target.accountId)
                            if (target.source == MappingTargetSource.MsidMatch) {
                                Text("已绑定此正版账号", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircleIconButton(icon = "", label = "不迁移", enabled = enabled && row.target != null, onClick = onClear)
                        CircleIconButton(icon = "", label = "对应到我", enabled = enabled, onClick = onAssignSelf)
                        CircleIconButton(icon = "", label = "按QQ号查找", enabled = enabled, onClick = onSearchQq)
                    }
                }
            }
            if (dual != null && dualKeep != null) {
                Text("此玩家在存档中有两份数据，进入房间后使用哪一份的背包、位置和进度？", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = dualKeep == DualIdentityKeep.Rdi,
                        onClick = { onDualKeep(DualIdentityKeep.Rdi) },
                        enabled = enabled,
                        label = { Text("RDI账号的数据" + (playTimeText(dual.rdiPlayTimeTicks)?.let { "（${it}）" } ?: "")) },
                    )
                    FilterChip(
                        selected = dualKeep == DualIdentityKeep.Other,
                        onClick = { onDualKeep(DualIdentityKeep.Other) },
                        enabled = enabled,
                        label = { Text("这份数据" + (playTimeText(dual.otherPlayTimeTicks)?.let { "（${it}）" } ?: "")) },
                    )
                }
                Text("未选择的那份会作为另一名玩家留在存档中，它名下的领地、机器等归属不会合并。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
