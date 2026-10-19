package calebxzhou.rdi.client

import calebxzau.rdi.client.ui.window.LinuxCompositor
import calebxzhou.rdi.common.model.LibraryOsArch
import calebxzau.rdi.client.CONF

val WINDOW_TRANSPARENT = windowTransparent(CONF.solidWindow, System.getProperty("rdi.window.transparent"))

// Linux desktops without a compositor draw transparent windows black, so Linux needs one running.
internal fun windowTransparent(
    solidWindow: Boolean,
    jvmProperty: String?,
    osArch: LibraryOsArch = LibraryOsArch.detectHostOs(),
    linuxCompositorRunning: () -> Boolean = LinuxCompositor::isRunningOrFalse,
): Boolean =
    jvmProperty?.toBooleanStrictOrNull() ?: (!solidWindow && (!osArch.isLinux || linuxCompositorRunning()))

// WindowChrome draws its own title bar and resize edges; only a solid Windows window keeps the system frame.
internal fun useSystemWindowDecoration(
    transparent: Boolean,
    osArch: LibraryOsArch = LibraryOsArch.detectHostOs(),
): Boolean = !transparent && osArch.isWindows
