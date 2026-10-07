package calebxzau.rdi.anvilrw.remap

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SaveSyncChunksTest {
    private val owner = UUID.fromString("00112233-4455-6677-8899-aabb00000000")

    private fun parse(save: SyntheticSave): Result<List<SaveSyncChunk>> =
        SaveSyncChunks.parse(Files.readAllBytes(save.root.resolve(SaveSyncChunks.RELATIVE_PATH)))

    @Test
    fun parsesAndCanonicalizesEntries() {
        val save = SyntheticSave().syncChunks(SaveSyncChunk("x", 1, 2, owner), rawDimension = "overworld")

        assertEquals(listOf(SaveSyncChunk("minecraft:overworld", 1, 2, owner)), parse(save).getOrThrow())
        assertEquals(emptyList(), parse(SyntheticSave().syncChunks()).getOrThrow())
    }

    @Test
    fun rejectsInvalidLists() {
        val chunk = SaveSyncChunk("minecraft:overworld", 0, 0, owner)
        val cases = mapOf(
            "duplicate" to SyntheticSave().syncChunks(chunk, chunk.copy(dimensionId = "overworld")),
            "too many" to SyntheticSave().syncChunks(*Array(257) { chunk.copy(chunkX = it) }),
            "unsafe dimension" to SyntheticSave().syncChunks(chunk, rawDimension = "demo:../../x"),
            "bad dimension" to SyntheticSave().syncChunks(chunk, rawDimension = "Demo:x"),
            "no list" to SyntheticSave().gzipNbt(SaveSyncChunks.RELATIVE_PATH, compound("data" to compound())),
            "bad owner" to SyntheticSave().gzipNbt(
                SaveSyncChunks.RELATIVE_PATH,
                compound("data" to compound("chunks" to TList(NbtTag.COMPOUND, listOf(
                    compound("dimension" to TString("minecraft:overworld"), "x" to TInt(0), "z" to TInt(0), "owner" to TString("1-2-3-4-5")),
                )))),
            ),
            "missing z" to SyntheticSave().gzipNbt(
                SaveSyncChunks.RELATIVE_PATH,
                compound("data" to compound("chunks" to TList(NbtTag.COMPOUND, listOf(
                    compound("dimension" to TString("minecraft:overworld"), "x" to TInt(0), "owner" to TString(owner.toString())),
                )))),
            ),
        )
        cases.forEach { (name, save) -> assertIs<SyncChunkListException>(parse(save).exceptionOrNull(), name) }
    }
}
