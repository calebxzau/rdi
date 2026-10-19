package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.database.PlayerInfoStore
import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.client.auth.LocalCredentials
import calebxzhou.rdi.client.model.LoginInfo
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.rdiResponse
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.json
import calebxzhou.rdi.common.model.MsaAccountInfo
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.net.json
import calebxzhou.rdi.common.util.ok
import calebxzau.rdi.playerinfo.PlayerInfoCache
import calebxzau.rdi.playerinfo.PlayerInfoSource
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.java.JavaAuthManager
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService
import net.raphimc.minecraftauth.msa.service.util.ParamMsaAuthServiceSupplier
import org.bson.types.ObjectId
import java.util.function.Consumer

private fun createPlayerInfoCache(store: PlayerInfoStore?): PlayerInfoCache = PlayerInfoCache(
    store = store,
    source = PlayerInfoSource { ids -> PlayerService.getPlayerInfos(ids) },
    defaultFactory = { id -> RAccount.Dto(id, RAccount.DEFAULT.name, RAccount.Cloth()) }
)

var playerInfoCache: PlayerInfoCache = createPlayerInfoCache(null)
    private set

fun initializePlayerInfoCache(store: PlayerInfoStore?) {
    val previous = playerInfoCache
    playerInfoCache = createPlayerInfoCache(store)
    previous.close()
}

object PlayerService {
    private val lgr by Loggers
    private const val MICROSOFT_LOGIN_TIMEOUT_MS = 10 * 60 * 1000
    fun microsoftLogin(onDevice: (MsaDeviceCode) -> Unit): Result<JavaAuthManager> = runCatching {
        val deviceCodeConsumer = Consumer<MsaDeviceCode> { deviceCode ->
            onDevice(deviceCode)
        }
        JavaAuthManager.create(MinecraftAuth.createHttpClient("rdi-client"))
            .login(
                ParamMsaAuthServiceSupplier<Consumer<MsaDeviceCode>> { httpClient, applicationConfig, callback ->
                    DeviceCodeMsaAuthService(
                        httpClient,
                        applicationConfig,
                        callback,
                        MICROSOFT_LOGIN_TIMEOUT_MS
                    )
                },
                deviceCodeConsumer
            )
    }
    suspend fun login(usr: String, pwd: String): Result<RAccount> = runCatching {
        val creds = LocalCredentials.read()
        val spec = getCachedOrFetchHwSpecJson()

        val resp = server.createRequest(
            path = "player/login",
            method = HttpMethod.Post,
            params = mutableMapOf("usr" to usr, "pwd" to pwd, "spec" to spec)
        )
        val account = resp.rdiResponse<RAccount>().run {
            data ?: run {
                throw RequestError(msg)
            }
        }
        account.jwt = resp.headers["jwt"]
        val loginInfo = LoginInfo(account.qq, account.name, account.pwd, System.currentTimeMillis())
        creds.loginInfos += account._id.toHexString() to loginInfo
        creds.autoLoginDisabled = false
        creds.save()
        loggedAccount = account
        account
    }

    suspend fun getJwt(usr: String, pwd: String): String {
        return server.makeRequest<String>(
            "player/jwt",
            HttpMethod.Post,
            params = mapOf("usr" to usr, "pwd" to pwd)
        ).data!!
    }

    suspend fun resetPasswordByMsa(msa: MsaAccountInfo, newPwd: String): Result<Unit> = runCatching {
        val resp = server.makeRequest<Unit>(
            "player/reset-pwd/msa",
            HttpMethod.Post
        ) {
            json()
            setBody(RAccount.ResetPasswordByMsaDto(msa, newPwd).json)
        }
        if (!resp.ok) throw RequestError(resp.msg)
    }

    suspend fun getPlayerInfo(uid: ObjectId): RAccount.Dto {
        return try {
            server.makeRequest<RAccount.Dto>("player/${uid}/info").data ?: RAccount.DEFAULT.dto
        } catch (e: Exception) {
            lgr.warn { "获取玩家信息失败" + "\n" + e }
            RAccount.DEFAULT.dto
        }
    }

    suspend fun getPlayerInfos(uids: List<ObjectId>): Result<List<RAccount.Dto>> {
        if (uids.isEmpty()) return Result.success(emptyList())
        return try {
            val idsParam = uids.joinToString("\n") { it.toHexString() }
            Result.success(
                server.makeRequest<List<RAccount.Dto>>("player/infos", params = mapOf("ids" to idsParam)).data
                    ?: emptyList()
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            lgr.warn { "批量获取玩家信息失败" + "\n" + cause }
            Result.failure(cause)
        }
    }

    suspend fun setCloth(cloth: RAccount.Cloth): Result<Unit> = runCatching {
        val params = mutableMapOf<String, Any>()
        params["isSlim"] = cloth.isSlim.toString()
        params["skin"] = cloth.skin
        cloth.cape?.let {
            params["cape"] = it
        }

        val resp = server.makeRequest<Unit>("player/skin", HttpMethod.Post, params = params)
        if (resp.ok) ok()
        else throw RequestError(resp.msg)
    }
}
