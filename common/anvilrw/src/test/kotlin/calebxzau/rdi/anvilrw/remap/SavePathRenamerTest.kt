package calebxzau.rdi.anvilrw.remap

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SavePathRenamerTest {
    private val pcl = UUID.fromString("00000000-0000-300c-9be5-0017dec2993d")
    private val online = UUID.fromString("1512ef02-2b79-4494-a4e9-e35cd8e03923")
    private val target = UUID.fromString("00112233-4455-6677-8899-aabb00000000")
    private val renamer = SavePathRenamer(mapOf(pcl to target))

    private fun dashless(uuid: UUID) = uuid.toString().replace("-", "")

    @Test
    fun renamesWholeUuidStemsKeepingStyleAndExtension() {
        assertEquals("playerdata/${target}.dat", renamer.rename("playerdata/${pcl}.dat"))
        assertEquals("playerdata/${target}.dat_old", renamer.rename("playerdata/${pcl}.dat_old"))
        assertEquals("tombstone/saved_players/${target}/x.json", renamer.rename("tombstone/saved_players/${pcl}/x.json"))
        assertEquals("mod/${dashless(target)}.snbt", renamer.rename("mod/${dashless(pcl)}.snbt"))
        assertEquals("mod/${target.toString().uppercase()}.备份.json", renamer.rename("mod/${pcl.toString().uppercase()}.备份.json"))
    }

    @Test
    fun leavesOtherSegmentsAlone() {
        listOf(
            "playerdata/${online}.dat",
            "mod/x${pcl}.dat",
            "mod/${pcl}x.dat",
            "mod/zzzz${dashless(pcl)}.dat",
            "mod/.${pcl}",
            "data/lootr/d/d0/${UUID.randomUUID()}.dat",
            "region/r.0.0.mca",
        ).forEach { assertEquals(it, renamer.rename(it)) }
    }

    @Test
    fun swapsTwoProfiles() {
        val swap = SavePathRenamer(mapOf(pcl to online, online to pcl))
        assertEquals("playerdata/${online}.dat", swap.rename("playerdata/${pcl}.dat"))
        assertEquals("playerdata/${pcl}.dat", swap.rename("playerdata/${online}.dat"))
    }
}
