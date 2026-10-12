package calebxzau.rdi.mc.syncchunk.backup

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncChunkBackupFileRulesTest {
    private val world: Path = Files.createTempDirectory("syncchunk-backup-rules")

    @AfterTest
    fun cleanUp() {
        world.toFile().deleteRecursively()
    }

    @Test
    fun includesItemAndPlayerDataButNotWorldWideOrRdiData() {
        listOf(
            "data/sophisticatedbackpacks.dat",
            "data/mekanism_InventoryFrequencyHandler.dat",
            "data/endertanks/[-Public-].dat",
            "data/lootr/0/00/0064e09f-6b67-4b3a-8243-242365e9742e.dat",
            "ftbquests/5cdd4196-f257-46c1-82f1-c3f6c7682f8a.snbt",
            "ftbessentials/playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.snbt",
            "stats/1512ef02-2b79-4494-a4e9-e35cd8e03923.json",
            "advancements/1512ef02-2b79-4494-a4e9-e35cd8e03923.json",
            "playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.dat",
            "playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.cosarmor",
        ).forEach { assertTrue(SyncChunkBackupFileRules.includes(it), it) }

        listOf(
            "data/rdi_sync_chunks.dat",
            "data/rdi_force_pvp.dat",
            SyncChunkBackupWorldToken.RELATIVE_PATH,
            "data/capabilities.dat",
            "data/raids.dat",
            "data/scoreboard.dat",
            "playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.dat_old",
            "playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923-4471236471827.dat",
            "data/map_0.dat.tmp",
            "data/.hidden",
            "level.dat",
            "region/r.0.0.mca",
            "tombstone/saved_players/a/b.save",
            "data/../level.dat",
            "data//x.dat",
            "/data/x.dat",
            "data\\x.dat",
        ).forEach { assertFalse(SyncChunkBackupFileRules.includes(it), it) }
    }

    @Test
    fun excludedNamesOnlyApplyAtTheTopOfData() {
        assertTrue(SyncChunkBackupFileRules.includes("data/somemod/raids.dat"))
        assertTrue(SyncChunkBackupFileRules.includes("data/somemod/rdi_x.dat"))
    }

    @Test
    fun playerDataUuidOnlyMatchesMainPlayerFiles() {
        assertEquals(
            "1512ef02-2b79-4494-a4e9-e35cd8e03923",
            SyncChunkBackupFileRules.playerDataUuid("playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.dat"),
        )
        assertNull(SyncChunkBackupFileRules.playerDataUuid("playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.cosarmor"))
        assertNull(SyncChunkBackupFileRules.playerDataUuid("stats/1512ef02-2b79-4494-a4e9-e35cd8e03923.json"))
    }

    @Test
    fun collectListsMatchingFilesSorted() {
        write("playerdata/b.dat")
        write("playerdata/b.dat_old")
        write("data/rdi_sync_chunks.dat")
        write("data/waystones.dat")
        write("data/lootr/0/a.dat")
        write("region/r.0.0.mca")
        write("level.dat")

        assertEquals(
            listOf("data/lootr/0/a.dat", "data/waystones.dat", "playerdata/b.dat"),
            SyncChunkBackupFileRules.collect(world).getOrThrow(),
        )
    }

    @Test
    fun collectOfAnEmptyWorldIsEmpty() {
        assertEquals(emptyList(), SyncChunkBackupFileRules.collect(world).getOrThrow())
    }

    @Test
    fun readStableRejectsFilesOverTheLimit() {
        val file = write("data/big.dat", ByteArray(10))
        assertEquals(10, SyncChunkBackupFileRules.readStable(file).getOrThrow().size)
        assertTrue(SyncChunkBackupFileRules.readStable(file, maxBytes = 9).isFailure)
    }

    private fun write(path: String, bytes: ByteArray = path.toByteArray()): Path =
        world.resolve(path).also { file ->
            file.parent.createDirectories()
            file.writeBytes(bytes)
        }
}
