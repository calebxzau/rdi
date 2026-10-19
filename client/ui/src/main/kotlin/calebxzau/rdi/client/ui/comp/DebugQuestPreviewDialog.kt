package calebxzau.rdi.client.ui.comp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import calebxzhou.rdi.client.net.lgr
import calebxzau.rdi.client.ui.CircleIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

@Composable
internal fun DebugQuestPreviewDialog(onDismiss: () -> Unit, onOpen: (Path) -> Unit) {
    var directoryText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(checking) {
        if (!checking) focusRequester.requestFocus()
    }

    fun openPreview() {
        if (checking) return
        checking = true
        errorMessage = null
        val input = directoryText
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val trimmed = input.trim()
                    val pathText = if (trimmed.length >= 2 &&
                        ((trimmed.first() == '"' && trimmed.last() == '"') ||
                            (trimmed.first() == '\'' && trimmed.last() == '\''))) {
                        trimmed.substring(1, trimmed.lastIndex)
                    } else trimmed
                    require(pathText.isNotBlank()) { "请输入整合包目录" }
                    val directory = Path.of(pathText).toAbsolutePath().normalize()
                    val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java)
                    require(attributes.isDirectory) { "输入的路径不是目录" }
                    directory
                }
            }
            checking = false
            result.fold(
                onSuccess = onOpen,
                onFailure = { exception ->
                    lgr.warn(exception) { "调试任务书预览：整合包目录无效" }
                    errorMessage = when (exception) {
                        is java.nio.file.NoSuchFileException -> "目录不存在"
                        is java.nio.file.AccessDeniedException -> "无法访问此目录"
                        is java.nio.file.InvalidPathException -> "目录路径格式无效"
                        else -> exception.message ?: "无法读取整合包目录"
                    }
                },
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
            when (event.key) {
                Key.Enter, Key.NumPadEnter -> {
                    if (event.type == KeyEventType.KeyUp) openPreview()
                    true
                }
                Key.Escape -> {
                    if (event.type == KeyEventType.KeyUp) onDismiss()
                    true
                }
                else -> false
            }
        },
        title = { Text("调试：预览任务书") },
        text = {
            OutlinedTextField(
                value = directoryText,
                onValueChange = {
                    directoryText = it
                    errorMessage = null
                },
                label = { Text("整合包目录") },
                singleLine = true,
                enabled = !checking,
                isError = errorMessage != null,
                supportingText = {
                    Text(errorMessage ?: "输入包含config/ftbquests/quests的整合包根目录。")
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = {
            CircleIconButton(
                icon = "\uF06E",
                label = if (checking) "检查中" else "预览",
                enabled = !checking && directoryText.isNotBlank(),
                onClick = ::openPreview,
            )
        },
        dismissButton = {
            CircleIconButton(icon = "\uF00D", label = "取消", onClick = onDismiss)
        },
    )
}
