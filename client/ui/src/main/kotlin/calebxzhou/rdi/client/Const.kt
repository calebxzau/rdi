package calebxzhou.rdi.client

import calebxzau.rdi.client.ui.window.LinuxCompositor
import calebxzhou.rdi.common.model.LibraryOsArch
import calebxzau.rdi.client.CONF
import org.bson.types.ObjectId

object Const {

    const val MODID = "rdi"
    var USE_MOCK_DATA = System.getProperty("rdi.mockData").toBoolean()
    var NO_UPDATE = System.getProperty("rdi.noUpdate").toBoolean()
    val WINDOW_TRANSPARENT = windowTransparent(CONF.solidWindow, System.getProperty("rdi.window.transparent"))
    val SEED = 1145141919810L
    val DEFAULT_MODPACK_ID = ObjectId("abcdefabcdefabcdefabcdef")
    //显示版本
    val VERSION_NUMBER: String = loadVersionNumber()


    private fun loadVersionNumber(): String {
        val manifestVersion = Const::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() }
        return manifestVersion
            ?: System.getProperty("rdi.version")
            ?: "dev"
    }



}

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
