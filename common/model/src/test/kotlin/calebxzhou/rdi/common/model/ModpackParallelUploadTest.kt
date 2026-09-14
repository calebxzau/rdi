package calebxzhou.rdi.common.model

import calebxzhou.rdi.common.serdesJson
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.jsonObject
import calebxzau.rdi.common.model.normalizeClientExtraPath
import calebxzau.rdi.common.model.validateClientExtra
import calebxzau.rdi.common.model.validateAndMergeClientExtras

class ModpackParallelUploadTest {
    private val extra = calebxzau.rdi.common.model.Content(
        platform = calebxzau.rdi.common.model.ContentPlatform.Modrinth,
        type = calebxzau.rdi.common.model.ContentType.ResPack,
        projectId = "project",
        fileId = "version",
        slug = "pack",
        hash = "a".repeat(40),
        path = "resourcepacks/pack.zip",
        side = calebxzau.rdi.common.model.ContentSide.Client,
        downloadUrls = listOf("https://example.com/pack.zip"),
    )

    @Test
    fun `create upload DTO round trips client extras and old payload defaults empty`() {
        val dto = Modpack.CreateWithVersionDto(
            name = "pack",
            mcVer = McVersion.V211,
            modLoader = ModLoader.neoforge,
            verName = "1.0",
            mods = mutableListOf(),
            clientExtras = mutableListOf(extra),
        )
        val decoded = serdesJson.decodeFromString<Modpack.CreateWithVersionDto>(serdesJson.encodeToString(dto))
        assertEquals(listOf(extra), decoded.clientExtras)
        val oldJson = kotlinx.serialization.json.JsonObject(
            serdesJson.parseToJsonElement(serdesJson.encodeToString(dto)).jsonObject
                .toMutableMap().apply { remove("clientExtras") }
        )
        assertEquals(emptyList(), serdesJson.decodeFromString<Modpack.CreateWithVersionDto>(oldJson.toString()).clientExtras)
    }

    @Test
    fun `client extra validation rejects absolute and mismatched paths`() {
        assertFailsWith<IllegalArgumentException> { normalizeClientExtraPath("/resourcepacks/a.zip") }
        assertFailsWith<IllegalArgumentException> {
            extra.copy(path = "shaderpacks/a.zip").validateClientExtra()
        }
    }

    @Test
    fun `client extra merge preserves placements and rejects contradictory destination`() {
        val secondPath = extra.copy(path = "resourcepacks/other.zip")
        assertEquals(2, listOf(extra, secondPath).validateAndMergeClientExtras().size)
        assertFailsWith<IllegalArgumentException> {
            listOf(extra, extra.copy(fileId = "other")).validateAndMergeClientExtras()
        }
    }

    @Test
    fun `version upload DTO round trips extras and missing field defaults empty`() {
        val uploadId = UUID.fromString("019fe723-72c0-7000-8000-000000000002")
        val dto = ModpackVersionCreateFromUploadDto(uploadId, mutableListOf(), mutableListOf(extra))
        val encoded = serdesJson.encodeToString(dto)
        assertEquals(dto, serdesJson.decodeFromString<ModpackVersionCreateFromUploadDto>(encoded))
        val oldJson = kotlinx.serialization.json.JsonObject(
            serdesJson.parseToJsonElement(encoded).jsonObject.toMutableMap().apply { remove("clientExtras") }
        )
        assertEquals(
            emptyList(),
            serdesJson.decodeFromString<ModpackVersionCreateFromUploadDto>(oldJson.toString()).clientExtras,
        )
    }

    @Test
    fun `upload session round trips UUID and sha1`() {
        val session = ModpackUploadSessionVo(
            id = UUID.fromString("019fe723-72c0-7000-8000-000000000001"),
            fileName = "pack.zip",
            size = 12,
            sha1 = "0123456789abcdef0123456789abcdef01234567",
            partSize = 4,
            partCount = 3,
            uploadedParts = listOf(0, 2),
            ready = false,
            expiresAt = 1_786_406_400_000
        )

        val decoded = serdesJson.decodeFromString<ModpackUploadSessionVo>(
            serdesJson.encodeToString(session)
        )

        assertEquals(session, decoded)
        assertEquals(8, decoded.maxParallelParts)
    }
}
