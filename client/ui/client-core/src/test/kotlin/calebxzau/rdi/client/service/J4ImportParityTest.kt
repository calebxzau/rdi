package calebxzau.rdi.client.service

import calebxzhou.rdi.client.service.*
import calebxzhou.rdi.common.model.McVersion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** The Rust port follows current runtime eligibility, including 1.20.1 Fabric. */
class J4ImportParityTest {
    private val cases = Json.parseToJsonElement(File("../../slint-proto/protocol/fixtures/j4a.json").readText()).jsonObject

    @Test
    fun `copy exclusions and unique names match shared cases`() {
        cases["copy"]!!.jsonArray.forEach { value ->
            val c = value.jsonObject
            assertEquals(c["expected"]!!.jsonPrimitive.boolean, LocalModpackInstaller.shouldCopyGameFile(
                c["path"]!!.jsonPrimitive.content, "pack", c["includeSaves"]!!.jsonPrimitive.boolean))
        }
        cases["uniqueNames"]!!.jsonArray.forEach { value ->
            val c = value.jsonObject
            assertEquals(c["expected"]!!.jsonPrimitive.content, LocalModpackStore.uniqueName(
                c["name"]!!.jsonPrimitive.content, c["existing"]!!.jsonArray.map { it.jsonPrimitive.content }))
        }
    }

    @Test
    fun `scanner manifests match current Kotlin candidate behavior`(): Unit = runBlocking {
        val root = Files.createTempDirectory("j4-scanner-parity")
        val scanner = LocalModpackScanner(object : calebxzhou.rdi.client.database.MinecraftInstallationStore {
            override suspend fun list() = Result.success(emptyList<calebxzhou.rdi.client.database.MinecraftInstallationRecord>())
            override suspend fun upsert(path: java.nio.file.Path, seenAt: Long) = error("unused")
            override suspend fun delete(path: java.nio.file.Path) = error("unused")
            override suspend fun lastFullScanAt() = Result.success<Long?>(null)
            override suspend fun setLastFullScanAt(scannedAt: Long) = error("unused")
            override fun close() {}
        }, root.resolve("managed"))
        cases["scanner"]!!.jsonArray.forEach { value ->
            val c = value.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val dir = root.resolve("versions/$name")
            Files.createDirectories(dir.resolve("mods"))
            Files.write(dir.resolve("mods/fake.jar"), byteArrayOf(1))
            Files.writeString(dir.resolve("$name.json"), c["manifest"].toString())
            val found = scanner.scanDirectory(dir).getOrThrow()
            val loader = c["loader"]!!.jsonPrimitive.contentOrNull
            if (loader == null) assertTrue(found.isEmpty(), name)
            else assertEquals(loader, found.single().modLoader.name, name)
        }
    }

    @Test
    fun `options output matches Kotlin writer`() {
        cases["options"]!!.jsonObject.forEach { (version, expected) ->
            val dir = Files.createTempDirectory("j4-options").toFile()
            calebxzau.rdi.mcinstall.writeMinecraftOptions(dir, McVersion.valueOf(version)).getOrThrow()
            assertEquals(expected.jsonPrimitive.content, dir.resolve("options.txt").readText())
        }
    }
}
