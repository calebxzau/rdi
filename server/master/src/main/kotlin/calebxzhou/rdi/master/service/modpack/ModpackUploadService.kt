package calebxzhou.rdi.master.service.modpack

import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.validateAndMergeClientExtras
import calebxzhou.rdi.common.DEBUG
import calebxzhou.rdi.common.archive.PackArchiveFormat
import calebxzhou.rdi.common.archive.detectArchiveFormat
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.service.ModpackModProcessor
import calebxzhou.rdi.common.service.validate
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.validateModpackName
import calebxzhou.rdi.master.MODPACK_DATA_DIR
import calebxzhou.rdi.master.exception.ParamError
import calebxzhou.rdi.master.net.*
import calebxzhou.rdi.master.service.*
import calebxzhou.rdi.master.service.modpack.ModpackServiceKernel.dbcl
import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.Updates
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Upload staging and Legacy modpack/version creation with rollback semantics. */
object ModpackUploadService {
    private const val MODPACK_UPLOAD_VERSION_ERROR = "目前只支持上传MC20和MC21整合包"
    private const val MAX_MODPACK_PER_USER = 10
    private const val VERSION_PUBLICATION_MUTEX_STRIPE_COUNT = 64
    private val lgr by calebxzau.rdi.common.logging.Loggers
    private val versionPublicationMutexes = Array(VERSION_PUBLICATION_MUTEX_STRIPE_COUNT) { Mutex() }

    internal val modpackUploadTempStorage = ModpackUploadTempStorage(MODPACK_DATA_DIR.resolve(".upload-tmp"))
    internal val legacyModpackUploadTempStorage = ModpackUploadTempStorage(MODPACK_DATA_DIR)
    internal val parallelUploadService = ModpackParallelUploadService(
        MODPACK_DATA_DIR.resolve(".upload-tmp").resolve("sessions"),
        MAX_PACK_SIZE
    )

    internal fun deleteUploadTempFile(file: File, reason: String) {
        modpackUploadTempStorage.deleteTempFile(file)
            .onFailure { error -> lgr.warn(error) { "${reason}时删除上传暂存文件失败: ${file.absolutePath}" } }
    }

    internal fun cleanupStaleUploadsOnStartup() {
        var cleanedCount = 0
        listOf(
            ".upload-tmp" to modpackUploadTempStorage,
            "旧modpack目录" to legacyModpackUploadTempStorage
        ).forEach { (name, storage) ->
            storage.cleanupStaleFiles().onSuccess { cleanedCount += it }
                .onFailure { error -> lgr.warn(error) { "启动清理${name}遗留上传文件失败" } }
        }
        parallelUploadService.cleanupStaleSessions().onSuccess { cleanedCount += it }
            .onFailure { error -> lgr.warn(error) { "启动清理分片上传会话失败" } }
        lgr.info { "启动清理遗留整合包上传文件${cleanedCount}个" }
    }

    internal fun cleanupExpiredUploadSessions() {
        parallelUploadService.cleanupStaleSessions().onSuccess { count ->
            if (count > 0) lgr.info { "清理过期整合包上传会话${count}个" }
        }.onFailure { error -> lgr.warn(error) { "清理过期整合包上传会话失败" } }
    }

    private fun requireModpackUploadVersion(mcVersion: McVersion) {
        if (!mcVersion.supportsModpackUpload()) throw RequestError(MODPACK_UPLOAD_VERSION_ERROR)
    }

    private data class ValidatedCreateMetadata(
        val normalizedVerName: String,
        val normalizedCategories: List<Modpack.Category>,
    )

    private suspend fun validateCreateMetadata(
        player: RAccount,
        name: String,
        mcVer: McVersion,
        verName: String,
        iconUrl: String?,
        sourceUrl: String?,
        info: String?,
        categories: List<Modpack.Category>,
    ): ValidatedCreateMetadata {
        requireModpackUploadVersion(mcVer)
        val normalizedVerName = ModpackQueryService.run { verName.validateVerName() }.getOrThrow()
        val normalizedCategories = Modpack.normalizeCategories(categories)
        if (!player.hasMsid && !DEBUG) throw RequestError("必须有微软账号才能传包")
        if (dbcl.countDocuments(eq(Modpack::authorId.name, player._id)) >= MAX_MODPACK_PER_USER && !player.isDav) {
            throw RequestError("一个人最多传${MAX_MODPACK_PER_USER}个包")
        }
        if (dbcl.countDocuments(eq(Modpack::name.name, name)) > 0) {
            throw RequestError("同名整合包已存在")
        }
        name.validateModpackName().getOrThrow()
        validateRequiredModpackUploadMetadata(info, iconUrl, categories).getOrElse {
            throw RequestError(it.message ?: "整合包简介、图标和分类不能为空")
        }
        Modpack.OptionsDto(name, iconUrl, info, sourceUrl, normalizedCategories)
            .validate()
            .getOrThrow()
        return ValidatedCreateMetadata(normalizedVerName, normalizedCategories)
    }

    fun ModpackContext.validateVersionUpload(
        verName: String,
        expectedMcVer: McVersion? = null,
        expectedModLoader: ModLoader? = null,
    ): String {
        ModpackVersionService.run { this@validateVersionUpload.requireCanUploadVersion() }
        requireModpackUploadVersion(modpack.mcVer)
        expectedMcVer?.let {
            if (it != modpack.mcVer) throw RequestError("MC版本与已有整合包不一致")
        }
        expectedModLoader?.let {
            if (it != modpack.modloader) throw RequestError("Mod加载器与已有整合包不一致")
        }
        val normalizedVerName = ModpackQueryService.run { verName.validateVerName() }.getOrThrow()
        if (modpack.versions.any { it.name.equals(normalizedVerName, ignoreCase = true) }) {
            throw RequestError("版本 $normalizedVerName 已存在")
        }
        return normalizedVerName
    }

    suspend fun ModpackUploadPreflightDto.preflight(player: RAccount) {
        if (modpackId == null) {
            validateCreateMetadata(
                player = player,
                name = name,
                mcVer = mcVer,
                verName = verName,
                iconUrl = iconUrl,
                sourceUrl = sourceUrl,
                info = info,
                categories = categories,
            )
            return
        }

        val targetId = modpackId ?: throw RequestError("整合包不存在")
        val modpack = ModpackQueryService.getById(targetId)
            ?: throw RequestError("整合包不存在")
        ModpackContext(player, modpack, null).validateVersionUpload(
            verName = verName,
            expectedMcVer = mcVer,
            expectedModLoader = modLoader,
        )
    }

    suspend fun Modpack.CreateWithVersionDto.createWithVersion(player: RAccount, uploadFile: File) {
        val metadata = validateCreateMetadata(
            player = player,
            name = name,
            mcVer = mcVer,
            verName = verName,
            iconUrl = iconUrl,
            sourceUrl = sourceUrl,
            info = info,
            categories = categories,
        )
        val modpack = Modpack(
            name = name,
            authorId = player._id,
            iconUrl = iconUrl?.trim()?.ifBlank { null },
            info = info?.trim()?.ifBlank { null },
            mcVer = mcVer,
            modloader = modLoader,
            sourceUrl = sourceUrl?.trim()?.ifBlank { null },
            playCount = 0,
            categories = metadata.normalizedCategories
        )
        modpack.dir.mkdirs()
        try {
            val prepared = prepareVersionUpload(
                modpack,
                metadata.normalizedVerName,
                uploadFile,
                mods,
                clientExtras = validateClientExtras(clientExtras),
                uploaderId = player._id,
            )
            var published = false
            var inserted = false
            runCatching {
                moveStagedArchiveToVersion(prepared)
                published = true
                modpack.versions += prepared.version
                dbcl.insertOne(modpack)
                inserted = true
                ModpackBuildService.enqueueVersionBuild(player, modpack, prepared.version)
            }.getOrElse { error ->
                prepared.stagedArchive.delete()
                if (published) cleanupUploadedVersionArtifacts(prepared.version)
                if (inserted) {
                    runCatching { dbcl.deleteOne(eq(Modpack::_id.name, modpack._id)) }
                }
                runCatching { modpack.dir.deleteRecursivelyNoSymlink() }
                if (error.isDuplicateKeyError()) throw RequestError("同名整合包已存在")
                throw error
            }
        } finally { deleteUploadTempFile(uploadFile, "创建整合包结束") }
    }

    suspend fun ModpackContext.createVersion(
        verName: String,
        uploadFile: File,
        mods: MutableList<Mod>,
        clientExtras: MutableList<Content> = arrayListOf(),
    ) {
        val normalizedVerName = ModpackQueryService.run { verName.validateVerName() }.getOrThrow()
        requireModpackUploadVersion(modpack.mcVer)
        try {
            withVersionPublicationLock(modpack._id, normalizedVerName) {
                var prepared: PreparedVersionUpload? = null
                var published = false
                var inserted = false
                var marker: PendingVersionPublication? = null
                var markerOwned = false
                try {
                    val freshModpack = reconcilePendingVersionPublication(modpack._id, normalizedVerName)
                        ?: throw RequestError("整合包不存在")
                    val freshContext = ModpackContext(player, freshModpack, null)
                    val freshVersionName = freshContext.validateVersionUpload(
                        verName = verName,
                    )
                    prepared = prepareVersionUpload(
                        freshModpack,
                        freshVersionName,
                        uploadFile,
                        mods,
                        clientExtras = validateClientExtras(clientExtras),
                        uploaderId = player._id,
                        stageArchive = false,
                    )
                    marker = pendingVersionPublication(prepared.version, prepared.stagedArchive)
                    writePendingVersionPublication(marker)
                    markerOwned = true
                    moveUploadedArchiveToStaged(uploadFile, prepared.stagedArchive)
                    moveStagedArchiveToVersion(prepared)
                    published = true
                    writePendingVersionPublication(marker.copy(state = PublicationState.PUBLISHED))
                    val result = dbcl.updateOne(
                        and(
                            eq(Modpack::_id.name, freshModpack._id),
                            not(
                                elemMatch(
                                    Modpack::versions.name,
                                    regex(
                                        Modpack.Version::name.name,
                                        "^${Pattern.quote(prepared.version.name)}$",
                                        "i"
                                    )
                                )
                            )
                        ),
                        Updates.push(Modpack::versions.name, prepared.version)
                    )
                    if (result.modifiedCount <= 0L) throw RequestError("版本 ${prepared.version.name} 已存在")
                    inserted = true
                    ModpackBuildService.enqueueVersionBuild(player, freshModpack, prepared.version)
                    try {
                        deletePendingVersionPublication(marker)
                        markerOwned = false
                    } catch (markerError: Throwable) {
                        lgr.warn(markerError) { "上传版本已提交，但删除恢复标记失败" }
                    }
                } catch (error: Throwable) {
                    prepared?.stagedArchive?.let { deleteOwnedFile(it) }
                    var rollbackConfirmed = !inserted
                    if (inserted) {
                        try {
                            val rollbackResult = dbcl.updateOne(
                                eq(Modpack::_id.name, modpack._id),
                                Updates.pull(
                                    Modpack::versions.name,
                                    eq(Modpack.Version::name.name, prepared!!.version.name)
                                )
                            )
                            val freshAfterRollback = ModpackQueryService.getById(modpack._id)
                            rollbackConfirmed = rollbackResult.modifiedCount > 0L ||
                                freshAfterRollback == null ||
                                freshAfterRollback.versions.none {
                                    it.name == prepared!!.version.name
                                }
                        } catch (rollbackError: Throwable) {
                            lgr.error(rollbackError) { "上传版本回滚数据库记录失败，保留归档和恢复标记" }
                            rollbackConfirmed = false
                        }
                    }
                    if (published && rollbackConfirmed) prepared?.let { cleanupPublishedVersionArtifact(it.version) }
                    if (markerOwned && rollbackConfirmed) marker?.let { deletePendingVersionPublication(it) }
                    throw error
                }
            }
        } finally { deleteUploadTempFile(uploadFile, "创建整合包版本结束") }
    }

    private data class PreparedVersionUpload(
        val version: Modpack.Version,
        val stagedArchive: File,
    )

    private enum class PublicationState {
        PREPARED,
        PUBLISHED,
    }

    private data class PendingVersionPublication(
        val markerFile: File,
        val targetFile: File,
        val stagedFile: File,
        val versionName: String,
        val state: PublicationState,
    )

    private data class VersionPublicationPaths(
        val storageDir: File,
        val lockFile: File,
        val markerFile: File,
    )

    private fun publicationKey(versionName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(versionName.lowercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun publicationPaths(modpackId: org.bson.types.ObjectId, versionName: String): VersionPublicationPaths {
        val storageDir = MODPACK_DATA_DIR.resolve(modpackId.toHexString())
        val key = publicationKey(versionName)
        return VersionPublicationPaths(
            storageDir = storageDir,
            lockFile = storageDir.resolve(".upload-${key}.lock"),
            markerFile = storageDir.resolve(".upload-${key}.pending"),
        )
    }

    private suspend fun <T> withVersionPublicationLock(
        modpackId: org.bson.types.ObjectId,
        versionName: String,
        block: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        val paths = publicationPaths(modpackId, versionName)
        paths.storageDir.mkdirs()
        val publicationKey = publicationKey(versionName)
        val localKey = "${modpackId.toHexString()}:$publicationKey"
        val mutex = versionPublicationMutexes[Math.floorMod(localKey.hashCode(), VERSION_PUBLICATION_MUTEX_STRIPE_COUNT)]
        mutex.withLock {
            withVersionPublicationFileLock(paths, block)
        }
    }

    private suspend fun <T> withVersionPublicationFileLock(
        paths: VersionPublicationPaths,
        block: suspend () -> T,
    ): T {
        return FileChannel.open(
            paths.lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val lock = channel.lock()
            try {
                block()
            } finally {
                lock.release()
            }
        }
    }

    private fun markerFailure(): Nothing =
        throw RequestError("整合包上传恢复标记无效")

    private fun safeMarkerFileName(value: String, expectedSuffix: String): Boolean =
        value.isNotBlank() &&
            value == File(value).name &&
            !value.contains('/') &&
            !value.contains('\\') &&
            value.endsWith(expectedSuffix)

    private fun readPendingVersionPublication(
        paths: VersionPublicationPaths,
        expectedVersionName: String,
    ): PendingVersionPublication? {
        if (!paths.markerFile.exists()) return null
        val lines = runCatching {
            Files.readString(paths.markerFile.toPath(), StandardCharsets.UTF_8)
                .trimEnd('\r', '\n')
                .split('\n')
                .map { it.trimEnd('\r') }
        }.getOrElse { markerFailure() }
        if (lines.size != 4) markerFailure()
        val state = runCatching { PublicationState.valueOf(lines[0]) }.getOrElse { markerFailure() }
        val versionName = lines[1]
        val targetName = lines[2]
        val stagedName = lines[3]
        if (versionName.isBlank() || publicationKey(versionName) != publicationKey(expectedVersionName)) {
            markerFailure()
        }
        if (ModpackQueryService.run { versionName.validateVerName() }.getOrNull() != versionName) {
            markerFailure()
        }
        if (targetName != "$versionName.tar.zst" || !safeMarkerFileName(targetName, ".tar.zst")) {
            markerFailure()
        }
        if (!safeMarkerFileName(stagedName, ".tar.zst") || !stagedName.startsWith(".upload-")) {
            markerFailure()
        }
        val absoluteStorageDir = paths.storageDir.absoluteFile
        val targetFile = absoluteStorageDir.toPath().resolve(targetName).normalize().toFile()
        val stagedFile = absoluteStorageDir.toPath().resolve(stagedName).normalize().toFile()
        if (targetFile.parentFile != paths.storageDir.absoluteFile || stagedFile.parentFile != paths.storageDir.absoluteFile) {
            markerFailure()
        }
        return PendingVersionPublication(
            markerFile = paths.markerFile,
            targetFile = targetFile,
            stagedFile = stagedFile,
            versionName = versionName,
            state = state,
        )
    }

    private fun writePendingVersionPublication(publication: PendingVersionPublication) {
        val temp = Files.createTempFile(
            publication.markerFile.parentFile.toPath(),
            ".upload-marker-",
            ".tmp",
        )
        try {
            Files.writeString(
                temp,
                listOf(
                    publication.state.name,
                    publication.versionName,
                    publication.targetFile.name,
                    publication.stagedFile.name,
                ).joinToString("\n") + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(temp, publication.markerFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, publication.markerFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun deletePendingVersionPublication(publication: PendingVersionPublication) {
        Files.deleteIfExists(publication.markerFile.toPath())
    }

    private fun deleteOwnedFile(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    private suspend fun reconcilePendingVersionPublication(
        modpackId: org.bson.types.ObjectId,
        versionName: String,
    ): Modpack? {
        val paths = publicationPaths(modpackId, versionName)
        val marker = readPendingVersionPublication(paths, versionName)
        val freshModpack = ModpackQueryService.getById(modpackId)
        if (marker != null) {
            val versionExists = freshModpack?.versions?.any {
                it.name.equals(marker.versionName, ignoreCase = true)
            } == true
            if (versionExists) {
                deleteOwnedFile(marker.stagedFile)
            } else if (marker.state == PublicationState.PUBLISHED || !marker.stagedFile.exists()) {
                deleteOwnedFile(marker.targetFile)
                deleteOwnedFile(marker.stagedFile)
            } else {
                deleteOwnedFile(marker.stagedFile)
            }
            deletePendingVersionPublication(marker)
        }
        return freshModpack
    }

    private fun pendingVersionPublication(
        version: Modpack.Version,
        stagedArchive: File,
    ): PendingVersionPublication {
        val paths = publicationPaths(version.modpackId, version.name)
        return PendingVersionPublication(
            markerFile = paths.markerFile,
            targetFile = version.zstdPack,
            stagedFile = stagedArchive,
            versionName = version.name,
            state = PublicationState.PREPARED,
        )
    }

    private fun cleanupPublishedVersionArtifact(version: Modpack.Version) {
        deleteOwnedFile(version.zstdPack)
    }

    internal fun writePendingVersionPublicationForTest(
        modpackId: org.bson.types.ObjectId,
        versionName: String,
        targetName: String = "$versionName.tar.zst",
        stagedName: String = ".upload-test-$versionName.tar.zst",
        published: Boolean = true,
    ): File {
        val paths = publicationPaths(modpackId, versionName)
        paths.storageDir.mkdirs()
        val marker = PendingVersionPublication(
            markerFile = paths.markerFile,
            targetFile = paths.storageDir.resolve(targetName),
            stagedFile = paths.storageDir.resolve(stagedName),
            versionName = versionName,
            state = if (published) PublicationState.PUBLISHED else PublicationState.PREPARED,
        )
        writePendingVersionPublication(marker)
        return paths.markerFile
    }

    private fun prepareVersionUpload(
        modpack: Modpack,
        verName: String,
        uploadFile: File,
        mods: MutableList<Mod>,
        clientExtras: MutableList<Content> = arrayListOf(),
        uploaderId: org.bson.types.ObjectId,
        stageArchive: Boolean = true,
    ): PreparedVersionUpload {
        mods.sortBy { it.slug.lowercase() }
        val version = Modpack.Version(
            modpackId = modpack._id,
            name = verName,
            changelog = "新上传",
            status = Modpack.Status.WAIT,
            mods = ModpackModProcessor.processMods(mods),
            clientExtras = clientExtras,
            time = System.currentTimeMillis(),
            uploaderId = uploaderId,
        )
        val stagedArchive = reserveStagedArchive(version, uploadFile.detectArchiveFormat())
        try {
            if (stageArchive) moveUploadedArchiveToStaged(uploadFile, stagedArchive)
            version.mods.sortBy { it.slug.lowercase() }
            return PreparedVersionUpload(version, stagedArchive)
        } catch (error: Throwable) {
            deleteOwnedFile(stagedArchive)
            throw error
        }
    }

    private fun validateClientExtras(extras: MutableList<Content>): MutableList<Content> {
        return runCatching {
            extras.forEach { extra ->
                require(extra.side != ContentSide.Server) { "客户端额外内容不能仅支持服务端" }
            }
            extras.validateAndMergeClientExtras().toMutableList()
        }
            .getOrElse { error -> throw RequestError(error.message ?: "客户端额外内容无效") }
    }

    private fun reserveStagedArchive(version: Modpack.Version, format: PackArchiveFormat): File {
        val extension = when (format) {
            PackArchiveFormat.TAR_ZST -> ".tar.zst"
            PackArchiveFormat.ZIP -> throw RequestError("现在只支持tar.zst格式传包，请更新客户端后重试")
        }
        version.storageDir.mkdirs()
        return Files.createTempFile(version.storageDir.toPath(), ".upload-", extension).toFile()
    }

    private fun moveUploadedArchiveToStaged(uploadFile: File, stagedArchive: File) {
        Files.move(uploadFile.toPath(), stagedArchive.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun moveStagedArchiveToVersion(prepared: PreparedVersionUpload) {
        try {
            Files.move(prepared.stagedArchive.toPath(), prepared.version.zstdPack.toPath())
        } catch (error: java.nio.file.FileAlreadyExistsException) {
            throw RequestError("版本 ${prepared.version.name} 已存在")
        }
    }

    internal fun cleanupUploadedVersionArtifacts(version: Modpack.Version) {
        version.zip.delete()
        version.zstdPack.delete()
        version.clientZip.delete()
        version.clientZstdPack.delete()
        ModpackBuildService.cleanupVersionBuildDirs(version)
    }

    private fun Throwable.isDuplicateKeyError() =
        this is MongoWriteException && ErrorCategory.fromErrorCode(code) == ErrorCategory.DUPLICATE_KEY
}

internal const val PART_SHA1_HEADER = "X-Part-SHA1"
internal val parallelUploadService get() = ModpackUploadService.parallelUploadService
internal suspend fun io.ktor.server.routing.RoutingContext.uploadSessionId(): java.util.UUID =
    param("uploadId").let { runCatching { java.util.UUID.fromString(it) }.getOrElse { throw ParamError("上传会话ID无效") } }
