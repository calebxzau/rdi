package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import calebxzau.rdi.client.ui.comp.NameInitialIcon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.ui.asIconText
import calebxzau.rdi.client.ui.baseRoundCornerShape
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.util.toFriendlyDateTime
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * calebxzhou @ 2026-01-13 16:14
 */

/**
 * The small amount of information shared by legacy and new public pack cards.
 *
 * Keeping this presentation model local to the client means a new source can reuse the
 * established card without pretending that its identifier is a legacy ObjectId.
 */
data class ModpackCardPresentation(
    val name: String,
    val iconUrl: String?,
    val intro: String?,
    /** A source-neutral activity value, such as a play count or play duration. */
    val activityText: String,
    val updatedTimeText: String? = null,
)

internal fun Modpack.BriefVo.toModpackCardPresentation(): ModpackCardPresentation =
    ModpackCardPresentation(
        name = name,
        iconUrl = icon,
        intro = info,
        activityText = playCount.toCompactCountText(),
        updatedTimeText = formatModpackUpdatedTime(
            lastUpdatedTime.takeIf { it > 0L } ?: (id.timestamp.toLong() * 1000L),
        ),
    )

internal fun formatModpackUpdatedTime(
    timestampMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val targetDate = Instant.ofEpochMilli(timestampMillis).atZone(zoneId).toLocalDate()
    val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    return timestampMillis.toFriendlyDateTime(
        nowMillis = nowMillis,
        zoneId = zoneId,
        dateOnly = targetDate != nowDate,
    )
}

internal fun formatModpackCardSupportingText(presentation: ModpackCardPresentation): String =
    buildList {
        add("\uDB80\uDE97")
        presentation.activityText.trim().takeIf(String::isNotBlank)?.let(::add)
        presentation.updatedTimeText?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        add(presentation.intro?.trim()?.takeIf(String::isNotBlank) ?: "暂无简介")
    }.joinToString("  ")

@Composable
fun Modpack.BriefVo.ModpackCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    ModpackCard(
        presentation = toModpackCardPresentation(),
        modifier = modifier,
        compact = compact,
        onClick = onClick,
    )
}

/** [compact] shows only a smaller icon and the name, for tight spots such as title rows. */
@Composable
fun ModpackCard(
    presentation: ModpackCardPresentation,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val clickableModifier = if (onClick != null) {
        modifier
            .clip(baseRoundCornerShape)
            .clickable(onClick = onClick)
    } else {
        modifier
    }

    Surface(
        modifier = clickableModifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = baseRoundCornerShape,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
            verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top
        ) {
            val iconUrl = presentation.iconUrl?.takeIf { it.isNotBlank() }
            Surface(
                modifier = Modifier.size(if (compact) 32.dp else 54.dp),
                shape = RoundedCornerShape(if (compact) 10.dp else 14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                if (iconUrl != null) {
                    HttpImage(
                        imgUrl = iconUrl,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = presentation.name,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    NameInitialIcon(presentation.name, Modifier.fillMaxSize())
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Text(
                    text = presentation.name.ifBlank { "未命名整合包" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!compact) {
                    Text(
                        text = formatModpackCardSupportingText(presentation).asIconText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Formats the server's accumulated play duration for a compact player-facing card.
 *
 * Negative values are treated as zero because the public statistic is cumulative and should not
 * make the card render an invalid duration if an older record is malformed.
 */
internal fun formatPlayTime(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val minutes = safeSeconds / 60L
    val hours = safeSeconds / 3_600L
    return when {
        safeSeconds < 60L -> "${safeSeconds}秒"
        safeSeconds < 3_600L -> "${minutes}分${safeSeconds % 60L}秒"
        else -> "${hours}小时${(safeSeconds % 3_600L) / 60L}分"
    }
}

private fun Int.toCompactCountText(): String {
    val safeValue = coerceAtLeast(0)
    return when {
        safeValue >= 100_000_000 -> compactUnitText(safeValue / 100_000_000.0, "亿")
        safeValue >= 10_000 -> compactUnitText(safeValue / 10_000.0, "万")
        else -> safeValue.toString()
    }
}

private fun compactUnitText(value: Double, suffix: String): String {
    val scaledTenths = (value * 10).roundToInt()
    val whole = scaledTenths / 10
    val decimal = scaledTenths % 10
    val formatted = if (value >= 100 || decimal == 0) {
        whole.toString()
    } else {
        "$whole.$decimal"
    }
    return formatted + suffix
}
