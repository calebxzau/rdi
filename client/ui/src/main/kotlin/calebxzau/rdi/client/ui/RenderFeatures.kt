package calebxzau.rdi.client.ui

import calebxzhou.rdi.common.model.LibraryOsArch

/**
 * Whether the launcher may start its OpenGL renderers (animated background, 3D player model).
 * macOS only allows GLFW on the main thread, which AWT owns, so the Mac launcher shows the static
 * background and a 2D head instead.
 */
object RenderFeatures {
    val available: Boolean = availableOn(LibraryOsArch.detectHostOs())

    internal fun availableOn(osArch: LibraryOsArch): Boolean = !osArch.isMac
}
