package calebxzau.rdi.client.ui.comp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.ui.SimpleTooltip
import calebxzhou.rdi.client.service.rememberPlayerHeadState
import calebxzhou.rdi.client.ui.comp.PlayerHead
import org.bson.types.ObjectId

/** 圆角与描边按这个边长设计，其他尺寸等比缩放。 */
private val BASE_HEAD_BADGE_SIZE = 18.dp

/** Player head, [size] wide, with a surface-colored ring that keeps it readable on any background; hover shows the name. */
@Composable
fun PlayerHeadBadge(uid: ObjectId, size: Dp, modifier: Modifier = Modifier, tooltipPrefix: String = "") {
    val state = rememberPlayerHeadState(uid)
    // 圆角与描边随头像尺寸等比缩放
    val scale = size / BASE_HEAD_BADGE_SIZE
    val badgeShape = RoundedCornerShape(4.dp * scale)
    Box(modifier) {
        SimpleTooltip("${tooltipPrefix}${state.name}") {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(badgeShape)
                    .background(MaterialTheme.colorScheme.surface, badgeShape)
                    .padding(1.5.dp * scale),
            ) {
                PlayerHead(
                    skinImage = state.skinImage,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp * scale)),
                )
            }
        }
    }
}
