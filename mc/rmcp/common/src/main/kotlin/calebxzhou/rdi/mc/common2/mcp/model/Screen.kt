package calebxzhou.rdi.mc.common2.mcp.model

import kotlinx.serialization.Serializable

/** One clickable element of the open screen, in GUI-scaled coordinates. */
@Serializable
data class ScreenWidget(
    val id: Int,
    val type: String,
    val text: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val active: Boolean,
    val visible: Boolean,
    val focused: Boolean = false,
    /** Edit box text, checkbox state, or similar current value. */
    val value: String? = null,
) {
    override fun toString() = buildString {
        append("#$id $type \"$text\" at $x,$y ${width}x$height")
        value?.let { append(" value=\"$it\"") }
        if (!active) append(" inactive")
        if (!visible) append(" hidden")
        if (focused) append(" focused")
    }
}

@Serializable
data class ScreenSnapshot(
    /** Changes whenever the screen or its set of widgets changes; widget ids are only valid for this version. */
    val version: Long,
    /** Screen class name, or null when no screen is open. */
    val screen: String?,
    val title: String,
    val guiWidth: Int,
    val guiHeight: Int,
    /** Screenshot pixels divided by this give GUI coordinates. */
    val guiScale: Double,
    val widgets: List<ScreenWidget>,
) {
    override fun toString() = buildString {
        appendLine("version $version")
        if (screen == null) {
            appendLine("no screen open (in world or loading); screen_key Escape opens the pause menu")
        } else {
            appendLine("screen $screen \"$title\"")
        }
        appendLine("gui ${guiWidth}x$guiHeight scale $guiScale")
        widgets.forEach { appendLine(it) }
    }.trimEnd()
}

@Serializable
data class ScreenClickQ(
    @McpParam("Screen version from the latest screen_read.")
    val version: Long,
    @McpParam("Widget id from the latest screen_read.", minimum = 0)
    val widget: Int,
    @McpParam("Mouse button: 0 left, 1 right, 2 middle.", minimum = 0, maximum = 2)
    val button: Int = 0,
)

@Serializable
data class ScreenClickAtQ(
    @McpParam("GUI-scaled x coordinate (screenshot pixel x divided by guiScale).")
    val x: Double,
    @McpParam("GUI-scaled y coordinate (screenshot pixel y divided by guiScale).")
    val y: Double,
    @McpParam("Mouse button: 0 left, 1 right, 2 middle.", minimum = 0, maximum = 2)
    val button: Int = 0,
)

@Serializable
data class ScreenTypeQ(
    @McpParam("Screen version from the latest screen_read.")
    val version: Long,
    @McpParam("Edit box widget id from the latest screen_read.", minimum = 0)
    val widget: Int,
    @McpParam("Text to type.")
    val text: String,
    @McpParam("Clear the edit box before typing.")
    val replace: Boolean = true,
)

@Serializable
enum class ScreenKey(val glfwCode: Int) {
    Escape(256),
    Enter(257),
    Tab(258),
    Backspace(259),
    Delete(261),
    Right(262),
    Left(263),
    Down(264),
    Up(265),
    PageUp(266),
    PageDown(267),
    Home(268),
    End(269),
}

@Serializable
data class ScreenKeyQ(
    @McpParam("Key to press and release on the open screen.")
    val key: ScreenKey,
)

@Serializable
data class ScreenScrollQ(
    @McpParam("GUI-scaled x coordinate to scroll at, e.g. the center of a list.")
    val x: Double,
    @McpParam("GUI-scaled y coordinate to scroll at.")
    val y: Double,
    @McpParam("Scroll amount: positive scrolls up, negative scrolls down.")
    val amount: Double,
)
