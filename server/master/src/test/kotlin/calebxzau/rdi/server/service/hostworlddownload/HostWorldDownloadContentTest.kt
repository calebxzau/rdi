package calebxzau.rdi.server.service.hostworlddownload

import kotlin.test.Test
import kotlin.test.assertEquals

class HostWorldDownloadContentTest {
    @Test
    fun chunkStoresAreLeftToTheBuilder() {
        listOf("region/r.0.0.mca", "entities/r.0.0.mca", "poi/r.0.0.mca", "DIM-1/region/c.1.2.mcc", "dimensions/twilightforest/twilight_forest/region/r.0.0.mca")
            .forEach { assertEquals(false, HostWorldDownloadContent.includesFile(it), it) }
        // Same names elsewhere are mod data.
        assertEquals(true, HostWorldDownloadContent.includesFile("dimensions/twilightforest/twilight_forest/data/raids.dat"))
        assertEquals(true, HostWorldDownloadContent.includesFile("somemod/region/notes.dat"))
    }

    @Test
    fun includesWorldAndModData() {
        listOf(
            "level.dat",
            "icon.png",
            "playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.dat",
            "playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.cosarmor",
            "advancements/1512ef02-2b79-4494-a4e9-e35cd8e03923.json",
            "data/scoreboard.dat",
            "data/rdi_sync_chunks.dat",
            "serverconfig/aether-server.toml",
            "datapacks/pack/pack.mcmeta",
            "tombstone/saved_players/x/20260829-165615.save",
            "kubejs_persistent_data.nbt",
        ).forEach { assertEquals(true, HostWorldDownloadContent.includesFile(it), it) }
    }

    @Test
    fun excludesLocksOldCopiesHostDataAndCaches() {
        listOf(
            "session.lock",
            "level.dat_old",
            "playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.dat_old",
            "playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923-123456.dat",
            "data/rdi_backup_token.txt",
            "data/rdi_force_pvp.dat",
            "data/DistantHorizons.sqlite",
            "data/map_0.dat.tmp",
            "ftbquests/.hidden",
        ).forEach { assertEquals(false, HostWorldDownloadContent.includesFile(it), it) }
    }

    @Test
    fun byteRangeSupportsSingleRangesInsideTheFile() {
        assertEquals(100L..999L, byteRange("bytes=100-", 1000))
        assertEquals(0L..0L, byteRange("bytes=0-0", 1000), "the downloader's range probe")
        assertEquals(500L..999L, byteRange("bytes=500-5000", 1000))
        assertEquals(null, byteRange("bytes=1000-", 1000))
        assertEquals(null, byteRange("bytes=10-5", 1000))
        assertEquals(null, byteRange("bytes=-100", 1000))
        assertEquals(null, byteRange("bytes=0-1,5-9", 1000))
        assertEquals(null, byteRange(null, 1000))
    }
}
