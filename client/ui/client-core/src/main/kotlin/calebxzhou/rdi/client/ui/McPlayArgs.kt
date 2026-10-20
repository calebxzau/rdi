package calebxzhou.rdi.client.ui

import calebxzau.rdi.mclaunch.MinecraftAccount
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader

data class McPlayArgs(
    val title: String,
    val mcVer: McVersion,
    val modLoader: ModLoader,
    val versionId: String,
    val playArg: String,
    val modpackName: String = "",
    val mcpPort: Int? = null,
    val versionDir: String? = null,
    val activeBaseMods: List<Mod> = emptyList(),
    val disabledBaseMods: List<Mod> = emptyList(),
    val manageHostBaseMods: Boolean = false,
    val extraMods: List<Mod> = emptyList(),
    val manageHostExtraMods: Boolean = false,
    val startupWarnings: List<String> = emptyList(),
    /** Runs once when the session ends, including a failed start. */
    val cleanup: (() -> Unit)? = null,
    val hostId: String? = null,
    /** Plays as this identity instead of the logged-in account (save import marking mode). */
    val account: MinecraftAccount? = null,
    val extraJvmArgs: List<String> = emptyList(),
    val extraGameArgs: List<String> = emptyList(),
    val hostName: String? = null,
    val modpackIconUrl: String? = null,
    val versionName: String,
    /** False for packs installed only on this computer: vanilla launch, no RDI core or RDI runtimes. */
    val withRdiCore: Boolean = true,
)
