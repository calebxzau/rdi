package calebxzhou.rdi.client.service

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import calebxzhou.rdi.client.model.UiMod
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Broad color groups used by ContentGrid's icon-color sort. */
enum class ModIconColorCategory(val order: Int) {
    CHROMATIC(0),
    ACHROMATIC(1),
    MISSING(2)
}

/** Hue bands are kept separate from the broad category for deterministic icon ordering. */
enum class ModIconColorBand(val order: Int) {
    RED(0),
    ORANGE(1),
    YELLOW(2),
    GREEN(3),
    CYAN(4),
    BLUE(5),
    PURPLE(6),
    PINK(7),
    NONE(8)
}

data class ModIconColorSortKey(
    val category: ModIconColorCategory,
    val band: ModIconColorBand,
    val hue: Float,
    val saturation: Float,
    val value: Float
)

internal val MISSING_MOD_ICON_COLOR_SORT_KEY = ModIconColorSortKey(
    category = ModIconColorCategory.MISSING,
    band = ModIconColorBand.NONE,
    hue = 0f,
    saturation = 0f,
    value = 0f
)

private const val ICON_SAMPLE_SIDE = 32
private const val MIN_CHROMA = 0.15f
private const val MIN_ALPHA = 0.5f

/**
 * Classifies visible pixels in the full centered square of an icon. The square is sampled with a
 * stride so large source images have approximately the same work as a 32x32 icon.
 */
fun classifyModIconColor(bitmap: ImageBitmap?): Result<ModIconColorSortKey> = runCatching {
    if (bitmap == null) return@runCatching MISSING_MOD_ICON_COLOR_SORT_KEY

    val squareSide = min(bitmap.width, bitmap.height)
    if (squareSide <= 0) return@runCatching MISSING_MOD_ICON_COLOR_SORT_KEY

    val startX = (bitmap.width - squareSide) / 2
    val startY = (bitmap.height - squareSide) / 2
    val stride = ceil(squareSide / ICON_SAMPLE_SIDE.toFloat()).toInt().coerceAtLeast(1)
    val pixels = bitmap.toPixelMap(
        startX = startX,
        startY = startY,
        width = squareSide,
        height = squareSide
    )
    val hueBuckets = Array(ModIconColorBand.entries.size - 1) { HueAccumulator() }
    var visibleWeight = 0f
    var visibleValueWeight = 0f
    var chromaticWeight = 0f

    for (offsetY in 0 until squareSide step stride) {
        for (offsetX in 0 until squareSide step stride) {
            val color = pixels[offsetX, offsetY]
            val alpha = color.alpha
            if (alpha < MIN_ALPHA) continue

            val red = color.red
            val green = color.green
            val blue = color.blue
            val maxChannel = max(red, max(green, blue))
            val minChannel = min(red, min(green, blue))
            val chroma = maxChannel - minChannel
            val saturation = if (maxChannel <= 0f) 0f else chroma / maxChannel
            val value = maxChannel
            visibleWeight += alpha
            visibleValueWeight += alpha * value
            if (saturation < MIN_CHROMA) continue

            val hue = hueDegrees(red, green, blue, maxChannel, chroma)
            val bucket = hueBucket(hue)
            val weight = alpha * saturation
            chromaticWeight += weight
            hueBuckets[bucket].add(hue, saturation, value, weight)
        }
    }

    if (visibleWeight <= 0f) return@runCatching MISSING_MOD_ICON_COLOR_SORT_KEY
    if (chromaticWeight <= 0f) {
        return@runCatching ModIconColorSortKey(
            category = ModIconColorCategory.ACHROMATIC,
            band = ModIconColorBand.NONE,
            hue = 0f,
            saturation = 0f,
            value = visibleValueWeight / visibleWeight
        )
    }

    var dominantBucket = 0
    var dominantWeight = 0f
    for (index in hueBuckets.indices) {
        if (hueBuckets[index].weight > dominantWeight) {
            dominantBucket = index
            dominantWeight = hueBuckets[index].weight
        }
    }
    val dominant = hueBuckets[dominantBucket]
    ModIconColorSortKey(
        category = ModIconColorCategory.CHROMATIC,
        band = ModIconColorBand.entries[dominantBucket],
        hue = dominant.meanHue,
        saturation = dominant.saturation,
        value = dominant.value
    )
}

/** Sorts an already analyzed snapshot without resolving icons or changing its contents. */
fun sortModsByIconColor(
    mods: List<UiMod>,
    colors: Map<String, ModIconColorSortKey>
): List<UiMod> {
    return mods.sortedWith { left, right ->
        val leftKey = colors[left.key] ?: MISSING_MOD_ICON_COLOR_SORT_KEY
        val rightKey = colors[right.key] ?: MISSING_MOD_ICON_COLOR_SORT_KEY
        compareColorKeys(leftKey, rightKey)
            .takeIf { it != 0 }
            ?: comparePrimaryNames(left, right)
    }
}

private fun compareColorKeys(
    left: ModIconColorSortKey,
    right: ModIconColorSortKey
): Int {
    return left.category.order.compareTo(right.category.order)
        .takeIf { it != 0 }
        ?: left.band.order.compareTo(right.band.order)
            .takeIf { it != 0 }
        ?: left.hue.compareTo(right.hue)
            .takeIf { it != 0 }
        ?: right.saturation.compareTo(left.saturation)
            .takeIf { it != 0 }
        ?: right.value.compareTo(left.value)
}

private fun comparePrimaryNames(left: UiMod, right: UiMod): Int {
    val insensitive = String.CASE_INSENSITIVE_ORDER.compare(left.primaryName, right.primaryName)
    if (insensitive != 0) return insensitive
    val exact = left.primaryName.compareTo(right.primaryName)
    if (exact != 0) return exact
    return left.key.compareTo(right.key)
}

private class HueAccumulator {
    var weight: Float = 0f
        private set
    private var hueSin: Float = 0f
    private var hueCos: Float = 0f
    private var saturationWeight: Float = 0f
    private var valueWeight: Float = 0f

    val meanHue: Float
        get() {
            val degrees = Math.toDegrees(atan2(hueSin.toDouble(), hueCos.toDouble())).toFloat()
            return if (degrees < 0f) degrees + 360f else degrees
        }

    val saturation: Float
        get() = if (weight <= 0f) 0f else saturationWeight / weight

    val value: Float
        get() = if (weight <= 0f) 0f else valueWeight / weight

    fun add(hue: Float, saturation: Float, value: Float, sampleWeight: Float) {
        val radians = Math.toRadians(hue.toDouble())
        weight += sampleWeight
        hueSin += sin(radians).toFloat() * sampleWeight
        hueCos += cos(radians).toFloat() * sampleWeight
        saturationWeight += saturation * sampleWeight
        valueWeight += value * sampleWeight
    }
}

private fun hueDegrees(
    red: Float,
    green: Float,
    blue: Float,
    maxChannel: Float,
    chroma: Float
): Float {
    val hue = when (maxChannel) {
        red -> (green - blue) / chroma
        green -> (blue - red) / chroma + 2f
        else -> (red - green) / chroma + 4f
    } * 60f
    return if (hue < 0f) hue + 360f else hue
}

private fun hueBucket(hue: Float): Int = when {
    hue < 15f || hue >= 345f -> 0 // red
    hue < 45f -> 1 // orange
    hue < 75f -> 2 // yellow
    hue < 165f -> 3 // green
    hue < 195f -> 4 // cyan
    hue < 255f -> 5 // blue
    hue < 300f -> 6 // purple
    else -> 7 // pink
}
