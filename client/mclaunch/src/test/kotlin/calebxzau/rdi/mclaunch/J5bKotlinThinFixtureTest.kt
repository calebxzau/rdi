package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.serdesJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * J5b step 1: what `GameKotlinRuntime.prepare` does to Kotlin mods in `mods`. The input jars live in
 * `slint-proto/protocol/fixtures/j5b-thin/`; the result is written to `j5b-thin.json` as each jar's entry
 * list with content hashes (nested jars listed recursively, `META-INF/jarjar/metadata.json` as parsed
 * JSON), because the zip bytes themselves depend on the encoder. Rust thins the same inputs and must
 * produce the same description. Run with `RDI_WRITE_FIXTURES=1` to rewrite the inputs and the result.
 */
class J5bKotlinThinFixtureTest {
    private val fixtures = File("../slint-proto/protocol/fixtures")
    private val inputs = fixtures.resolve("j5b-thin")
    private val fixture = fixtures.resolve("j5b-thin.json")
    private val write = System.getenv("RDI_WRITE_FIXTURES") == "1"

    private fun jar(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun text(value: String) = value.toByteArray(Charsets.UTF_8)

    private fun metadata(vararg jars: Triple<String, String, String>, extra: String = ""): ByteArray = text(buildString {
        append("{")
        if (extra.isNotEmpty()) append(extra).append(",")
        append("\"jars\":[")
        append(jars.joinToString(",") { (group, artifact, path) ->
            "{\"identifier\":{\"group\":\"$group\",\"artifact\":\"$artifact\"},\"version\":{\"range\":\"[1,)\",\"artifactVersion\":\"1.0\"},\"path\":\"$path\",\"isObfuscated\":false}"
        })
        append("]}")
    })

    private val manifest = "META-INF/MANIFEST.MF" to text("Manifest-Version: 1.0\r\n\r\n")

    /** Inputs, written only in write mode; zip timestamps make their bytes differ on every run. */
    private fun inputJars(): Map<String, ByteArray> {
        val coroutines = jar(listOf(manifest, "kotlinx/coroutines/Job.class" to text("job")))
        val helper = jar(listOf(
            manifest,
            "META-INF/jarjar/metadata.json" to metadata(
                Triple("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "META-INF/jarjar/coroutines.jar"),
            ),
            "META-INF/jarjar/coroutines.jar" to coroutines,
            // Nested jars keep shaded Kotlin classes; only top-level jars lose them.
            "kotlin/Shaded.class" to text("nested shaded"),
            "com/example/helper/Helper.class" to text("helper"),
        ))
        val stdlib = jar(listOf(manifest, "kotlin/Unit.class" to text("unit")))
        val kotlinMod = jar(listOf(
            manifest,
            "META-INF/MOD.SF" to text("signature"),
            "META-INF/MOD.RSA" to text("rsa"),
            "META-INF/mods.toml" to text("modLoader=\"javafml\""),
            "META-INF/jarjar/metadata.json" to metadata(
                Triple("org.jetbrains.kotlin", "kotlin-stdlib", "META-INF/jarjar/kotlin-stdlib-2.1.0.jar"),
                Triple("com.example", "helper", "META-INF/jarjar/helper.jar"),
                Triple("com.github.luben", "zstd-jni", "META-INF/jarjar/zstd-jni.jar"),
                extra = "\"other\":{\"keep\":[1,2]}",
            ),
            "META-INF/jarjar/kotlin-stdlib-2.1.0.jar" to stdlib,
            "META-INF/jarjar/helper.jar" to helper,
            "META-INF/jarjar/zstd-jni.jar" to jar(listOf(manifest, "com/github/luben/zstd/Zstd.class" to text("zstd"))),
            "kotlin/Shaded.class" to text("top shaded"),
            "kotlinx/serialization/Json.class" to text("json"),
            "_COROUTINE/ArtificialStackFrames.class" to text("frames"),
            "kotlinmod/Main.class" to text("main"),
            "assets/kotlinmod/lang/zh_cn.json" to text("{\"a\":\"甲\"}"),
        ))
        val kff = jar(listOf(
            manifest,
            "META-INF/jarjar/metadata.json" to metadata(
                Triple("thedarkcolour", "kfflang", "META-INF/jarjar/kfflang.jar"),
                Triple("thedarkcolour", "kfflib", "META-INF/jarjar/kfflib.jar"),
                Triple("thedarkcolour", "kffmod", "META-INF/jarjar/kffmod.jar"),
                Triple("", "kotlinx.serialization.json", "META-INF/jarjar/serialization-json.jar"),
            ),
            "META-INF/jarjar/kfflang.jar" to jar(listOf(manifest, "thedarkcolour/kotlinforforge/Lang.class" to text("lang"))),
            "META-INF/jarjar/kfflib.jar" to jar(listOf(manifest, "thedarkcolour/kotlinforforge/Lib.class" to text("lib"))),
            "META-INF/jarjar/kffmod.jar" to jar(listOf(manifest, "thedarkcolour/kotlinforforge/Mod.class" to text("mod"))),
            "META-INF/jarjar/serialization-json.jar" to jar(listOf(manifest, "kotlinx/serialization/json/Json.class" to text("json"))),
        ))
        // A declared runtime jar that is missing: Kotlin leaves the whole jar alone.
        val missingRuntime = jar(listOf(
            manifest,
            "META-INF/jarjar/metadata.json" to metadata(
                Triple("org.jetbrains.kotlin", "kotlin-reflect", "META-INF/jarjar/kotlin-reflect.jar"),
            ),
            "kotlin/Shaded.class" to text("kept"),
        ))
        // Nested jar without anything to remove: no rewrite.
        val alreadyThin = jar(listOf(
            manifest,
            "META-INF/jarjar/metadata.json" to metadata(Triple("com.example", "helper", "META-INF/jarjar/plain.jar")),
            "META-INF/jarjar/plain.jar" to jar(listOf(manifest, "com/example/Plain.class" to text("plain"))),
            "thinmod/Main.class" to text("thin"),
        ))
        val plain = jar(listOf(manifest, "kotlin/NotAModRuntime.class" to text("no metadata, untouched")))
        return linkedMapOf(
            "kotlin-mod.jar" to kotlinMod,
            "kff-5.jar" to kff,
            "missing-runtime.jar" to missingRuntime,
            "already-thin.jar" to alreadyThin,
            "plain.jar" to plain,
        )
    }

    private fun sha1(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun describe(bytes: ByteArray): JsonArray = buildJsonArray {
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                val content = zip.readBytes()
                add(buildJsonObject {
                    put("name", entry.name)
                    when {
                        entry.name == "META-INF/jarjar/metadata.json" ->
                            put("json", serdesJson.parseToJsonElement(content.toString(Charsets.UTF_8)))
                        entry.name.endsWith(".jar") -> put("jar", describe(content))
                        else -> put("sha1", sha1(content))
                    }
                })
            }
        }
    }

    @Test
    fun `thinned Kotlin mods match the shared fixture`() {
        if (write) {
            inputs.deleteRecursively()
            inputs.mkdirs()
            inputJars().forEach { (name, bytes) -> inputs.resolve(name).writeBytes(bytes) }
        }
        val names = inputs.listFiles().orEmpty().map { it.name }.sorted()
        assertTrue(names.isNotEmpty(), "缺少j5b-thin输入，请用RDI_WRITE_FIXTURES=1运行本测试")
        val root = Files.createTempDirectory("rdi-j5b-thin").toFile()
        val mods = root.resolve("mods").apply { mkdirs() }
        names.forEach { inputs.resolve(it).copyTo(mods.resolve(it)) }
        GameKotlinRuntime.prepare(McVersion.V211, mods, root.resolve("kotlin-runtime")).getOrThrow()
        // The second run must find everything thin and change nothing.
        val firstPass = names.associateWith { sha1(mods.resolve(it).readBytes()) }
        GameKotlinRuntime.prepare(McVersion.V211, mods, root.resolve("kotlin-runtime")).getOrThrow()
        names.forEach { assertEquals(firstPass[it], sha1(mods.resolve(it).readBytes()), "第二次精简改动了$it") }

        val cache = root.resolve("kotlin-runtime")
        val generated = buildJsonObject {
            put("comment", "Written by J5bKotlinThinFixtureTest (client/mclaunch); inputs in j5b-thin/, checked by the thinning tests in Rust.")
            put("cacheSchema", "v3")
            put("mods", buildJsonArray {
                names.forEach { name ->
                    val input = inputs.resolve(name).readBytes()
                    val output = mods.resolve(name).readBytes()
                    val changed = !input.contentEquals(output)
                    add(buildJsonObject {
                        put("name", name)
                        put("changed", changed)
                        if (changed) {
                            val source = sha1(input)
                            put("thinCache", "thin-kotlin-mod/v3-$source.jar")
                            put("originalCache", "original-kotlin-mod/$source.jar")
                            assertTrue(cache.resolve("thin-kotlin-mod/v3-$source.jar").isFile)
                            assertTrue(cache.resolve("original-kotlin-mod/$source.jar").readBytes().contentEquals(input))
                            put("entries", describe(output))
                        }
                    })
                }
            })
        }
        // Two Kotlin for Forge files are refused before anything is changed.
        val twoKff = root.resolve("two-kff").apply { mkdirs() }
        inputs.resolve("kff-5.jar").copyTo(twoKff.resolve("kff-a.jar"))
        inputs.resolve("kff-5.jar").copyTo(twoKff.resolve("kff-b.jar"))
        val error = GameKotlinRuntime.prepare(McVersion.V211, twoKff, root.resolve("kotlin-runtime-2")).exceptionOrNull()
        assertTrue(error?.message.orEmpty().startsWith("发现多个Kotlin for Forge文件: "), "${error?.message}")
        assertTrue(inputs.resolve("kff-5.jar").readBytes().contentEquals(twoKff.resolve("kff-a.jar").readBytes()))

        val text = jsonPretty(generated) + "\n"
        if (write) fixture.writeText(text)
        assertTrue(fixture.isFile, "缺少j5b-thin.json，请用RDI_WRITE_FIXTURES=1运行本测试")
        assertEquals(fixture.readText().replace("\r\n", "\n"), text)
        root.deleteRecursively()
    }

    private fun jsonPretty(element: JsonElement, indent: String = ""): String = when (element) {
        is JsonObject -> if (element.isEmpty()) "{}" else element.entries.joinToString(",\n", "{\n", "\n$indent}") { (key, value) ->
            "$indent  ${JsonPrimitive(key)}: ${jsonPretty(value, "$indent  ")}"
        }
        is JsonArray -> if (element.isEmpty()) "[]" else element.joinToString(",\n", "[\n", "\n$indent]") {
            "$indent  ${jsonPretty(it, "$indent  ")}"
        }
        else -> element.toString()
    }
}
