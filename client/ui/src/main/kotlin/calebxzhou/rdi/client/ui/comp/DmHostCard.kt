package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import calebxzau.rdi.client.ui.comp.NameInitialIcon
import calebxzhou.rdi.common.model.DmHostBrief
import calebxzhou.rdi.common.model.DmHostState

/** An rdi7 test room: left click copies its ID for joining in game, right click deletes it. */
@Composable
fun DmHostCard(
    room: DmHostBrief,
    onCopyId: (DmHostBrief) -> Unit,
    onDelete: (DmHostBrief) -> Unit,
    glass: TileGlass,
    modifier: Modifier = Modifier,
) {
    val name = room.name.ifBlank { "未命名房间" }
    IconTileCard(
        hint = CardHint(
            title = name,
            detail = room.state.label(),
            actions = listOf(
                CardHintAction(HintMouseButton.Left, "复制房间ID"),
                CardHintAction(HintMouseButton.Right, "删除房间"),
            ),
        ),
        modifier = modifier,
        statusDotColor = room.state.dotColor(),
        glass = glass,
        onPrimaryClick = { onCopyId(room) },
        onSecondaryClick = { onDelete(room) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(TileIconShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, TileIconShape),
            contentAlignment = Alignment.Center,
        ) {
            NameInitialIcon(name, Modifier.fillMaxSize())
        }
    }
}

private fun DmHostState.label(): String = when (this) {
    DmHostState.Online -> "房主在线"
    DmHostState.Offline -> "房主离线"
    DmHostState.Unavailable -> "暂时无法确认房间状态"
    DmHostState.Leaving -> "房主正在退出"
}

private fun DmHostState.dotColor(): Color? = when (this) {
    DmHostState.Online -> TILE_ACTIVE_DOT_COLOR
    DmHostState.Leaving -> HOST_STARTING_COLOR
    DmHostState.Offline, DmHostState.Unavailable -> null
}
