package calebxzau.rdi.common.model

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.supportLoader
import calebxzhou.rdi.common.model.supportsRuntime
import calebxzhou.rdi.common.serdesJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class LoaderRecognitionTest {
    @Test
    fun `mod loader parses only fabric loader identifiers`() {
        listOf("fabric", " Fabric ", "fabric-loader", "Fabric-0.16.10", "fabric-loader-0.16.10+1.20.1")
            .forEach { assertEquals(ModLoader.Fabric, ModLoader.from(it)) }
        listOf("fabric-api", "fabric-language-kotlin", "fabric-foo", "quilt-loader", "quilt-0.27.1")
            .forEach { assertNull(ModLoader.from(it)) }
        assertEquals(ModLoader.forge, ModLoader.from("forge-47.4.20"))
        assertEquals(ModLoader.neoforge, ModLoader.from("NeoForge-21.1.250"))
    }

    @Test
    fun `fabric runs for 1 20 1 without joining the forge installer metadata`() {
        assertTrue(McVersion.V201.supportsRuntime(ModLoader.Fabric))
        assertFalse(McVersion.V201.supportLoader(ModLoader.Fabric))
        assertFalse(McVersion.V211.supportsRuntime(ModLoader.Fabric))
        assertTrue(McVersion.V201.supportsRuntime(ModLoader.forge))
        assertTrue(McVersion.V211.supportsRuntime(ModLoader.neoforge))
    }

    @Test
    fun `fabric server arguments fail explicitly`() {
        val version = ModLoader.Version(ModLoader.Fabric, "fabric-0.16.10", "", "")
        assertFailsWith<UnsupportedOperationException> { version.serverArgsPath(true) }
    }

    @Test
    fun `loader serialization preserves existing names and adds fabric`() {
        assertEquals("\"forge\"", serdesJson.encodeToString(ModLoader.forge))
        assertEquals("\"neoforge\"", serdesJson.encodeToString(ModLoader.neoforge))
        assertEquals("\"fabric\"", serdesJson.encodeToString(ModLoader.Fabric))
        assertEquals(ModLoader.forge, serdesJson.decodeFromString<ModLoader>("\"forge\""))
        assertEquals(ModLoader.neoforge, serdesJson.decodeFromString<ModLoader>("\"neoforge\""))
        assertEquals(ModLoader.Fabric, serdesJson.decodeFromString<ModLoader>("\"fabric\""))
    }

    @Test
    fun `modrinth reads only loader keys and preserves declared versions`() {
        val result = LoaderRecognition.modrinth(
            mapOf("minecraft" to "1.20.1", "fabric-api" to "0.92.2", "fabric-loader" to " 0.16.10 ")
        )

        assertEquals(LoaderIdentity(ModLoader.Fabric, "0.16.10"), result.getOrThrow())
        assertTrue(LoaderRecognition.modrinth(mapOf("fabric-api" to "0.92.2")).isFailure)
        assertTrue(LoaderRecognition.modrinth(mapOf("quilt-loader" to "0.27.1")).isFailure)
        assertTrue(LoaderRecognition.modrinth(mapOf("fabric-loader" to " ")).isFailure)
    }

    @Test
    fun `modrinth rejects multiple declarations including quilt`() {
        val result = LoaderRecognition.modrinth(
            mapOf("fabric-loader" to "0.16.10", "quilt-loader" to "0.27.1")
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("多个"))
    }

    @Test
    fun `curseforge chooses unique primary and preserves its declared version`() {
        val result = LoaderRecognition.curseForge(
            listOf(
                LoaderDeclaration("forge-47.4.20"),
                LoaderDeclaration("fabric-0.16.10+1.20.1", primary = true),
            )
        )

        assertEquals(LoaderIdentity(ModLoader.Fabric, "0.16.10+1.20.1"), result.getOrThrow())
    }

    @Test
    fun `curseforge rejects ambiguous and malformed declarations`() {
        assertTrue(LoaderRecognition.curseForge(listOf(LoaderDeclaration("forge-1"), LoaderDeclaration("fabric-0.16.10"))).isFailure)
        assertTrue(
            LoaderRecognition.curseForge(
                listOf(LoaderDeclaration("forge-1", true), LoaderDeclaration("fabric-0.16.10", true))
            ).isFailure
        )
        assertTrue(LoaderRecognition.curseForge(listOf(LoaderDeclaration("quilt-0.27.1", true))).isFailure)
        assertTrue(LoaderRecognition.curseForge(listOf(LoaderDeclaration("fabric-api"))).isFailure)
    }
}
