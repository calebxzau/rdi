package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.server
import calebxzau.rdi.client.ui.AlertErr
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.SearchField
import calebxzau.rdi.client.ui.SimpleTooltip
import calebxzau.rdi.client.ui.Space8h
import calebxzau.rdi.client.ui.RVerticalScrollbar
import calebxzau.rdi.client.ui.ContentBody
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.TitleRow
import calebxzhou.rdi.client.ui.comp.ModpackCard
import calebxzhou.rdi.client.ui.comp.ModpackCardPresentation
import calebxzhou.rdi.client.ui.comp.formatModpackUpdatedTime
import calebxzhou.rdi.client.ui.loadResourceBitmap
import calebxzau.rdi.client.ui.baseRoundCornerShape
import calebxzhou.rdi.common.DEBUG
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.isDav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * calebxzhou @ 2026-05-14 11:29
 */

internal enum class RemoteModpackSource {
    Legacy,
}

internal data class RemoteModpackRef(
    val source: RemoteModpackSource,
    val id: String,
) {
    val key: String get() = "${source.name.lowercase()}:$id"
}

internal data class RemoteModpackPresentation(
    val ref: RemoteModpackRef,
    val card: ModpackCardPresentation,
)

internal fun Modpack.BriefVo.toRemoteModpackPresentation(): RemoteModpackPresentation =
    RemoteModpackPresentation(
        ref = RemoteModpackRef(RemoteModpackSource.Legacy, id.toHexString()),
        card = ModpackCardPresentation(
            name = name,
            iconUrl = icon,
            intro = info,
            activityText = playCount.toString(),
            updatedTimeText = formatModpackUpdatedTime(
                lastUpdatedTime.takeIf { it > 0L } ?: (id.timestamp.toLong() * 1000L),
            ),
        ),
    )

internal fun mergeRemoteModpackPresentations(
    legacy: List<Modpack.BriefVo>,
    keyword: String = "",
    onlyMine: Boolean = false,
    selectedCategory: Modpack.Category? = null,
): List<RemoteModpackPresentation> {
    val normalizedKeyword = keyword.trim().lowercase()
    val legacyItems = legacy
        .asSequence()
        .filter { item ->
                normalizedKeyword.isBlank() ||
                    item.name.lowercase().contains(normalizedKeyword) ||
                    item.info.orEmpty().lowercase().contains(normalizedKeyword)
        }
        .filter { item -> selectedCategory == null || selectedCategory in item.categories }
        .map(Modpack.BriefVo::toRemoteModpackPresentation)
    return legacyItems.toList()
}

@Composable
fun ModpackPlazaScreen(
    onOpenInfo: (String) -> Unit,
    onOpenUpload: (() -> Unit)? = null,
    onOpenBaseWorlds: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    requiredMcVer: McVersion? = null,
    requiredLoader: String? = null,
    onBack: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    var modpacks by remember { mutableStateOf<List<Modpack.BriefVo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var onlyMine by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<Modpack.Category?>(null) }
    var selectedMcVer by rememberSaveable(requiredMcVer, requiredLoader) { mutableStateOf(requiredMcVer) }
    var selectedSort by rememberSaveable { mutableStateOf(Modpack.SearchSort.UPDATED) }
    var hasMore by remember { mutableStateOf(false) }
    var requestKeyword by rememberSaveable { mutableStateOf("") }
    var requestVersion by remember { mutableStateOf(0) }
    var selectedLoader by rememberSaveable(requiredLoader) { mutableStateOf<String?>(requiredLoader) }

    suspend fun loadModpacks(reset: Boolean) {
        val offset = if (reset) 0 else modpacks.size
        if (reset) {
            loading = true
        } else {
            loadingMore = true
        }
        errorMessage = null
        val result = runCatching {
            server.makeRequest<Modpack.SearchResultVo>(
                path = "modpack/search",
                params = buildMap {
                    put("offset", offset)
                    put("limit", 100)
                    if (requestKeyword.isNotBlank()) {
                        put("q", requestKeyword)
                    }
                    if (onlyMine) {
                        put("mine", true)
                    }
                    if (selectedMcVer != null) {
                        put("mcVer", selectedMcVer!!.mcVer)
                    }
                    if (selectedSort != Modpack.SearchSort.RELEVANCE) {
                        put("sort", selectedSort.name)
                    }
                    if (selectedCategory != null) {
                        put("category", selectedCategory!!.name)
                    }
                }
            ).data ?: Modpack.SearchResultVo()
        }
        result.onSuccess { page ->
            hasMore = page.hasMore
            modpacks = if (reset) page.items else modpacks + page.items
        }.onFailure {
            errorMessage = "加载整合包失败: ${it.message}"
            if (reset) {
                modpacks = emptyList()
                hasMore = false
            }
        }
        loading = false
        loadingMore = false
    }

    fun submitSearch() {
        val keyword = searchText.trim()
        if (requestKeyword == keyword) {
            requestVersion += 1
        } else {
            requestKeyword = keyword
        }
    }

    fun clearFilters() {
        val hasAppliedFilters = requestKeyword.isNotBlank() ||
            onlyMine ||
            selectedCategory != null ||
            selectedMcVer != null ||
            selectedSort != Modpack.SearchSort.UPDATED
        searchText = ""
        requestKeyword = ""
        onlyMine = false
        selectedCategory = null
        selectedMcVer = null
        selectedSort = Modpack.SearchSort.UPDATED
        if (!hasAppliedFilters) {
            return
        }
    }

    @Composable
    fun FilterSidebar(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RemoteModpackFilterSection("分类") {
                RemoteModpackCategoryFilter(
                    selected = selectedCategory,
                    onlyMine = onlyMine,
                    onSelectedChange = { selectedCategory = it },
                    onOnlyMineChange = { onlyMine = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            RemoteModpackFilterSection("排序") {
                RemoteModpackFilterGrid(
                    texts = Modpack.SearchSort.entries.map { it.toLabel() },
                    selectedIndex = Modpack.SearchSort.entries.indexOf(selectedSort),
                    onChipClick = { index -> selectedSort = Modpack.SearchSort.entries[index] }
                )
            }
            RemoteModpackFilterSection("MC版本") {
                val versions = listOf<McVersion?>(null) + McVersion.entries.filter { it.enabled }
                RemoteModpackFilterGrid(
                    items = versions.map { version ->
                        RemoteModpackGridChipItem(
                            text = version?.simpleVer ?: "全部",
                            selected = selectedMcVer == version,
                            iconPath = version?.icon,
                            showText = version == null,
                            tooltip = version?.simpleVer,
                            onClick = { selectedMcVer = version }
                        )
                    },
                    columns = 4
                )
            }
            if (
                requestKeyword.isNotBlank() ||
                onlyMine ||
                selectedCategory != null ||
                selectedMcVer != null ||
                selectedSort != Modpack.SearchSort.UPDATED
            ) {
                TextButton(onClick = ::clearFilters) {
                    Text("清空筛选", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    @Composable
    fun ResultList(modifier: Modifier = Modifier) {
        val presentations = remember(
            modpacks,
            requestKeyword,
            onlyMine,
            requiredMcVer,
            requiredLoader,
            selectedMcVer,
            selectedLoader,
            selectedCategory,
        ) {
            mergeRemoteModpackPresentations(
                legacy = modpacks,
                keyword = requestKeyword,
                onlyMine = onlyMine,
                selectedCategory = selectedCategory,
            )
        }
        Column(modifier = modifier.fillMaxSize()) {
            listOfNotNull(errorMessage)
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n")
                ?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Space8h()
            }

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
                Space8h()
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 360.dp),
                    modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    gridItems(presentations, key = { it.ref.key }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            ModpackCard(
                                presentation = item.card,
                                modifier = Modifier.widthIn(max = 360.dp),
                                onClick = {
                                    when (item.ref.source) {
                                        RemoteModpackSource.Legacy -> onOpenInfo(item.ref.id)
                                    }
                                },
                            )
                        }
                    }
                    if (!loading && presentations.isEmpty()) {
                        item(
                            key = "empty",
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            Text(
                                text = "没有找到符合条件的整合包",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (hasMore) {
                        item(
                            key = "load-more",
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (loadingMore) {
                                    CircularProgressIndicator()
                                } else {
                                    TextButton(onClick = {
                                        scope.launch {
                                            loadModpacks(reset = false)
                                        }
                                    }) {
                                        Text("加载更多")
                                    }
                                }
                            }
                        }
                    }
                }
                RVerticalScrollbar(
                    gridState = gridState,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }

    LaunchedEffect(requestKeyword, requestVersion, selectedCategory, selectedMcVer, selectedSort, onlyMine) {
        loadModpacks(reset = true)
    }

    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.LARGE) {
            TitleRow("整合广场", onBack) {
                SearchField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = "包名/简介",
                    onSearch = ::submitSearch,
                    modifier = Modifier.width(240.dp),
                    loading = loading,
                )
                onOpenBaseWorlds?.let { onOpen ->
                    //todo not ready
                    if(loggedAccount.isDav){
                        CircleIconButton(
                            icon = "\uF279",
                            label = "地图模板",
                            onClick = onOpen,
                        )
                    }
                }
            }
            ContentBody {
                Box(modifier = modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ResultList(modifier = Modifier.weight(1f))
                        /*BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        *//*FilterSidebar(
                            modifier = Modifier
                                .width(220.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        )*//*
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {

                        }
                    }

                        }*/
                    }
                    if (onOpenUpload != null && (loggedAccount.hasMsid|| DEBUG)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            CircleIconButton(
                                icon = "\uDB80\uDFD5",
                                label = "传新包",
                                onClick = onOpenUpload,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modpack.SearchSort.toLabel(): String = when (this) {
    Modpack.SearchSort.RELEVANCE -> "相关"
    Modpack.SearchSort.UPDATED -> "最近"
    Modpack.SearchSort.POPULAR -> "最热"
    // Modpack.SearchSort.NAME -> "名称"
}

@Composable
private fun RemoteModpackFilterSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun RemoteModpackCategoryFilter(
    selected: Modpack.Category?,
    onlyMine: Boolean,
    onSelectedChange: (Modpack.Category?) -> Unit,
    onOnlyMineChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val categoryItems = listOf(
        RemoteModpackGridChipItem(
            text = "我的包",
            selected = onlyMine,
            onClick = { onOnlyMineChange(!onlyMine) }
        ),
        RemoteModpackGridChipItem(
            text = "全部",
            selected = selected == null,
            onClick = { onSelectedChange(null) }
        )
    ) + Modpack.Category.entries.map { category ->
        RemoteModpackGridChipItem(
            text = category.label,
            selected = selected == category,
            onClick = {
                onSelectedChange(
                    if (selected == category) null else category
                )
            }
        )
    }
    RemoteModpackFilterGrid(
        items = categoryItems,
        modifier = modifier
    )
}


@Composable
private fun RemoteModpackFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    iconPath: String? = null,
    showText: Boolean = true,
    tooltip: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = if (selected) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    val button: @Composable () -> Unit = {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            shape = baseRoundCornerShape,
            colors = colors,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 1.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                iconPath?.let { path ->
                    Image(
                        bitmap = remember(path) { loadResourceBitmap(path) },
                        contentDescription = text,
                        modifier = Modifier.size(24.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                if (showText || iconPath == null) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
    Box(modifier = modifier) {
        tooltip?.let {
            SimpleTooltip(it) { button() }
        } ?: button()
    }
}

private data class RemoteModpackGridChipItem(
    val text: String,
    val selected: Boolean,
    val iconPath: String? = null,
    val showText: Boolean = true,
    val tooltip: String? = null,
    val onClick: () -> Unit
)

@Composable
private fun RemoteModpackFilterGrid(
    texts: List<String>,
    selectedIndex: Int,
    onChipClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3
) {
    RemoteModpackFilterGrid(
        items = texts.mapIndexed { index, text ->
            RemoteModpackGridChipItem(
                text = text,
                selected = index == selectedIndex,
                onClick = { onChipClick(index) }
            )
        },
        modifier = modifier,
        columns = columns
    )
}

@Composable
private fun RemoteModpackFilterGrid(
    items: List<RemoteModpackGridChipItem>,
    modifier: Modifier = Modifier,
    columns: Int = 3
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { item ->
                    RemoteModpackFilterChip(
                        text = item.text,
                        selected = item.selected,
                        onClick = item.onClick,
                        iconPath = item.iconPath,
                        showText = item.showText,
                        tooltip = item.tooltip,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
