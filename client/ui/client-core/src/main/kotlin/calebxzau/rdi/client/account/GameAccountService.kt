package calebxzau.rdi.client.account

import calebxzau.rdi.common.logging.Loggers
import calebxzau.rdi.mclaunch.MinecraftAccount
import calebxzhou.rdi.client.service.PlayerService
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.java.JavaAuthManager
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode
import java.nio.charset.StandardCharsets
import java.util.UUID

private val OFFLINE_NAME_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")

/** Offline identities follow vanilla: `OfflinePlayer:<name>` name-based UUID, `legacy` user type. */
object OfflineGameAccount {
    fun uuid(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(StandardCharsets.UTF_8))

    fun create(name: String): Result<MinecraftAccount> = runCatching {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入游戏昵称" }
        require(trimmed.length <= 16) { "游戏昵称最多16个字符" }
        require(trimmed.none(Char::isWhitespace)) { "游戏昵称不能包含空格" }
        MinecraftAccount(name = trimmed, uuid = uuid(trimmed).toString(), accessToken = "0", userType = "legacy")
    }

    /** Names vanilla servers accept; others usually work in singleplayer but may break mods or LAN. */
    fun isStandardName(name: String): Boolean = OFFLINE_NAME_PATTERN.matches(name.trim())
}

/** A Microsoft login that MinecraftAuth refreshes; abstracted for tests. */
interface MsaGameSession {
    val profileId: UUID
    val profileName: String
    val accessToken: String
    fun toJson(): String
}

interface MsaGameSessionProvider {
    /** Restores a saved session and refreshes its tokens. */
    fun restore(json: String): Result<MsaGameSession>

    /** Signs in with a device code; blocks until the player finishes or it times out. */
    fun login(onDeviceCode: (MsaDeviceCode) -> Unit): Result<MsaGameSession>
}

private class MinecraftAuthSession(private val manager: JavaAuthManager) : MsaGameSession {
    private val profile = manager.minecraftProfile.upToDate
    override val profileId: UUID = profile.id
    override val profileName: String = profile.name
    override val accessToken: String = manager.minecraftToken.upToDate.token
    override fun toJson(): String = JavaAuthManager.toJson(manager).toString()
}

object MinecraftAuthSessionProvider : MsaGameSessionProvider {
    override fun restore(json: String): Result<MsaGameSession> = runCatching {
        val manager = JavaAuthManager.fromJson(
            MinecraftAuth.createHttpClient("rdi-client"),
            JsonParser.parseString(json).asJsonObject,
        )
        MinecraftAuthSession(manager)
    }

    override fun login(onDeviceCode: (MsaDeviceCode) -> Unit): Result<MsaGameSession> =
        PlayerService.microsoftLogin(onDeviceCode).mapCatching(::MinecraftAuthSession)
}

/** Online identity for local packs: the Microsoft profile bound to the RDI account (its msid). */
class OnlineGameAccountService(
    private val store: MsaSessionStore = MsaSessionStore.shared,
    private val provider: MsaGameSessionProvider = MinecraftAuthSessionProvider,
) {
    private val lgr by Loggers

    suspend fun account(
        accountId: String,
        msid: UUID,
        onDeviceCode: (MsaDeviceCode) -> Unit,
    ): Result<MinecraftAccount> = try {
        Result.success(withContext(Dispatchers.IO) { resolve(accountId, msid, onDeviceCode) })
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        Result.failure(cause)
    }

    private suspend fun resolve(accountId: String, msid: UUID, onDeviceCode: (MsaDeviceCode) -> Unit): MinecraftAccount {
        val key = msid.toString()
        val saved = store.find(accountId, key).getOrElse { cause ->
            lgr.warn(cause) { "读取正版会话失败，将重新登录" }
            null
        }
        val restored = saved?.let { json ->
            provider.restore(json)
                .onFailure { lgr.warn(it) { "正版会话已失效，将重新登录" } }
                .getOrNull()
                ?.takeIf { session ->
                    (session.profileId == msid).also { matches ->
                        if (!matches) lgr.warn { "保存的正版会话不属于绑定账号，将重新登录" }
                    }
                }
        }
        val session = restored ?: provider.login(onDeviceCode).getOrThrow().also { session ->
            require(session.profileId == msid) {
                "登录的正版账号${session.profileName}与RDI账号绑定的正版账号不一致"
            }
        }
        store.save(accountId, key, session.toJson())
            .onFailure { lgr.warn(it) { "保存正版会话失败，下次启动需重新登录" } }
        return MinecraftAccount(
            name = session.profileName,
            uuid = session.profileId.toString(),
            accessToken = session.accessToken,
            userType = "msa",
        )
    }
}
