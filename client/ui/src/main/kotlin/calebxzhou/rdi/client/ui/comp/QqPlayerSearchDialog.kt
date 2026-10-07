package calebxzau.rdi.client.ui.comp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.ui.viewmodel.MappingTarget
import calebxzhou.rdi.client.ui.comp.HeadButton
import kotlinx.coroutines.launch

/** Finds an RDI account by QQ number and asks the owner to confirm it (plan §7, D7). */
@Composable
fun QqPlayerSearchDialog(
    onSearch: suspend (String) -> Result<MappingTarget>,
    onPick: (MappingTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var qq by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<MappingTarget?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按QQ号查找玩家") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = qq,
                    onValueChange = { value ->
                        qq = value.filter(Char::isDigit)
                        found = null
                        error = null
                    },
                    label = { Text("QQ号") },
                    singleLine = true,
                    enabled = !searching,
                )
                found?.let { target ->
                    Text("找到玩家：")
                    HeadButton(target.accountId)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val target = found
            if (target != null) {
                TextButton(onClick = { onPick(target) }) { Text("就是这名玩家") }
            } else {
                TextButton(
                    enabled = qq.isNotBlank() && !searching,
                    onClick = {
                        searching = true
                        scope.launch {
                            onSearch(qq).fold(
                                onSuccess = { found = it },
                                onFailure = { error = it.message ?: "查找失败" },
                            )
                            searching = false
                        }
                    },
                ) { Text(if (searching) "正在查找..." else "查找") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
