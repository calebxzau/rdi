package calebxzhou.rdi.master.service.host

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.FABRIC_1_20_1_LOADER_VERSION
import calebxzhou.rdi.common.model.FABRIC_SERVER_JAR
import calebxzhou.rdi.common.model.FABRIC_SERVER_LAUNCHER_JAR
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.supportsRuntime
import java.io.File

/** Checks the owner-provided Fabric runtime files before they are mounted into a room. */
internal object FabricServerRuntimeFiles {
    val fileNames = listOf(
        FABRIC_SERVER_LAUNCHER_JAR,
        FABRIC_SERVER_JAR,
        "fabric-server-launcher.properties",
    )

    fun requireAvailable(sharedLibsDir: File, mcVersion: McVersion, loader: ModLoader): File {
        if (!mcVersion.supportsRuntime(loader) || mcVersion != McVersion.V201 || loader != ModLoader.Fabric) {
            throw RequestError("此Minecraft版本与Mod加载器组合暂不支持")
        }
        val root = sharedLibsDir.absoluteFile.toPath().normalize().toFile()
        if (!root.isDirectory) {
            throw ownerRuntimeError("运行库目录不存在或不可用")
        }
        fileNames.forEach { fileName ->
            requireFile(root.resolve(fileName), fileName)
        }
        if (!root.resolve("libraries").isDirectory) {
            throw ownerRuntimeError("缺少libraries目录")
        }
        requireFile(
            root.resolve("libraries/net/fabricmc/fabric-loader/$FABRIC_1_20_1_LOADER_VERSION/fabric-loader-$FABRIC_1_20_1_LOADER_VERSION.jar"),
            "Fabric Loader运行库",
        )
        requireFile(
            root.resolve("mods/rdi-5-mc-server-${McVersion.V201.mcVer}-fabric.jar"),
            "RDI Fabric服务端核心",
        )
        return root
    }

    private fun requireFile(file: File, label: String) {
        if (!file.isFile) throw ownerRuntimeError("缺少${label}")
    }

    private fun ownerRuntimeError(detail: String) =
        RequestError("请联系服主手动准备Fabric服务端运行库：${detail}")
}
