package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.FABRIC_1_20_1_LOADER_VERSION
import calebxzhou.rdi.common.serdesJson
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import java.io.File

data class ClientLoaderRuntime(
    val mcVersion: McVersion,
    val loader: ModLoader,
    val profileId: String,
    val loaderVersion: String,
    val profileUrl: String?,
) {
    fun manifestFile(versionsDir: File): File =
        versionsDir.resolve(profileId).resolve("${profileId}.json")

    fun loadManifest(versionsDir: File): Result<MojangVersionManifest> = runCatching {
        val manifest = serdesJson.decodeFromString<MojangVersionManifest>(manifestFile(versionsDir).readText())
        if (loader == ModLoader.Fabric) FabricClientProfile.validate(manifest, this, requireChecksums = true).getOrThrow()
        manifest
    }

    companion object {
        fun resolve(mcVersion: McVersion, loader: ModLoader): ClientLoaderRuntime {
            if (loader == ModLoader.Fabric) {
                require(mcVersion == McVersion.V201) { "Fabric客户端仅支持Minecraft1.20.1" }
                val profileId = "fabric-loader-${FABRIC_LOADER_VERSION}-${mcVersion.mcVer}"
                return ClientLoaderRuntime(
                    mcVersion = mcVersion,
                    loader = loader,
                    profileId = profileId,
                    loaderVersion = FABRIC_LOADER_VERSION,
                    profileUrl = "https://meta.fabricmc.net/v2/versions/loader/${mcVersion.mcVer}/${FABRIC_LOADER_VERSION}/profile/json",
                )
            }
            val configured = requireNotNull(mcVersion.loaderVersions[loader]) {
                "${mcVersion.mcVer}/${loader}客户端加载器未配置"
            }
            return ClientLoaderRuntime(
                mcVersion = mcVersion,
                loader = loader,
                profileId = configured.dirName,
                loaderVersion = configured.ver,
                profileUrl = null,
            )
        }

        private const val FABRIC_LOADER_VERSION = FABRIC_1_20_1_LOADER_VERSION
    }
}
