package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Task2Progress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object UpdateService {
    private val coreUpdates = mutableMapOf<String, CompletableDeferred<Result<McCoreUpdateResult>>>()

    suspend fun updateUpdater(
        onStatus: (String) -> Unit,
        onDetail: (String) -> Unit
    ): Result<UpdaterUpdateResult> = updateUpdater(onStatus, onDetail, UpdaterUpdater::update)

    internal suspend fun updateUpdater(
        onStatus: (String) -> Unit,
        onDetail: (String) -> Unit,
        update: suspend ((String) -> Unit, (String) -> Unit) -> Result<UpdaterUpdateResult>
    ): Result<UpdaterUpdateResult> = withContext(Dispatchers.IO) {
        update(onStatus, onDetail)
    }

    suspend fun prepareMcCore(
        mcVersion: McVersion,
        modLoader: ModLoader,
        modsDir: File,
        onStatus: (String) -> Unit,
        onDetail: (String) -> Unit,
        onProgress: (Task2Progress) -> Unit = {},
    ): Result<McCoreUpdateResult> = prepareMcCore(
        mcVersion = mcVersion,
        modLoader = modLoader,
        modsDir = modsDir,
        onStatus = onStatus,
        onDetail = onDetail,
        updateCore = McCoreUpdater::update,
        linkCore = { version, loader, targetDir, update ->
            ModpackService.installRdiCore(
                mcVersion = version,
                modLoader = loader,
                modsDir = targetDir,
                update = update,
                onDetail = onDetail,
                onProgress = onProgress,
            )
        }
    )

    internal suspend fun prepareMcCore(
        mcVersion: McVersion,
        modLoader: ModLoader,
        modsDir: File,
        onStatus: (String) -> Unit,
        onDetail: (String) -> Unit,
        updateCore: suspend (McVersion, ModLoader, (String) -> Unit, (String) -> Unit) -> Result<McCoreUpdateResult>,
        linkCore: suspend (McVersion, ModLoader, File, McCoreUpdateResult) -> Unit
    ): Result<McCoreUpdateResult> {
        val slug = McCoreUpdater.slug(mcVersion, modLoader)
        val pending = CompletableDeferred<Result<McCoreUpdateResult>>()
        val activeUpdate = synchronized(coreUpdates) {
            coreUpdates[slug] ?: pending.also { coreUpdates[slug] = it }
        }

        if (activeUpdate === pending) {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    updateCore(mcVersion, modLoader, onStatus, onDetail).getOrThrow()
                }
            }
            pending.complete(result)
            synchronized(coreUpdates) {
                if (coreUpdates[slug] === pending) coreUpdates.remove(slug)
            }
        } else {
            onStatus("等待相同RDI核心检查...")
        }

        return activeUpdate.await().mapCatching { update ->
            linkCore(mcVersion, modLoader, modsDir, update)
            update
        }
    }
}
