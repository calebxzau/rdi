package calebxzhou.rdi.client.ui.screen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import calebxzhou.rdi.client.model.UiMod
import calebxzhou.rdi.client.ui.comp.ContentGridDragEvent
import calebxzhou.rdi.client.ui.comp.ContentGridItem
import calebxzhou.rdi.common.model.Mod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostModDragTest {
    private val enabledArea = Rect(left = 0f, top = 0f, right = 500f, bottom = 1000f)
    private val disabledArea = Rect(left = 500f, top = 0f, right = 1000f, bottom = 500f)

    @Test
    fun `enabled mod can be dropped into disabled area`() {
        assertEquals(
            HostModAreaType.Disabled,
            hostModDropTarget(HostModAreaType.Enabled, Offset(750f, 250f), enabledArea, disabledArea),
        )
    }

    @Test
    fun `disabled mod can be dropped into enabled area`() {
        assertEquals(
            HostModAreaType.Enabled,
            hostModDropTarget(HostModAreaType.Disabled, Offset(250f, 250f), enabledArea, disabledArea),
        )
    }

    @Test
    fun `source area is not a drop target`() {
        assertNull(hostModDropTarget(HostModAreaType.Enabled, Offset(250f, 250f), enabledArea, disabledArea))
        assertNull(hostModDropTarget(HostModAreaType.Disabled, Offset(750f, 250f), enabledArea, disabledArea))
    }

    @Test
    fun `unpositioned areas are not drop targets`() {
        assertNull(hostModDropTarget(HostModAreaType.Disabled, Offset(250f, 250f), null, disabledArea))
    }

    @Test
    fun `second disabled mod dragged back to enabled keeps disabled source`() {
        val firstMod = uiMod("first")
        val secondMod = uiMod("second")
        var dragState: HostModDragState? = null
        val mutations = mutableListOf<HostModDragMutation>()

        fun dispatch(source: HostModAreaType, event: ContentGridDragEvent) {
            val update = hostModDragUpdate(
                dragState = dragState,
                source = source,
                event = event,
                enabledAreaBounds = enabledArea,
                disabledAreaBounds = disabledArea,
            )
            dragState = update.state
            update.mutation?.let(mutations::add)
        }

        dispatch(
            HostModAreaType.Enabled,
            ContentGridDragEvent.Start(ContentGridItem.ModItem(firstMod), Offset(250f, 250f)),
        )
        dispatch(HostModAreaType.Enabled, ContentGridDragEvent.End(Offset(750f, 250f)))
        dispatch(
            HostModAreaType.Enabled,
            ContentGridDragEvent.Start(ContentGridItem.ModItem(secondMod), Offset(250f, 250f)),
        )
        dispatch(HostModAreaType.Enabled, ContentGridDragEvent.End(Offset(750f, 250f)))

        dispatch(
            HostModAreaType.Disabled,
            ContentGridDragEvent.Start(ContentGridItem.ModItem(secondMod), Offset(750f, 250f)),
        )
        assertEquals(HostModAreaType.Disabled, dragState?.source)

        dispatch(HostModAreaType.Disabled, ContentGridDragEvent.Move(Offset(250f, 250f)))
        assertEquals(HostModAreaType.Enabled, dragState?.dropTarget)

        dispatch(HostModAreaType.Disabled, ContentGridDragEvent.End(Offset(250f, 250f)))
        assertEquals(listOf(true, true, false), mutations.map(HostModDragMutation::disabled))
        assertEquals(secondMod.mod, mutations.last().mod)
    }

    private fun uiMod(id: String) = UiMod(
        Mod(
            platform = "mr",
            projectId = id,
            slug = id,
            fileId = "$id-file",
            hash = "$id-hash",
        )
    )
}
