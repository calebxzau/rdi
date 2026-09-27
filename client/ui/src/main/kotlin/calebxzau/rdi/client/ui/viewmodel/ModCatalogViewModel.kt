package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzau.rdi.common.logging.Loggers
import calebxzau.rdi.client.modcatalog.CatalogMod
import calebxzau.rdi.client.modcatalog.CatalogSearchCursor
import calebxzau.rdi.client.modcatalog.CatalogSearchRequest
import calebxzau.rdi.client.modcatalog.CatalogSort
import calebxzau.rdi.client.modcatalog.CatalogTarget
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzhou.rdi.client.ui.screen.ModCatalogRoute
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.supportsRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val lgr by Loggers

data class ModCatalogUiState(
    val searchText: String = "",
    val query: String = "",
    val mods: List<CatalogMod> = emptyList(),
    val nextCursor: CatalogSearchCursor? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val errorMessage: String? = null,
)

internal fun ModCatalogRoute.toCatalogTarget(): CatalogTarget {
    val minecraftVersion = requireNotNull(McVersion.from(requiredMcVer)) {
        "Unknown Minecraft version: ${requiredMcVer}"
    }
    val loader = requireNotNull(ModLoader.from(requiredLoader)) {
        "Unknown mod loader: ${requiredLoader}"
    }
    require(minecraftVersion.supportsRuntime(loader)) {
        "${minecraftVersion.mcVer} does not support ${loader.name}"
    }
    return CatalogTarget(minecraftVersion, loader)
}

internal fun ModCatalogUiState.toSearchRequest(target: CatalogTarget, cursor: CatalogSearchCursor?) =
    CatalogSearchRequest(
        query = query,
        target = target,
        sort = if (query.isBlank()) CatalogSort.DOWNLOADS else CatalogSort.RELEVANCE,
        cursor = cursor,
    )

class ModCatalogViewModel(
    private val route: ModCatalogRoute,
    private val catalog: ModCatalog,
) : ViewModel() {
    private val target = route.toCatalogTarget()

    private val _uiState = MutableStateFlow(ModCatalogUiState())
    val uiState: StateFlow<ModCatalogUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        load(reset = true)
    }

    fun setSearchText(value: String) {
        _uiState.update { it.copy(searchText = value) }
    }

    fun submitSearch() {
        val query = _uiState.value.searchText.trim()
        _uiState.update { it.copy(query = query) }
        load(reset = true)
    }

    fun loadMore() {
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        val state = _uiState.value
        val cursor = if (reset) null else state.nextCursor ?: return
        if (!reset && (state.loadingMore || searchJob?.isActive == true)) return
        if (reset) searchJob?.cancel()

        val request = state.toSearchRequest(target, cursor)
        _uiState.update {
            it.copy(
                loading = reset,
                loadingMore = !reset,
                nextCursor = if (reset) null else it.nextCursor,
                errorMessage = null,
            )
        }

        searchJob = viewModelScope.launch {
            try {
                val outcome = catalog.search(request).getOrThrow()
                _uiState.update {
                    it.copy(
                        mods = if (reset) outcome.value.items else it.mods + outcome.value.items,
                        nextCursor = outcome.value.nextCursor,
                        errorMessage = if (outcome.issues.isNotEmpty()) {
                            "部分目录信息暂时不可用，已显示可用结果"
                        } else {
                            it.errorMessage
                        },
                    )
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                lgr.warn(cause) { "加载模组目录失败" }
                _uiState.update {
                    it.copy(
                        mods = if (reset) emptyList() else it.mods,
                        nextCursor = if (reset) null else it.nextCursor,
                        errorMessage = "加载模组失败，请稍后重试",
                    )
                }
            } finally {
                if (currentCoroutineContext().isActive) {
                    _uiState.update { it.copy(loading = false, loadingMore = false) }
                }
            }
        }
    }
}
