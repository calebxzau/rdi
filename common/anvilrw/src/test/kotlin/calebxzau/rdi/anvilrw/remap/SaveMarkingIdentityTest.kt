package calebxzau.rdi.anvilrw.remap

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveMarkingIdentityTest {
    private val owner = UUID.fromString("00000000-0000-300c-9be5-0017dec2993d")
    private val friend = UUID.fromString("1512ef02-2b79-4494-a4e9-e35cd8e03923")
    private val rdi = UUID.fromString("00112233-4455-6677-8899-aabb00000000")

    @Test
    fun compoundReplacementPreservesUnrelatedBytesAndModifiedUtf8() {
        val old = compound("UUID" to uuidInts(owner))
        val chosen = compound("UUID" to uuidInts(friend), "ModName" to TString("中文😀\u0000"))
        val extra = compound("Unknown" to TByteArray(listOf(0, 1, -1)), "Text" to TString("🎉\u0000"))
        for (existing in listOf(false, true)) {
            val before = compound("Data" to compound(*buildList {
                add("Before" to extra)
                if (existing) add("Player" to old)
                add("After" to extra)
            }.toTypedArray()), "ModRoot" to extra)
            val expected = compound("Data" to compound(*buildList {
                add("Before" to extra)
                if (existing) add("Player" to chosen)
                add("After" to extra)
                if (!existing) add("Player" to chosen)
            }.toTypedArray()), "ModRoot" to extra)
            val result = NbtMetadataReader.open(JavaNbt.write(before)).getOrThrow()
                .replaceCompound(listOf("Data"), "Player", JavaNbt.payload(chosen)).getOrThrow()
            assertContentEquals(JavaNbt.write(expected), result)
        }
    }

    @Test
    fun selectedDataSurvivesRemappingAlongsideExistingRdiProfile() {
        val source = SyntheticSave().levelDat(compound("UUID" to uuidInts(owner), "InventoryMarker" to TInt(1)))
            .gzipNbt("playerdata/${friend}.dat", compound("UUID" to uuidInts(friend), "InventoryMarker" to TInt(2)))
            .gzipNbt("playerdata/${rdi}.dat", compound("UUID" to uuidInts(rdi), "InventoryMarker" to TInt(3)))
        SaveMarkingIdentity.prepare(source.root, friend, source.root.resolveSibling("backup")).getOrThrow()
        val scan = SavePlayerScanner().scan(source.root).getOrThrow()
        assertEquals(friend, scan.singleplayerHostUuid)
        assertEquals(setOf(owner, friend, rdi), scan.players.map { it.uuid }.toSet())
        for (keep in DualIdentityKeep.entries) {
            val mapping = SaveIdentityMapping.build(scan, mapOf(friend to rdi), mapOf(friend to keep)).getOrThrow()
            val target = source.root.resolveSibling("result-${keep}")
            SaveUuidRemapper(mapping).remapTree(source.root, target, SaveSnapshot.take(source.root).getOrThrow()).getOrThrow()
            val actual = NbtMetadataReader.openGzipFile(target.resolve("playerdata/${rdi}.dat")).getOrThrow()
            assertEquals(if (keep == DualIdentityKeep.Other) 2 else 3, actual.root.child("InventoryMarker")!!.intOrNull())
            assertEquals(1, NbtMetadataReader.openGzipFile(target.resolve("playerdata/${owner}.dat"))
                .getOrThrow().root.child("InventoryMarker")!!.intOrNull())
        }
    }

    @Test
    fun mismatchingPlayerFileAndMalformedPlayerAreRejected() {
        val source = SyntheticSave().levelDat().gzipNbt("playerdata/${friend}.dat", compound("UUID" to uuidInts(owner)))
        assertTrue(SaveMarkingIdentity.players(source.root).isFailure)
        source.gzipNbt("playerdata/${friend}.dat", compound("UUID" to uuidInts(friend)))
        assertEquals(1, SaveMarkingIdentity.players(source.root).getOrThrow().size)
        source.levelDat(compound("UUID" to TString("invalid")))
        assertTrue(SaveMarkingIdentity.players(source.root).isFailure)
    }

    @Test
    fun duplicateIdentityTagsAreRejectedBeforeEditing() {
        val source = SyntheticSave().levelDat(compound("UUID" to uuidInts(owner)), extraData = listOf(
            "Player" to compound("UUID" to uuidInts(friend)),
        ))
        assertTrue(SaveMarkingIdentity.players(source.root).isFailure)
        val level = NbtMetadataReader.openGzipFile(source.root.resolve("level.dat")).getOrThrow()
        assertTrue(level.replaceCompound(listOf("Data"), "Player", JavaNbt.payload(compound("UUID" to uuidInts(friend)))).isFailure)
        source.levelDat(compound("UUID" to uuidInts(owner), "UUID" to uuidInts(friend)))
        assertTrue(SaveMarkingIdentity.players(source.root).isFailure)
    }
}
