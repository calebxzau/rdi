package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateServiceTest {
    @Test
    fun `updater flow delegates without checking MC cores`() = runBlocking {
        var updaterRan = false

        val result = UpdateService.updateUpdater(
            onStatus = {},
            onDetail = {},
            update = { _, _ ->
                updaterRan = true
                Result.success(UpdaterUpdateResult.UPDATED)
            }
        )

        assertTrue(updaterRan)
        assertEquals(UpdaterUpdateResult.UPDATED, result.getOrThrow())
    }

    @Test
    fun `simultaneous launches share one core update and link both modpacks`() = runBlocking {
        val updateStarted = CompletableDeferred<Unit>()
        val finishUpdate = CompletableDeferred<Unit>()
        val updateCount = AtomicInteger()
        val linkedDirs = mutableListOf<java.io.File>()
        val root = Files.createTempDirectory("core-dedupe").toFile()
        val update: suspend (McVersion, ModLoader, (String) -> Unit, (String) -> Unit) -> Result<McCoreUpdateResult> =
            { _, _, _, _ ->
                updateCount.incrementAndGet()
                updateStarted.complete(Unit)
                finishUpdate.await()
                Result.success(McCoreUpdateResult(updated = true))
            }
        val link: suspend (McVersion, ModLoader, java.io.File, McCoreUpdateResult) -> Unit = { _, _, dir, _ ->
            synchronized(linkedDirs) { linkedDirs += dir }
        }

        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                UpdateService.prepareMcCore(
                    McVersion.V201,
                    ModLoader.forge,
                    root.resolve("first"),
                    {},
                    {},
                    update,
                    link
                )
            }
            updateStarted.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                UpdateService.prepareMcCore(
                    McVersion.V201,
                    ModLoader.forge,
                    root.resolve("second"),
                    {},
                    {},
                    update,
                    link
                )
            }
            finishUpdate.complete(Unit)

            first.await().getOrThrow()
            second.await().getOrThrow()
            assertEquals(1, updateCount.get())
            assertEquals(setOf("first", "second"), linkedDirs.map { it.name }.toSet())
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }
}
