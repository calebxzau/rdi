package calebxzau.rdi.questviewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

private sealed interface QuestLoadState {
    data object Loading : QuestLoadState
    data class Failed(val message: String) : QuestLoadState
    data class Ready(val data: QuestPreviewData) : QuestLoadState
}

/** Offline, read-only quest browser. Navigation and the surrounding window belong to the caller. */
@Composable
fun QuestViewer(
    modpackDir: Path,
    modifier: Modifier = Modifier,
    detailsButton: @Composable (expanded: Boolean, onClick: () -> Unit) -> Unit,
) {
    var revision by remember(modpackDir) { mutableIntStateOf(0) }
    val state by produceState<QuestLoadState>(QuestLoadState.Loading, modpackDir, revision) {
        value = QuestLoadState.Loading
        val result = withContext(Dispatchers.IO) { QuestPreviewLoader.load(modpackDir) }
        value = result.fold(
            onSuccess = { QuestLoadState.Ready(it) },
            onFailure = { exception ->
                logger.error(exception) { "读取任务书失败：$modpackDir" }
                QuestLoadState.Failed(exception.message ?: "读取任务书失败")
            },
        )
    }
    Surface(modifier) {
        Column(Modifier.fillMaxSize()) {
            /* 暂时不用刷新按钮 直接重进 Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { revision++ }) { Text("刷新") }
            }*/
            when (val current = state) {
                QuestLoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is QuestLoadState.Failed -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("无法打开任务书", style = MaterialTheme.typography.titleLarge)
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { revision++ }) { Text("重试") }
                }
                is QuestLoadState.Ready -> key(current.data) { QuestBookView(current.data, Modifier.weight(1f), detailsButton) }
            }
        }
    }
}

@Composable
private fun QuestBookView(
    data: QuestPreviewData,
    modifier: Modifier,
    detailsButton: @Composable (expanded: Boolean, onClick: () -> Unit) -> Unit,
) {
    var chapterId by remember { mutableStateOf(data.book.chapters.firstOrNull()?.id) }
    var selectedQuestId by remember { mutableStateOf<String?>(null) }
    var focusQuestId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showDetails by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    val cache = remember(data) { PreviewAtlasCache(data.assets) }
    val chapter = data.chapters[chapterId]
    val graph = remember(data, chapter) { chapter?.let(data::graph) ?: QuestGraph(emptyList(), emptyList()) }
    val results = remember(data, query) {
        if (query.isBlank()) emptyList() else data.quests.values.filter { quest ->
            val content = listOf(
                quest.id, data.questTitle(quest), quest.subtitle.orEmpty(),
                quest.description.joinToString("\n") { data.text(it) },
                quest.tasks.joinToString(" ") { data.taskTitle(it) + " " + it.item?.itemId.orEmpty() },
                quest.rewards.joinToString(" ") { data.rewardTitle(it) + " " + it.item?.itemId.orEmpty() },
            ).joinToString("\n")
            searchableQuestText(content).contains(query.trim(), ignoreCase = true)
        }
    }
    fun navigate(target: PreviewTarget) {
        chapterId = target.chapterId
        selectedQuestId = target.questId
        focusQuestId = target.questId
        showDetails = target.questId != null
    }
    Column(modifier) {
        /*普通玩家不需要知道 if (data.issues.isNotEmpty() || cache.failedPages > 0 || data.book.chapters.any { it.images.isNotEmpty() }) {
            TextButton(onClick = { showIssues = !showIssues }, modifier = Modifier.padding(horizontal = 8.dp)) {
                val imageNotice = if (data.book.chapters.any { it.images.isNotEmpty() }) 1 else 0
                Text(if (showIssues) "收起读取提示" else "读取提示（${data.issues.size + cache.failedPages + imageNotice}）")
            }
            if (showIssues) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(horizontal = 16.dp)) {
                    items(data.issues) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (cache.failedPages > 0) item { Text("部分图集无法读取，已显示占位图。详细原因见日志。") }
                    item { Text("图标使用导出的默认物品外观；NBT或components的特殊外观可能不同。") }
                    if (data.book.chapters.any { it.images.isNotEmpty() }) item { Text("当前素材不含章节装饰图片，预览中暂未显示。") }
                }
            }
        }*/
        if (data.book.chapters.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("任务书中没有可浏览的章节") }
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth >= 980.dp
            val sidebarWidth = if (maxWidth >= 1100.dp) 230.dp else 190.dp
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(sidebarWidth).fillMaxHeight().padding(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索任务、物品或ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (query.isNotBlank()) {
                            item { Text("找到${results.size}个任务", style = MaterialTheme.typography.labelSmall) }
                            items(results, key = { it.id }) { quest ->
                                TextButton(onClick = { data.resolve(quest.id).singleOrNull()?.let(::navigate) }) {
                                    Text(questAnnotatedText(data.questTitle(quest)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        } else {
                            var previousGroup: String? = "__initial__"
                            data.book.chapters.forEach { entry ->
                                if (entry.groupId != previousGroup) {
                                    val group = data.book.chapterGroups.firstOrNull { it.id == entry.groupId }
                                    item(key = "group:${entry.groupId}") {
                                        Text(questAnnotatedText(group?.title?.let(data::text) ?: "章节"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                                    }
                                    previousGroup = entry.groupId
                                }
                                item(key = entry.id) {
                                    Surface(
                                        color = if (chapterId == entry.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            chapterId = entry.id
                                            selectedQuestId = null
                                            focusQuestId = null
                                            showDetails = false
                                        },
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(questAnnotatedText(data.chapterTitle(entry)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text("${entry.quests.size}个任务", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (query.isNotBlank()) TextButton(onClick = { query = "" }) { Text("返回章节列表") }
                }
                VerticalDivider()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(questAnnotatedText(chapter?.let(data::chapterTitle).orEmpty()), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (!wide && showDetails) TextButton(onClick = { showDetails = false }) { Text("返回任务树") }
                    }
                    if (wide || !showDetails) {
                        QuestGraphView(
                            data, graph, cache, selectedQuestId, focusQuestId,
                            onSelect = { id ->
                                val target = data.resolve(id).singleOrNull()
                                if (target != null) {
                                    val crossChapter = target.chapterId != chapterId
                                    selectedQuestId = id
                                    focusQuestId = if (crossChapter) id else null
                                    if (crossChapter) {
                                        chapterId = target.chapterId
                                    }
                                    showDetails = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    } else QuestDetailPane(data, data.quests[selectedQuestId], cache, ::navigate, Modifier.weight(1f), detailsButton)
                }
                if (wide) {
                    VerticalDivider()
                    QuestDetailPane(data, data.quests[selectedQuestId], cache, ::navigate, Modifier.width(310.dp).fillMaxHeight(), detailsButton)
                }
            }
        }
    }
}
