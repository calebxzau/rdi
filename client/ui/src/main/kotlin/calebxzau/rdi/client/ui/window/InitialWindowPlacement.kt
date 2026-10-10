package calebxzau.rdi.client.ui.window

import androidx.compose.ui.window.WindowPlacement
import oshi.SystemInfo
import oshi.util.EdidUtil
import kotlin.math.sqrt

/** 逻辑屏幕高度低于此值时（如1080p@125%缩放），2/3窗口过小，直接最大化 */
const val MAXIMIZE_LOGICAL_HEIGHT_THRESHOLD = 900

/** EDID物理对角线不超过此值时，视为笔记本内屏 */
const val LAPTOP_DIAGONAL_INCHES_THRESHOLD = 17.3

private const val CM_PER_INCH = 2.54

/**
 * 仅在只有一块显示器时读取其EDID物理对角线（英寸）。
 * 多块显示器无法与主屏对应，或EDID未提供物理尺寸时返回null。
 */
fun readSingleDisplayDiagonalInches(): Result<Double?> = runCatching {
    val displays = SystemInfo().hardware.displays
    if (displays.size != 1) return@runCatching null
    val edid = displays.single().edid
    val widthCm = EdidUtil.getHcm(edid)
    val heightCm = EdidUtil.getVcm(edid)
    if (widthCm <= 0 || heightCm <= 0) return@runCatching null
    sqrt((widthCm * widthCm + heightCm * heightCm).toDouble()) / CM_PER_INCH
}

fun shouldStartMaximized(logicalScreenHeight: Int, diagonalInches: Double?): Boolean {
    if (logicalScreenHeight < MAXIMIZE_LOGICAL_HEIGHT_THRESHOLD) return true
    return diagonalInches != null && diagonalInches <= LAPTOP_DIAGONAL_INCHES_THRESHOLD
}

fun initialWindowPlacement(logicalScreenHeight: Int, diagonalInches: Double?): WindowPlacement =
    if (shouldStartMaximized(logicalScreenHeight, diagonalInches)) {
        WindowPlacement.Maximized
    } else {
        WindowPlacement.Floating
    }
