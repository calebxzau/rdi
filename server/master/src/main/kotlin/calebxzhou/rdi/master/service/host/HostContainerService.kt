package calebxzhou.rdi.master.service.host

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.FORGEGUARD_AGENT_FILE_NAME
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.sameMod
import calebxzhou.rdi.common.model.supportsForgeguard
import calebxzhou.rdi.common.model.resolveServerRuntime
import calebxzhou.rdi.common.util.str
import calebxzhou.rdi.master.GAME_LIBS_DIR
import calebxzhou.rdi.master.service.CLIENT_ONLY_MARK_PREFIX
import calebxzhou.rdi.master.service.DockerService
import calebxzhou.rdi.master.service.Lwjgl3ifyServerSupport
import calebxzhou.rdi.master.service.WorldService
import calebxzhou.rdi.master.service.host.HostInstallService.ensureWorkdirQuota
import calebxzhou.rdi.master.service.host.HostInstallService.legacyForgeUniversalJarName
import calebxzhou.rdi.master.service.libsDir
import com.github.dockerjava.api.model.Mount
import com.github.dockerjava.api.model.MountType
import com.github.dockerjava.api.model.TmpfsOptions
import org.bson.types.ObjectId
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

object HostContainerService {
    internal const val FORGEGUARD_CONTAINER_PATH = "/opt/forgeguard.jar"
    private const val MC_LOG4J2_FILE_NAME = "mc-log4j2.xml"
    internal const val MC_LOG4J2_CONTAINER_PATH = "/opt/rdi/mc-log4j2.xml"
    private const val MC_LOG4J2_CONFIGURATION_ARG = "-Dlog4j.configurationFile=$MC_LOG4J2_CONTAINER_PATH"

    internal fun requireModernLog4j2Config(
        mcv: McVersion,
        configFile: File = GAME_LIBS_DIR.resolve(MC_LOG4J2_FILE_NAME),
    ): File? {
        if (!mcv.isModern) return null
        if (!configFile.isFile) {
            throw RequestError("房间日志配置不可用，请联系管理员")
        }
        return configFile
    }

    internal fun modernLog4j2Mount(configFile: File): Mount {
        return Mount()
            .withType(MountType.BIND)
            .withSource(configFile.absolutePath)
            .withTarget(MC_LOG4J2_CONTAINER_PATH)
            .withReadOnly(true)
    }

    internal fun forgeguardMount(): Mount {
        val agentFile = GAME_LIBS_DIR.resolve(FORGEGUARD_AGENT_FILE_NAME)
        if (!agentFile.isFile) {
            throw RequestError("缺少Forgeguard服务端启动保护文件: ${agentFile.absolutePath}")
        }
        return Mount()
            .withType(MountType.BIND)
            .withSource(agentFile.absolutePath)
            .withTarget(FORGEGUARD_CONTAINER_PATH)
            .withReadOnly(true)
    }

    internal fun Host.containerEnv(
        mcv: McVersion,
        loaderVersion: ModLoader.Version?,
        modpack: Modpack,
        lwjgl3ifyRuntime: Lwjgl3ifyServerSupport.PreparedRuntime?,
        fabricRuntime: calebxzhou.rdi.common.model.ServerLoaderRuntime? = null,
    ): MutableList<String> {
        val serverArgs = when (mcv) {
            McVersion.V201,
            McVersion.V211 -> fabricRuntime?.launchArgs
                ?: listOf((loaderVersion ?: throw RequestError("找不到对应版本的运行库")).serverArgsPath(true))
            McVersion.V071 -> buildList {
                if (lwjgl3ifyRuntime != null) {
                    addAll(lwjgl3ifyRuntime.launchArgs)
                } else {
                    addAll(McVersion.V071.plusJvmArgs)
                    add("-jar")
                    add((loaderVersion ?: throw RequestError("找不到对应版本的运行库")).legacyForgeUniversalJarName)
                }
            }
        }
        val noguiArg = if (mcv == McVersion.V071 || fabricRuntime != null) "nogui" else "--nogui"
        val totalArg = mutableListOf<String>().apply {

            this.add("-XX:+UseCompactObjectHeaders")

            this.add("-Xmx8G")
            if (modpack.supportsForgeguard(modpack.modloader)) {
                this.add("-javaagent:$FORGEGUARD_CONTAINER_PATH")
            }
            if (mcv.isModern) {
                this.add(MC_LOG4J2_CONFIGURATION_ARG)
            }

        } + serverArgs + noguiArg
        return mutableListOf(
            "HOST_ID=${_id.str}",
            "GAME_PORT=${port}",
            "ALL_OP=${if (allowCheats) "true" else "false"}",
            "START_PARAMS=${totalArg.joinToString(" ")}"
        ).apply {
            gameRules.forEach { id, value ->
                this += "GAME_RULE_${id}=${value}"
            }
        }
    }

    internal fun Host.makeContainer(
        worldId: ObjectId?,
        modpack: Modpack,
        version: Modpack.Version
    ) {
        val modernLog4j2Config = requireModernLog4j2Config(modpack.mcVer)
        if (realVersion == 2 && worldId != null) {
            throw RequestError("v2房间不能使用外置存档")
        }
        if (realVersion != 1 && realVersion != 2) {
            throw RequestError("无效房间版本")
        }
        val resolvedRuntime = modpack.mcVer.resolveServerRuntime(modpack.modloader)
            ?: throw RequestError("不支持的mod加载器")
        val isFabricRuntime = modpack.modloader == ModLoader.Fabric
        val sharedLibsDir = if (isFabricRuntime) {
            modpack.libsDir.absoluteFile.toPath().normalize().toFile()
        } else {
            modpack.libsDir.canonicalFile.also { it.mkdirs() }
        }
        if (isFabricRuntime) {
            FabricServerRuntimeFiles.requireAvailable(sharedLibsDir, modpack.mcVer, modpack.modloader)
        }
        DockerService.deleteContainer(_id.str)
        ensureWorkdirQuota()
        cleanupModFilesBeforeContainerCreate(version)

        val loaderVer = modpack.mcVer.loaderVersions[modpack.modloader]
        if (modpack.modloader != ModLoader.Fabric && loaderVer == null) {
            throw RequestError("找不到对应版本的运行库")
        }
        val fabricRuntime = if (isFabricRuntime) resolvedRuntime else null
        val lwjgl3ifyRuntime = if (Lwjgl3ifyServerSupport.shouldEnable(modpack)) {
            Lwjgl3ifyServerSupport.prepare(modpack, dir)
        } else {
            null
        }
        val rdiCore = if (modpack.modloader == ModLoader.Fabric) {
            "rdi-5-mc-server-${modpack.mcVer.mcVer}-fabric.jar"
        } else {
            "rdi-5-mc-server-${modpack.mcVer.mcVer}-${modpack.modloader}.jar"
        }
        val sharedRdiCore = sharedLibsDir.resolve("mods").resolve(rdiCore)
        val rdiCoreSource = sharedRdiCore.takeIf { it.exists() }
            ?: sharedRdiCore
        val librariesSource = if (isFabricRuntime) {
            sharedLibsDir.resolve("libraries")
        } else {
            lwjgl3ifyRuntime?.librariesDir ?: sharedLibsDir.resolve("libraries")
        }
        val mounts = mutableListOf(
            Mount()
                .withType(MountType.BIND)
                .withSource(dir.absolutePath)
                .withTarget("/opt/server"),
            Mount()
                .withType(MountType.BIND)
                .withSource(librariesSource.absolutePath)
                .withTarget("/opt/server/libraries"),
            Mount()
                .withType(MountType.BIND)
                .withSource(rdiCoreSource.absolutePath)
                .withTarget("/opt/server/mods/${rdiCore}"),
        ).apply {
            if (isFabricRuntime) {
                FabricServerRuntimeFiles.fileNames.forEach { fileName ->
                    this += Mount()
                        .withType(MountType.BIND)
                        .withSource(sharedLibsDir.resolve(fileName).absolutePath)
                        .withTarget("/opt/server/${fileName}")
                }
            }
            modernLog4j2Config?.let { this += modernLog4j2Mount(it) }
            if (modpack.supportsForgeguard(modpack.modloader)) {
                this += forgeguardMount()
            }
            version.mods
                .filter(::isServerInstalledMod)
                .filterNot { this@makeContainer.isDisabledMod(it) }
                .forEach { mod ->
                    val source = mod.candidateFiles.firstOrNull(File::exists)
                    if (source != null) {
                        this += Mount()
                            .withType(MountType.BIND)
                            .withSource(source.absolutePath)
                            .withTarget("/opt/server/mods/${mod.fileName}")
                    }
                }
            extraMods
                .filter(::isServerInstalledMod)
                .forEach { mod ->
                    val source = mod.candidateFiles.firstOrNull(File::exists)
                        ?: throw RequestError("房间附加Mod文件缺失:${mod.slug} 请重新上传")
                    this += Mount()
                        .withType(MountType.BIND)
                        .withSource(source.absolutePath)
                        .withTarget("/opt/server/mods/${mod.fileName}")
                }
            if (modpack.mcVer == McVersion.V071) {
                val legacyLoaderVersion = loaderVer ?: throw RequestError("找不到对应版本的运行库")
                val loaderJar = lwjgl3ifyRuntime?.forgeUniversalJar
                    ?: sharedLibsDir.resolve(
                        if (modpack.mcVer == McVersion.V071 && modpack.modloader == ModLoader.forge) {
                            legacyLoaderVersion.legacyForgeUniversalJarName
                        } else {
                            legacyLoaderVersion.serverJarName
                        }
                    )
                val serverJar = lwjgl3ifyRuntime?.minecraftServerJar
                    ?: sharedLibsDir.resolve(modpack.mcVer.serverJarName)
                this += Mount()
                    .withType(MountType.BIND)
                    .withSource(loaderJar.absolutePath)
                    .withTarget("/opt/server/${loaderJar.name}")
                this += Mount()
                    .withType(MountType.BIND)
                    .withSource(serverJar.absolutePath)
                    .withTarget("/opt/server/${serverJar.name}")
            }
            if (realVersion == 2) {
                // v2 worlds live below the host root bind at /opt/server/world.
            } else if (worldId != null) {
                this += Mount()
                    .withType(MountType.BIND)
                    .withSource(WorldService.getLevelDir(worldId).absolutePath)
                    .withTarget("/opt/server/world")
            } else {
                this += Mount()
                    .withType(MountType.TMPFS)
                    .withTarget("/opt/server/world")
                    .withTmpfsOptions(TmpfsOptions().withSizeBytes(512 * 1024 * 1024))
            }
        }
        val image = if (lwjgl3ifyRuntime != null) "rdi:j25" else "rdi:j${modpack.mcVer.jreSupport}"
        val cpu = when(modpack.mcVer){
            McVersion.V071 -> 2
            else -> 4
        }
        val memory = 8 * 1024 * 1024 * 1024L
        val memorySwap = 16 * 1024 * 1024 * 1024L

        DockerService.createContainer(
            port,
            this._id.str,
            cpu,
            memory,memorySwap,
            mounts,
            image,
            containerEnv(modpack.mcVer, loaderVer, modpack, lwjgl3ifyRuntime, fabricRuntime)
        )
    }

    internal fun isServerInstalledMod(mod: Mod): Boolean =
        mod.side != Mod.Side.CLIENT &&
                mod.side != Mod.Side.UNKNOWN &&
                !mod.fileName.startsWith(CLIENT_ONLY_MARK_PREFIX)

    internal fun Host.isDisabledMod(mod: Mod): Boolean =
        disabledMods.any { sameMod(it, mod) }

    internal fun Host.cleanupModFilesBeforeContainerCreate(
        version: Modpack.Version,
        modsDir: File = dir.resolve("mods"),
    ) {
        if (Files.isSymbolicLink(modsDir.toPath())) {
            throw RequestError("房间Mod目录不能是软链接: ${modsDir.absolutePath}")
        }
        if (!modsDir.exists() || !modsDir.isDirectory) return
        val disabledServerMods = version.mods
            .filter(::isServerInstalledMod)
            .filter { isDisabledMod(it) }

        disabledServerMods
            .flatMap { it.fileNames }
            .distinct()
            .forEach { fileName ->
                deleteHostModFile(modsDir.resolve(fileName))
            }

        val allowedFileNames = (version.mods + extraMods)
            .flatMap(Mod::fileNames)
            .toSet()
        modsDir.listFiles()
            ?.filter {
                Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    it.length() == 0L &&
                    it.extension.equals("jar", ignoreCase = true) &&
                    it.name !in allowedFileNames
            }
            ?.forEach(::deleteHostModFile)
    }

    private fun deleteHostModFile(file: File) {
        runCatching { Files.deleteIfExists(file.toPath()) }
            .getOrElse { err ->
                throw RequestError("房间Mod文件清理失败: ${file.name} ${err.message ?: ""}".trim())
            }
    }
}
