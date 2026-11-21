package calebxzau.rdi.client.service

import calebxzhou.rdi.client.service.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/** Generates the same real ZIP corpus consumed by Rust; contains only inert test data. */
class J4bArchiveParityTest {
    private val fixture = File("../../slint-proto/protocol/fixtures")
    private val cases = Json.parseToJsonElement(fixture.resolve("j4b.json").readText()).jsonObject
    private val catalog = object : ModpackArchiveCatalog {
        override suspend fun resolveCurseForgeFiles(refs: List<CurseForgeArchiveRef>) =
            Result.success(emptyMap<Int, ArchiveCatalogFile>())
        override suspend fun resolveModrinthFiles(refs: List<ModrinthArchiveRef>) =
            Result.success(emptyMap<String, ArchiveCatalogFile>())
    }
    private val manifest = """{"formatVersion":1,"game":"minecraft","name":"Fixture","versionId":"1","dependencies":{"minecraft":"1.21.1","neoforge":"21.1.0"},"files":[]}"""

    @Test
    fun `archive paths and Chinese encoding agree with Kotlin`(): Unit = runBlocking {
        val output = fixture.resolve("j4b-archives").apply { mkdirs() }
        cases["paths"]!!.jsonArray.forEachIndexed { index, value ->
            val item = value.jsonObject
            val path = output.resolve("path-$index.zip")
            ZipOutputStream(path.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("modrinth.index.json"))
                zip.write(manifest.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("overrides/${item["path"]!!.jsonPrimitive.content}"))
                zip.write("fixture".toByteArray()); zip.closeEntry()
            }
            val result = ModpackArchiveReader(catalog).inspect(path.toPath())
            assertEquals(item["valid"]!!.jsonPrimitive.boolean, result.isSuccess, path.name)
        }
        for (encoding in listOf("UTF-8", "GBK")) {
            val path = output.resolve("chinese-$encoding.zip")
            ZipOutputStream(path.outputStream(), Charset.forName(encoding)).use { zip ->
                zip.putNextEntry(ZipEntry("modrinth.index.json")); zip.write(manifest.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("overrides/config/中文.json")); zip.write("{}".toByteArray()); zip.closeEntry()
            }
            assertEquals("config/中文.json", ModpackArchiveReader(catalog).inspect(path.toPath()).getOrThrow().overrides.single().targetPath)
        }
    }

    @Test
    fun `digest fixtures match JVM hashes and CurseForge whitespace rule`() {
        cases["digests"]!!.jsonArray.forEach { value ->
            val item = value.jsonObject
            val bytes = item["text"]!!.jsonPrimitive.content.toByteArray()
            assertEquals(item["sha1"]!!.jsonPrimitive.content,
                MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) })
            val normalized = bytes.filterNot { it.toInt() in listOf(9, 10, 13, 32) }.toByteArray()
            assertEquals(item["murmur2"]!!.jsonPrimitive.content, murmur(normalized).toString())
        }
    }

    @Test
    fun `hostile manifest fixtures preserve Kotlin error messages`(): Unit = runBlocking {
        val output = fixture.resolve("j4b-archives").apply { mkdirs() }
        val base = Json.parseToJsonElement(manifest).jsonObject
        val file = buildJsonObject {
            put("path", "mods/fixture.jar")
            put("fileSize", 3)
            put("hashes", buildJsonObject { put("sha1", "a9993e364706816aba3e25717850c26c9cd0d89d") })
            put("downloads", buildJsonArray { add("https://cdn.modrinth.com/fixture.jar") })
        }
        fun withFiles(vararg entries: JsonObject) = JsonObject(base + ("files" to JsonArray(entries.toList())))
        val badUrl = JsonObject(file + ("downloads" to JsonArray(listOf(JsonPrimitive("http://evil.invalid/a")))))
        val badSha1 = JsonObject(file + ("hashes" to buildJsonObject { put("sha1", "bad") }))
        val bad = listOf(
            "bad-url" to withFiles(badUrl),
            "bad-sha1" to withFiles(badSha1),
            "duplicate-target" to withFiles(file, file)
        )
        for ((name, body) in bad) {
            val path = output.resolve("$name.zip")
            ZipOutputStream(path.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("modrinth.index.json")); zip.write(body.toString().toByteArray()); zip.closeEntry()
            }
            assertTrue(ModpackArchiveReader(catalog).inspect(path.toPath()).isFailure, name)
        }
    }

    private fun murmur(bytes: ByteArray): UInt {
        if (bytes.isEmpty()) return 0u
        val m = 0x5bd1e995u
        var h = 1u xor bytes.size.toUInt()
        var position = 0
        while (position + 4 <= bytes.size) {
            var k = (0..3).fold(0u) { n, i -> n or ((bytes[position + i].toUInt() and 255u) shl (i * 8)) }
            k *= m; k = k xor (k shr 24); k *= m; h = h * m xor k; position += 4
        }
        var tail = 0u
        for (i in position until bytes.size) tail = tail or ((bytes[i].toUInt() and 255u) shl ((i - position) * 8))
        if (position < bytes.size) h = (h xor tail) * m
        h = h xor (h shr 13); h *= m
        return h xor (h shr 15)
    }
}
