package calebxzhou.rdi.client.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import calebxzau.rdi.client.blessingskin.BlessingSkinClient
import calebxzau.rdi.client.modcatalog.CatalogMod
import calebxzau.rdi.client.ui.screen.PlayerInfoScreen
import calebxzau.rdi.client.ui.screen.BaseWorldListScreen
import calebxzau.rdi.client.ui.screen.BaseWorldUploadScreen
import calebxzau.rdi.client.ui.screen.HostSaveImportScreen
import calebxzhou.rdi.client.auth.AccountSessionStore
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.ui.screen.ModpackVersionInfoScreen
import calebxzau.rdi.client.ui.screen.ModpackVersionBaseWorldManageScreen
import calebxzhou.rdi.client.ui.screen.*
import calebxzhou.rdi.common.model.McVersion
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private const val SCREEN_FADE_DURATION_MS = 500
private const val BLESSING_SKIN_BASE_URL = "https://littleskin.cn"

private fun ModCatalogRoute.toInfoRoute(mod: CatalogMod): ModCatalogInfoRoute =
    ModCatalogInfoRoute(
        platform = mod.primaryRef.platform.name,
        projectId = mod.primaryRef.projectId,
        requiredMcVer = requiredMcVer,
        requiredLoader = requiredLoader,
        targetLocalVersionId = targetLocalVersionId,
        targetLocalKind = targetLocalKind,
        targetLocalId = targetLocalId,
        targetHostId = targetHostId,
        fromAllHosts = fromAllHosts,
        fromHostMods = fromHostMods,
    )

private fun ModCatalogInfoRoute.toBrowseRouteOrNull(clearLocalTarget: Boolean = false): ModCatalogRoute? {
    val mcVersion = requiredMcVer ?: return null
    val loader = requiredLoader ?: return null
    return ModCatalogRoute(
        requiredMcVer = mcVersion,
        requiredLoader = loader,
        targetLocalVersionId = targetLocalVersionId.takeUnless { clearLocalTarget },
        targetLocalKind = targetLocalKind.takeUnless { clearLocalTarget },
        targetLocalId = targetLocalId.takeUnless { clearLocalTarget },
        targetHostId = targetHostId,
        fromAllHosts = fromAllHosts,
        fromHostMods = fromHostMods,
    )
}

private fun NavHostController.returnFromModCatalogInfo(route: ModCatalogInfoRoute, clearLocalTarget: Boolean) {
    route.toBrowseRouteOrNull(clearLocalTarget)?.let(::navigateAbsolute) ?: when {
        !clearLocalTarget && route.localCatalogTarget() != null -> navigateAbsolute(ModpackLocalListRoute)
        else -> navigateAbsolute(MenuRoute)
    }
}

private fun NavHostController.returnFromModCatalog(route: ModCatalogRoute) {
    if (popBackStack()) return
    val hostId = route.targetHostId
    when {
        hostId != null -> {
            if (route.fromHostMods) {
                navigateAbsolute(HostModsRoute(hostId, route.fromAllHosts, HostKind.Legacy.name))
            } else {
                navigateAbsolute(HostInfoRoute(hostId, route.fromAllHosts, HostKind.Legacy.name))
            }
        }
        route.localCatalogTarget() != null -> navigateAbsolute(ModpackLocalListRoute)
        else -> navigateAbsolute(MenuRoute)
    }
}

private fun NavHostController.returnFromModpackPlaza(route: ModpackPlazaRoute) {
    if (popBackStack()) return
    when {
        route.fromHostId != null -> {
            if (route.fromHostMods) {
                navigateAbsolute(HostModsRoute(route.fromHostId, route.fromAllHosts, HostKind.Legacy.name))
            } else {
                navigateAbsolute(HostInfoRoute(route.fromHostId, route.fromAllHosts, HostKind.Legacy.name))
            }
        }
        else -> navigateAbsolute(ModpackLocalListRoute)
    }
}

inline fun <reified T : Any> NavHostController.navigateAbsolute(route: T) {
    navigate(route) {
        popUpTo<T> { inclusive = true }
        launchSingleTop = true
        restoreState = false
    }
}

fun NavHostController.navigateRoot(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
        restoreState = false
    }
}

/** Main application navigation graph. */
@Composable
fun AppNavigation(
    navController: NavHostController,
    modCatalog: ModCatalog,
    onOpenTask: (String) -> Unit,
    startDestination: Any = LoginRoute,
) {
    val blessingSkin = remember { BlessingSkinClient(BLESSING_SKIN_BASE_URL) }
    val openMcPlay: (McPlayArgs, (() -> Unit)?) -> Unit = { args, onBack ->
        McPlayStore.pendingLaunch = args
        McPlayStore.onBack = onBack
        navController.navigate(McPlayRoute)
    }
    val returnToHostList: () -> Unit = {
        if (!navController.popBackStack()) {
            navController.navigateAbsolute(HostListRoute)
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { fadeIn(animationSpec = tween(SCREEN_FADE_DURATION_MS)) },
        exitTransition = { fadeOut(animationSpec = tween(SCREEN_FADE_DURATION_MS)) },
        popEnterTransition = { fadeIn(animationSpec = tween(SCREEN_FADE_DURATION_MS)) },
        popExitTransition = { fadeOut(animationSpec = tween(SCREEN_FADE_DURATION_MS)) }
    ) {
        composable<LoginRoute> {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(MenuRoute) {
                        popUpTo(LoginRoute) { inclusive = true }
                    }
                },
                onOpenRegister = { msa -> navController.navigate(RegisterRoute(msa)) },
                onOpenResetPassword = { navController.navigate(ResetPasswordRoute) }
            )
        }
        composable<MenuRoute> {
            MenuScreen(
                onOpenModpacks = { navController.navigate(ModpackLocalListRoute) },
                onOpenHostLobby = { navController.navigate(HostListRoute) },
                onOpenMcPlay = { args ->
                    openMcPlay(args) { navController.navigateAbsolute(MenuRoute) }
                },
                onOpenTaskList = { runId ->
                    onOpenTask(runId)
                }
            )
        }
        composable<BaseWorldListRoute> {
            BaseWorldListScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigateAbsolute(ModpackPlazaRoute())
                    }
                },
                onOpenUpload = { navController.navigate(BaseWorldUploadRoute) },
            )
        }
        composable<BaseWorldUploadRoute> {
            BaseWorldUploadScreen(
                onBack = {
                    if (!navController.popBackStack<BaseWorldListRoute>(inclusive = false)) {
                        navController.navigateAbsolute(BaseWorldListRoute)
                    }
                },
                onUploadSubmitted = { runId ->
                    onOpenTask(runId)
                    if (!navController.popBackStack<BaseWorldListRoute>(inclusive = false)) {
                        navController.navigateAbsolute(BaseWorldListRoute)
                    }
                },
            )
        }
        composable<RegisterRoute> {
            val route = it.toRoute<RegisterRoute>()
            RegisterScreen(
                route.msa,
                onBack = { navController.navigateAbsolute(LoginRoute) },
                onRegisterSuccess = {
                    navController.navigateAbsolute(LoginRoute)
                }
            )
        }
        composable<ResetPasswordRoute> {
            ResetPasswordScreen(
                onBack = { navController.navigateAbsolute(LoginRoute) },
                onResetSuccess = { navController.navigateAbsolute(LoginRoute) }
            )
        }
        composable<WardrobeRoute> {
            WardrobeScreen(
                blessingSkin = blessingSkin,
                onBack = { navController.navigateAbsolute(MenuRoute) },
                onOpenSkinPreview = { textureId ->
                    navController.navigate(SkinPreviewRoute(textureId))
                }
            )
        }
        composable<SkinPreviewRoute> {
            val route = it.toRoute<SkinPreviewRoute>()
            SkinPreviewScreen(
                blessingSkin = blessingSkin,
                textureId = route.textureId,
                onBack = { navController.navigateAbsolute(WardrobeRoute) }
            )
        }
        composable<MailInfoRoute> {
            val route = it.toRoute<MailInfoRoute>()
            MailInfoScreen(
                mailId = route.mailId,
                onBack = { navController.navigateAbsolute(MailListScreen) }
            )
        }
        composable<MailListScreen> {
            MailListScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigateRoot(if (AccountSessionStore.isLoggedIn) MenuRoute else LoginRoute)
                    }
                }
            )
        }
        composable<HostListRoute> {
            HostListScreen(
                onBack = { navController.navigateAbsolute(MenuRoute) },
                onOpenModpackPlaza = { navController.navigate(ModpackPlazaRoute()) },
                onOpenWorlds = { navController.navigate(WorldRoute) },
                onOpenHostInfo = { target, fromAllHosts ->
                    navController.navigate(HostInfoRoute(target.id, fromAllHosts, target.kind.name))
                },
                onOpenHostMembers = { target, fromAllHosts ->
                    navController.navigate(HostMembersRoute(target.id, fromAllHosts, target.kind.name))
                },
                onOpenHostMods = { target, fromAllHosts ->
                    navController.navigate(HostModsRoute(target.id, fromAllHosts, target.kind.name))
                },
                onOpenHostWorld = { target, fromAllHosts ->
                    navController.navigate(HostWorldRoute(target.id, fromAllHosts, target.kind.name))
                },
                onOpenHostFiles = { target, fromAllHosts ->
                    navController.navigate(HostFilesRoute(target.id, fromAllHosts, target.kind.name))
                },
                onOpenHostBackend = { target, fromAllHosts ->
                    navController.navigate(HostBackendRoute(target.id, fromAllHosts, target.kind.name))
                },
                onOpenHostSettings = { target, fromAllHosts ->
                    navController.navigate(HostCreateRoute(target.id, fromAllHosts, target.kind.name))
                },
                onOpenMcPlay = { args, _ ->
                    openMcPlay(args) { navController.navigateAbsolute(HostListRoute) }
                },
                /*
                onOpenBirdView = { worldId ->
                    WorldBirdViewStore.current = WorldBirdViewSourceSpec.Remote(worldId)
                    navController.navigate(WorldBirdView(worldId))
                },
                onOpenLocalBirdView = {
                    scope.launch {
                        val rootPath = pickLocalMinecraftWorldDir() ?: return@launch
                        WorldBirdViewStore.current = WorldBirdViewSourceSpec.Local(rootPath)
                        navController.navigate(WorldBirdView(LOCAL_WORLD_BIRD_VIEW_ROUTE_ID))
                    }
                },
                */
                onOpenTaskList = { runId ->
                    onOpenTask(runId)
                }
            )
        }
        composable<WorldRoute> {
            WorldListScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigateAbsolute(HostListRoute)
                    }
                }
            )
        }
        composable<HostInfoRoute> {
            val route = it.toRoute<HostInfoRoute>()
            val kind = HostKind.fromRouteValue(route.kind)
            HostInfoScreen(
                target = HostTarget(kind, route.hostId),
                onBack = {
                    navController.navigateAbsolute(HostListRoute)
                },
                onOpenModpackInfo = { modpackId ->
                    navController.navigate(
                        ModpackInfoRoute(
                            modpackId = modpackId,
                            fromHostId = route.hostId,
                            fromAllHosts = route.fromAllHosts
                        )
                    )
                },
                onOpenPlay = { args ->
                    openMcPlay(args) { navController.navigateAbsolute(HostListRoute) }
                },
            )
        }
        composable<HostMembersRoute> {
            val route = it.toRoute<HostMembersRoute>()
            val kind = HostKind.fromRouteValue(route.kind)
            HostMembersScreen(
                target = HostTarget(kind, route.hostId),
                onBack = returnToHostList,
                onQuit = returnToHostList
            )
        }
        composable<HostModsRoute> {
            val route = it.toRoute<HostModsRoute>()
            val kind = HostKind.fromRouteValue(route.kind)
            HostModsScreen(
                modCatalog = modCatalog,
                target = HostTarget(kind, route.hostId),
                onBack = returnToHostList,
                onOpenResourceMods = { mcVersion, modLoader ->
                    navController.navigate(
                        ModCatalogRoute(
                            requiredMcVer = mcVersion.mcVer,
                            requiredLoader = modLoader.name,
                            targetHostId = route.hostId.takeIf { kind == HostKind.Legacy },
                            fromAllHosts = route.fromAllHosts,
                            fromHostMods = true
                        )
                    )
                },
                onOpenTaskList = { runId ->
                    onOpenTask(runId)
                }
            )
        }
        composable<HostWorldRoute> {
            val route = it.toRoute<HostWorldRoute>()
            HostWorldScreen(
                onBack = returnToHostList,
                onImportSave = { navController.navigate(HostSaveImportRoute(route.hostId)) },
            )
        }
        composable<HostSaveImportRoute> {
            val route = it.toRoute<HostSaveImportRoute>()
            HostSaveImportScreen(
                hostId = route.hostId,
                onBack = { navController.popBackStack() },
                onOpenMcPlay = { args -> openMcPlay(args) { navController.popBackStack() } },
                onOpenTask = onOpenTask,
            )
        }
        composable<HostFilesRoute> {
            val route = it.toRoute<HostFilesRoute>()
            val kind = HostKind.fromRouteValue(route.kind)
            HostFilesScreen(
                target = HostTarget(kind, route.hostId),
                onBack = {
                    navController.navigateAbsolute(HostListRoute)
                },
                onOpenTaskList = { runId ->
                    onOpenTask(runId)
                }
            )
        }
        composable<HostBackendRoute> {
            val route = it.toRoute<HostBackendRoute>()
            val kind = HostKind.fromRouteValue(route.kind)
            HostBackendScreen(
                target = HostTarget(kind, route.hostId),
                onBack = returnToHostList
            )
        }
        /*composable<WorldBirdView> {
            val route = it.toRoute<WorldBirdView>()
            val sourceSpec = when (route.worldId) {
                LOCAL_WORLD_BIRD_VIEW_ROUTE_ID -> {
                    (WorldBirdViewStore.current as? WorldBirdViewSourceSpec.Local)
                        ?: WorldBirdViewSourceSpec.Remote(route.worldId)
                }
                else -> WorldBirdViewSourceSpec.Remote(route.worldId)
            }
            WorldBirdViewScreen(
                sourceSpec = sourceSpec,
                onBack = { navController.navigateAbsolute(WorldRoute) }
            )
        }*/
        composable<HostCreateRoute> {
            val route = it.toRoute<HostCreateRoute>()
            val kind = HostKind.fromRouteValue(route.kind)
            val onBack = {
                if (route.hostId == null) {
                    if (!navController.popBackStack()) {
                        navController.navigateAbsolute(ModpackPlazaRoute())
                    }
                } else {
                    navController.navigateAbsolute(HostListRoute)
                }
            }
            if (kind == HostKind.Host2) {
                HostDetailRouteError("该房间类型暂不可用", onBack)
            } else if (route.hostId == null) {
                HostNewCreateScreen(
                    route,
                    onBack = onBack,
                )
            } else {
                HostNewCreateScreen(route, onBack = onBack)
            }
        }
        composable<McPlayRoute> {
            val launchArgs = androidx.compose.runtime.remember {
                if (McPlayStore.openConsoleOnly) {
                    McPlayStore.openConsoleOnly = false
                    null
                } else {
                    McPlayStore.pendingLaunch.also { McPlayStore.pendingLaunch = null }
                }
            }
            McPlayScreen(
                launchArgs = launchArgs,
                autoStart = launchArgs != null,
                onBack = {
                    val callback = McPlayStore.onBack
                    McPlayStore.onBack = null
                    if (callback != null) {
                        callback()
                    } else {
                        navController.navigate(HostListRoute)
                    }
                }
            )
        }
        composable<SettingRoute> {
            SettingScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigateRoot(if (AccountSessionStore.isLoggedIn) MenuRoute else LoginRoute)
                    }
                },
            )
        }
        composable<ModpackLocalAdvanceOptionsRoute> {
            val route = it.toRoute<ModpackLocalAdvanceOptionsRoute>()
            ModpackLocalAdvanceOptionScreen(
                versionId = route.versionId,
                modpackName = route.modpackName,
                versionName = route.versionName,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigateAbsolute(ModpackLocalListRoute)
                    }
                }
            )
        }
        composable<PlayerInfoRoute> { entry ->
            val route = entry.toRoute<PlayerInfoRoute>()
            PlayerInfoScreen(
                playerId = route.playerId,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigateRoot(if (AccountSessionStore.isLoggedIn) MenuRoute else LoginRoute)
                    }
                }
            )
        }
        composable<ModpackLocalListRoute> {
            ModpackLocalListScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigateAbsolute(MenuRoute)
                    }
                },
                onOpenPlaza = { navController.navigate(ModpackPlazaRoute()) },
                onOpenContent = { pack, contentType ->
                    when (contentType) {
                        ModpackContentType.Mods -> navController.navigate(
                            ModCatalogRoute(
                                requiredMcVer = pack.mcVersion.mcVer,
                                requiredLoader = pack.modLoader.name,
                                targetLocalVersionId = pack.versionId,
                            )
                        )

                        ModpackContentType.ResourcePacks,
                        ModpackContentType.Shaders -> navController.navigate(
                            ModpackContentRoute(
                                type = contentType.name,
                                requiredMcVer = pack.mcVersion.mcVer,
                                requiredLoader = pack.modLoader.name,
                                targetLocalVersionId = pack.versionId,
                            )
                        )
                    }
                },
                onOpenOptions = { pack ->
                    navController.navigate(
                        ModpackLocalAdvanceOptionsRoute(
                            versionId = pack.versionId,
                            modpackName = pack.name,
                            versionName = pack.verName,
                        )
                    )
                },
                onOpenPlay = { args ->
                    openMcPlay(args) { navController.navigateAbsolute(ModpackLocalListRoute) }
                },
                onOpenTask = onOpenTask,
            )
        }
        /* Friends navigation is archived with the friend system. */
        composable<ModpackPlazaRoute> {
            val route = it.toRoute<ModpackPlazaRoute>()
            ModpackPlazaScreen(
                onOpenInfo = { modpackId ->
                    navController.navigate(
                        ModpackInfoRoute(
                            modpackId = modpackId,
                        )
                    )
                },
                onBack = { navController.returnFromModpackPlaza(route) },
                onOpenUpload = { navController.navigate(ModpackUploadRoute(modpackId = null)) },
                onOpenBaseWorlds = { navController.navigate(BaseWorldListRoute) },
                requiredMcVer = route.requiredMcVer?.let(McVersion::from),
                requiredLoader = route.requiredLoader,
            )
        }
        composable<ModpackContentRoute> {
            val route = it.toRoute<ModpackContentRoute>()
            val contentType = ModpackContentType.valueOf(route.type)
            val onBack = {
                if (!navController.popBackStack()) {
                    navController.navigateAbsolute(ModpackLocalListRoute)
                }
            }
            when (contentType) {
                ModpackContentType.Mods -> ModCatalogScreen(
                    route = ModCatalogRoute(
                        requiredMcVer = route.requiredMcVer,
                        requiredLoader = route.requiredLoader,
                        targetLocalVersionId = route.targetLocalVersionId,
                        targetLocalKind = route.targetLocalKind,
                        targetLocalId = route.targetLocalId,
                    ),
                    onBack = onBack,
                    onOpenMod = { mod ->
                        navController.navigate(
                            ModCatalogInfoRoute(
                                platform = mod.primaryRef.platform.name,
                                projectId = mod.primaryRef.projectId,
                                requiredMcVer = route.requiredMcVer,
                                requiredLoader = route.requiredLoader,
                                targetLocalVersionId = route.targetLocalVersionId,
                                targetLocalKind = route.targetLocalKind,
                                targetLocalId = route.targetLocalId,
                            )
                        )
                    },
                )

                ModpackContentType.ResourcePacks -> ResourcepackListScreen(
                    requiredMcVer = route.requiredMcVer?.let(McVersion::from),
                    onBack = onBack,
                    onOpenResourcepack = { project ->
                        navController.navigate(
                            ResourceInfoRoute(
                                type = ResourceInfoType.ResourcePack.name,
                                projectId = project.projectId,
                                targetLocalVersionId = route.targetLocalVersionId,
                                targetLocalKind = route.targetLocalKind,
                                targetLocalId = route.targetLocalId,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                ModpackContentType.Shaders -> ShaderListScreen(
                    requiredMcVer = route.requiredMcVer?.let(McVersion::from),
                    onBack = onBack,
                    onOpenShader = { project ->
                        navController.navigate(
                            ResourceInfoRoute(
                                type = ResourceInfoType.Shader.name,
                                projectId = project.projectId,
                                targetLocalVersionId = route.targetLocalVersionId,
                                targetLocalKind = route.targetLocalKind,
                                targetLocalId = route.targetLocalId,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composable<ResourceInfoRoute> {
            val route = it.toRoute<ResourceInfoRoute>()
            ResourceInfoScreen(
                route = route,
                modCatalog = modCatalog,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigateAbsolute(ModpackLocalListRoute)
                    }
                },
                onOpenTaskList = onOpenTask,
                onTargetUnavailable = {
                    if (route.localCatalogTarget() != null) {
                        navController.navigateAbsolute(ModpackLocalListRoute)
                    } else if (!navController.popBackStack()) {
                        navController.navigateAbsolute(ModpackLocalListRoute)
                    }
                }
            )
        }
        composable<ModpackUploadRoute> {
            val route = it.toRoute<ModpackUploadRoute>()
            ModpackUploadScreen(
                onBack = {
                    if (!navController.popBackStack()) navController.navigateAbsolute(ModpackPlazaRoute())
                },
                onUploadSubmitted = { runId ->
                    navController.popBackStack()
                    onOpenTask(runId)
                },
                viewModel = koinViewModel(key = "modpack-upload:${route.modpackId ?: "new"}") {
                    parametersOf(route.modpackId)
                },
            )
        }
        /* composable<Modpack2Upload> {
            Modpack2UploadScreen(
                onBack = {
                    if (!navController.popBackStack()) navController.navigateAbsolute(ModpackPlazaRoute())
                },
                onOpenTask = onOpenTask,
            )
        }
        composable<Modpack2ContentRoute> {
            val route = it.toRoute<Modpack2ContentRoute>()
            Modpack2ContentScreen(
                versionId = route.versionId,
                onBack = {
                    if (!navController.popBackStack()) navController.navigateAbsolute(MyModpackRoute)
                },
                onBrowse = { type, versionId, mcVersion, modLoader ->
                    when (type) {
                        ModpackContentType.Mods -> navController.navigate(ModCatalogRoute(
                            requiredMcVer = mcVersion.mcVer,
                            requiredLoader = modLoader.name,
                            targetLocalKind = CatalogLocalTargetKind.Modpack2.name,
                            targetLocalId = versionId,
                        ))
                        else -> navController.navigate(ModpackContentRoute(
                            type = type.name,
                            requiredMcVer = mcVersion.mcVer,
                            requiredLoader = modLoader.name,
                            targetLocalKind = CatalogLocalTargetKind.Modpack2.name,
                            targetLocalId = versionId,
                        ))
                    }
                },
                onOpenKnown = { content, mcVersion, modLoader ->
                    navController.navigate(ModCatalogInfoRoute(
                        platform = when (content.platform.name) { "CurseForge" -> "CURSEFORGE"; else -> "MODRINTH" },
                        projectId = content.projectId,
                        requiredMcVer = mcVersion.mcVer,
                        requiredLoader = modLoader.name,
                        targetLocalKind = CatalogLocalTargetKind.Modpack2.name,
                        targetLocalId = route.versionId,
                    ))
                },
            )
        } */
        composable<ModCatalogRoute> {
            val route = it.toRoute<ModCatalogRoute>()
            if (isDisabledCatalogLocalTargetKind(route.targetLocalKind)) {
                LaunchedEffect(route) {
                    navController.navigateAbsolute(ModpackLocalListRoute)
                }
            } else {
                ModCatalogScreen(
                    route = route,
                    onBack = { navController.returnFromModCatalog(route) },
                    onOpenMod = { mod ->
                        navController.navigate(route.toInfoRoute(mod))
                    },
                )
            }
        }
        composable<ModCatalogInfoRoute> {
            val route = it.toRoute<ModCatalogInfoRoute>()
            ModCatalogInfoScreen(
                route = route,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.returnFromModCatalogInfo(route, clearLocalTarget = false)
                    }
                },
                onOpenDependencyMod = { dependency ->
                    val ref = dependency.primaryRef
                    navController.navigate(
                        route.copy(
                            platform = ref.platform.name,
                            projectId = ref.projectId
                        )
                    )
                },
                onOpenTaskList = onOpenTask,
                onTargetUnavailable = {
                    if (route.hasDisabledCatalogLocalTargetKind()) {
                        navController.navigateAbsolute(ModpackLocalListRoute)
                    } else if (route.localCatalogTarget() != null) {
                        navController.returnFromModCatalogInfo(route, clearLocalTarget = true)
                    } else if (!navController.popBackStack()) {
                        navController.returnFromModCatalogInfo(route, clearLocalTarget = false)
                    }
                }
            )
        }
        composable<ModpackInfoRoute> {
            val route = it.toRoute<ModpackInfoRoute>()
            val onBackFromModpackInfo = {
                if (route.fromHostId != null) {
                    navController.navigateAbsolute(HostInfoRoute(route.fromHostId, route.fromAllHosts))
                } else if (!navController.popBackStack()) {
                    navController.navigateAbsolute(ModpackPlazaRoute())
                }
            }
            if (ModpackInfoSource.fromRouteValue(route.source) != ModpackInfoSource.Legacy) {
                LaunchedEffect(route) {
                    onBackFromModpackInfo()
                }
            } else {
                ModpackInfoScreen(
                    modpackId = route.modpackId,
                    onBack = onBackFromModpackInfo,
                    onManageUploaders = {
                        navController.navigate(
                            ModpackUploaderManageRoute(
                                modpackId = route.modpackId,
                                fromHostId = route.fromHostId,
                                fromAllHosts = route.fromAllHosts,
                            )
                        )
                    },
                    onOpenUpload = {
                        navController.navigate(ModpackUploadRoute(modpackId = route.modpackId))
                    },
                    onOpenTaskList = onOpenTask,
                    onOpenVersionInfo = { verName ->
                        navController.navigate(
                            ModpackVersionInfoRoute(
                                modpackId = route.modpackId,
                                verName = verName,
                                fromHostId = route.fromHostId,
                                fromAllHosts = route.fromAllHosts
                            )
                        )
                    },
                    onCreateHost = { pack, version ->
                        navController.navigate(
                            HostCreateRoute(
                                kind = HostKind.Legacy.name,
                                sourceId = pack._id.toHexString(),
                                legacyVersionName = version.name,
                                displayName = pack.name,
                                legacyMcVersion = pack.mcVer.name,
                            )
                        ) {
                            popUpTo<ModpackInfoRoute> { inclusive = true }
                        }
                    },
                )
            }
        }
        composable<ModpackUploaderManageRoute> {
            val route = it.toRoute<ModpackUploaderManageRoute>()
            val returnToPack = {
                navController.navigateAbsolute(
                    ModpackInfoRoute(
                        modpackId = route.modpackId,
                        fromHostId = route.fromHostId,
                        fromAllHosts = route.fromAllHosts,
                    )
                )
            }
            ModpackUploaderManageScreen(
                modpackId = route.modpackId,
                onBack = returnToPack,
                onSaved = returnToPack,
            )
        }
        composable<ModpackVersionInfoRoute> {
            val route = it.toRoute<ModpackVersionInfoRoute>()
            val returnToPack = {
                navController.navigateAbsolute(
                    ModpackInfoRoute(
                        modpackId = route.modpackId,
                        fromHostId = route.fromHostId,
                        fromAllHosts = route.fromAllHosts,
                    )
                )
            }
            ModpackVersionInfoScreen(
                modpackId = route.modpackId,
                verName = route.verName,
                onBack = returnToPack,
                onVersionDeleted = returnToPack,
                onOpenBaseWorldManage = {
                    navController.navigate(
                        ModpackVersionBaseWorldManageRoute(
                            modpackId = route.modpackId,
                            verName = route.verName,
                            fromHostId = route.fromHostId,
                            fromAllHosts = route.fromAllHosts,
                        )
                    )
                },
            )
        }
        composable<ModpackVersionBaseWorldManageRoute> {
            val route = it.toRoute<ModpackVersionBaseWorldManageRoute>()
            val returnToVersion: () -> Unit = {
                if (!navController.popBackStack()) {
                    navController.navigateAbsolute(
                        ModpackVersionInfoRoute(
                            modpackId = route.modpackId,
                            verName = route.verName,
                            fromHostId = route.fromHostId,
                            fromAllHosts = route.fromAllHosts,
                        )
                    )
                }
            }
            ModpackVersionBaseWorldManageScreen(
                modpackId = route.modpackId,
                verName = route.verName,
                onBack = returnToVersion,
                onSaved = returnToVersion,
            )
        }
        composable<ModpackVersionEditRoute> {
            val route = it.toRoute<ModpackVersionEditRoute>()
            ModpackVersionInfoScreen(
                modpackId = route.modpackId,
                verName = route.verName,
                onBack = {
                    navController.navigateAbsolute(
                        ModpackInfoRoute(
                            modpackId = route.modpackId,
                            fromHostId = route.fromHostId,
                            fromAllHosts = route.fromAllHosts
                        )
                    )
                },
                onVersionDeleted = {
                    navController.navigateAbsolute(
                        ModpackInfoRoute(
                            modpackId = route.modpackId,
                            fromHostId = route.fromHostId,
                            fromAllHosts = route.fromAllHosts,
                        )
                    )
                },
                onOpenBaseWorldManage = {
                    navController.navigate(
                        ModpackVersionBaseWorldManageRoute(
                            modpackId = route.modpackId,
                            verName = route.verName,
                            fromHostId = route.fromHostId,
                            fromAllHosts = route.fromAllHosts,
                        )
                    )
                },
            )
        }

    }
}
