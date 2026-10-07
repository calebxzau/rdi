package calebxzau.rdi.anvilrw.remap

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavePlayerScannerTest {
    private val pcl = UUID.fromString("00000000-0000-300c-9be5-0017dec2993d")
    private val online = UUID.fromString("1512ef02-2b79-4494-a4e9-e35cd8e03923")
    private val rdi = UUID.fromString("00112233-4455-6677-8899-aabb00000000")
    private val steve = SavePlayerScanner.offlineUuid("Steve")

    @Test
    fun scansPlayersHintsAndMetadata() {
        val save = SyntheticSave()
            .levelDat(host = SyntheticSave.player(pcl), hardcore = true)
            .gzipNbt("playerdata/${online}.dat", SyntheticSave.player(online))
            .gzipNbt("playerdata/${steve}.dat", SyntheticSave.player(steve))
            .gzipNbt("playerdata/${rdi}.dat", SyntheticSave.player(rdi))
            .gzipNbt("playerdata/${pcl}.dat_old", SyntheticSave.player(pcl))
            .text("playerdata/not-a-uuid.dat", "x")
            .text("stats/${steve}.json", """{"stats":{"minecraft:custom":{"minecraft:play_time":72000,"minecraft:jump":3}},"DataVersion":3465}""")
            .text("ftbteams/player/${steve}.snbt", "{\n\tid: \"${steve}\"\n\tplayer_name: \"Steve\"\n}")
            .text("ftbquests/${online}.snbt", "{\n\tname: \"Alex#1a2b3c4d\"\n}")
        Files.writeString(save.root.parent.parent.resolve("usercache.json"), """[{"name":"Notch","uuid":"${online}","expiresOn":"2020-01-01 00:00:00 +0800"}]""")

        val result = SavePlayerScanner().scan(save.root).getOrThrow()

        assertEquals("1.20.1", result.mcVersionName)
        assertEquals(3465, result.dataVersion)
        assertEquals(SaveLoader.Forge, result.lastSavedLoader)
        assertTrue(result.isHardcore)
        assertEquals(pcl, result.singleplayerHostUuid)
        assertNull(result.syncChunks)
        val players = result.players.associateBy { it.uuid }
        assertEquals(setOf(online, steve, rdi, pcl), players.keys)
        players.getValue(online).let {
            assertEquals(SavePlayerKind.Online, it.kind)
            assertEquals(listOf(NameHint("Notch", NameHintSource.UserCache), NameHint("Alex", NameHintSource.FtbQuests)), it.nameHints)
            assertNull(it.verifiedName)
        }
        players.getValue(steve).let {
            assertEquals(SavePlayerKind.Offline, it.kind)
            assertEquals("Steve", it.verifiedName)
            assertEquals(72000L, it.playTimeTicks)
            assertFalse(it.isSingleplayerHost)
        }
        assertEquals(SavePlayerKind.Rdi, players.getValue(rdi).kind)
        players.getValue(pcl).let {
            assertEquals(SavePlayerKind.Offline, it.kind)
            assertTrue(it.isSingleplayerHost)
            assertEquals(Files.getLastModifiedTime(save.root.resolve("level.dat")).toInstant(), it.lastPlayed)
            assertNull(it.verifiedName)
        }
    }

    @Test
    fun loaderComesFromTheLastSave() {
        assertEquals(SaveLoader.NeoForge, scan(listOf("minecraft", "neoforge")).lastSavedLoader)
        assertNull(scan(listOf("minecraft")).lastSavedLoader)
        scan(null).let {
            assertNull(it.lastSavedLoader)
            assertNull(it.fmlModIds)
        }
    }

    @Test
    fun readsSyncChunks() {
        val chunk = SaveSyncChunk("minecraft:the_nether", -3, 7, rdi)
        val save = SyntheticSave().levelDat().syncChunks(chunk)

        assertEquals(listOf(chunk), SavePlayerScanner().scan(save.root).getOrThrow().syncChunks)
    }

    @Test
    fun missingOrBrokenLevelDatFails() {
        assertIs<java.io.FileNotFoundException>(SavePlayerScanner().scan(SyntheticSave().root).exceptionOrNull())
        val broken = SyntheticSave().bytes("level.dat", BoundedIo.gzip(byteArrayOf(10, 0, 0, 99)))
        assertIs<NbtFormatException>(SavePlayerScanner().scan(broken.root).exceptionOrNull())
    }

    private fun scan(modIds: List<String>?): SaveScanResult =
        SavePlayerScanner().scan(SyntheticSave().levelDat(modIds = modIds).root).getOrThrow()
}
