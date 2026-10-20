package calebxzau.rdi.client.di

import calebxzhou.rdi.client.database.ModpackLaunchOptionsStore
import calebxzhou.rdi.client.database.MinecraftInstallationStore
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzhou.rdi.client.service.CatalogModpackArchiveCatalog
import calebxzhou.rdi.client.service.ModpackArchiveReader
import calebxzhou.rdi.client.service.ModpackArchiveContentResolutionSource
import calebxzhou.rdi.client.service.ModpackArchiveContentResolver
import calebxzhou.rdi.client.service.ClientDirs
import calebxzhou.rdi.client.service.LocalModpackInstaller
import calebxzhou.rdi.client.service.LocalModpackScanner
import calebxzau.rdi.client.ui.viewmodel.LocalModpackInstallViewModel
import calebxzau.rdi.client.ui.viewmodel.ModpackOptionViewModel
import calebxzau.rdi.client.ui.viewmodel.ModpackOptionRuntime
import calebxzau.rdi.client.ui.viewmodel.HostCreateGateway
import calebxzau.rdi.client.ui.viewmodel.HostCreateViewModel
import calebxzau.rdi.client.ui.viewmodel.HostGateway
import calebxzau.rdi.client.ui.viewmodel.HostInfoViewModel
import calebxzau.rdi.client.ui.viewmodel.HostModsViewModel
import calebxzau.rdi.client.ui.viewmodel.ModpackInfoGateway
import calebxzau.rdi.client.ui.viewmodel.ModpackInfoViewModel
import calebxzau.rdi.client.ui.viewmodel.SettingsModpackOptionRuntime
import calebxzau.rdi.client.ui.viewmodel.ModpackVersionInfoViewModel
import calebxzau.rdi.client.ui.viewmodel.ModpackVersionBaseWorldManageViewModel
import calebxzau.rdi.client.ui.viewmodel.RdiModpackInfoGateway
import calebxzau.rdi.client.ui.viewmodel.ModpackUploaderManageGateway
import calebxzau.rdi.client.ui.viewmodel.ModpackUploaderManageViewModel
import calebxzau.rdi.client.ui.viewmodel.RdiModpackUploaderManageGateway
import calebxzau.rdi.client.ui.viewmodel.RdiHostCreateGateway
import calebxzau.rdi.client.ui.viewmodel.RdiHostGateway
import calebxzau.rdi.client.ui.viewmodel.RdiSettingsGateway
import calebxzau.rdi.client.ui.viewmodel.SettingsGateway
import calebxzau.rdi.client.ui.viewmodel.SettingsViewModel
import calebxzau.rdi.client.ui.viewmodel.HostListGateway
import calebxzau.rdi.client.ui.viewmodel.HostListViewModel
import calebxzau.rdi.client.ui.viewmodel.RdiHostListGateway
import calebxzau.rdi.client.ui.viewmodel.OnlinePlayersGateway
import calebxzau.rdi.client.ui.viewmodel.OnlinePlayersViewModel
import calebxzau.rdi.client.ui.viewmodel.RdiOnlinePlayersGateway
import calebxzau.rdi.client.ui.viewmodel.ModCatalogViewModel
import calebxzau.rdi.client.ui.viewmodel.ModCatalogInfoViewModel
import calebxzau.rdi.client.ui.viewmodel.ModpackUploadGateway
import calebxzau.rdi.client.ui.viewmodel.ModpackUploadViewModel
import calebxzau.rdi.client.ui.viewmodel.RdiModpackUploadGateway
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun appModule(
    modCatalog: ModCatalog,
    modpackLaunchOptionsStore: ModpackLaunchOptionsStore,
    minecraftInstallationStore: MinecraftInstallationStore,
) = module {
    single<ModCatalog> { modCatalog }
    single<ModpackLaunchOptionsStore> { modpackLaunchOptionsStore }
    single<MinecraftInstallationStore> { minecraftInstallationStore }
    factory<ModpackUploadGateway> { RdiModpackUploadGateway(get()) }
    single { ModpackArchiveReader(CatalogModpackArchiveCatalog(modCatalog)) }
    single<ModpackArchiveContentResolutionSource> { ModpackArchiveContentResolver(modCatalog) }
    single { LocalModpackScanner(get(), ClientDirs.mcDir.toPath()) }
    single { LocalModpackInstaller(ClientDirs.versionsDir.toPath()) }
    viewModel { LocalModpackInstallViewModel(get(), get(), get(), get()) }
    single<ModpackOptionRuntime> { SettingsModpackOptionRuntime }
    single<HostCreateGateway> { RdiHostCreateGateway() }
    single<HostGateway> { RdiHostGateway() }
    single<HostListGateway> { RdiHostListGateway() }
    single<OnlinePlayersGateway> { RdiOnlinePlayersGateway() }
    single<SettingsGateway> { RdiSettingsGateway() }
    single<ModpackInfoGateway> { RdiModpackInfoGateway() }
    single<ModpackUploaderManageGateway> { RdiModpackUploaderManageGateway() }
    viewModel { parameters ->
        HostCreateViewModel(
            args = parameters.get(),
            gateway = get(),
        )
    }
    viewModel { parameters ->
        HostInfoViewModel(
            hostId = parameters.get(),
            gateway = get(),
        )
    }
    viewModel { parameters ->
        HostModsViewModel(
            hostId = parameters.get(),
            gateway = get(),
        )
    }
    viewModel { SettingsViewModel(gateway = get()) }
    viewModel { HostListViewModel(gateway = get(), useMockData = calebxzhou.rdi.client.Const.USE_MOCK_DATA) }
    viewModel { OnlinePlayersViewModel(gateway = get()) }
    viewModel { parameters ->
        ModpackOptionViewModel(
            versionId = parameters.get(),
            store = get(),
            runtime = get(),
        )
    }
    viewModel { parameters ->
        ModpackInfoViewModel(
            modpackId = parameters.get(),
            gateway = get(),
        )
    }
    viewModel { parameters ->
        ModpackUploaderManageViewModel(
            modpackId = parameters.get(),
            gateway = get(),
        )
    }
    viewModel { parameters ->
        ModpackVersionInfoViewModel(
            modCatalog = get(),
            gateway = get(),
            modpackId = parameters.get(),
            verName = parameters.get(),
        )
    }
    viewModel { parameters ->
        ModpackVersionBaseWorldManageViewModel(
            gateway = get(),
            modpackId = parameters.get(),
            verName = parameters.get(),
        )
    }
    viewModel { parameters ->
        ModCatalogViewModel(
            route = parameters.get(),
            catalog = get(),
        )
    }
    viewModel { parameters ->
        ModCatalogInfoViewModel(
            route = parameters.get(),
            catalog = get(),
        )
    }
    viewModel { parameters ->
        ModpackUploadViewModel(
            gateway = get(),
            modpackId = parameters.getOrNull<String>(),
        )
    }
}
