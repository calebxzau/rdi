package calebxzau.rdi.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import calebxzau.rdi.client.ui.AppBackgroundImage
import calebxzau.rdi.client.ui.AppBackgroundProvider
import calebxzau.rdi.client.di.appModule
import calebxzhou.rdi.common.util.decodeBase64
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.jarResource
import calebxzhou.rdi.client.auth.AccountSessionStore
import calebxzhou.rdi.client.auth.LocalCredentials
import calebxzhou.rdi.client.database.MinecraftInstallationDatabase
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.service.ClientDirs
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.service.PackProcStartupCleanup
import calebxzau.rdi.mcinstall.LocalMcDiscovery
import calebxzau.rdi.mcinstall.McInstallationReuse
// import calebxzhou.rdi.client.service.LocalMinecraftReuseService
import calebxzhou.rdi.client.service.NodeRefreshCoordinator
import calebxzhou.rdi.client.service.PlayerService
import calebxzhou.rdi.client.service.UpdateService
import calebxzhou.rdi.client.service.UpdaterUpdateResult
import calebxzhou.rdi.client.service.ModpackLaunchOptionsService
import calebxzhou.rdi.client.service.initializePlayerInfoCache
import calebxzhou.rdi.client.service.playerInfoCache
import calebxzhou.rdi.client.service.warmUpHwSpecCache
import calebxzhou.rdi.client.service.content.submitClientContentMigrationOnStartup
import calebxzau.rdi.client.modcatalog.CatalogNetworkPolicy
import calebxzau.rdi.client.modcatalog.createModCatalog
import calebxzhou.rdi.client.ui.AppNavigation
import calebxzhou.rdi.client.ui.McGameSession
import calebxzhou.rdi.client.ui.McPlayStore
import calebxzau.rdi.client.ui.RTheme
import calebxzhou.rdi.client.ui.navigateRoot
import calebxzhou.rdi.client.ui.screen.*
import calebxzau.rdi.client.ui.window.WindowChrome
import calebxzau.rdi.client.ui.window.activeMcSessions
import calebxzhou.rdi.client.Const
import calebxzhou.rdi.common.DEBUG
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.model.Task2Entry
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.net.ktorClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import java.awt.Dimension
import java.awt.Toolkit
import calebxzau.rdi.client.ui.showFatalStartupErrorAndExit

lateinit var ScreenSize: Pair<Dp, Dp>

fun main() {
    if (DEBUG) {
        System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
    }
    val databasePath = RDIClient.DIR.toPath().resolve("data.db")
    val databaseHandle = MinecraftInstallationDatabase.open(databasePath)
        .onFailure { cause -> lgr.error(cause) { "初始化客户端数据库失败，RDI将退出" } }
        .getOrElse {
            showFatalStartupErrorAndExit(
                "客户端数据库data.db初始化失败，RDI将退出。\n" +
                    "请检查文件权限或联系开发者。"
            )
        }
    initializePlayerInfoCache(databaseHandle.playerInfoStore)
    ModpackLaunchOptionsService.initialize(databaseHandle.modpackLaunchOptionsStore)
    clearClientContentTempDirOnStartup()
    clearPackProcDirOnStartup()
    // LocalMinecraftReuseService.start()
    GlobalScope.launch(Dispatchers.IO) {
        warmUpHwSpecCache()
    }
    GlobalScope.launch(Dispatchers.IO) {
        NodeRefreshCoordinator.refreshCurrent()
    }
    initializeLoggedAccountOnStartup()
    val modCatalog = createModCatalog(
        httpClient = ktorClient,
        identityDatabaseMaterializationDir = ClientDirs.toolsDir.resolve("mod-catalog").toPath(),
        networkPolicy = CatalogNetworkPolicy(
            preferMirrorProvider = { CONF.preferModMirror }
        ),
        onWarning = { cause -> lgr.warn(cause) { "模组目录本地索引不可用或请求降级" } }
    )
    startKoin {
        modules(
            appModule(
                modCatalog = modCatalog,
                modpackLaunchOptionsStore = databaseHandle.modpackLaunchOptionsStore,
                minecraftInstallationStore = databaseHandle.store,
            )
        )
    }
    val mcDiscovery = LocalMcDiscovery(
        databasePath = databasePath,
        initialDatabaseHandle = databaseHandle
    )
    mcDiscovery.start()
    val mcInstallationReuse = McInstallationReuse(installations = mcDiscovery.installations)
    mcInstallationReuse.start()
    try {
        application {
        val windowIcon = remember {
            jarResource("icon.png").use { stream ->
                BitmapPainter(stream.readAllBytes().decodeToImageBitmap())
            }
        }

        // 设置窗口初始大小为屏幕的2/3，并居中显示
        val screen = remember { Toolkit.getDefaultToolkit().screenSize }
        ScreenSize = (screen.width * 2 / 3).dp to (screen.height * 2 / 3).dp
        val windowState = rememberWindowState(
            width = ScreenSize.first,
            height = ScreenSize.second,
            position = WindowPosition(Alignment.Center)
        )
        val minimumSize = remember(screen) { Dimension(screen.width / 3, screen.height / 3) }
        val taskEntries by ClientTaskManager.entries.collectAsState()
        val activeTasks = remember(taskEntries) { taskEntries.filter(::isActiveTask) }
        val runningMcSessions = activeMcSessions(McPlayStore.sessions)
        var showExitConfirm by remember { mutableStateOf(false) }
        val globalSnackbar = remember { SnackbarHostState() }

        LaunchedEffect(Unit) {
            submitClientContentMigrationOnStartup()
            if (!Const.NO_UPDATE) {
                UpdateService.updateUpdater(
                    onStatus = { lgr.info { it } },
                    onDetail = { if (it.isNotBlank()) lgr.info { it } }
                ).onSuccess {
                    if (it == UpdaterUpdateResult.UPDATED) {
                        globalSnackbar.showSnackbar(
                            message = "启动程序已更新，下次启动生效",
                            duration = SnackbarDuration.Short
                        )
                    }
                }.onFailure {
                    lgr.warn(it) { "启动程序后台更新失败" }
                }
            }
        }

        fun performExit(terminateActiveWork: Boolean) {
            if (terminateActiveWork) {
                activeTasks.forEach { entry ->
                    ClientTaskManager.cancel(entry.runId, "应用关闭，任务已终止")
                }
                runningMcSessions.forEach { it.requestStop(force = true) }
            }
            playerInfoCache.close()
            mcInstallationReuse.close()
            mcDiscovery.close()
            exitApplication()
        }

        val requestClose = {
            if (activeTasks.isNotEmpty() || runningMcSessions.isNotEmpty()) {
                showExitConfirm = true
            } else {
                performExit(false)
            }
        }

        Window(
            onCloseRequest = requestClose,
            title = "RDI ${Const.VERSION_NUMBER}",
            icon = windowIcon,
            state = windowState,
            transparent = Const.WINDOW_TRANSPARENT,
            decoration = if(Const.WINDOW_TRANSPARENT) WindowDecoration.Undecorated(resizerThickness = 0.dp) else WindowDecoration.SystemDefault,
            onPreviewKeyEvent = { event ->
                event.type == KeyEventType.KeyDown && event.key == Key.Escape
            }
        ) {
            RTheme {
                AppBackgroundProvider {


                    val navController = rememberNavController()
                    val currentBackStackEntry by navController.currentBackStackEntryAsState()
                    val settingsOpen = currentBackStackEntry?.destination?.hasRoute<SettingRoute>() == true

                    WindowChrome(
                        windowState = windowState,
                        minimumSize = minimumSize,
                        onCloseRequest = requestClose,
                        onOpenHome = {
                            navController.navigateRoot(if (AccountSessionStore.isLoggedIn) MenuRoute else LoginRoute)
                        },
                        onOpenMail = { navController.navigate(MailListScreen) { launchSingleTop = true } },
                        onOpenSettings = {
                            if (settingsOpen) {
                                navController.popBackStack()
                            } else {
                                navController.navigate(SettingRoute) { launchSingleTop = true }
                            }
                        },
                        onOpenMcSession = { sessionId ->
                            McPlayStore.selectedSessionId = sessionId
                            McPlayStore.openConsoleOnly = true
                            McPlayStore.onBack = { navController.popBackStack() }
                            navController.navigate(McPlayRoute) { launchSingleTop = true }
                        },
                        onLogin = { navController.navigateRoot(LoginRoute) },
                        onLogout = {
                            LocalCredentials.read().setAutoLoginDisabled(true).onFailure {
                                lgr.error(it) { "保存自动登录设置失败" }
                            }
                            AccountSessionStore.logout()
                            navController.navigateRoot(LoginRoute)
                        },
                        onOpenPlayerInfo = {
                            navController.navigate(PlayerInfoRoute()) { launchSingleTop = true }
                        },
                        onOpenWardrobe = { navController.navigate(WardrobeRoute) }
                    ) { onOpenTask ->
                        val dynamicBackgroundActive = !windowState.isMinimized &&
                            (currentBackStackEntry?.destination?.hasRoute<MenuRoute>() == true ||
                                currentBackStackEntry?.destination?.hasRoute<LoginRoute>() == true)
                        Box(modifier = Modifier.fillMaxSize()) {
                            AppBackgroundImage(
                                active = dynamicBackgroundActive,
                                modifier = Modifier.fillMaxSize()
                            )
                            AppNavigation(navController, modCatalog, onOpenTask = onOpenTask)
                            SnackbarHost(
                                hostState = globalSnackbar,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp)
                            )
                        }
                        if (showExitConfirm) {
                            AlertDialog(
                                onDismissRequest = { showExitConfirm = false },
                                title = { Text("仍有任务或MC正在运行") },
                                text = { Text(buildExitConfirmMessage(activeTasks, runningMcSessions)) },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            showExitConfirm = false
                                            performExit(true)
                                        }
                                    ) {
                                        Text("全部停止并退出")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showExitConfirm = false }) {
                                        Text("继续等待")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        }
    } finally {
        mcDiscovery.close()
        playerInfoCache.close()
        modCatalog.close()
    }
}

private fun initializeLoggedAccountOnStartup() {
    System.getProperty("rdi.account")?.let {
        loggedAccount = serdesJson.decodeFromString(it.decodeBase64)
    }
    System.getProperty("rdi.jwt")?.let {
        AccountSessionStore.updateJwt(it)
    }
    if (loggedAccount.jwt == null && loggedAccount != RAccount.DEFAULT) {
        GlobalScope.launch {
            val jwt = PlayerService.getJwt(loggedAccount.qq, loggedAccount.pwd)
            AccountSessionStore.updateJwt(jwt)
        }
    }
}

private fun isActiveTask(entry: Task2Entry): Boolean = !entry.status.isTerminal

private fun buildExitConfirmMessage(
    activeTasks: List<Task2Entry>,
    activeMcSessions: List<McGameSession>
): String = buildString {
    if (activeTasks.isNotEmpty()) append("${activeTasks.size}个任务正在运行\n")
    if (activeMcSessions.isNotEmpty()) append("${activeMcSessions.size}个MC正在运行\n")
    append("\n要全部停止并退出，还是继续等待？")
}

private fun clearClientContentTempDirOnStartup() {
    runCatching {
        ClientDirs.dlcDir.resolve(".tmp").deleteRecursivelyNoSymlink()
    }.onFailure {
        lgr.warn(it) { "清理客户端内容临时目录失败" }
    }
}

private fun clearPackProcDirOnStartup() {
    GlobalScope.launch(Dispatchers.IO) {
        PackProcStartupCleanup.clear(ClientDirs.packProcDir.toPath())
            .onFailure { cause ->
                lgr.warn(cause) { "清理pack-proc目录失败" }
            }
    }
}
