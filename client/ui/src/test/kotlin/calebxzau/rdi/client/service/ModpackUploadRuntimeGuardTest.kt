package calebxzau.rdi.client.service

import calebxzau.rdi.client.packproc.LocalModpackSourceType
import calebxzau.rdi.client.packproc.ModpackProcessor
import calebxzau.rdi.client.packproc.PackProcessingPaths
import calebxzau.rdi.client.packproc.UploadPayload
import calebxzhou.rdi.client.service.createUploadModpackTask2
import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.ModpackCreateFromUploadDto
import calebxzhou.rdi.common.model.ModpackUploadSessionCreateDto
import calebxzhou.rdi.common.model.ModpackUploadSessionVo
import calebxzhou.rdi.common.model.ModpackVersionCreateFromUploadDto
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ModpackUploadRuntimeGuardTest {
    @Test
    fun `upload task rejects Fabric before test bypass can create a task`(): Unit = runBlocking {
        val root = Files.createTempDirectory("rdi-fabric-upload-guard").toFile()
        try {
            val payload = UploadPayload(
                sourceType = LocalModpackSourceType.MODRINTH,
                sourceDir = root,
                mods = mutableListOf(),
                mcVersion = McVersion.V201,
                modloader = ModLoader.Fabric,
                sourceName = "Fabric",
                sourceVersion = "1.0",
            )

            assertFailsWith<ModpackError> {
                createUploadModpackTask2(
                    processor = ModpackProcessor(PackProcessingPaths(root.resolve("work"))),
                    payload = payload,
                    mods = emptyList(),
                    modpackName = "Fabric",
                    versionName = "1.0",
                    categories = emptyList(),
                    iconUrl = null,
                    sourceUrl = null,
                    info = null,
                    updateModpackId = null,
                    api = NoCallUploadApi,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

private object NoCallUploadApi : ModpackUploadApi {
    override suspend fun createSession(request: ModpackUploadSessionCreateDto): ModpackUploadSessionVo =
        error("upload API must not be called")

    override suspend fun uploadPart(uploadId: UUID, index: Int, bytes: ByteArray, sha1: String) =
        error("upload API must not be called")

    override suspend fun completeSession(uploadId: UUID): ModpackUploadSessionVo =
        error("upload API must not be called")

    override suspend fun cancelSession(uploadId: UUID) = error("upload API must not be called")

    override suspend fun publishNew(request: ModpackCreateFromUploadDto) =
        error("upload API must not be called")

    override suspend fun publishVersion(
        modpackId: ObjectId,
        versionName: String,
        request: ModpackVersionCreateFromUploadDto,
    ) = error("upload API must not be called")

    override suspend fun listMy(): List<Modpack> = error("upload API must not be called")
}
