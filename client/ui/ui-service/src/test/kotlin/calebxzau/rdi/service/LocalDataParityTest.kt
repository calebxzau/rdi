package calebxzau.rdi.service

import calebxzhou.rdi.client.database.MinecraftInstallationDatabase
import calebxzhou.rdi.client.database.ModpackLaunchOptionsRecord
import calebxzhou.rdi.client.database.ModpackLaunchOptionsStore
import calebxzhou.rdi.client.service.LocalModpackInfo
import calebxzhou.rdi.client.service.LocalModpackSourceKind
import calebxzhou.rdi.client.service.LocalModpackStore
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.serdesJson
import calebxzau.rdi.client.ui.viewmodel.ModpackOptionDraft
import calebxzau.rdi.client.ui.viewmodel.ModpackOptionRuntime
import calebxzau.rdi.client.ui.viewmodel.ModpackOptionViewModel
import calebxzau.rdi.client.ui.viewmodel.SettingsModpackOptionRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.*

/** J2: the Rust launcher reads the same files and must reach the same results (`src/pack_options.rs`, `src/local_packs.rs`). */
class LocalDataParityTest {
    private val fixtures = File("../slint-proto/protocol/fixtures")
    private val cases = Json.parseToJsonElement(fixtures.resolve("pack_options_cases.json").readText()).jsonObject

    @Test
    fun `shared option cases give the same records and messages as Rust`(): Unit = runBlocking {
        cases["records"]!!.jsonArray.map { it.jsonObject }.forEach { case ->
            val name = case["name"]!!.jsonPrimitive.content
            val store = object : ModpackLaunchOptionsStore {
                var record: ModpackLaunchOptionsRecord? = null
                var deleted = false
                override suspend fun find(versionId: String) = Result.success<ModpackLaunchOptionsRecord?>(null)
                override suspend fun upsert(record: ModpackLaunchOptionsRecord): Result<Unit> { this.record = record; return Result.success(Unit) }
                override suspend fun delete(versionId: String): Result<Unit> { deleted = true; return Result.success(Unit) }
                override fun close() {}
            }
            val model = ModpackOptionViewModel("v1", store, object : ModpackOptionRuntime {
                override fun getTotalPhysicalMemoryMb() = 16384
                override fun validateJdkPath(rawPath: String) = Result.success(rawPath)
                override fun validateMaxMemory(maxMemoryText: String, totalMemoryMb: Int) =
                    SettingsModpackOptionRuntime.validateMaxMemory(maxMemoryText, totalMemoryMb)
            }, this)
            withTimeout(3000) { while (model.uiState.value.loading) delay(5) }
            model.updateDraft(Json.decodeFromJsonElement<ModpackOptionDraft>(case["draft"]!!))
            model.save()
            withTimeout(3000) { while (model.uiState.value.saving) delay(5) }
            val error = case["error"]?.jsonPrimitive?.content
            val expected = case["record"]
            when {
                error != null -> assertEquals("保存整合包设置失败：$error", model.uiState.value.errorMessage, name)
                expected == null || expected is JsonNull -> assertTrue(store.deleted && store.record == null, name)
                else -> {
                    val e = expected.jsonObject
                    assertEquals(ModpackLaunchOptionsRecord(
                        versionId = "v1",
                        javaPath = e["javaPath"]?.jsonPrimitive?.contentOrNull,
                        maxMemoryMb = e["maxMemoryMb"]?.jsonPrimitive?.intOrNull,
                        jdwpEnabled = e["jdwpEnabled"]!!.jsonPrimitive.boolean,
                        jdwpParam = e["jdwpParam"]?.jsonPrimitive?.contentOrNull,
                        customJvmParams = e["customJvmParams"]!!.jsonPrimitive.content,
                        forgeguardDisabled = e["forgeguardDisabled"]!!.jsonPrimitive.boolean,
                    ), store.record, name)
                }
            }
        }
    }

    @Test
    fun `shared memory cases match Rust`() {
        cases["memory"]!!.jsonArray.map { it.jsonObject }.forEach { case ->
            val result = SettingsModpackOptionRuntime.validateMaxMemory(
                case["text"]!!.jsonPrimitive.content, case["total"]!!.jsonPrimitive.int)
            val error = case["error"]?.jsonPrimitive?.content
            if (error != null) assertEquals(error, result.exceptionOrNull()?.message, case.toString())
            else assertEquals(case["value"]!!.jsonPrimitive.int, result.getOrThrow(), case.toString())
        }
    }

    /** Rust refuses any other version (J2 D4), so a schema bump must update `pack_options.rs` too. */
    @Test
    fun `data db fixture is a current Kotlin database with the rows Rust expects`(): Unit = runBlocking {
        assertEquals(5L, MinecraftInstallationDatabase.SCHEMA_VERSION, "Update SCHEMA_VERSION in slint-proto/src/pack_options.rs")
        val fixture = fixtures.resolve("data_v5.db")
        if (!fixture.exists()) {
            MinecraftInstallationDatabase.open(fixture.toPath()).getOrThrow().use { database ->
                expectedRows.forEach { database.modpackLaunchOptionsStore.upsert(it).getOrThrow() }
            }
        }
        val copy = Files.createTempDirectory("j2-data-db").resolve("data.db")
        Files.copy(fixture.toPath(), copy, StandardCopyOption.REPLACE_EXISTING)
        MinecraftInstallationDatabase.open(copy).getOrThrow().use { database ->
            expectedRows.forEach { assertEquals(it, database.modpackLaunchOptionsStore.find(it.versionId).getOrThrow()) }
        }
    }

    @Test
    fun `local pack info fixture is what Kotlin writes`() {
        val expected = LocalModpackInfo(
            id = "019a6c40-0000-7000-8000-000000000001", name = "测试本地整合包", iconUrl = "https://example.com/icon.png",
            mcVersion = McVersion.V211, modLoader = ModLoader.neoforge, createdAt = 1762732800000,
            source = LocalModpackSourceKind.Archive,
        )
        val text = fixtures.resolve("rdi-local-pack.json").readText()
        assertEquals(serdesJson.encodeToString(expected), text.trim())
        val dir = Files.createTempDirectory("j2-local-pack").toFile()
        dir.resolve(LocalModpackStore.INFO_FILE_NAME).writeText(text)
        assertEquals(expected, LocalModpackStore.read(dir).getOrThrow())
    }

    private val expectedRows = listOf(
        ModpackLaunchOptionsRecord(
            versionId = "fixture_custom", javaPath = "C:\\Java\\jdk-21\\bin\\java.exe", maxMemoryMb = 8192,
            jdwpEnabled = true, jdwpParam = "transport=dt_socket,server=y,suspend=n,address=*:5006",
            customJvmParams = "-Dexample=true\n-XX:+UseG1GC", forgeguardDisabled = true,
        ),
        ModpackLaunchOptionsRecord(
            versionId = "fixture_blank_jdwp", javaPath = null, maxMemoryMb = null,
            jdwpEnabled = false, jdwpParam = "  ", customJvmParams = "", forgeguardDisabled = true,
        ),
    )
}
