package calebxzhou.rdi.client.service

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * J5b step 1: `gameWindowTitle` cases for the Rust launcher, written to
 * `slint-proto/protocol/fixtures/j5b-window.json` (one JSON object per line, so long titles stay readable).
 * Run with `RDI_WRITE_FIXTURES=1` to rewrite it; otherwise it is checked.
 */
class J5bWindowTitleFixtureTest {
    // The test working directory differs between Gradle and the IDE; find the repository's client folder.
    private val fixture = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { it.resolve("slint-proto/protocol/fixtures").isDirectory }
        .resolve("slint-proto/protocol/fixtures/j5b-window.json")

    private val cases: List<Triple<String, String?, String>> = listOf(
        Triple("Prominence", "我的房间", "4.1.0"),
        Triple("Prominence", null, "4.1.0"),
        Triple("", "房间", ""),
        Triple("  包\u0007名\t ", " 房\n间 ", " 1.0 "),
        Triple("A".repeat(70), null, ""),
        Triple("整合包".repeat(20), "房间名".repeat(20), "v1"),
        Triple("短", "很长的房间名".repeat(15), "2.0"),
        Triple("emoji😀".repeat(8), "😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀", "1"),
        Triple("Pack", "  ", "1.0"),
    )

    @Test
    fun `window titles match the shared fixture`() {
        val lines = cases.map { (pack, host, version) ->
            buildJsonObject {
                put("pack", JsonPrimitive(pack))
                put("host", host?.let(::JsonPrimitive) ?: JsonNull)
                put("version", JsonPrimitive(version))
                put("title", JsonPrimitive(gameWindowTitle(pack, host, version)))
            }.toString()
        }
        val text = buildJsonArray { }.let { "[\n" + lines.joinToString(",\n") { "  $it" } + "\n]\n" }
        if (System.getenv("RDI_WRITE_FIXTURES") == "1") {
            fixture.writeText(text)
        }
        assertTrue(fixture.isFile, "缺少j5b-window.json，请用RDI_WRITE_FIXTURES=1运行本测试")
        assertEquals(fixture.readText().replace("\r\n", "\n"), text)
    }
}
