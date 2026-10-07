package calebxzau.rdi.client.service

import calebxzau.rdi.anvilrw.remap.SaveLoader
import calebxzau.rdi.anvilrw.remap.SavePlayerKind
import calebxzau.rdi.anvilrw.remap.SavePlayerScanner
import calebxzau.rdi.anvilrw.remap.SaveScanResult
import calebxzau.rdi.anvilrw.remap.SaveSnapshot
import calebxzau.rdi.anvilrw.remap.SaveSourceLock
import calebxzau.rdi.anvilrw.remap.SaveSyncChunks
import calebxzau.rdi.anvilrw.remap.SaveUuidRemapper
import calebxzau.rdi.anvilrw.remap.SaveInUseException
import calebxzau.rdi.anvilrw.remap.UnhandledFile
import calebxzau.rdi.client.service.HostSaveImportMarkingService.Companion.toPlayerError
import calebxzau.rdi.common.model.HostWorldImportCreateDto
import calebxzau.rdi.common.model.HostWorldImportMemberWarning
import calebxzau.rdi.common.model.HostWorldImportPrecheckDto
import calebxzau.rdi.common.model.HostWorldImportStatus
import calebxzau.rdi.common.model.SavePlayerMsidMatchVo
import calebxzhou.rdi.client.net.lgr
import calebxzhou.rdi.client.service.ClientDirs
import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.service.MojangApi
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.digestHex
import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.common.util.sha1dig
import calebxzhou.rdi.common.util.toObjectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bson.types.ObjectId
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** What the server and Mojang say about the players of a save, for the mapping step. */
data class SaveImportPreparation(
    val scan: SaveScanResult,
    /** Current Mojang names of online players. */
    val mojangNames: Map<UUID, String>,
    /** Online players whose Microsoft profile is bound to an RDI account. */
    val msidMatches: Map<UUID, SavePlayerMsidMatchVo>,
    /** RDI players of the save whose accounts still exist. */
    val existingRdiPlayers: Set<UUID>,
)

/** Everything the owner confirmed; the import task runs from this alone. */
data class SaveImportPlan(
    val hostId: ObjectId,
    val hostName: String,
    val record: SaveImportMarkingRecord,
    val mapping: Map<UUID, UUID>,
    val memberPlayerIds: List<ObjectId>,
    val mcVersionName: String,
    val loader: ModLoader,
    /** Membership warnings the owner saw; new ones stop the import before upload. */
    val confirmedWarnings: List<HostWorldImportMemberWarning>,
)

fun SaveLoader.toModLoader(): ModLoader = when (this) {
    SaveLoader.Forge -> ModLoader.forge
    SaveLoader.NeoForge -> ModLoader.neoforge
}

/**
 * Imports a marked save copy into a host (plan §4 steps 7–14): scan, mapping lookups, then a background
 * task that remaps and filters the copy into a temp dir, packs it, uploads it in parts and waits for the
 * server. The marking copy is deleted after a successful import.
 */
class HostSaveImportService(
    private val api: HostSaveImportApi,
    private val marking: HostSaveImportMarkingService,
    private val tempRoot: File = ClientDirs.packProcDir.resolve("save-imports"),
    private val mojangName: suspend (UUID) -> Result<String> = { uuid -> MojangApi.getProfile(uuid).map { it.name } },
    private val pollIntervalMillis: Long = 3_000L,
    private val pollTimeoutMillis: Long = 60 * 60 * 1000L,
) {
    suspend fun prepare(record: SaveImportMarkingRecord): Result<SaveImportPreparation> = runCatchingIo {
        marking.checkIdentity(record).getOrThrow()
        val actualScan = SavePlayerScanner().scan(record.copy).getOrElse { throw it.toPlayerError() }
        val originalOwner = if (record.identityVersion >= 1) record.originalOwnerUuid?.let(UUID::fromString)
            else actualScan.singleplayerHostUuid
        // Keep the scan's actual Data.Player identity for remapping, but preserve the original owner badge.
        val scan = actualScan.copy(players = actualScan.players.map { it.copy(isSingleplayerHost = it.uuid == originalOwner) })
        val online = scan.players.filter { it.kind == SavePlayerKind.Online }.map { it.uuid }
        val names = online.mapNotNull { uuid ->
            mojangName(uuid).fold(
                onSuccess = { uuid to it },
                onFailure = { error ->
                    lgr.warn(error) { "查询正版玩家名失败: ${uuid}" }
                    null
                },
            )
        }.toMap()
        val matches = api.msidMatch(online).associateBy { it.msid }
        val rdiPlayers = scan.players.filter { it.kind == SavePlayerKind.Rdi }.map { it.uuid }
        val existing = api.existingAccounts(rdiPlayers.map { it.toObjectId().getOrThrow() })
        SaveImportPreparation(scan, names, matches, rdiPlayers.filter { it.toObjectId().getOrThrow() in existing }.toSet())
    }

    fun importTask(plan: SaveImportPlan): Task2 = Task2.Leaf("导入存档到房间${plan.hostName}") { context -> runImport(plan, context) }

    private suspend fun runImport(plan: SaveImportPlan, context: Task2Context) = withContext(Dispatchers.IO) {
        var temporary: Path? = null
        var importId: UUID? = null
        var completed = false
        try {
            marking.checkIdentity(plan.record).getOrThrow()
            Files.createDirectories(tempRoot.toPath())
            temporary = Files.createTempDirectory(tempRoot.toPath(), "import-")
            val remapped = temporary.resolve("remapped")
            val unhandled = remap(plan, remapped, context)

            val archive = temporary.resolve("world.tar.zst")
            pack(remapped, archive, context)
            val size = Files.size(archive)
            if (size > MAX_UPLOAD_BYTES) throw RequestError("处理后的存档有${size.humanFileSize}，超过${MAX_UPLOAD_BYTES.humanFileSize}的上限，请减少标记的区块")
            context.emit(Task2Progress("正在校验存档", 0.5f))
            val sha1 = sha1(archive, context)

            val fresh = api.precheck(plan.hostId, HostWorldImportPrecheckDto(plan.mcVersionName, plan.loader, plan.memberPlayerIds))
            val newWarnings = fresh.memberWarnings - plan.confirmedWarnings.toSet()
            if (newWarnings.isNotEmpty()) {
                throw RequestError("房间成员情况有变化（${newWarnings.joinToString("；") { it.reason }}），请重新确认后导入")
            }
            val session = api.create(plan.hostId, HostWorldImportCreateDto(size, sha1, plan.mcVersionName, plan.loader, plan.memberPlayerIds))
            importId = session.id
            ChunkedUploader(
                file = archive,
                descriptor = ChunkedUploadDescriptor(session.size, session.partSize, session.partCount, session.uploadedParts),
                uploadPart = { index, bytes, partSha1, onBytesSent ->
                    api.uploadPart(plan.hostId, session.id, index, bytes, partSha1, onBytesSent)
                },
                ensureActive = context::ensureActive,
                onProgress = { done, parts ->
                    context.emit(
                        Task2Progress(
                            "正在上传存档${parts}/${session.partCount}",
                            0.6f + 0.3f * done / size,
                            done,
                            size,
                            completedItems = parts,
                            totalItems = session.partCount,
                        ),
                    )
                },
            ).upload()
            context.ensureActive()
            api.complete(plan.hostId, session.id)
            completed = true
            val result = awaitResult(plan.hostId, session.id, context)
            if (result == HostWorldImportStatus.Ready) {
                marking.delete(plan.record).onFailure { error -> lgr.warn(error) { "清理存档导入副本失败: ${plan.record.copyPath}" } }
                val note = if (unhandled.isEmpty()) "" else "，有${unhandled.size}个文件可能还有未迁移的玩家数据"
                context.emit(Task2Progress("存档已导入房间${plan.hostName}${note}", 1f))
            }
        } catch (cause: Throwable) {
            val created = importId
            if (created != null && !completed) {
                withContext(NonCancellable) {
                    runCatching { withTimeout(10_000L) { api.cancel(plan.hostId, created) } }
                        .onFailure { lgr.warn(it) { "取消存档导入任务失败: ${created}" } }
                }
            }
            throw cause
        } finally {
            temporary?.let { dir ->
                withContext(NonCancellable) {
                    dir.toFile().deleteRecursivelyNoSymlink()
                    if (Files.exists(dir)) lgr.warn { "清理存档导入临时目录失败: ${dir}" }
                }
            }
        }
    }

    /** Remaps the copy under its lock (the game must be closed). Returns the files that may hold unmigrated data. */
    private fun remap(plan: SaveImportPlan, target: Path, context: Task2Context): List<UnhandledFile> {
        val copy = plan.record.copy
        val lock = SaveSourceLock.acquire(copy).getOrElse { error ->
            throw if (error is SaveInUseException) RequestError("请先退出游戏再导入", error) else error.toPlayerError()
        }
        return lock.use {
            val snapshot = SaveSnapshot.take(copy).getOrElse { throw it.toPlayerError() }
            val syncFile = copy.resolve(SaveSyncChunks.RELATIVE_PATH)
            if (!Files.isRegularFile(syncFile)) throw RequestError("请至少标记一个要保留的区块")
            val chunks = SaveSyncChunks.parse(snapshot.readFile(SaveSyncChunks.RELATIVE_PATH).getOrThrow())
                .getOrElse { throw RequestError("同步区块列表无效", it) }
            if (chunks.isEmpty()) throw RequestError("请至少标记一个要保留的区块")
            val report = SaveUuidRemapper(plan.mapping, chunks).remapTree(
                copy,
                target,
                snapshot,
                progress = { progress ->
                    val fraction = if (progress.bytesTotal == 0L) 0f else progress.bytesDone.toFloat() / progress.bytesTotal
                    context.emit(Task2Progress("正在处理存档", 0.4f * fraction, progress.bytesDone, progress.bytesTotal))
                },
                ensureActive = context::ensureActive,
            ).getOrElse { throw it.toPlayerError() }
            report.possiblyUnmigrated.forEach { lgr.warn { "可能未迁移的玩家数据: ${it.relativePath}（${it.reason}）" } }
            report.possiblyUnmigrated
        }
    }

    private fun pack(root: Path, archive: Path, context: Task2Context) {
        val entries = Files.walk(root).use { stream -> stream.filter { it != root }.sorted().toList() }
        TarZstArchiveWriter(archive.toFile()).use { writer ->
            entries.forEachIndexed { index, path ->
                context.ensureActive()
                val relative = root.relativize(path).joinToString("/")
                if (Files.isDirectory(path)) {
                    writer.addDirectory(relative, Files.getLastModifiedTime(path).toMillis())
                } else {
                    Files.newInputStream(path).use { input ->
                        writer.addFileStreaming(relative, input, Files.size(path), Files.getLastModifiedTime(path).toMillis(), context::ensureActive)
                    }
                }
                context.emit(Task2Progress("正在打包存档", 0.4f + 0.1f * (index + 1) / entries.size, completedItems = index + 1, totalItems = entries.size))
            }
        }
    }

    private fun sha1(file: Path, context: Task2Context): String {
        val digest = sha1dig()
        val buffer = ByteArray(1024 * 1024)
        Files.newInputStream(file).use { input ->
            while (true) {
                context.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digestHex()
    }

    private suspend fun awaitResult(hostId: ObjectId, importId: UUID, context: Task2Context): HostWorldImportStatus {
        val deadline = System.currentTimeMillis() + pollTimeoutMillis
        while (System.currentTimeMillis() < deadline) {
            context.ensureActive()
            val status = api.status(hostId, importId)
            when (status.status) {
                HostWorldImportStatus.Ready -> return HostWorldImportStatus.Ready
                HostWorldImportStatus.Failed -> throw RequestError(status.errorMessage ?: "存档导入失败")
                else -> context.emit(Task2Progress("服务器正在处理存档", 0.95f))
            }
            delay(pollIntervalMillis)
        }
        context.emit(Task2Progress("服务器仍在处理存档，结果将通过邮件通知", 1f))
        return HostWorldImportStatus.Processing
    }

    private suspend fun <T> runCatchingIo(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024
    }
}
