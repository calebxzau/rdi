package calebxzau.rdi.mc.client.dm

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Which master checkpoint this local world corresponds to.
 *
 * [pendingCycleId] is written before an upload, so a world whose last upload reached the master
 * but whose confirmation was never recorded is still recognized as current.
 */
data class DmCheckpointMark(val cycleId: UUID?, val pendingCycleId: UUID? = null) {
    fun knows(cycleId: UUID): Boolean = cycleId == this.cycleId || cycleId == pendingCycleId
}

/** [checkpoint] is null only for worlds associated before checkpoint marks existed. */
data class DmWorldAssociation(val hostId: UUID, val checkpoint: DmCheckpointMark? = DmCheckpointMark(null)) {
    /** True when the master holds a checkpoint this world has never seen, so hosting it would roll the room back. */
    fun isStaleAgainst(masterCycleId: UUID?): Boolean {
        if (masterCycleId == null) return false
        val mark = checkpoint ?: return false
        return !mark.knows(masterCycleId)
    }
}

object DmWorldAssociationStore {
    private val gson = Gson()

    fun path(worldRoot: Path): Path = worldRoot.resolve("rdi").resolve("host.json")

    fun read(worldRoot: Path): Result<DmWorldAssociation?> = runCatching {
        val file = path(worldRoot)
        if (!Files.isRegularFile(file)) return@runCatching null
        val json = gson.fromJson(Files.readString(file), JsonObject::class.java)
        val hostId = UUID.fromString(json.get("hostId")?.asString ?: error("host.json缺少hostId"))
        val checkpoint = json.get("checkpoint")?.takeIf { it.isJsonObject }?.asJsonObject?.let { mark ->
            DmCheckpointMark(mark.uuidOrNull("cycleId"), mark.uuidOrNull("pendingCycleId"))
        }
        DmWorldAssociation(hostId, checkpoint)
    }

    fun write(worldRoot: Path, hostId: UUID): Result<Unit> = write(worldRoot, DmWorldAssociation(hostId))

    fun write(worldRoot: Path, association: DmWorldAssociation): Result<Unit> = runCatching {
        val directory = worldRoot.resolve("rdi")
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "host", ".json.tmp")
        try {
            val json = JsonObject().apply {
                addProperty("hostId", association.hostId.toString())
                association.checkpoint?.let { mark ->
                    add("checkpoint", JsonObject().apply {
                        mark.cycleId?.let { addProperty("cycleId", it.toString()) }
                        mark.pendingCycleId?.let { addProperty("pendingCycleId", it.toString()) }
                    })
                }
            }
            Files.writeString(temporary, gson.toJson(json))
            Files.move(temporary, path(worldRoot), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Records [cycleId] as uploading, keeping the last confirmed cycle. */
    fun markPending(worldRoot: Path, hostId: UUID, cycleId: UUID): Result<Unit> = runCatching {
        val current = read(worldRoot).getOrThrow()?.takeIf { it.hostId == hostId }
        write(worldRoot, DmWorldAssociation(hostId, DmCheckpointMark(current?.checkpoint?.cycleId, cycleId))).getOrThrow()
    }

    fun markConfirmed(worldRoot: Path, hostId: UUID, cycleId: UUID): Result<Unit> =
        write(worldRoot, DmWorldAssociation(hostId, DmCheckpointMark(cycleId)))

    private fun JsonObject.uuidOrNull(name: String): UUID? =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.let(UUID::fromString)
}
