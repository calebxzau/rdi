package calebxzhou.rdi.common.service

import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.model.CurseForgeFile
import calebxzhou.rdi.common.model.CurseForgeFileHash
import calebxzhou.rdi.common.model.CurseForgeModInfo
import calebxzhou.rdi.common.model.CurseForgePackManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CurseForgeServiceTest {
    @Test
    fun `extra resolver maps supported classes and preserves required and exact url`() {
        val resourceManifest = CurseForgePackManifest.File(projectId = 12, fileId = 1201, required = false)
        val shaderManifest = CurseForgePackManifest.File(projectId = 13, fileId = 1301, required = true)
        val resourceFile = file(id = 1201, modId = 12, fileName = "Clarity.zip")
        val shaderFile = file(id = 1301, modId = 13, fileName = "Bliss.zip")
        val resourceProject = project(id = 12, classId = 12)
        val shaderProject = project(id = 13, classId = 6552)

        val result = CurseForgeService.resolveManifestEntriesToContents(
            listOf(resourceManifest, shaderManifest),
            mapOf(resourceProject.id to resourceProject, shaderProject.id to shaderProject),
            mapOf(resourceFile.id to resourceFile, shaderFile.id to shaderFile),
        )

        assertEquals(2, result.size)
        assertEquals("resourcepacks/Clarity.zip", result[0].path)
        assertEquals("shaderpacks/Bliss.zip", result[1].path)
        assertEquals(false, result[0].required)
        assertEquals(listOf(resourceFile.realDownloadUrl), result[0].downloadUrls)
        assertEquals(resourceFile.fileFingerprint.toString(), result[0].hash)
    }

    @Test
    fun `extra resolver rejects file belonging to another project and unsafe filename`() {
        val manifestFile = CurseForgePackManifest.File(projectId = 12, fileId = 1201)
        val project = project(id = 12, classId = 6552)
        val wrongOwner = file(id = 1201, modId = 13, fileName = "shader.zip")
        assertFailsWith<ModpackError> {
            CurseForgeService.resolveManifestEntriesToContents(
                listOf(manifestFile), mapOf(project.id to project), mapOf(wrongOwner.id to wrongOwner)
            )
        }

        val unsafe = file(id = 1201, modId = 12, fileName = "../shader.zip")
        assertFailsWith<ModpackError> {
            CurseForgeService.resolveManifestEntriesToContents(
                listOf(manifestFile), mapOf(project.id to project), mapOf(unsafe.id to unsafe)
            )
        }
    }

    @Test
    fun `unsupported project classes stay out of client extras`() {
        val manifestFile = CurseForgePackManifest.File(projectId = 12, fileId = 1201)
        val file = file(id = 1201, modId = 12, fileName = "unknown.zip")
        val project = project(id = 12, classId = 5)
        assertEquals(
            emptyList(),
            CurseForgeService.resolveManifestEntriesToContents(
                listOf(manifestFile), mapOf(project.id to project), mapOf(file.id to file)
            )
        )
    }

    private fun project(id: Int, classId: Long) = CurseForgeModInfo(
        id = id, name = "fixture", slug = "fixture-$id", classId = classId
    )

    private fun file(id: Int, modId: Int, fileName: String) = CurseForgeFile(
        id = id,
        modId = modId,
        fileName = fileName,
        downloadUrl = "https://cdn.example/$fileName",
        fileFingerprint = 1234L,
        hashes = listOf(CurseForgeFileHash("a".repeat(40), 1)),
    )
}
