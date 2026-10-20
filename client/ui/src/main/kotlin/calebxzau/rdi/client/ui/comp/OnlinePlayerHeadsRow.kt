package calebxzau.rdi.client.ui.comp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import calebxzhou.rdi.client.service.rememberPlayerInfoPrefetch
import org.bson.types.ObjectId

private val ONLINE_PLAYER_HEAD_SIZE = 24.dp

/** 最多显示这么多个头像，其余的折叠成“+N”。 */
private const val MAX_SHOWN_ONLINE_PLAYERS = 20

/** 所有房间里正在游戏的玩家头像排成一行；没人在线时什么都不画。 */
@Composable
fun OnlinePlayerHeadsRow(playerIds: List<ObjectId>, modifier: Modifier = Modifier) {
    if (playerIds.isEmpty()) return
    val shown = playerIds.take(MAX_SHOWN_ONLINE_PLAYERS)
    rememberPlayerInfoPrefetch(shown)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { uid ->
            PlayerHeadBadge(uid, ONLINE_PLAYER_HEAD_SIZE)
        }
        val hidden = playerIds.size - shown.size
        if (hidden > 0) {
            Text("+${hidden}", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}
