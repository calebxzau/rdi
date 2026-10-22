package calebxzau.rdi.common.model

import kotlinx.serialization.Serializable

/**
 * Files of one modpack version with their SHA-1, written when the version is built. Lists only the
 * files from the uploaded pack; CurseForge/Modrinth mods stay in the version's mod list.
 */
@Serializable
data class ModpackFileManifest(
    val modpackId: String,
    val version: String,
    val files: List<Entry>,
) {
    @Serializable
    data class Entry(
        /** Client manifest: path in the game directory. Full manifest: path in the uploaded pack. */
        val path: String,
        val size: Long,
        val sha1: String,
        /** Set only in the full manifest. */
        val side: Side? = null,
    )

    /** Where a file of the uploaded pack ends up. */
    @Serializable
    enum class Side {
        Both,
        ClientOnly,
        ServerOnly,

        /** Pack metadata such as `manifest.json`, or a file another layer overrides on both sides. */
        Neither,
    }
}
