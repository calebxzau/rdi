package calebxzau.mc.common2021.mcp

import calebxzhou.rdi.mc.common2.mcp.McpBadArgsError
import calebxzhou.rdi.mc.common2.mcp.McpError
import calebxzhou.rdi.mc.common2.mcp.model.ScreenClickAtQ
import calebxzhou.rdi.mc.common2.mcp.model.ScreenClickQ
import calebxzhou.rdi.mc.common2.mcp.model.ScreenKey
import calebxzhou.rdi.mc.common2.mcp.model.ScreenKeyQ
import calebxzhou.rdi.mc.common2.mcp.model.ScreenScrollQ
import calebxzhou.rdi.mc.common2.mcp.model.ScreenSnapshot
import calebxzhou.rdi.mc.common2.mcp.model.ScreenTypeQ
import calebxzhou.rdi.mc.common2.mcp.model.ScreenWidget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractSelectionList
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.contents.TranslatableContents
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

/** The parts of screen control whose Minecraft API differs between 1.20.1 and 1.21.1. */
interface McpScreenPlatform {
    /** GUI-scaled top and bottom of row [index] of [list], an `AbstractSelectionList`. */
    fun rowTopBottom(list: GuiEventListener, index: Int): Pair<Int, Int>

    /** GUI-scaled top and bottom of the visible part of [list], an `AbstractSelectionList`. */
    fun listViewport(list: GuiEventListener): Pair<Int, Int>

    fun scroll(screen: Screen, x: Double, y: Double, amount: Double): Boolean
}

/**
 * Reads and drives the open screen for the MCP screen tools, through the same input methods
 * the player's mouse and keyboard use. Every Minecraft access runs on the client thread.
 */
class McpScreenControl(private val platform: McpScreenPlatform) {
    private class Target(val listener: GuiEventListener, val widget: ScreenWidget)

    // Client thread only.
    private var lastScreen: Screen? = null
    private var lastListeners: List<GuiEventListener> = emptyList()
    private var version = 0L

    fun read(): Result<ScreenSnapshot> = onClient { snapshot(collect()) }

    fun click(req: ScreenClickQ): Result<ScreenSnapshot> = act {
        val screen = requireScreen()
        val target = resolve(req.version, req.widget)
        check(target.widget.visible) { "widget #${req.widget} is hidden; scroll it into view first" }
        check(target.widget.active) { "widget #${req.widget} is inactive" }
        val x = target.widget.x + target.widget.width / 2.0
        val y = target.widget.y + target.widget.height / 2.0
        screen.mouseClicked(x, y, req.button)
        screen.mouseReleased(x, y, req.button)
    }

    fun clickAt(req: ScreenClickAtQ): Result<ScreenSnapshot> = act {
        val screen = requireScreen()
        screen.mouseClicked(req.x, req.y, req.button)
        screen.mouseReleased(req.x, req.y, req.button)
    }

    fun type(req: ScreenTypeQ): Result<ScreenSnapshot> = act {
        val screen = requireScreen()
        val box = resolve(req.version, req.widget).listener as? EditBox
            ?: throw McpBadArgsError("widget #${req.widget} is not an EditBox")
        check(box.isVisible && box.isActive) { "widget #${req.widget} cannot be edited" }
        if (box in screen.children()) screen.setFocused(box) else box.isFocused = true
        if (req.replace) box.value = ""
        req.text.forEach { char -> box.charTyped(char, 0) }
    }

    fun key(req: ScreenKeyQ): Result<ScreenSnapshot> = act {
        val minecraft = Minecraft.getInstance()
        val screen = minecraft.screen
        if (screen == null) {
            if (req.key != ScreenKey.Escape) throw McpError("no screen is open; only Escape is accepted")
            minecraft.pauseGame(false)
            return@act
        }
        screen.keyPressed(req.key.glfwCode, 0, 0)
        screen.keyReleased(req.key.glfwCode, 0, 0)
    }

    fun scroll(req: ScreenScrollQ): Result<ScreenSnapshot> = act {
        platform.scroll(requireScreen(), req.x, req.y, req.amount)
    }

    /** Runs [action], then reads the screen in a later task so screens it schedules are already shown. */
    private fun act(action: () -> Unit): Result<ScreenSnapshot> = onClient(action).mapCatching { read().getOrThrow() }

    private fun requireScreen(): Screen = Minecraft.getInstance().screen ?: throw McpError("no screen is open")

    private fun resolve(requestedVersion: Long, id: Int): Target {
        val targets = collect()
        if (requestedVersion != version) throw McpError("the screen changed since that read (now version $version); call screen_read again")
        return targets.getOrNull(id) ?: throw McpBadArgsError("no widget #$id on this screen")
    }

    /** Lists the targets of the open screen and moves [version] on when the screen or its widgets changed. */
    private fun collect(): List<Target> {
        val screen = Minecraft.getInstance().screen
        val targets = if (screen == null) emptyList() else Collector().apply { screen.children().forEach { visit(it, true) } }.targets
        val listeners = targets.map { it.listener }
        if (screen !== lastScreen || listeners.size != lastListeners.size || listeners.indices.any { listeners[it] !== lastListeners[it] }) {
            version++
            lastScreen = screen
            lastListeners = listeners
        }
        return targets
    }

    private fun snapshot(targets: List<Target>): ScreenSnapshot {
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        val screen = minecraft.screen
        return ScreenSnapshot(
            version = version,
            screen = screen?.javaClass?.name,
            title = screen?.title?.string.orEmpty(),
            guiWidth = window.guiScaledWidth,
            guiHeight = window.guiScaledHeight,
            guiScale = window.guiScale,
            widgets = targets.map { it.widget },
        )
    }

    private inner class Collector {
        val targets = mutableListOf<Target>()

        fun visit(listener: GuiEventListener, shown: Boolean) {
            when {
                listener is AbstractSelectionList<*> -> visitList(listener, shown)
                listener is ContainerEventHandler && listener.children().isNotEmpty() ->
                    listener.children().forEach { visit(it, shown && (listener as? AbstractWidget)?.visible != false) }
                listener is AbstractWidget -> add(listener, widget(listener, shown))
            }
        }

        private fun visitList(list: AbstractSelectionList<*>, shown: Boolean) {
            val (viewTop, viewBottom) = platform.listViewport(list)
            val selected = list.selected
            list.children().forEachIndexed { index, entry ->
                val (top, bottom) = platform.rowTopBottom(list, index)
                val rowShown = shown && top >= viewTop && bottom <= viewBottom
                if (entry is ContainerEventHandler) {
                    // Rows of option lists hold their own buttons; positions are from the last frame.
                    entry.children().forEach { visit(it, rowShown) }
                    return@forEachIndexed
                }
                val text = (entry as? ObjectSelectionList.Entry<*>)?.narration?.let(::entryText).orEmpty()
                add(entry, ScreenWidget(
                    id = targets.size,
                    type = "ListEntry",
                    text = text,
                    x = list.rowLeft,
                    y = top,
                    width = list.rowWidth,
                    height = bottom - top,
                    active = true,
                    visible = rowShown,
                    value = if (entry === selected) "selected" else null,
                ))
            }
        }

        private fun widget(widget: AbstractWidget, shown: Boolean) = ScreenWidget(
            id = targets.size,
            type = typeOf(widget),
            text = widget.message.string,
            x = widget.x,
            y = widget.y,
            width = widget.width,
            height = widget.height,
            active = widget.active,
            visible = shown && widget.visible,
            focused = widget.isFocused,
            value = when (widget) {
                is EditBox -> widget.value
                is Checkbox -> widget.selected().toString()
                else -> null
            },
        )

        private fun add(listener: GuiEventListener, widget: ScreenWidget) {
            targets += Target(listener, widget)
        }
    }

    /** Row narrations wrap names in "Selected: %s" whether or not the row is selected; [ScreenWidget.value] says that instead. */
    private fun entryText(narration: Component): String = buildString { appendWithoutSelectPrefix(narration) }

    private fun StringBuilder.appendWithoutSelectPrefix(component: Component) {
        val contents = component.contents
        if (contents is TranslatableContents && contents.key == "narrator.select" && contents.args.size == 1) {
            when (val inner = contents.args[0]) {
                is Component -> appendWithoutSelectPrefix(inner)
                else -> append(inner)
            }
        } else {
            contents.visit(FormattedText.ContentConsumer<Unit> { text ->
                append(text)
                Optional.empty()
            })
        }
        component.siblings.forEach { appendWithoutSelectPrefix(it) }
    }

    private fun typeOf(widget: AbstractWidget): String = when (widget) {
        is EditBox -> "EditBox"
        is Checkbox -> "Checkbox"
        is AbstractSliderButton -> "Slider"
        is CycleButton<*> -> "CycleButton"
        is AbstractButton -> "Button"
        else -> widget.javaClass.simpleName.ifEmpty { "Widget" }
    }

    private fun <T> onClient(action: () -> T): Result<T> = runCatching {
        try {
            Minecraft.getInstance().submit(Supplier { action() }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            throw McpError("the game did not respond in time")
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
