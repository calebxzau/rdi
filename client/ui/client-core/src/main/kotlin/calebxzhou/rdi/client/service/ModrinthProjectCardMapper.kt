package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.model.ModrinthProjectCardVo
import calebxzhou.rdi.client.model.ModrinthProjectCategoryVo
import calebxzhou.rdi.common.model.ModrinthSearchHit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

fun ModrinthSearchHit.toModrinthProjectCardVo(): ModrinthProjectCardVo {
    val selectedCategories = displayCategories.ifEmpty { categories }.take(4)
    val loaderIds = categories.filter { it.lowercase() in modrinthLoaderCategoryIds }
    return ModrinthProjectCardVo(
        projectId = projectId,
        projectType = projectType,
        slug = slug,
        title = title,
        author = author,
        description = description?.takeIf(String::isNotBlank) ?: "暂无简介",
        iconUrl = iconUrl,
        bannerUrl = featuredGallery ?: gallery.firstOrNull(),
        categories = selectedCategories.map { ModrinthProjectCategoryVo(it, it.toModrinthProjectCategoryLabel()) },
        downloadsText = downloads.toCompactCountText(),
        followsText = follows.toSeparatedCountText(),
        modifiedText = dateModified.toRelativeTimeText(),
        latestVersionId = latestVersion,
        gameVersions = versions,
        loaders = loaderIds,
        clientSide = clientSide,
        serverSide = serverSide
    )
}

private val modrinthLoaderCategoryIds = setOf(
    "babric",
    "fabric",
    "forge",
    "liteloader",
    "neoforge",
    "quilt",
    "rift"
)

internal fun Long.toCompactCountText(): String = when {
    abs(this) >= 1_000_000_000 -> "${formatCompact(this / 1_000_000_000.0)}B"
    abs(this) >= 1_000_000 -> "${formatCompact(this / 1_000_000.0)}M"
    abs(this) >= 1_000 -> "${formatCompact(this / 1_000.0)}K"
    else -> toSeparatedCountText()
}

internal fun Long.toSeparatedCountText(): String {
    val raw = abs(this).toString()
    val grouped = raw.reversed().chunked(3).joinToString(",").reversed()
    return if (this < 0) "-$grouped" else grouped
}

private fun formatCompact(value: Double): String {
    val scaled = (value * 100).roundToInt()
    val whole = scaled / 100
    val fraction = abs(scaled % 100)
    return when {
        fraction == 0 -> whole.toString()
        fraction % 10 == 0 -> "$whole.${fraction / 10}"
        else -> "$whole.${fraction.toString().padStart(2, '0')}"
    }
}

@OptIn(ExperimentalTime::class)
internal fun String.toRelativeTimeText(): String {
    val instant = runCatching { kotlin.time.Instant.parse(this) }.getOrNull() ?: return "未知"
    val elapsedMillis = (Clock.System.now().toEpochMilliseconds() - instant.toEpochMilliseconds()).coerceAtLeast(0)
    val minutes = elapsedMillis / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days >= 365 -> "${days / 365}年前"
        days >= 30 -> "${days / 30}个月前"
        days >= 1 -> "${days}天前"
        hours >= 1 -> "${hours}小时前"
        minutes >= 1 -> "${minutes}分钟前"
        else -> "刚刚"
    }
}

internal fun String.toModrinthProjectCategoryLabel(): String =
    when (lowercase()) {
        "iris" -> "Iris"
        "optifine" -> "OptiFine"
        "pbr" -> "PBR"
        else -> split('-')
            .filter(String::isNotBlank)
            .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
    }
