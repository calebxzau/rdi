package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import calebxzau.rdi.client.ui.AlertErr
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ConfirmDialog
import calebxzau.rdi.client.ui.RColumn
import calebxzau.rdi.client.ui.RDropdownMenuItem
import calebxzau.rdi.client.ui.RRow
import calebxzau.rdi.client.ui.Space8w
import calebxzau.rdi.client.ui.asIconText
import calebxzau.rdi.client.ui.baseRoundCornerShape
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.rdiRequest
import calebxzhou.rdi.client.net.rdiRequestU
import calebxzhou.rdi.client.service.rememberPlayerInfoPrefetch
import calebxzau.rdi.client.ui.themeNow
import calebxzhou.rdi.client.ui.comp.HeadButton
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.isDav
import calebxzhou.rdi.model.Role
import io.ktor.http.HttpMethod
import org.bson.types.ObjectId

/** Members row of the host info screen; owns its own member list loading and dialogs. */
@Composable
internal fun HostMembersPane(
    hostId: ObjectId,
    snackbarHostState: SnackbarHostState,
    onQuit: () -> Unit,
    onHostChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf<Host.DetailVo?>(null) }
    var members by remember { mutableStateOf<List<Host.Member>>(emptyList()) }
    var loadingHost by remember { mutableStateOf(true) }
    var loadingMembers by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var okMessage by remember { mutableStateOf<String?>(null) }
    var openedMemberId by remember { mutableStateOf<ObjectId?>(null) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var inviteQq by remember { mutableStateOf("") }
    var roleChangeConfirm by remember { mutableStateOf<MemberRoleChange?>(null) }
    var transferConfirm by remember { mutableStateOf<ObjectId?>(null) }
    var kickConfirm by remember { mutableStateOf<ObjectId?>(null) }
    var quitConfirm by remember { mutableStateOf(false) }

    fun loadHost() {
        loadingHost = true
        scope.rdiRequest<Host.DetailVo>(
            path = "host/$hostId/detail",
            onOk = { host = it.data },
            onErr = { errorMessage = it.message ?: "无法加载房间信息" },
            onDone = { loadingHost = false }
        )
    }

    fun loadMembers() {
        loadingMembers = true
        scope.rdiRequest<List<Host.Member>>(
            path = "host/$hostId/members",
            onOk = { members = it.data.orEmpty().sortedBy { member -> member.role.level } },
            onErr = { errorMessage = it.message ?: "无法加载成员名单" },
            onDone = { loadingMembers = false }
        )
    }

    fun reload() {
        loadHost()
        loadMembers()
    }

    LaunchedEffect(hostId) { reload() }
    LaunchedEffect(okMessage) {
        okMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            okMessage = null
        }
    }

    val currentHost = host
    val meRole = members.firstOrNull { it.id == loggedAccount._id }?.role
    val meOwner = currentHost?.ownerId == loggedAccount._id || loggedAccount.isDav
    val meAdmin = meOwner || meRole == Role.OWNER || meRole == Role.ADMIN
    val canView = meRole != null || meOwner

    rememberPlayerInfoPrefetch(members.map { it.id })

    errorMessage?.let { AlertErr(it) { errorMessage = null } }

    when {
        loadingHost || loadingMembers -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        currentHost == null -> Text("无法加载房间信息", color = MaterialTheme.colorScheme.error)
        !canView -> Text("仅房间成员可查看成员名单", color = MaterialTheme.colorScheme.error)
        else ->
            RColumn {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("成员列表", fontWeight = FontWeight.Bold)
                    Text("仅房间成员可随时启动房间")
                }
                RRow(modifier = Modifier.fillMaxWidth()) {
                    members.forEach { member ->
                        key(member.id) {
                            val canKick = member.role != Role.OWNER &&
                                    (meOwner || member.role.level > Role.ADMIN.level)
                            val canOpenMenu = (meOwner && member.role != Role.OWNER) ||
                                    canKick || (!meOwner && member.id == loggedAccount._id)
                            Box {
                                Surface(
                                    // Clip before clickable so the hover/press highlight follows the rounded corners.
                                    modifier = Modifier
                                        .clip(baseRoundCornerShape)
                                        .then(
                                            if (canOpenMenu) Modifier.clickable { openedMemberId = member.id }
                                            else Modifier
                                        ),
                                    shape = baseRoundCornerShape,
                                    color = MaterialTheme.colorScheme.surfaceContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        HeadButton(
                                            uid = member.id,
                                            avatarSize = 18.dp,
                                            nameFontSize = 16.sp,
                                            showName = true,
                                        )
                                        Box(
                                            modifier = Modifier
                                                .background(roleColor(member.role), baseRoundCornerShape)
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "${roleIcon(member.role).asIconText} ${roleLabel(member.role)}",
                                                color = Color.White,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                                DropdownMenu(
                                    expanded = openedMemberId == member.id,
                                    onDismissRequest = { openedMemberId = null }
                                ) {
                                    if (meOwner && member.role != Role.OWNER) {
                                        RDropdownMenuItem("转让房间", "\uF416", onClick = {
                                            openedMemberId = null
                                            transferConfirm = member.id
                                        })
                                        RDropdownMenuItem(
                                            text = if (member.role == Role.ADMIN) "取消管理员" else "设为管理员",
                                            icon = "\uEFA6",
                                            onClick = {
                                                openedMemberId = null
                                                val role = if (member.role == Role.ADMIN) Role.MEMBER else Role.ADMIN
                                                roleChangeConfirm = MemberRoleChange(member.id, role)
                                            }
                                        )
                                    }
                                    if (canKick) {
                                        RDropdownMenuItem("踢出成员", "\uEE8B", danger = true, onClick = {
                                            openedMemberId = null
                                            kickConfirm = member.id
                                        })
                                    }
                                    if (!meOwner && member.id == loggedAccount._id) {
                                        RDropdownMenuItem("退出房间", "\uEF69", danger = true, onClick = {
                                            openedMemberId = null
                                            quitConfirm = true
                                        })
                                    }
                                }
                            }
                        }
                    }
                    if (meAdmin) {
                        CircleIconButton(
                            icon = "\uF067",
                            label = "邀请",
                        ) {
                            inviteQq = ""
                            showInviteDialog = true
                        }
                    }
                }
        }
    }

    if (showInviteDialog) {
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("邀请成员") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入对方QQ号：")
                    OutlinedTextField(inviteQq, { inviteQq = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val qq = inviteQq.trim()
                    if (qq.isBlank()) {
                        errorMessage = "QQ不能为空"
                        return@TextButton
                    }
                    scope.rdiRequestU(
                        path = "host/$hostId/member/$qq",
                        method = HttpMethod.Post,
                        onOk = {
                            okMessage = "已发送邀请"
                            loadMembers()
                        },
                        onErr = { errorMessage = it.message ?: "邀请失败" }
                    )
                    showInviteDialog = false
                }) { Text("邀请") }
            },
            dismissButton = { TextButton(onClick = { showInviteDialog = false }) { Text("取消") } }
        )
    }

    roleChangeConfirm?.let { change ->
        ConfirmDialog(
            title = "确认操作",
            message = if (change.role == Role.ADMIN) "确定设置该成员为管理员？" else "确定取消管理员身份？",
            onConfirm = {
                scope.rdiRequestU(
                    path = "host/$hostId/member/${change.memberId}/role/${change.role.name}",
                    method = HttpMethod.Put,
                    onOk = {
                        okMessage = "已更新"
                        loadMembers()
                    },
                    onErr = { errorMessage = it.message ?: "操作失败" }
                )
                roleChangeConfirm = null
            },
            onDismiss = { roleChangeConfirm = null }
        )
    }

    transferConfirm?.let { memberId ->
        ConfirmDialog(
            title = "确认转让",
            message = "确定将房间所有权转让给该成员吗？",
            onConfirm = {
                scope.rdiRequestU(
                    path = "host/$hostId/transfer/$memberId",
                    method = HttpMethod.Post,
                    onOk = {
                        okMessage = "已转让"
                        reload()
                        onHostChanged()
                    },
                    onErr = { errorMessage = it.message ?: "转让失败" }
                )
                transferConfirm = null
            },
            onDismiss = { transferConfirm = null }
        )
    }

    kickConfirm?.let { memberId ->
        ConfirmDialog(
            title = "确认踢出",
            message = "要踢出该成员吗？",
            onConfirm = {
                scope.rdiRequestU(
                    path = "host/$hostId/member/$memberId",
                    method = HttpMethod.Delete,
                    onOk = {
                        okMessage = "已踢出"
                        loadMembers()
                    },
                    onErr = { errorMessage = it.message ?: "踢出失败" }
                )
                kickConfirm = null
            },
            onDismiss = { kickConfirm = null }
        )
    }

    if (quitConfirm) {
        ConfirmDialog(
            title = "退出房间",
            message = "确定退出该房间吗？",
            onConfirm = {
                scope.rdiRequestU(
                    path = "host/$hostId/quit",
                    method = HttpMethod.Put,
                    onOk = {onQuit()},
                    onErr = { errorMessage = it.message ?: "退出失败" }
                )
                quitConfirm = false
            },
            onDismiss = { quitConfirm = false }
        )
    }
}


private data class MemberRoleChange(val memberId: ObjectId, val role: Role)

private fun roleLabel(role: Role) = when (role) {
    Role.OWNER -> "所有者"
    Role.ADMIN -> "管理员"
    else -> "成员"
}

private fun roleIcon(role: Role) = when (role) {
    Role.OWNER -> "\uEDEB"
    Role.ADMIN -> "\uEFA6"
    else -> "\uEF0C"
}

@Composable
private fun roleColor(role: Role) = when (role) {
    Role.OWNER -> themeNow.tertiary
    Role.ADMIN -> Color(0xFFC0C0C0)
    Role.MEMBER -> Color(0xFFCD7F32)
    else -> themeNow.surfaceVariant
}
