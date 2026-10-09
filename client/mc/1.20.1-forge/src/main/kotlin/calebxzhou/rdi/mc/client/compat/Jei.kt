package calebxzhou.rdi.mc.client.compat

import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.runtime.IJeiRuntime
import net.minecraft.resources.ResourceLocation

@JeiPlugin
class RJeiPlugin : IModPlugin {
    override fun getPluginUid(): ResourceLocation {
        return UID
    }

    override fun onRuntimeAvailable(jeiRuntime: IJeiRuntime) {
        RJeiRuntimeStore.set(jeiRuntime)
    }

    override fun onRuntimeUnavailable() {
        RJeiRuntimeStore.clear()
    }

    companion object {
        private val UID = ResourceLocation.fromNamespaceAndPath("rdi", "jei_plugin")
    }
}

object RJeiRuntimeStore {
    @Volatile
    private var state = State()

    val runtime: IJeiRuntime? get() = state.runtime

    val generation: Int get() = state.generation

    fun set(runtime: IJeiRuntime) {
        state = State(runtime, state.generation + 1)
        calebxzau.rdi.mc.client.preview.ItemPreviewExporter.onJeiRuntimeChanged()
    }

    fun clear() {
        state = State(generation = state.generation + 1)
        calebxzau.rdi.mc.client.preview.ItemPreviewExporter.onJeiRuntimeChanged()
    }

    private data class State(
        val runtime: IJeiRuntime? = null,
        val generation: Int = 0,
    )
}
