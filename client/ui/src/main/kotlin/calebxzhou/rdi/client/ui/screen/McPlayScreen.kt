package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.RDIClient
import calebxzau.rdi.client.lgr
import calebxzau.rdi.client.service.dmGameJvmArgs
import calebxzau.rdi.client.ui.*
import calebxzau.rdi.mcinstall.McLaunchPreparationRequest
import calebxzau.rdi.mclaunch.MinecraftLaunchOverrides
import calebxzhou.rdi.client.net.RServer
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.service.*
import calebxzhou.rdi.client.ui.McGameSession
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.client.ui.McPlayStore
import calebxzhou.rdi.client.ui.comp.Console
import calebxzhou.rdi.common.model.FORGEGUARD_AGENT_FILE_NAME
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.model.compactText
import calebxzhou.rdi.common.model.isDav
import calebxzhou.rdi.common.util.encodeBase64
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException

private val gameWindowService = GameWindowService(encodePng = ::encodeComposeWindowIconPng)

@Composable
fun McPlayScreen(
    launchArgs: McPlayArgs? = null,
    autoStart: Boolean = true,
    extraJvmArgs: List<String> = emptyList(),
    onBack: () -> Unit
) {
    McPlayStore.revision.collectAsState().value
    val sessions = McPlayStore.sessions
    var duplicateLaunchArgs by remember { mutableStateOf<McPlayArgs?>(null) }
    val screenFocusRequester = remember { FocusRequester() }

    val controller = remember(extraJvmArgs) { McPlayController(gameWindowService = gameWindowService, extraJvmArgs = extraJvmArgs, onDuplicate = { duplicateLaunchArgs = it }) }
    fun startSession(args: McPlayArgs, allowDuplicateVersion: Boolean = false) = controller.startSession(args, allowDuplicateVersion)
    fun stopSession(session: McGameSession, force: Boolean = false) = controller.stopSession(session, force)

    LaunchedEffect(launchArgs, autoStart) {
        if (autoStart && launchArgs != null) {
            startSession(launchArgs)
        }
    }

    LaunchedEffect(sessions.size) {
        if (McPlayStore.selectedSessionId == null || sessions.none { it.id == McPlayStore.selectedSessionId }) {
            McPlayStore.selectedSessionId = sessions.lastOrNull()?.id
        }
    }

    val selectedSession = McPlayStore.selectedSession()

    LaunchedEffect(Unit) {
        screenFocusRequester.requestFocus()
    }

    MaxBox {
        ScreenContentSurface(
            size = ScreenContentSize.FULL,
            modifier = Modifier
                .focusRequester(screenFocusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.Delete &&
                        selectedSession?.let { McPlayStore.closeStoppedSession(it.id) } == true
                },
            containerAlpha = 1f,
            shadowElevation = 0.dp
        ) {
            TitleRow("MC控制台", onBack) {
                selectedSession?.let { session ->
                    val modpackDir = session.args.versionDir
                        ?.let { java.io.File(it) }
                        ?: mcInstall.versionListDir.resolve(session.args.versionId)
                    CircleIconButton("\uEAED", "打开目录", enabled = modpackDir.isDirectory, showText = false) {
                        openFolder(modpackDir.absolutePath)
                    }
                    //if(Const.AI_TEST){
                        /*CircleIconButton(
                            icon = "\uE0CA",
                            tooltip = "AI陪玩",
                            bgColor = themeNow.secondary
                        ) {
                            onOpenAiChat(session.args.mcpPort, session.args.versionDir)
                        }*/
                    //}
                    CircleIconButton("\uEAD2", "重启MC", showText = false) {
                        session.requestStop()
                        startSession(session.args, allowDuplicateVersion = true)
                    }
                    CircleIconButton(
                        "\uF04D",
                        "终止MC",
                        bgColor = themeNow.error,
                        iconColor = themeNow.onError, showText = false
                    ) {
                        stopSession(session,force = true)
                    }
                }
            }
            ContentBody {
                if (sessions.isEmpty()) {
                    Text("没有可显示的游戏", color = themeNow.onSurfaceVariant)
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sessions.forEach { session ->
                            McSessionChip(
                                session = session,
                                selected = session.id == selectedSession?.id,
                                onClick = { McPlayStore.selectedSessionId = session.id }
                            )
                        }
                    }
                    Space8h()
                    selectedSession?.let { session ->
                        Console(session.consoleState, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    duplicateLaunchArgs?.let { args ->
        AlertDialog(
            onDismissRequest = { duplicateLaunchArgs = null },
            title = { Text("该整合包已在运行") },
            text = {
                Text("再次启动${args.title}可能造成存档、配置、日志或Mod缓存冲突。是否继续？")
            },
            confirmButton = {
                TextButton(onClick = {
                    duplicateLaunchArgs = null
                    startSession(args, allowDuplicateVersion = true)
                }) {
                    Text("继续启动")
                }
            },
            dismissButton = {
                TextButton(onClick = { duplicateLaunchArgs = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun McSessionChip(
    session: McGameSession,
    selected: Boolean,
    onClick: () -> Unit
) {
    val status = when {
        session.preparing -> "启动中"
        session.isAlive() -> "运行中"
        else -> "已结束"
    }
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(session.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(status, color = sessionStatusColor(session), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun sessionStatusColor(session: McGameSession) = when {
    session.preparing -> themeNow.tertiary
    session.isAlive() -> themeNow.primary
    else -> themeNow.onSurfaceVariant
}
