package calebxzau.rdi.anvilrw.remap

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SaveIdentityMappingTest {
    private val m = UUID.fromString("1512ef02-2b79-4494-a4e9-e35cd8e03923")
    private val r = UUID.fromString("00112233-4455-6677-8899-aabb00000000")
    private val steve = SavePlayerScanner.offlineUuid("Steve")
    private val steveRdi = UUID.fromString("66778899-aabb-ccdd-eeff-001100000000")

    private fun player(uuid: UUID, ticks: Long?) = SavePlayer(
        uuid, SavePlayerScanner.classify(uuid), emptyList(), null, false, null, ticks,
    )

    private fun scan(vararg players: SavePlayer) =
        SaveScanResult("1.20.1", 3465, emptyList(), null, null, false, null, players.toList(), null, 0)

    @Test
    fun plainAssignmentMapsToTheAccount() {
        val mapping = SaveIdentityMapping.build(scan(player(m, 10), player(steve, null)), mapOf(m to r, steve to steveRdi)).getOrThrow()

        assertEquals(mapOf(m to r, steve to steveRdi), mapping)
    }

    @Test
    fun dualIdentityDefaultsToTheLongerPlayTime() {
        val longerOther = scan(player(m, 500), player(r, 100))
        val longerRdi = scan(player(m, 100), player(r, 500))
        val unknown = scan(player(m, null), player(r, null))

        assertEquals(DualIdentityKeep.Other, SaveIdentityMapping.findDualIdentities(longerOther, mapOf(m to r)).single().defaultKeep)
        assertEquals(DualIdentityKeep.Rdi, SaveIdentityMapping.findDualIdentities(longerRdi, mapOf(m to r)).single().defaultKeep)
        assertEquals(DualIdentityKeep.Rdi, SaveIdentityMapping.findDualIdentities(unknown, mapOf(m to r)).single().defaultKeep)
        assertEquals(mapOf(m to r, r to m), SaveIdentityMapping.build(longerOther, mapOf(m to r)).getOrThrow())
        assertEquals(emptyMap(), SaveIdentityMapping.build(longerRdi, mapOf(m to r)).getOrThrow())
    }

    @Test
    fun ownerChoiceOverridesTheDefault() {
        val save = scan(player(m, 500), player(r, 100), player(steve, 1))

        assertEquals(
            mapOf(steve to steveRdi),
            SaveIdentityMapping.build(save, mapOf(m to r, steve to steveRdi), mapOf(m to DualIdentityKeep.Rdi)).getOrThrow(),
        )
        assertEquals(
            mapOf(m to r, r to m),
            SaveIdentityMapping.build(save, mapOf(m to r), mapOf(m to DualIdentityKeep.Other)).getOrThrow(),
        )
    }

    @Test
    fun rejectsInvalidAssignments() {
        val save = scan(player(m, 1), player(r, 1), player(steve, 1))
        listOf(
            mapOf(UUID.randomUUID() to steveRdi),
            mapOf(r to steveRdi),
            mapOf(m to steve),
            mapOf(m to steveRdi, steve to steveRdi),
        ).forEach { assignments ->
            assertIs<IllegalArgumentException>(SaveIdentityMapping.build(save, assignments).exceptionOrNull(), assignments.toString())
        }
        assertIs<IllegalArgumentException>(
            SaveIdentityMapping.build(save, mapOf(m to r), mapOf(steve to DualIdentityKeep.Other)).exceptionOrNull(),
        )
    }
}
