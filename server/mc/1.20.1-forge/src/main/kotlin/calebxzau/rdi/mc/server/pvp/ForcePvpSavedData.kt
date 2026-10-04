package calebxzau.rdi.mc.server.pvp

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData

/** One protection switch for the whole world, including every dimension. */
class ForcePvpSavedData private constructor(initialEnabled: Boolean) : SavedData() {
    var protectionEnabled: Boolean = initialEnabled
        private set

    constructor() : this(false)

    fun setProtection(enabled: Boolean) {
        if (protectionEnabled == enabled) return
        protectionEnabled = enabled
        setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putBoolean(ENABLED_TAG, protectionEnabled)
        return tag
    }

    companion object {
        private const val DATA_NAME = "rdi_force_pvp"
        private const val ENABLED_TAG = "enabled"

        fun load(tag: CompoundTag): ForcePvpSavedData = ForcePvpSavedData(tag.getBoolean(ENABLED_TAG))

        fun get(server: MinecraftServer): ForcePvpSavedData =
            server.overworld().dataStorage.computeIfAbsent(::load, ::ForcePvpSavedData, DATA_NAME)
    }
}
