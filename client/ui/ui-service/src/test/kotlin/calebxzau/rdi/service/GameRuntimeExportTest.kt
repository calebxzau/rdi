package calebxzau.rdi.service

import calebxzhou.rdi.common.serdesJson
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The manifest must name exactly what the service's own resolvers put on a room game's class path. */
class GameRuntimeExportTest {
    @Test
    fun `manifest matches the runtime resolvers`() {
        val root = Files.createTempDirectory("rdi-game-runtime-test").toFile()
        try {
            val agent = root.resolve("forgeguard.jar").apply { writeText("agent") }
            val output = root.resolve("out")
            output.mkdirs()
            output.resolve("stale.jar").writeText("old")
            exportGameRuntime(output, agent).getOrThrow()
            val expected = resolveGameRuntimeFiles(root.resolve("scratch")).getOrThrow()
            val manifest = serdesJson.parseToJsonElement(output.resolve("manifest.json").readText()).jsonObject
            fun names(key: String) = manifest.getValue(key).jsonArray.map { it.jsonPrimitive.content }
            assertEquals(GAME_RUNTIME_SCHEMA, manifest.getValue("schema").jsonPrimitive.int)
            assertEquals(expected.kotlin.map { it.name }, names("kotlin"))
            assertEquals(5, names("kotlin").size)
            assertEquals(expected.zstd.map { it.name }, names("zstd"))
            assertEquals(expected.media.map { it.name }, names("media"))
            assertEquals(expected.mediaNative.name, manifest.getValue("mediaNative").jsonPrimitive.content)
            assertTrue(expected.mediaNative.name in names("media"))
            assertEquals(expected.mediaPlatform, manifest.getValue("mediaPlatform").jsonPrimitive.content)
            assertEquals("forgeguard.jar", manifest.getValue("forgeguard").jsonPrimitive.content)
            (expected.kotlin + expected.zstd + expected.media).forEach { file ->
                assertTrue(output.resolve(file.name).readBytes().contentEquals(file.readBytes()), file.name)
            }
            assertTrue(!output.resolve("stale.jar").exists(), "旧文件应被移除")
        } finally {
            root.deleteRecursively()
        }
    }
}
