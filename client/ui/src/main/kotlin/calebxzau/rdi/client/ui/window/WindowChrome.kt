package calebxzau.rdi.client.ui.window

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import calebxzau.rdi.client.ui.CodeFontFamily
import calebxzau.rdi.client.ui.ImageIconButton
import calebxzau.rdi.client.ui.RDropdownMenuItem
import calebxzau.rdi.client.ui.IconFontFamily
import calebxzau.rdi.client.ui.UIFontFamily
import calebxzhou.rdi.client.auth.AccountSessionStore
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.ui.McGameSession
import calebxzhou.rdi.client.ui.McPlayStore
import calebxzau.rdi.client.ui.SimpleTooltip
import calebxzau.rdi.client.ui.baseShapeRadius
import calebxzau.rdi.client.ui.openUrl
import calebxzhou.rdi.client.ui.comp.HeadButton
import calebxzhou.rdi.client.ui.comp.Task2DetailDialog
import calebxzhou.rdi.common.model.RAccount
import java.awt.Cursor
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window
import kotlin.math.abs

private val titleBarHeight = 40.dp
private val windowButtonWidth = 46.dp
private val edgeResizeThickness = 6.dp
private val cornerResizeSize = 10.dp
private val chromeIconButtonSize = 28.dp
private val appIconSize = 24.dp

/** The window's corner radius scaled down to the title-bar icon. */
private val appIconShape = RoundedCornerShape(6.dp)

private val titleBarBackground = Color(0xFFF7F8FA)
private val titleBarDivider = Color(0xFFE1E4EA)
private val titleTextColor = Color(0xFF171A1F)
private val windowButtonColor = Color(0xFF3F4652)
private val windowButtonHover = Color(0xFFE9ECF2)
private val closeButtonHover = Color(0xFFE81123)

/** Keeps the floating window's edge visible against a white desktop. */
private val windowBorderColor = Color.Black.copy(alpha = 0.12f)
private const val MCMOD_BROWSER_URL = "https://play.mcmod.cn/sv20188037.html"

@Composable
fun FrameWindowScope.WindowChrome(
    windowState: WindowState,
    minimumSize: Dimension,
    appIcon: Painter,
    onCloseRequest: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenMail: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMcSession: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onOpenPlayerInfo: () -> Unit,
    onOpenWardrobe: () -> Unit,
    content: @Composable (onOpenTask: (String) -> Unit) -> Unit
) {
    val account by AccountSessionStore.account.collectAsState()
    val taskEntries by ClientTaskManager.entries.collectAsState()
    var selectedTaskRunId by remember { mutableStateOf<String?>(null) }
    val openTask: (String) -> Unit = remember {
        { runId: String -> selectedTaskRunId = runId }
    }
    val selectedTaskEntry = taskEntries.firstOrNull { it.runId == selectedTaskRunId }
    val floating = windowState.placement == WindowPlacement.Floating
    val windowShape = if (floating) {
        RoundedCornerShape(baseShapeRadius.dp)
    } else {
        RectangleShape
    }

    DisposableEffect(window, minimumSize) {
        window.minimumSize = minimumSize
        onDispose {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(windowShape)
            .background(Color.White)
            .then(if (floating) Modifier.border(1.dp, windowBorderColor, windowShape) else Modifier)
    ) {
        Column(Modifier.fillMaxSize()) {
            TitleBar(
                window = window,
                windowState = windowState,
                appIcon = appIcon,
                loggedIn = account._id != RAccount.DEFAULT._id,
                onCloseRequest = onCloseRequest,
                onOpenHome = onOpenHome,
                onOpenMail = onOpenMail,
                onOpenSettings = onOpenSettings,
                onOpenMcSession = onOpenMcSession,
                onOpenTask = openTask,
                onLogin = onLogin,
                onLogout = onLogout,
                onOpenPlayerInfo = onOpenPlayerInfo,
                onOpenWardrobe = onOpenWardrobe
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                content(openTask)
            }
        }

        if (floating) {
            ResizeOverlay(window)
        }

        selectedTaskEntry?.let { entry ->
            Task2DetailDialog(entry = entry, onClose = { selectedTaskRunId = null })
        }
    }

    LaunchedEffect(selectedTaskRunId, selectedTaskEntry) {
        if (selectedTaskRunId != null && selectedTaskEntry == null) selectedTaskRunId = null
    }
}

@Composable
private fun TitleBar(
    window: Window,
    windowState: WindowState,
    appIcon: Painter,
    loggedIn: Boolean,
    onCloseRequest: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenMail: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMcSession: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onOpenPlayerInfo: () -> Unit,
    onOpenWardrobe: () -> Unit
) {
    val activeSessions = McPlayStore.sessions

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(titleBarHeight)
            .background(titleBarBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .titleBarGestures(window, windowState)
                        .padding(start = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = appIcon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(appIconSize)
                                .clip(appIconShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = buildAnnotatedString {
                                //append("start")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("rdi") }
                            },
                            color = titleTextColor,
                            fontFamily = UIFontFamily,
                            fontSize = 18.sp
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                ChromeIconButton("\uF015", "主页", onOpenHome)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .titleBarGestures(window, windowState)
                )
            }

            if (loggedIn) {
                ChromeIconButton(
                    icon = "m",
                    "MC百科",
                    { openUrl(MCMOD_BROWSER_URL) }
                )
                Spacer(Modifier.width(8.dp))
                ChromeIconButton("\uF0E0", "邮件", onOpenMail)
                Spacer(Modifier.width(8.dp))
            }

                McSessionMenu(activeSessions, onOpenMcSession)
                Spacer(Modifier.width(8.dp))

            TaskMenuButton(onOpenTask)
            Spacer(Modifier.width(8.dp))
            ChromeIconButton("\uEB51", "设置", onOpenSettings)
            Spacer(Modifier.width(10.dp))
            AccountMenu(loggedIn, onLogin, onLogout, onOpenPlayerInfo, onOpenWardrobe)
            Spacer(Modifier.width(10.dp))
            WindowButton("\uF2D1", "最小化") { windowState.isMinimized = true }
            WindowButton(
                icon = if (windowState.placement == WindowPlacement.Maximized) "\uF2D2" else "\uF2D0",
                description = if (windowState.placement == WindowPlacement.Maximized) "还原" else "最大化",
                onClick = windowState::toggleMaximized
            )
            WindowButton("\uF00D", "关闭", closeButton = true, onClick = onCloseRequest)
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(titleBarDivider)
        )
    }
}

@Composable
private fun McSessionMenu(
    sessions: List<McGameSession>,
    onOpenSession: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        ChromeIconButton("\uDB80\uDF73", "运行中MC") { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 12.dp)
        ) {
            if (sessions.isEmpty()) {
                DropdownMenuItem({ Text("没有运行中mc") },{})
            }else{
                sessions.asReversed().forEach { session ->
                    DropdownMenuItem(
                        text = {
                            Column(Modifier.widthIn(min = 180.dp, max = 260.dp)) {
                                Text(
                                    text = session.args.modpackName.ifBlank { session.title },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (session.preparing) "准备中·${session.args.mcVer.mcVer}" else "运行中·${session.args.mcVer.mcVer}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onOpenSession(session.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountMenu(
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onOpenPlayerInfo: () -> Unit,
    onOpenWardrobe: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val account by AccountSessionStore.account.collectAsState()

    if (!loggedIn) {
        Surface(onClick = onLogin, color = Color.Transparent, shape = CircleShape) {
            Text("请登录", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = titleTextColor)
        }
        return
    }

    Box {
        Surface(
            onClick = { expanded = true },
            color = Color.Transparent,
            shape = CircleShape
        ) {
            Row(
                modifier = Modifier.widthIn(max = 180.dp).padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeadButton(
                    uid = account._id,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 12.dp)
        ) {
            RDropdownMenuItem(
                text = "个人信息",
                icon = "\uF449",
                onClick = {
                    expanded = false
                    onOpenPlayerInfo()
                }
            )
            RDropdownMenuItem(
                text = "衣柜",
                icon = "\uEE1C",
                onClick = {
                    expanded = false
                    onOpenWardrobe()
                }
            )
            // 好友系统已归档，菜单入口暂时停用。
            RDropdownMenuItem(
                text = "退出登录",
                icon = "\uF2F5",
                onClick = {
                    expanded = false
                    onLogout()
                }
            )
        }
    }
}

@Composable
internal fun ChromeIconButton(
    icon: String,
    description: String,
    onClick: () -> Unit = {}
) {
    SimpleTooltip(description) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(chromeIconButtonSize),
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = Color.Transparent,
            contentColor = Color.Black
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(icon, fontFamily = IconFontFamily, fontSize = 15.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun WindowButton(
    icon: String,
    description: String,
    closeButton: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        !hovered -> Color.Transparent
        closeButton -> closeButtonHover
        else -> windowButtonHover
    }
    val iconColor = if (hovered && closeButton) Color.White else windowButtonColor

    SimpleTooltip(description) {
        Box(
            modifier = Modifier
                .size(windowButtonWidth, titleBarHeight)
                .background(background)
                .hoverable(interactionSource)
                .pointerInput(onClick) { detectTapGestures { onClick() } }
                .semantics {
                    contentDescription = description
                    role = Role.Button
                },
            contentAlignment = Alignment.Center
        ) {
            Text(icon, color = iconColor, fontFamily = IconFontFamily, fontSize = 14.sp)
        }
    }
}

internal fun activeMcSessions(sessions: List<McGameSession>): List<McGameSession> =
    sessions.filter { it.preparing || it.isAlive() }

private fun Modifier.titleBarGestures(window: Window, windowState: WindowState) = pointerInput(window, windowState) {
    var initialMousePosition = Point()
    var initialWindowPosition = Point()
    var dragging = false
    var moved = false
    var lastClickAt = 0L

    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.first()
            val mousePosition = mousePositionOnScreen()
            val pressed = !change.previousPressed && change.pressed
            val released = change.previousPressed && !change.pressed

            if (pressed && mousePosition != null) {
                initialMousePosition = mousePosition
                initialWindowPosition = Point(window.x, window.y)
                dragging = windowState.placement == WindowPlacement.Floating
                moved = false
            }
            if (dragging && event.type == PointerEventType.Move && event.buttons.isPrimaryPressed && mousePosition != null) {
                val deltaX = mousePosition.x - initialMousePosition.x
                val deltaY = mousePosition.y - initialMousePosition.y
                if (abs(deltaX) > 2 || abs(deltaY) > 2) moved = true
                window.setLocation(initialWindowPosition.x + deltaX, initialWindowPosition.y + deltaY)
            }
            if (released) {
                dragging = false
                if (!moved) {
                    val now = System.currentTimeMillis()
                    if (now - lastClickAt <= 400) {
                        windowState.toggleMaximized()
                        lastClickAt = 0L
                    } else {
                        lastClickAt = now
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ResizeOverlay(window: Window) {
    ResizeArea(ResizeDirection.Left, Modifier.align(Alignment.CenterStart).width(edgeResizeThickness).fillMaxHeight(), window)
    ResizeArea(ResizeDirection.Right, Modifier.align(Alignment.CenterEnd).width(edgeResizeThickness).fillMaxHeight(), window)
    ResizeArea(ResizeDirection.Top, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(edgeResizeThickness), window)
    ResizeArea(ResizeDirection.Bottom, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(edgeResizeThickness), window)
    ResizeArea(ResizeDirection.TopLeft, Modifier.align(Alignment.TopStart).size(cornerResizeSize), window)
    ResizeArea(ResizeDirection.TopRight, Modifier.align(Alignment.TopEnd).size(cornerResizeSize), window)
    ResizeArea(ResizeDirection.BottomLeft, Modifier.align(Alignment.BottomStart).size(cornerResizeSize), window)
    ResizeArea(ResizeDirection.BottomRight, Modifier.align(Alignment.BottomEnd).size(cornerResizeSize), window)
}

@Composable
private fun ResizeArea(direction: ResizeDirection, modifier: Modifier, window: Window) {
    Box(modifier.pointerHoverIcon(PointerIcon(Cursor(direction.cursor))).resizeOnDrag(window, direction))
}

private fun Modifier.resizeOnDrag(window: Window, direction: ResizeDirection) = pointerInput(window, direction) {
    var initialMousePosition = Point()
    var initialWindowPosition = Point()
    var initialWindowSize = Dimension()
    var resizing = false

    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val mousePosition = mousePositionOnScreen()
            val change = event.changes.first()
            if (!change.previousPressed && change.pressed && mousePosition != null) {
                initialMousePosition = mousePosition
                initialWindowPosition = Point(window.x, window.y)
                initialWindowSize = Dimension(window.width, window.height)
                resizing = true
            }
            if (!event.buttons.isPrimaryPressed || mousePosition == null) resizing = false
            if (resizing && event.type == PointerEventType.Move && mousePosition != null) {
                resizeWindow(window, direction, initialMousePosition, initialWindowPosition, initialWindowSize, mousePosition)
            }
        }
    }
}

private fun resizeWindow(
    window: Window,
    direction: ResizeDirection,
    initialMousePosition: Point,
    initialWindowPosition: Point,
    initialWindowSize: Dimension,
    mousePosition: Point
) {
    val deltaX = mousePosition.x - initialMousePosition.x
    val deltaY = mousePosition.y - initialMousePosition.y
    var x = initialWindowPosition.x
    var y = initialWindowPosition.y
    var width = initialWindowSize.width
    var height = initialWindowSize.height

    if (direction.left) {
        width = (initialWindowSize.width - deltaX).coerceAtLeast(window.minimumSize.width)
        x = initialWindowPosition.x + initialWindowSize.width - width
    } else if (direction.right) {
        width = (initialWindowSize.width + deltaX).coerceAtLeast(window.minimumSize.width)
    }
    if (direction.top) {
        height = (initialWindowSize.height - deltaY).coerceAtLeast(window.minimumSize.height)
        y = initialWindowPosition.y + initialWindowSize.height - height
    } else if (direction.bottom) {
        height = (initialWindowSize.height + deltaY).coerceAtLeast(window.minimumSize.height)
    }
    window.setBounds(x, y, width, height)
}

private fun WindowState.toggleMaximized() {
    placement = if (placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
}

private fun mousePositionOnScreen(): Point? = MouseInfo.getPointerInfo()?.location

private enum class ResizeDirection(
    val cursor: Int,
    val left: Boolean = false,
    val top: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false
) {
    Left(Cursor.W_RESIZE_CURSOR, left = true),
    Right(Cursor.E_RESIZE_CURSOR, right = true),
    Top(Cursor.N_RESIZE_CURSOR, top = true),
    Bottom(Cursor.S_RESIZE_CURSOR, bottom = true),
    TopLeft(Cursor.NW_RESIZE_CURSOR, left = true, top = true),
    TopRight(Cursor.NE_RESIZE_CURSOR, top = true, right = true),
    BottomLeft(Cursor.SW_RESIZE_CURSOR, left = true, bottom = true),
    BottomRight(Cursor.SE_RESIZE_CURSOR, right = true, bottom = true)
}
