package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.ui.CursorPositionBox
import calebxzau.rdi.client.ui.DEFAULT_HOST_ICON
import calebxzau.rdi.client.ui.OffsetFirstItemUnderCursor
import calebxzau.rdi.client.ui.RDropdownMenuItem
import calebxzau.rdi.client.ui.RRow
import calebxzau.rdi.client.ui.Space8w
import calebxzau.rdi.client.ui.baseRoundCornerShape
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.service.rememberPlayerInfoPrefetch
import calebxzhou.rdi.client.ui.screen.UnifiedHostBrief
import calebxzhou.rdi.client.ui.screen.HostTarget
import calebxzhou.rdi.model.Role
import calebxzhou.rdi.common.model.isDav

/** Shared room card. It deliberately accepts the UI projection, not either server DTO. */
@Composable
fun UnifiedHostCard(
    host: UnifiedHostBrief,
    modifier: Modifier = Modifier,
    onClick: (HostTarget) -> Unit,
    onDirectClick: ((UnifiedHostBrief) -> Unit)? = null,
    onPlay: ((UnifiedHostBrief) -> Unit)? = null,
    onOpenMembers: ((HostTarget) -> Unit)? = null,
    onOpenMods: ((HostTarget) -> Unit)? = null,
    onOpenWorld: ((HostTarget) -> Unit)? = null,
    onOpenFiles: ((HostTarget) -> Unit)? = null,
    onOpenBackend: ((HostTarget) -> Unit)? = null,
    onOpenSettings: ((HostTarget) -> Unit)? = null,
    onDelete: ((UnifiedHostBrief) -> Unit)? = null,
    playEnabled: Boolean = true,
    playLoading: Boolean = false,
) {
    var menuExpanded by remember(host.target) { mutableStateOf(false) }
    val isDav = loggedAccount.isDav
    val isOwner = host.role == Role.OWNER || isDav
    val canManage = host.role in setOf(Role.OWNER, Role.ADMIN) || isDav
    val canUseMemberFeatures = host.isMember || isOwner
    rememberPlayerInfoPrefetch(host.onlinePlayerIds)
    val onlinePlayerIds = host.onlinePlayerIds

    CursorPositionBox(
        cursorContent = {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                offset = OffsetFirstItemUnderCursor,
            ) {
                onPlay?.let {
                    RDropdownMenuItem(
                        text = if (playLoading) "启动中..." else "开玩",
                        icon = "\uF04B",
                        enabled = host.playable && playEnabled && !playLoading,
                        onClick = { menuExpanded = false; it(host) },
                    )
                }
                RDropdownMenuItem(
                    text = "详情",
                    icon = "\uF05A",
                    onClick = { menuExpanded = false; onClick(host.target) },
                )
                if (canUseMemberFeatures) {
                    onOpenMembers?.let {
                        RDropdownMenuItem("成员", "\uF0C0", onClick = { menuExpanded = false; it(host.target) })
                    }
                    onOpenMods?.let {
                        RDropdownMenuItem("模组", "\uDB85\uDCD3", onClick = { menuExpanded = false; it(host.target) })
                    }
                    onOpenWorld?.let {
                        RDropdownMenuItem("存档", "\uDB85\uDC5B", onClick = { menuExpanded = false; it(host.target) })
                    }
                }
                if (canManage) {
                    onOpenFiles?.let {
                        RDropdownMenuItem("文件", "\uF07C", onClick = { menuExpanded = false; it(host.target) })
                    }
                    onOpenSettings?.let {
                        RDropdownMenuItem("设置", "\uEAF8", onClick = { menuExpanded = false; it(host.target) })
                    }
                }
                if (canUseMemberFeatures) {
                    onOpenBackend?.let {
                        RDropdownMenuItem("后台", "\uDB80\uDD8D", onClick = { menuExpanded = false; it(host.target) })
                    }
                }
                if (isOwner && onDelete != null) {
                    HorizontalDivider()
                    RDropdownMenuItem(
                        text = "删除",
                        icon = "\uEA81",
                        danger = true,
                        onClick = { menuExpanded = false; onDelete(host) },
                    )
                }
            }
        },
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .clip(baseRoundCornerShape)
                .clickable(
                    enabled = onDirectClick == null || (host.playable && playEnabled && !playLoading),
                ) {
                    onDirectClick?.invoke(host) ?: run { menuExpanded = true }
                },
            color = MaterialTheme.colorScheme.surface,
            shape = baseRoundCornerShape,
            tonalElevation = 1.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (host.playable) 1f else 0.45f)
                    .padding(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    UnifiedHostIcon(host.iconUrl, 48.dp)
                    Space8w()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        RRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            host.ownerId?.let {
                                HeadButton(it, avatarSize = 16.dp, showName = false)
                            }
                            Text(
                                text = host.name.ifBlank { "未命名房间" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            onlinePlayerIds.forEach {
                                HeadButton(it, avatarSize = 16.dp, showName = false)
                            }
                            if (playLoading) {
                                Spacer(Modifier.size(22.dp))
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = listOf(host.modpackName, host.packVersion).filter(String::isNotBlank).joinToString(" "),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (playLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.TopEnd).size(22.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedHostIcon(iconUrl: String?, size: androidx.compose.ui.unit.Dp) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (!iconUrl.isNullOrBlank()) {
            HttpImage(
                imgUrl = iconUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = "房间图标",
                contentScale = ContentScale.Crop,
            )
        } else {
            Image(
                bitmap = DEFAULT_HOST_ICON,
                contentDescription = "房间图标",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
