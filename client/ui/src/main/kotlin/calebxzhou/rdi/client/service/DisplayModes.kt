package calebxzhou.rdi.client.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.DisplayMode
import java.awt.GraphicsEnvironment

private val logger = KotlinLogging.logger {}

// AWT reads the current mode of every monitor on all platforms without initializing GLFW in the launcher.
fun getDisplayModes(): List<String> {
    return runCatching {
        formatDisplayModes(GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.displayMode })
    }.onFailure { logger.warn(it) { "读取显示器模式失败" } }.getOrElse { emptyList() }
}

internal fun formatDisplayModes(modes: List<DisplayMode>): List<String> =
    modes.map { "${it.width}x${it.height}@${it.refreshRate}" }.distinct()
