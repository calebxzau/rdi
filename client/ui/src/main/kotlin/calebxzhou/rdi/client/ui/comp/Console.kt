package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import calebxzau.rdi.client.ui.CodeFontFamily
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.RVerticalScrollbar as SharedRVerticalScrollbar
import calebxzau.rdi.client.ui.themeNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.filechooser.FileSystemView

@Composable
fun Console(
    state: ConsoleState,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var consoleSnapshot by remember(state) { mutableStateOf(state.uiSnapshot()) }
    var updateId by remember(state) { mutableStateOf(0L) }
    var previousRemovedLineCount by remember(state) {
        mutableStateOf(consoleSnapshot.removedLineCount)
    }
    var followTail by remember(state) { mutableStateOf(true) }
    var automaticScroll by remember(state) { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        state.revision.collect {
            withFrameNanos { }
            consoleSnapshot = state.uiSnapshot()
            updateId++
        }
    }

    val lines = consoleSnapshot.lines
    val currentLineCount by rememberUpdatedState(lines.size)
    LaunchedEffect(updateId, followTail) {
        if (followTail && lines.isNotEmpty()) {
            automaticScroll = true
            try {
                listState.scrollToItem(lines.lastIndex)
            } finally {
                automaticScroll = false
            }
        } else if (lines.isNotEmpty()) {
            val removedSinceLastUpdate = consoleSnapshot.removedLineCount - previousRemovedLineCount
            if (removedSinceLastUpdate > 0) {
                val adjustedIndex = listState.firstVisibleItemIndex - removedSinceLastUpdate.toInt()
                listState.scrollToItem(
                    index = adjustedIndex.coerceAtLeast(0),
                    scrollOffset = listState.firstVisibleItemScrollOffset
                )
            }
        }
        previousRemovedLineCount = consoleSnapshot.removedLineCount
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !automaticScroll) {
                followTail = false
            } else if (!scrolling && listState.isAtBottom(currentLineCount)) {
                followTail = true
            }
        }
    }

    val colors = consoleColors()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                items(lines) { line ->
                    Text(
                        text = if (line.isEmpty()) AnnotatedString(" ") else formatLine(line, colors),
                        color = colors.normal,
                        fontFamily = CodeFontFamily,
                        fontSize = 10.sp,
                        lineHeight = 12.sp
                    )
                }
            }
        }

        SharedRVerticalScrollbar(
            listState = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            CircleIconButton(
                icon = "\uEF11",
                tooltip = "导出日志",
                bgColor = themeNow.tertiary,
                size = 32.dp,
                enabled = lines.isNotEmpty() && !exporting,
                showText = false
            ) {
                val exportedLines = state.snapshot()
                if (exportedLines.isEmpty()) return@CircleIconButton
                exporting = true
                scope.launch {
                    val result = exportLogsToDesktop(exportedLines)
                    exporting = false
                    snackbarHostState.showSnackbar(
                        result.fold(
                            onSuccess = { "日志已导出到桌面：${it.fileName}" },
                            onFailure = { "日志导出失败：${it.message ?: "未知错误"}" }
                        )
                    )
                }
            }
            CircleIconButton(
                icon = "\uF103",
                tooltip = "回到底部",
                size = 32.dp,
                enabled = lines.isNotEmpty(),
                showText = false
            ) {
                followTail = true
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
        )
    }
}

private fun LazyListState.isAtBottom(itemCount: Int): Boolean {
    if (itemCount == 0) return true
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return false
    return lastVisibleItem.index == itemCount - 1 &&
            lastVisibleItem.offset + lastVisibleItem.size <= layoutInfo.viewportEndOffset
}

private suspend fun exportLogsToDesktop(lines: List<String>): Result<Path> = withContext(Dispatchers.IO) {
    try {
        val desktop = FileSystemView.getFileSystemView().homeDirectory.toPath()
        check(Files.isDirectory(desktop)) { "找不到桌面目录" }
        val outputFile = nextAvailableLogFile(desktop)
        Files.writeString(
            outputFile,
            lines.joinToString(separator = "\n", postfix = "\n"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        Result.success(outputFile)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Result.failure(exception)
    }
}

private fun nextAvailableLogFile(desktop: Path): Path {
    val baseName = "rdi_console_${LOG_FILE_TIME_FORMAT.format(LocalDateTime.now())}"
    var file = desktop.resolve("$baseName.txt")
    var suffix = 2
    while (Files.exists(file)) {
        file = desktop.resolve("${baseName}_$suffix.txt")
        suffix++
    }
    return file
}

private val LOG_FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

private data class ConsoleColors(
    val time: Color,
    val info: Color,
    val warning: Color,
    val error: Color,
    val normal: Color
)

@Composable
private fun consoleColors(): ConsoleColors = ConsoleColors(
    time = MaterialTheme.colorScheme.primary,
    info = Color(0xFF2E7D32),
    warning = Color(0xFF9A6700),
    error = MaterialTheme.colorScheme.error,
    normal = MaterialTheme.colorScheme.onSurface
)

private fun formatLine(line: String, colors: ConsoleColors): AnnotatedString = buildAnnotatedString {
    val leadingLevel = LEVEL_SECTION.find(line)
    if (leadingLevel != null) {
        appendLevelSection(line, leadingLevel, colors)
        return@buildAnnotatedString
    }

    val time = TIME_PREFIX.find(line)
    if (time == null) {
        withStyle(SpanStyle(color = messageColor(line, colors))) { append(line) }
        return@buildAnnotatedString
    }

    withStyle(SpanStyle(color = colors.time)) { append(time.value) }
    val remainder = line.substring(time.range.last + 1)
    val level = LEVEL_SECTION.find(remainder)
    if (level == null) {
        withStyle(SpanStyle(color = messageColor(remainder, colors))) { append(remainder) }
    } else {
        appendLevelSection(remainder, level, colors)
    }
}

private fun AnnotatedString.Builder.appendLevelSection(
    text: String,
    match: MatchResult,
    colors: ConsoleColors
) {
    withStyle(SpanStyle(color = levelColor(match.groupValues[1], colors))) {
        append(match.value)
    }
    val remainder = text.substring(match.range.last + 1)
    withStyle(SpanStyle(color = messageColor(remainder, colors))) { append(remainder) }
}

private fun levelColor(level: String, colors: ConsoleColors): Color = when {
    level.equals("INFO", ignoreCase = true) -> colors.info
    level.equals("WARN", ignoreCase = true) ||
            level.equals("WARNING", ignoreCase = true) -> colors.warning
    level.equals("ERROR", ignoreCase = true) ||
            level.equals("SEVERE", ignoreCase = true) ||
            level.equals("FATAL", ignoreCase = true) -> colors.error
    else -> colors.normal
}

private fun messageColor(text: String, colors: ConsoleColors): Color = when {
    text.contains("ERROR", ignoreCase = true) ||
            text.contains("SEVERE", ignoreCase = true) ||
            text.contains("FATAL", ignoreCase = true) ||
            text.contains("Exception", ignoreCase = true) -> colors.error
    text.contains("WARN", ignoreCase = true) ||
            text.contains("WARNING", ignoreCase = true) -> colors.warning
    else -> colors.normal
}

private val TIME_PREFIX = Regex("""^\[[^]]*]""")
private val LEVEL_SECTION = Regex(
    """^\s*\[[^]]*/(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|SEVERE|FATAL)]""",
    RegexOption.IGNORE_CASE
)
