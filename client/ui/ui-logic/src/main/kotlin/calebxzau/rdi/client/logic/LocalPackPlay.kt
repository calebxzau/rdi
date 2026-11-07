package calebxzau.rdi.client.logic

import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.LocalModpackStore
import calebxzhou.rdi.client.service.LocalPlayAccountMode
import calebxzhou.rdi.client.service.ModpackLocalDir
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzau.rdi.client.account.OfflineGameAccount
import calebxzau.rdi.client.account.OnlineGameAccountService
import calebxzau.rdi.mclaunch.MinecraftAccount
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode

/** The same local singleplayer launch arguments as the Compose launcher. */
fun localPackPlayArgs(pack: ModpackLocalDir, account: MinecraftAccount): McPlayArgs = McPlayArgs(
    title = "单机 - ${pack.name}", mcVer = pack.mcVersion, modLoader = pack.modLoader,
    versionId = pack.versionId, playArg = "", modpackName = pack.name,
    versionDir = pack.dir.absolutePath, modpackIconUrl = pack.iconUrl, versionName = pack.verName,
    account = account, withRdiCore = false,
)

fun rdiPackPlayArgs(pack: ModpackLocalDir): McPlayArgs {
    require(!pack.isLocal) { "请选择RDI整合包" }
    return McPlayArgs(
        title = "单机 - ${pack.name}", mcVer = pack.mcVersion, modLoader = pack.modLoader,
        versionId = pack.versionId,
        playArg = "${server.hqUrl}\n127.0.0.1:55667\ntest\n55555\n${loggedAccount.uuid}\n${loggedAccount.name}",
        modpackName = pack.name, versionDir = pack.dir.absolutePath,
        modpackIconUrl = pack.iconUrl, versionName = pack.verName,
    )
}

class LocalPackPlay(private val online: Lazy<OnlineGameAccountService> = lazy { OnlineGameAccountService() }) {
    private val logger = KotlinLogging.logger {}

    suspend fun prepare(
        pack: ModpackLocalDir,
        mode: LocalPlayAccountMode,
        nickname: String,
        onDeviceCode: (MsaDeviceCode) -> Unit,
    ): McPlayArgs {
        require(pack.isLocal) { "请选择本地整合包" }
        val account = when (mode) {
            LocalPlayAccountMode.Offline -> OfflineGameAccount.create(nickname).getOrThrow()
            LocalPlayAccountMode.Online -> online.value.account(
                accountId = loggedAccount._id.toHexString(),
                msid = requireNotNull(loggedAccount.msid) { "请先在玩家信息里绑定微软账号" },
                onDeviceCode = onDeviceCode,
            ).getOrThrow()
        }
        withContext(Dispatchers.IO) {
            LocalModpackStore.update(pack.dir) { info ->
                info.copy(lastAccountMode = mode,
                    lastOfflineName = if (mode == LocalPlayAccountMode.Offline) account.name else info.lastOfflineName)
            }.onFailure { logger.warn(it) { "保存整合包${pack.versionId}的游玩方式失败" } }
        }
        return localPackPlayArgs(pack, account)
    }
}
