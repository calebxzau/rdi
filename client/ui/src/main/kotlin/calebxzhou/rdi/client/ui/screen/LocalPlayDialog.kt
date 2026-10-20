package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.account.OfflineGameAccount
import calebxzau.rdi.client.account.OnlineGameAccountService
import calebxzau.rdi.client.lgr
import calebxzau.rdi.client.ui.RTextField
import calebxzau.rdi.client.ui.copyToClipboard
import calebxzau.rdi.client.ui.openMsaVerificationUrl
import calebxzau.rdi.mclaunch.MinecraftAccount
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.service.LocalModpackStore
import calebxzhou.rdi.client.service.LocalPlayAccountMode
import calebxzhou.rdi.client.service.ModpackLocalDir
import calebxzhou.rdi.client.ui.McPlayArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode

/** Launch arguments of a pack installed only on this computer: vanilla launch as [account]. */
internal fun ModpackLocalDir.toLocalPlayArgs(account: MinecraftAccount): McPlayArgs = McPlayArgs(
    title = "单机 - $name",
    mcVer = mcVersion,
    modLoader = modLoader,
    versionId = versionId,
    playArg = "",
    modpackName = name,
    versionDir = dir.absolutePath,
    modpackIconUrl = iconUrl,
    versionName = verName,
    account = account,
    withRdiCore = false,
)

/** Asks whether a local pack starts offline with a nickname or online with the bound Microsoft account. */
@Composable
internal fun LocalPlayDialog(
    pack: ModpackLocalDir,
    onDismiss: () -> Unit,
    onPlay: (McPlayArgs) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val onlineService = remember { OnlineGameAccountService() }
    val msid = loggedAccount.msid
    val local = pack.local
    var mode by remember(pack.versionId) {
        mutableStateOf(
            local?.lastAccountMode?.takeIf { it == LocalPlayAccountMode.Offline || msid != null }
                ?: LocalPlayAccountMode.Offline
        )
    }
    var nickname by remember(pack.versionId) { mutableStateOf(local?.lastOfflineName ?: loggedAccount.name) }
    var busy by remember { mutableStateOf(false) }
    var deviceCode by remember { mutableStateOf<MsaDeviceCode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }

    fun dismiss() {
        loginJob?.cancel()
        onDismiss()
    }

    fun startWith(account: MinecraftAccount) {
        scope.launch {
            withContext(Dispatchers.IO) {
                LocalModpackStore.update(pack.dir) { info ->
                    info.copy(
                        lastAccountMode = mode,
                        lastOfflineName = if (mode == LocalPlayAccountMode.Offline) account.name else info.lastOfflineName,
                    )
                }
            }.onFailure { lgr.warn(it) { "保存整合包${pack.versionId}的游玩方式失败" } }
            onPlay(pack.toLocalPlayArgs(account))
        }
    }

    fun confirm() {
        error = null
        when (mode) {
            LocalPlayAccountMode.Offline -> OfflineGameAccount.create(nickname)
                .onSuccess(::startWith)
                .onFailure { error = it.message ?: "游戏昵称无效" }

            LocalPlayAccountMode.Online -> {
                val boundMsid = msid ?: run {
                    error = "请先在玩家信息里绑定微软账号"
                    return
                }
                busy = true
                loginJob = scope.launch {
                    onlineService.account(
                        accountId = loggedAccount._id.toHexString(),
                        msid = boundMsid,
                        onDeviceCode = { code ->
                            scope.launch {
                                deviceCode = code
                                runCatching { openMsaVerificationUrl(code.directVerificationUri) }
                                    .onFailure { lgr.warn(it) { "打开微软登录页面失败" } }
                            }
                        },
                    ).onSuccess(::startWith)
                        .onFailure { cause ->
                            lgr.warn(cause) { "正版登录失败" }
                            error = "正版登录失败：${cause.message ?: cause.javaClass.simpleName}"
                        }
                    busy = false
                    deviceCode = null
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text("开始游戏 ${pack.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeOption(
                    selected = mode == LocalPlayAccountMode.Offline,
                    label = "离线模式",
                    enabled = !busy,
                    onSelect = { mode = LocalPlayAccountMode.Offline },
                )
                if (mode == LocalPlayAccountMode.Offline) {
                    RTextField(
                        label = "游戏昵称",
                        value = nickname,
                        enabled = !busy,
                        modifier = Modifier.width(320.dp),
                        onValueChange = { nickname = it },
                    )
                    if (nickname.isNotBlank() && !OfflineGameAccount.isStandardName(nickname)) {
                        Text(
                            "昵称不是3～16位的字母、数字或下划线，单机一般能玩，但部分模组和局域网联机可能出问题",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                ModeOption(
                    selected = mode == LocalPlayAccountMode.Online,
                    label = "正版登录",
                    enabled = !busy && msid != null,
                    onSelect = { mode = LocalPlayAccountMode.Online },
                )
                if (msid == null) {
                    Text(
                        "需要先在玩家信息里绑定微软账号",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (mode == LocalPlayAccountMode.Online) {
                    Text(
                        "使用RDI账号绑定的微软账号进入游戏，首次使用需要在浏览器里登录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                deviceCode?.let { code ->
                    Text("请在浏览器中完成微软登录（10分钟内有效），点击下方链接可复制：")
                    Text(
                        text = code.directVerificationUri,
                        color = MaterialTheme.colorScheme.primary,
                        style = LocalTextStyle.current.copy(textDecoration = TextDecoration.Underline),
                        modifier = Modifier.clickable { copyToClipboard(code.directVerificationUri) },
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = ::confirm) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("开始游戏")
                }
            }
        },
    )
}

@Composable
private fun ModeOption(selected: Boolean, label: String, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
