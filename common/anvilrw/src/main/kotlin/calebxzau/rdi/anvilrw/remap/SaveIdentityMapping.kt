package calebxzau.rdi.anvilrw.remap

import java.util.UUID

enum class DualIdentityKeep { Rdi, Other }

/**
 * A save player [other] assigned to the RDI account [rdi], whose RDI profile is also in the save. The
 * two profiles stay two separate players; the owner chooses which one the account plays as.
 */
data class DualIdentity(
    val other: UUID,
    val rdi: UUID,
    val otherPlayTimeTicks: Long?,
    val rdiPlayTimeTicks: Long?,
    /** The profile with the longer play time; [DualIdentityKeep.Rdi] on a tie or when unknown. */
    val defaultKeep: DualIdentityKeep,
)

/**
 * Turns the owner's choices into the UUID mapping used by the remapper.
 *
 * - A save player assigned to an RDI account whose profile is not in the save is mapped to it.
 * - For a [DualIdentity], keeping the RDI profile leaves both untouched, so the other profile stays a
 *   separate player that never logs in. Keeping the other profile swaps the two everywhere, so the
 *   account plays as the other profile and the old RDI profile moves to the other UUID.
 *
 * Ownership is never merged: whatever belongs to the profile that is not kept stays with it.
 */
object SaveIdentityMapping {
    fun findDualIdentities(scan: SaveScanResult, assignments: Map<UUID, UUID>): List<DualIdentity> {
        val players = scan.players.associateBy { it.uuid }
        return assignments.mapNotNull { (other, rdi) ->
            val rdiPlayer = players[rdi] ?: return@mapNotNull null
            val otherTicks = players[other]?.playTimeTicks
            DualIdentity(
                other = other,
                rdi = rdi,
                otherPlayTimeTicks = otherTicks,
                rdiPlayTimeTicks = rdiPlayer.playTimeTicks,
                defaultKeep = if ((otherTicks ?: 0) > (rdiPlayer.playTimeTicks ?: 0)) DualIdentityKeep.Other else DualIdentityKeep.Rdi,
            )
        }
    }

    /**
     * [assignments] maps save players (never RDI profiles) to the RDI accounts they belong to. [keep]
     * holds the owner's choice per [DualIdentity.other]; missing choices use the default.
     */
    fun build(
        scan: SaveScanResult,
        assignments: Map<UUID, UUID>,
        keep: Map<UUID, DualIdentityKeep> = emptyMap(),
    ): Result<Map<UUID, UUID>> = remapResult {
        val players = scan.players.associateBy { it.uuid }
        assignments.forEach { (source, target) ->
            val player = requireNotNull(players[source]) { "${source} is not a player of this save" }
            require(player.kind != SavePlayerKind.Rdi) { "${source} is already an RDI profile" }
            require(SavePlayerScanner.isRdiUuid(target)) { "${target} is not an RDI account UUID" }
        }
        require(assignments.values.toSet().size == assignments.size) { "Two save players are assigned to the same account" }
        require(keep.keys.all { it in assignments }) { "A dual identity choice names a player without an assignment" }

        val duals = findDualIdentities(scan, assignments).associateBy { it.other }
        buildMap {
            assignments.forEach { (source, target) ->
                val dual = duals[source]
                when {
                    dual == null -> put(source, target)
                    (keep[source] ?: dual.defaultKeep) == DualIdentityKeep.Other -> {
                        put(source, target)
                        put(target, source)
                    }
                    else -> Unit
                }
            }
        }
    }
}
