package calebxzau.rdi.questviewer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import calebxzau.rdi.quests.ftb.model.*
import calebxzau.rdi.quests.ftb.snbt.FtbSnbtData
import kotlinx.serialization.json.Json

private val detailJson = Json { prettyPrint = true }

@Composable
internal fun QuestDetailPane(
    data: QuestPreviewData,
    quest: FtbQuest?,
    cache: PreviewAtlasCache,
    onNavigate: (PreviewTarget) -> Unit,
    modifier: Modifier = Modifier,
    detailsButton: @Composable (expanded: Boolean, onClick: () -> Unit) -> Unit,
) {
    val scroll = key(quest?.id) { rememberScrollState() }
    Column(modifier.verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (quest == null) {
            Text("选择一个任务", style = MaterialTheme.typography.titleMedium)
            Text("点击任务节点，查看目标、奖励和前置任务。")
            return@Column
        }
        RawQuestFields(quest.data, detailsButton, header = {
            PreviewItemIcon(data.questIcon(quest), data.assets, cache)
            Text(questAnnotatedText(data.questTitle(quest)), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        })
        quest.subtitle?.takeIf(String::isNotBlank)?.let { QuestText(data.text(it)) }
        val attributes = listOfNotNull(
            "可选任务".takeIf { quest.optional },
            "可重复".takeIf { data.repeatable(quest) },
        )
        if (attributes.isNotEmpty()) Text(attributes.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                quest.description.forEach { line -> QuestDescription(line, data, onNavigate) }
            }
        }
        if (quest.dependencies.isNotEmpty()) {
            HorizontalDivider()
            Text("前置任务", style = MaterialTheme.typography.titleMedium)
            quest.dependencyRequirement?.let { requirement ->
                val label = when (requirement) {
                    "all_completed" -> "全部完成"
                    "one_completed" -> "任意一个完成"
                    "all_started" -> "全部开始"
                    "one_started" -> "任意一个开始"
                    else -> requirement
                }
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
            quest.minRequiredDependencies?.takeIf { it > 0 }?.let { Text("至少需要${it}个前置条件") }
            quest.dependencies.forEach { reference ->
                val targets = data.resolve(reference)
                if (targets.isEmpty()) Text("无法解析前置引用：$reference", color = MaterialTheme.colorScheme.error)
                if (targets.size > 1) Text("标签${reference}对应${targets.size}个对象，无法确定唯一前置条件。")
                targets.forEach { target ->
                    TextButton(onClick = { onNavigate(target) }) {
                        val icon = target.questId?.let(data.quests::get)?.let(data::questIcon)
                            ?: data.chapters[target.chapterId]?.icon?.let { PreviewIcon.Item(it.itemId) }
                        PreviewItemIcon(icon, data.assets, cache)
                        Spacer(Modifier.width(8.dp))
                        Text(questAnnotatedText(target.label))
                    }
                }
            }
        }
        HorizontalDivider()
        Text("任务目标（${quest.tasks.size}）", style = MaterialTheme.typography.titleMedium)
        quest.tasks.forEach { task ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewItemIcon(task.previewIcon(), data.assets, cache)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RawQuestFields(task.data, detailsButton, header = {
                        Text(questAnnotatedText(data.taskTitle(task)), Modifier.weight(1f))
                    }, content = {
                        val count = task.count ?: task.item?.count
                        if (count != null) Text("数量${count}")
                        if (task.optional) Text("可选目标", style = MaterialTheme.typography.labelSmall)
                    })
                }
            }
        }
        HorizontalDivider()
        Text("奖励（${quest.rewards.size}）", style = MaterialTheme.typography.titleMedium)
        quest.rewards.forEach { reward -> RewardPreview(data, reward, cache, emptySet(), 0, detailsButton) }
    }
}

@Composable
private fun RewardPreview(
    data: QuestPreviewData,
    reward: FtbReward,
    cache: PreviewAtlasCache,
    visited: Set<String>,
    depth: Int,
    detailsButton: @Composable (expanded: Boolean, onClick: () -> Unit) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PreviewItemIcon(reward.previewIcon(), data.assets, cache)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val count = reward.count ?: reward.item?.count
            val title = data.rewardTitle(reward)
            RawQuestFields(reward.data, detailsButton, header = {
                Text(questAnnotatedText(if (count != null && count >= 2) "${count}x ${title}" else title), Modifier.weight(1f))
            }, content = {
                reward.data.number("xp")?.let { Text("经验${it.toLong()}") }
            })
        }
    }
    val table = reward.inlineTable ?: data.tables[reward.tableId]
    if (reward.tableId != null && table == null) Text("找不到奖励表：${reward.tableId}", color = MaterialTheme.colorScheme.error)
    if (table != null) {
        var expanded by remember(reward, table) { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }) {
            Text((if (expanded) "收起" else "查看") + "奖励表（${table.entries.size}项）")
        }
        if (expanded) {
            if (depth >= 8 || (table.id != null && table.id in visited)) {
                Text("奖励表存在循环或嵌套过深，停止展开。")
            } else {
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    table.title?.let { QuestText(data.text(it)) }
                    if (table.emptyWeight > 0) Text("空奖励权重${table.emptyWeight}")
                    table.lootSize?.let { Text("抽取数量${it}") }
                    table.entries.forEach { entry ->
                        Text("权重${entry.weight}", style = MaterialTheme.typography.labelSmall)
                        RewardPreview(data, entry.reward, cache, visited + listOfNotNull(table.id), depth + 1, detailsButton)
                    }
                }
            }
        }
    }
}

@Composable
private fun RawQuestFields(
    data: FtbSnbtData,
    detailsButton: @Composable (expanded: Boolean, onClick: () -> Unit) -> Unit,
    header: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit = {},
) {
    var expanded by remember(data) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        header()
        detailsButton(expanded) { expanded = !expanded }
    }
    content()
    if (expanded) {
        val raw = remember(data) { detailJson.encodeToString(FtbSnbtData.serializer(), data) }
        SelectionContainer { Text(raw, style = MaterialTheme.typography.bodySmall) }
    }
}
