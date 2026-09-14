package calebxzhou.rdi.master.service

import calebxzau.rdi.common.model.Content
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.util.str
import calebxzhou.rdi.master.GAME_LIBS_DIR
import calebxzhou.rdi.master.MODPACK_DATA_DIR
import calebxzhou.rdi.master.service.modpack.modpackRoutes as focusedModpackRoutes
import calebxzhou.rdi.master.service.modpack.*
import calebxzhou.rdi.master.net.*
import io.ktor.server.application.ApplicationCall
import org.bson.types.ObjectId
import io.ktor.server.routing.Route
import java.io.File

val Modpack.dir get() = MODPACK_DATA_DIR.resolve(_id.str)
val Modpack.libsDir get() = GAME_LIBS_DIR.resolve("${mcVer.mcVer}-${modloader}")
val Modpack.Version.storageDir get() = MODPACK_DATA_DIR.resolve(modpackId.str)
fun Modpack.Version.tempDir(buildId: String): File = storageDir.resolve(".build-$name-$buildId")
val Modpack.Version.zip get() = storageDir.resolve("${name}.zip")
val Modpack.Version.zstdPack get() = storageDir.resolve("${name}.tar.zst")
val Modpack.Version.fullPackFile get() = zstdPack.takeIf(File::exists) ?: zip
val Modpack.Version.clientZip get() = storageDir.resolve("${name}-client.zip")
val Modpack.Version.clientZstdPack get() = storageDir.resolve("${name}-client.tar.zst")
val Modpack.Version.clientPackFile get() = clientZstdPack.takeIf(File::exists) ?: clientZip

const val CLIENT_ONLY_MARK_PREFIX = "C" + "$$" + "_"
val MAX_PACK_SIZE = 2L * 1024 * 1024 * 1024

fun Route.modpackRoutes() = this.focusedModpackRoutes()

/** Compatibility facade for existing Legacy callers; implementations live in focused services. */
object ModpackService {
    internal var testDbcl
        get() = ModpackServiceKernel.testDbcl
        set(value) {
            ModpackServiceKernel.testDbcl = value
        }
    internal var testBuildVersionObserver
        get() = ModpackServiceKernel.testBuildVersionObserver
        set(value) {
            ModpackServiceKernel.testBuildVersionObserver = value
        }
    internal var testServerModDownloadTaskFactory
        get() = ModpackServiceKernel.testServerModDownloadTaskFactory
        set(value) {
            ModpackServiceKernel.testServerModDownloadTaskFactory = value
        }
    internal var testServerModPreparer
        get() = ModpackServiceKernel.testServerModPreparer
        set(value) {
            ModpackServiceKernel.testServerModPreparer = value
        }
    internal var testClientModDownloadTaskFactory
        get() = ModpackServiceKernel.testClientModDownloadTaskFactory
        set(value) {
            ModpackServiceKernel.testClientModDownloadTaskFactory = value
        }
    internal var testGameLibsDirOverride
        get() = ModpackServiceKernel.testGameLibsDirOverride
        set(value) {
            ModpackServiceKernel.testGameLibsDirOverride = value
        }
    val dbcl get() = ModpackServiceKernel.dbcl

    fun Modpack.isMcVer(ver: McVersion) =
        ModpackQueryService.run { this@isMcVer.isMcVer(ver) }

    suspend fun ApplicationCall.modpackGuardContext() =
        ModpackQueryService.run { this@modpackGuardContext.modpackGuardContext() }
    suspend fun listByAuthor(uid: ObjectId) = ModpackQueryService.listByAuthor(uid)
    suspend fun listUploadable(player: RAccount) = ModpackQueryService.listUploadable(player)
    suspend fun getById(id: ObjectId) = ModpackQueryService.getById(id)
    suspend fun incrementPlayCount(id: ObjectId) = ModpackQueryService.incrementPlayCount(id)
    suspend fun searchByName(name: String) = ModpackQueryService.searchByName(name)
    suspend fun search(call: ApplicationCall) = ModpackQueryService.search(call)
    suspend fun listAll() = ModpackQueryService.listAll()
    suspend fun listSimple(hasIconOnly: Boolean = false) =
        ModpackQueryService.listSimple(hasIconOnly)
    suspend fun listByIds(ids: List<ObjectId>) = ModpackQueryService.listByIds(ids)
    internal fun normalizeInfoBatchIds(ids: List<ObjectId>) = ModpackQueryService.normalizeInfoBatchIds(ids)
    internal fun orderModpacksByIds(ids: List<ObjectId>, packs: List<Modpack>) =
        ModpackQueryService.orderModpacksByIds(ids, packs)

    fun String.validateVerName() =
        ModpackQueryService.run { this@validateVerName.validateVerName() }
    suspend fun toModpackVoList(packs: List<Modpack>) = ModpackQueryService.toModpackVoList(packs)
    suspend fun Modpack.toBriefVo() = ModpackQueryService.run { this@toBriefVo.toBriefVo() }
    suspend fun Modpack.toDetailVo() = ModpackQueryService.run { this@toDetailVo.toDetailVo() }

    fun ModpackContext.requireAuthor() =
        ModpackVersionService.run { this@requireAuthor.requireAuthor() }

    fun ModpackContext.canManageVersion() =
        ModpackVersionService.run { this@canManageVersion.canManageVersion() }

    fun ModpackContext.requireCanManageVersion() =
        ModpackVersionService.run { this@requireCanManageVersion.requireCanManageVersion() }

    fun ModpackContext.requireCanUploadVersion() =
        ModpackVersionService.run { this@requireCanUploadVersion.requireCanUploadVersion() }

    suspend fun ModpackContext.addVersionMod(newMod: Mod) =
        ModpackVersionService.run { this@addVersionMod.addVersionMod(newMod) }

    suspend fun ModpackContext.addVersionMods(newMods: List<Mod>) =
        ModpackVersionService.run { this@addVersionMods.addVersionMods(newMods) }

    suspend fun ModpackContext.replaceVersionMod(
        projectId: String,
        fileId: String,
        newMod: Mod
    ) = ModpackVersionService.run {
        this@replaceVersionMod.replaceVersionMod(projectId, fileId, newMod)
    }

    suspend fun ModpackContext.replaceVersionMods(items: List<ModBatchReplaceItem>) =
        ModpackVersionService.run { this@replaceVersionMods.replaceVersionMods(items) }

    suspend fun ModpackContext.removeVersionMod(projectId: String, fileId: String) =
        ModpackVersionService.run { this@removeVersionMod.removeVersionMod(projectId, fileId) }

    suspend fun ModpackContext.removeVersionMods(refs: List<ModRef>) =
        ModpackVersionService.run { this@removeVersionMods.removeVersionMods(refs) }

    suspend fun ModpackContext.changeOptions(payload: Modpack.OptionsDto) =
        ModpackVersionService.run { this@changeOptions.changeOptions(payload) }

    suspend fun ModpackContext.deleteVersion() =
        ModpackVersionService.run { this@deleteVersion.deleteVersion() }

    suspend fun ModpackContext.deleteModpack() =
        ModpackVersionService.run { this@deleteModpack.deleteModpack() }

    fun cleanupStaleUploadsOnStartup() = ModpackUploadService.cleanupStaleUploadsOnStartup()
    fun cleanupExpiredUploadSessions() = ModpackUploadService.cleanupExpiredUploadSessions()
    suspend fun Modpack.CreateWithVersionDto.createWithVersion(
        player: RAccount,
        file: File
    ) = ModpackUploadService.run {
        this@createWithVersion.createWithVersion(player, file)
    }

    suspend fun ModpackContext.createVersion(
        name: String,
        file: File,
        mods: MutableList<Mod>,
        clientExtras: MutableList<Content> = arrayListOf(),
    ) = ModpackUploadService.run {
        this@createVersion.createVersion(name, file, mods, clientExtras)
    }

    suspend fun recoverUnfinishedVersionBuildsOnStartup() =
        ModpackBuildService.recoverUnfinishedVersionBuildsOnStartup()
    internal fun enqueueVersionBuild(
        player: RAccount,
        pack: Modpack,
        version: Modpack.Version
    ) = ModpackBuildService.enqueueVersionBuild(player, pack, version)

    suspend fun ModpackContext.rebuildVersion() =
        ModpackBuildService.run { this@rebuildVersion.rebuildVersion() }

    suspend fun Modpack.buildVersion(
        version: Modpack.Version,
        onProgress: (String) -> Unit
    ) = ModpackBuildService.run {
        this@buildVersion.buildVersion(version, onProgress)
    }

    internal fun createVersionBuildTaskForTest(
        player: RAccount,
        pack: Modpack,
        version: Modpack.Version,
        reprocessMods: Boolean
    ) = ModpackBuildService.createVersionBuildTaskForTest(player, pack, version, reprocessMods)

    suspend fun Modpack.getVersion(name: String) =
        ModpackQueryService.run { this@getVersion.getVersion(name) }
    suspend fun getVersion(id: ObjectId, name: String) = ModpackQueryService.getVersion(id, name)
    suspend fun Modpack.installToHost(
        name: String,
        host: Host,
        onProgress: (String) -> Unit
    ) = ModpackInstallService.run {
        this@installToHost.installToHost(name, host, onProgress)
    }

    internal fun unzipOverridesForTest(file: File, dir: File) =
        ModpackArchiveService.unzipOverridesForTest(file, dir)
    internal fun buildClientPackForTest(version: Modpack.Version) =
        ModpackArchiveService.buildClientPackForTest(version)
    internal fun upgradeFullPackArchiveForTest(version: Modpack.Version) =
        ModpackArchiveService.upgradeFullPackArchiveForTest(version)

    internal fun extractServerInstallRelativePathForTest(entry: String, root: String) =
        ModpackArchiveService.extractServerInstallRelativePathForTest(entry, root)
    fun extractOverridesRelativePath(entry: String) = ModpackArchiveService.extractOverridesRelativePath(entry)
    internal fun extractClientPackRelativePathForTest(entry: String) =
        ModpackArchiveService.extractClientPackRelativePathForTest(entry)

    internal fun isClientOnlyMarkedModPathForTest(path: String) =
        ModpackArchiveService.isClientOnlyMarkedModPathForTest(path)

    internal fun shouldSkipHostClientOnlyJarForTest(path: String) =
        ModpackArchiveService.shouldSkipHostClientOnlyJarForTest(path)
    internal fun shouldSkipRootWorld(path: String) = ModpackArchiveService.shouldSkipRootWorld(path)
}

class ModpackContext(
    val player: RAccount,
    val modpack: Modpack,
    val versionNull: Modpack.Version?
) {
    val version
        get() = versionNull
            ?: throw calebxzhou.rdi.master.exception.ParamError("缺少版本信息")
}
