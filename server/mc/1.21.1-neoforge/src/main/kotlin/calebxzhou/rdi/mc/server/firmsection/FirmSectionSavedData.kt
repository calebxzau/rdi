package calebxzhou.rdi.mc.server.firmsection

import calebxzhou.rdi.mc.firmsection.FirmSectionKey
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class FirmSectionSavedData : SavedData() {
    private val core = FirmSectionSavedDataCore(onDirty = ::setDirty)

    fun allSections(): List<FirmSectionKey> =
        core.allSections()

    fun isAutoSetEnabled(playerId: UUID): Boolean = core.isAutoSetEnabled(playerId)

    fun setAutoSetEnabled(playerId: UUID, enabled: Boolean) = core.setAutoSetEnabled(playerId, enabled)

    fun hasFirmChunk(dimensionId: String, chunkX: Int, chunkZ: Int): Boolean =
        core.hasFirmChunk(dimensionId, chunkX, chunkZ)

    fun hasFirmSection(dimensionId: String, chunkX: Int, sectionY: Int, chunkZ: Int): Boolean =
        core.hasFirmSection(dimensionId, chunkX, sectionY, chunkZ)

    fun set(playerId: UUID, key: FirmSectionKey) = core.set(playerId, key)

    fun unset(playerId: UUID, key: FirmSectionKey) = core.unset(playerId, key)

    fun list(playerId: UUID) = core.list(playerId)

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = core.write(tag)

    companion object {
        const val FILE_ID = "rdi_firm_sections"

        fun factory(): Factory<FirmSectionSavedData> = Factory(::FirmSectionSavedData, ::load)

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider): FirmSectionSavedData {
            val data = FirmSectionSavedData()
            data.core.read(tag)
            return data
        }
    }
}
