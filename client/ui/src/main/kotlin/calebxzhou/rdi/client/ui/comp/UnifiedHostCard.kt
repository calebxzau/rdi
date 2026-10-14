package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.ui.SimpleTooltip
import calebxzau.rdi.client.ui.comp.NameInitialIcon
import calebxzhou.rdi.client.service.rememberPlayerHeadState
import calebxzhou.rdi.client.service.rememberPlayerInfoPrefetch
import calebxzhou.rdi.client.ui.screen.UnifiedHostBrief
import calebxzhou.rdi.client.ui.screen.HostTarget
import calebxzhou.rdi.common.model.HostStatus
import org.bson.types.ObjectId

/** Shared room tile (icon on top, name below): left click plays, right click opens details; a cursor-following hint (drawn by [CardHintHost]) explains both. */
@Composable
fun UnifiedHostCard(
    host: UnifiedHostBrief,
    modifier: Modifier = Modifier,
    onPlay: (UnifiedHostBrief) -> Unit,
    onOpenDetail: ((HostTarget) -> Unit)? = null,
    playEnabled: Boolean = true,
    playLoading: Boolean = false,
) {
    rememberPlayerInfoPrefetch(host.onlinePlayerIds)
    val canPlay = host.playable && playEnabled && !playLoading
    val hostName = host.name.ifBlank { "未命名房间" }
    val hint = CardHint(
        title = hostName,
        detail = host.modpackName,
        detailVersion = host.packVersion,
        ownerId = host.ownerId,
        actions = listOfNotNull(
            CardHintAction(HintMouseButton.Left, if (playLoading) "启动中..." else "开玩", active = canPlay),
            onOpenDetail?.let { CardHintAction(HintMouseButton.Right, "详情 设置") },
        ),
    )
    IconTileCard(
        title = hostName,
        hint = hint,
        modifier = modifier,
        statusDotColor = host.status.statusDotColor(),
        dimmed = !host.playable,
        primaryEnabled = canPlay,
        onPrimaryClick = { onPlay(host) },
        onSecondaryClick = onOpenDetail?.let { { it(host.target) } },
    ) {
        UnifiedHostIcon(
            iconUrl = host.iconUrl,
            fallbackName = host.modpackName.ifBlank { hostName },
            onlinePlayerIds = host.onlinePlayerIds,
            playLoading = playLoading,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private val HOST_STARTING_COLOR = Color(0xFFFFC107)

/** Green when players can join right now, yellow while the room is still booting; no dot otherwise. */
private fun HostStatus.statusDotColor(): Color? = when (this) {
    HostStatus.PLAYABLE -> TILE_ACTIVE_DOT_COLOR
    HostStatus.STARTED -> HOST_STARTING_COLOR
    else -> null
}

@Composable
private fun UnifiedHostIcon(
    iconUrl: String?,
    fallbackName: String,
    onlinePlayerIds: List<ObjectId>,
    playLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                NameInitialIcon(fallbackName, Modifier.fillMaxSize())
            }
            if (playLoading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        }
        // A room holds at most 10 players, so every online head fits; extra heads wrap downward.
        if (onlinePlayerIds.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .offset(x = (-3).dp, y = (-3).dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                onlinePlayerIds.forEach { uid ->
                    HeadBadge(uid)
                }
            }
        }
    }
}

/** Player head pinned onto the icon; the surface-colored ring keeps it readable on any icon. */
@Composable
private fun HeadBadge(uid: ObjectId, modifier: Modifier = Modifier) {
    val state = rememberPlayerHeadState(uid)
    val badgeShape = RoundedCornerShape(4.dp)
    Box(modifier) {
        SimpleTooltip(state.name) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(badgeShape)
                    .background(MaterialTheme.colorScheme.surface, badgeShape)
                    .padding(1.5.dp),
            ) {
                PlayerHead(
                    skinImage = state.skinImage,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}
