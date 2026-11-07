package calebxzau.rdi.service

import calebxzhou.rdi.client.service.*
import calebxzhou.rdi.common.net.ktorClient
import calebxzau.rdi.client.modcatalog.CatalogNetworkPolicy
import calebxzau.rdi.client.modcatalog.createModCatalog
import calebxzau.rdi.client.ui.viewmodel.LocalModpackInstallViewModel
import calebxzau.rdi.client.ui.viewmodel.ModpackOptionViewModel
import calebxzau.rdi.client.ui.viewmodel.SettingsModpackOptionRuntime
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope

internal interface PackServiceDependencies : AutoCloseable {
    suspend fun loadPacks(): List<ModpackLocalDir>
    fun installer(scope: CoroutineScope): LocalModpackInstallViewModel
    fun options(versionId: String, scope: CoroutineScope): ModpackOptionViewModel
    override fun close() {}
}

internal class RuntimePackServiceDependencies(private val runtime: ServiceRuntime) : PackServiceDependencies {
    private val logger = KotlinLogging.logger {}
    private val catalog = lazy {
        createModCatalog(ktorClient, ClientDirs.toolsDir.resolve("mod-catalog").toPath(),
            networkPolicy = CatalogNetworkPolicy(preferMirrorProvider = { calebxzau.rdi.client.CONF.preferModMirror }),
            onWarning = { logger.warn(it) { "模组目录索引不可用或请求降级" } })
    }
    override suspend fun loadPacks() = ModpackService.getLocalPackDirs()
    override fun installer(scope: CoroutineScope) = LocalModpackInstallViewModel(
        LocalModpackScanner(runtime.database.store, ClientDirs.mcDir.toPath()),
        LocalModpackInstaller(ClientDirs.versionsDir.toPath()),
        ModpackArchiveReader(CatalogModpackArchiveCatalog(catalog.value)),
        ModpackArchiveContentResolver(catalog.value), scope,
    )
    override fun options(versionId: String, scope: CoroutineScope) = ModpackOptionViewModel(
        versionId, runtime.database.modpackLaunchOptionsStore, SettingsModpackOptionRuntime, scope,
    )
    override fun close() { if (catalog.isInitialized()) catalog.value.close() }
}
