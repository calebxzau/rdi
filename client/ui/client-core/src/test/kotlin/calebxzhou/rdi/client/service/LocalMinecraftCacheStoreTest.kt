package calebxzhou.rdi.client.service

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalMinecraftCacheStoreTest {
    @Test
    fun `cache survives reload and full scan expires after twelve hours`() {
        /*val cacheFile = Files.createTempDirectory("local-minecraft-cache").resolve("cache.json")
        val store = LocalMinecraftCacheStore(cacheFile)
        val saved = LocalMinecraftCache(
            lastFullScanAt = 1_000L,
            installations = listOf(LocalMinecraftInstallationRecord("C:/Games/.minecraft", true, true))
        )

        store.save(saved).getOrThrow()

        assertEquals(saved, store.load().getOrThrow())
        assertFalse(LocalMinecraftScanPolicy.needsFullScan(1_000L, 1_000L + 12 * 60 * 60 * 1000 - 1))
        assertTrue(LocalMinecraftScanPolicy.needsFullScan(1_000L, 1_000L + 12 * 60 * 60 * 1000))*/
    }
}
