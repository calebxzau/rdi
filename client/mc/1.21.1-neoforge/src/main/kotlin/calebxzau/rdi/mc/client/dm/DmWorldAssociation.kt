package calebxzau.rdi.mc.client.dm

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

data class DmWorldAssociation(val hostId: UUID)

object DmWorldAssociationStore {
    private val gson = Gson()

    fun path(worldRoot: Path): Path = worldRoot.resolve("rdi").resolve("host.json")

    fun read(worldRoot: Path): Result<DmWorldAssociation?> = runCatching {
        val file = path(worldRoot)
        if (!Files.isRegularFile(file)) return@runCatching null
        val json = gson.fromJson(Files.readString(file), JsonObject::class.java)
        DmWorldAssociation(UUID.fromString(json.get("hostId")?.asString ?: error("host.json缺少hostId")))
    }

    fun write(worldRoot: Path, hostId: UUID): Result<Unit> = runCatching {
        val directory = worldRoot.resolve("rdi")
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "host", ".json.tmp")
        try {
            Files.writeString(temporary, gson.toJson(JsonObject().apply { addProperty("hostId", hostId.toString()) }))
            Files.move(temporary, path(worldRoot), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
