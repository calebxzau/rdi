package calebxzau.rdi.anvilrw.remap

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory

/** Builds small saves on disk for scanner and remapper tests. */
class SyntheticSave(val root: Path = createTempDirectory("synthetic-game").resolve("saves").resolve("world")) {
    init {
        Files.createDirectories(root)
    }

    fun levelDat(
        host: TCompound? = null,
        hardcore: Boolean = false,
        modIds: List<String>? = listOf("minecraft", "forge"),
        extraData: List<Pair<String, Tag>> = emptyList(),
    ): SyntheticSave {
        val data = mutableListOf<Pair<String, Tag>>(
            "Version" to compound("Name" to TString("1.20.1"), "Id" to TInt(3465)),
            "DataVersion" to TInt(3465),
            "ServerBrands" to TList(NbtTag.STRING, listOf(TString("forge"))),
            "hardcore" to TByte(if (hardcore) 1 else 0),
        )
        host?.let { data += "Player" to it }
        data += extraData
        val root = mutableListOf<Pair<String, Tag>>("Data" to TCompound(data))
        modIds?.let { ids ->
            root += "fml" to compound("LoadingModList" to TList(NbtTag.COMPOUND, ids.map { compound("ModId" to TString(it)) }))
        }
        return gzipNbt("level.dat", TCompound(root))
    }

    fun gzipNbt(relativePath: String, root: TCompound): SyntheticSave = bytes(relativePath, BoundedIo.gzip(JavaNbt.write(root)))

    fun text(relativePath: String, text: String): SyntheticSave = bytes(relativePath, text.toByteArray(Charsets.UTF_8))

    fun bytes(relativePath: String, bytes: ByteArray): SyntheticSave {
        val file = root.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
        return this
    }

    fun syncChunks(vararg chunks: SaveSyncChunk, rawDimension: String? = null): SyntheticSave = gzipNbt(
        SaveSyncChunks.RELATIVE_PATH,
        compound(
            "data" to compound(
                "chunks" to TList(NbtTag.COMPOUND, chunks.map {
                    compound(
                        "dimension" to TString(rawDimension ?: it.dimensionId),
                        "x" to TInt(it.chunkX),
                        "z" to TInt(it.chunkZ),
                        "owner" to TString(it.owner.toString()),
                    )
                }),
            ),
            "DataVersion" to TInt(3465),
        ),
    )

    companion object {
        fun player(uuid: UUID, vararg extra: Pair<String, Tag>): TCompound =
            TCompound(listOf("UUID" to uuidInts(uuid)) + extra)
    }
}
