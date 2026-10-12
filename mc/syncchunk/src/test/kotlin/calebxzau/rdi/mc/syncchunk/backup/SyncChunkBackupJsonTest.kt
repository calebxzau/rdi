package calebxzau.rdi.mc.syncchunk.backup

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncChunkBackupJsonTest {
    private val hashA = "a".repeat(40)
    private val hashB = "b".repeat(40)

    private val point = SyncChunkBackupPoint(
        id = "0199c2b4-1a2b-7c3d-8e4f-5a6b7c8d9e0f",
        kind = SyncChunkBackupKind.Auto,
        createdAt = 1_760_078_100_000,
        gameTime = 1_234_560,
        chunks = listOf(
            SyncChunkBackupChunk(SyncChunkKey("minecraft:overworld", -3, 8), hashA, null, hashB),
            SyncChunkBackupChunk(SyncChunkKey("twilightforest:twilight_forest", 0, 0), hashB, hashA, null),
        ),
        files = listOf(SyncChunkBackupFile("playerdata/1512ef02-2b79-4494-a4e9-e35cd8e03923.dat", hashA, 117582)),
        changed = SyncChunkBackupChanges(2, 1, 1, 1, 1),
        addedBytes = 4096,
    )

    @Test
    fun pointRoundTrips() {
        assertEquals(point, SyncChunkBackupJson.decodePoint(SyncChunkBackupJson.encodePoint(point)).getOrThrow())
        val undo = point.copy(kind = SyncChunkBackupKind.BeforeRollback, rollbackId = "0199c2bf-0000-7000-8000-000000000000")
        assertEquals(undo, SyncChunkBackupJson.decodePoint(SyncChunkBackupJson.encodePoint(undo)).getOrThrow())
    }

    @Test
    fun indexRoundTripsWithSummaries() {
        val index = SyncChunkBackupIndex("0199c2a1-7f3e-7c4b-9a10-3b6e2d4f8a11", 1_760_078_100_000, 312, 1024, listOf(point.summary()))
        val decoded = SyncChunkBackupJson.decodeIndex(SyncChunkBackupJson.encodeIndex(index)).getOrThrow()
        assertEquals(index, decoded)
        assertEquals(2, decoded.points.single().chunkCount)
        assertEquals(1, decoded.points.single().playerCount)
    }

    @Test
    fun rejectsInvalidPoints() {
        val json = SyncChunkBackupJson.encodePoint(point)
        listOf(
            json.replace("\"formatVersion\": 1", "\"formatVersion\": 2"),
            json.replace(hashA, "nothex"),
            json.replace("playerdata/", "../"),
            json.replace("minecraft:overworld", "Bad Dimension"),
            json.replace("\"Auto\"", "\"Unknown\""),
            "{}",
            "not json",
        ).forEach { text -> assertTrue(SyncChunkBackupJson.decodePoint(text).isFailure, text) }
    }
}
