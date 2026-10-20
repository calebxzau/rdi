package calebxzau.rdi.client.account

import calebxzau.rdi.client.RDIClient
import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.serdesJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
private data class MsaSessionsData(
    /** RDI account id → bound Minecraft profile id → MinecraftAuth session JSON. */
    val accounts: Map<String, Map<String, String>> = emptyMap(),
)

/** Microsoft sessions used for online play of local packs, kept in `msa_sessions.json` (plain text). */
class MsaSessionStore(private val file: File) {
    private val lgr by Loggers
    private val mutex = Mutex()

    suspend fun find(accountId: String, msid: String): Result<String?> = operation {
        read().accounts[accountId]?.get(msid)
    }

    suspend fun save(accountId: String, msid: String, sessionJson: String): Result<Unit> = operation {
        val data = readOrReset()
        val sessions = data.accounts[accountId].orEmpty() + (msid to sessionJson)
        write(data.copy(accounts = data.accounts + (accountId to sessions)))
    }

    suspend fun remove(accountId: String, msid: String): Result<Unit> = operation {
        val data = readOrReset()
        val sessions = data.accounts[accountId] ?: return@operation
        write(data.copy(accounts = data.accounts + (accountId to sessions - msid)))
    }

    /** Forgets every session of [accountId], e.g. when the player logs out of RDI. */
    suspend fun clear(accountId: String): Result<Unit> = operation {
        val data = readOrReset()
        if (accountId !in data.accounts) return@operation
        write(data.copy(accounts = data.accounts - accountId))
    }

    private fun read(): MsaSessionsData =
        if (!file.isFile) MsaSessionsData() else serdesJson.decodeFromString(file.readText())

    /** A damaged file is set aside so a new login can still be saved. */
    private fun readOrReset(): MsaSessionsData = runCatching(::read).getOrElse { cause ->
        val backup = file.resolveSibling("${file.name}.broken-${System.currentTimeMillis()}")
        lgr.warn(cause) { "正版会话文件损坏，已改名为${backup.name}" }
        Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        MsaSessionsData()
    }

    private fun write(data: MsaSessionsData) {
        file.parentFile?.mkdirs()
        val temporary = file.resolveSibling(".${file.name}.tmp").toPath()
        Files.writeString(temporary, serdesJson.encodeToString(data))
        try {
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun <T> operation(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                Result.success(block())
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                Result.failure(cause)
            }
        }
    }

    companion object {
        val shared: MsaSessionStore by lazy { MsaSessionStore(File(RDIClient.DIR, "msa_sessions.json")) }
    }
}
