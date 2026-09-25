package calebxzhou.rdi.mc.server.firmsection

import calebxzhou.rdi.mc.firmsection.FirmSectionKey
import calebxzhou.rdi.mc.firmsection.FirmSectionSetResult
import calebxzhou.rdi.mc.firmsection.FirmSectionSetStatus
import calebxzhou.rdi.mc.firmsection.FirmSectionState
import calebxzhou.rdi.mc.firmsection.FirmSectionUnsetResult
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import java.util.UUID

/** Shared state and persistence logic for version-specific SavedData adapters. */
class FirmSectionSavedDataCore(
    private val state: FirmSectionState = FirmSectionState(),
    private val onDirty: () -> Unit = {},
) {
    fun allSections(): List<FirmSectionKey> = state.allSections()

    fun isAutoSetEnabled(playerId: UUID): Boolean = state.isAutoSetEnabled(playerId)

    fun setAutoSetEnabled(playerId: UUID, enabled: Boolean) {
        if (state.setAutoSetEnabled(playerId, enabled)) {
            onDirty()
        }
    }

    fun hasFirmChunk(dimensionId: String, chunkX: Int, chunkZ: Int): Boolean =
        state.hasFirmChunk(dimensionId, chunkX, chunkZ)

    fun hasFirmSection(dimensionId: String, chunkX: Int, sectionY: Int, chunkZ: Int): Boolean =
        state.hasFirmSection(dimensionId, chunkX, sectionY, chunkZ)

    fun set(playerId: UUID, key: FirmSectionKey): FirmSectionSetResult {
        val result = state.set(playerId, key)
        if (result.status == FirmSectionSetStatus.ADDED) {
            onDirty()
        }
        return result
    }

    fun unset(playerId: UUID, key: FirmSectionKey): FirmSectionUnsetResult {
        val result = state.unset(playerId, key)
        if (result.removed) {
            onDirty()
        }
        return result
    }

    fun list(playerId: UUID) = state.list(playerId)

    fun write(tag: CompoundTag): CompoundTag {
        val players = ListTag()
        state.players().forEach { player ->
            players.add(CompoundTag().apply {
                putString(UUID_TAG, player.playerId.toString())
                put(SECTIONS_TAG, player.sections.toTag())
                putBoolean(AUTO_SET_TAG, player.autoSet)
            })
        }
        tag.put(PLAYERS_TAG, players)
        return tag
    }

    fun read(tag: CompoundTag) {
        state.clear()
        val players = tag.getList(PLAYERS_TAG, Tag.TAG_COMPOUND.toInt())
        for (playerTagBase in players) {
            val playerTag = playerTagBase as CompoundTag
            val playerId = try {
                UUID.fromString(playerTag.getString(UUID_TAG))
            } catch (_: IllegalArgumentException) {
                continue
            }
            val sections = linkedSetOf<FirmSectionKey>()
            val sectionTags = playerTag.getList(SECTIONS_TAG, Tag.TAG_COMPOUND.toInt())
            for (sectionTagBase in sectionTags) {
                val sectionTag = sectionTagBase as CompoundTag
                sections += FirmSectionKey(
                    sectionTag.getString(DIMENSION_TAG),
                    sectionTag.getInt(CHUNK_X_TAG),
                    sectionTag.getInt(SECTION_Y_TAG),
                    sectionTag.getInt(CHUNK_Z_TAG),
                )
            }
            val autoSet = playerTag.contains(AUTO_SET_TAG, Tag.TAG_BYTE.toInt()) &&
                playerTag.getBoolean(AUTO_SET_TAG)
            state.loadPlayer(playerId, sections, autoSet)
        }
    }

    private fun Collection<FirmSectionKey>.toTag() = ListTag().also { list ->
        forEach { section ->
            list.add(CompoundTag().apply {
                putString(DIMENSION_TAG, section.dimensionId)
                putInt(CHUNK_X_TAG, section.chunkX)
                putInt(SECTION_Y_TAG, section.sectionY)
                putInt(CHUNK_Z_TAG, section.chunkZ)
            })
        }
    }

    private companion object {
        const val PLAYERS_TAG = "players"
        const val UUID_TAG = "uuid"
        const val SECTIONS_TAG = "sections"
        const val AUTO_SET_TAG = "autoSet"
        const val DIMENSION_TAG = "dimensionId"
        const val CHUNK_X_TAG = "chunkX"
        const val SECTION_Y_TAG = "sectionY"
        const val CHUNK_Z_TAG = "chunkZ"
    }
}
