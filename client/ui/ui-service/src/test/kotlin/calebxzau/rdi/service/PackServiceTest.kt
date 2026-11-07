package calebxzau.rdi.service

import calebxzhou.rdi.client.database.*
import calebxzhou.rdi.client.service.*
import calebxzhou.rdi.common.model.*
import calebxzau.rdi.client.ui.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PackServiceTest {
    @Test
    fun `installed list and folder action use the same selected pack and reject unknown ids`(): Unit = runBlocking {
        val fixture = Fixture(this)
        fixture.service.use { service ->
            val result = service.handle("installedPacks.subscribe", obj())
            assertEquals(fixture.pack.versionId, result["packs"]!!.jsonArray.single().jsonObject.text("versionId"))
            assertEquals(fixture.pack.dir.absolutePath, service.handle("installedPacks.directory", obj("versionId" to fixture.pack.versionId)).text("path"))
            assertFailsWith<IllegalStateException> { service.handle("installedPacks.directory", obj("versionId" to "../outside")) }
            assertEquals(false, fixture.events.last().second["loading"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `installer returns a task id and closing it stops future snapshots`(): Unit = runBlocking {
        val fixture = Fixture(this)
        fixture.service.use { service ->
            service.handle("installer.open", obj())
            service.handle("installer.action", obj("action" to "create", "index" to 0))
            service.handle("installer.action", obj("action" to "name", "value" to "测试整合包"))
            val result = service.handle("installer.install", obj())
            assertEquals("test-run", result.text("runId"))
            assertNotNull(fixture.submitted)
            service.handle("installer.close", obj())
            val count = fixture.events.size
            delay(80)
            assertEquals(count, fixture.events.size)
            assertFailsWith<IllegalStateException> { service.handle("installer.install", obj()) }
        }
    }

    @Test
    fun `advanced settings save and reload through the service and preserve validation errors`(): Unit = runBlocking {
        val fixture = Fixture(this)
        fixture.service.use { service ->
            service.handle("installedPacks.subscribe", obj())
            service.handle("packOptions.open", obj("versionId" to fixture.pack.versionId))
            withTimeout(3000) { while (fixture.optionModel!!.uiState.value.loading) delay(5) }
            val draft = ModpackOptionDraft(customJvmParams = "-Dexample=true", forgeguardDisabled = true)
            service.handle("packOptions.action", buildJsonObject { put("action", "update"); put("draft", Json.encodeToJsonElement(draft)) })
            service.handle("packOptions.action", obj("action" to "save"))
            withTimeout(3000) { while (fixture.optionModel!!.uiState.value.saving) delay(5) }
            assertEquals("-Dexample=true", fixture.record?.customJvmParams)
            assertEquals(true, fixture.record?.forgeguardDisabled)
            service.handle("packOptions.open", obj("versionId" to fixture.pack.versionId))
            withTimeout(3000) { while (fixture.optionModel!!.uiState.value.loading) delay(5) }
            assertEquals(draft, fixture.optionModel!!.uiState.value.draft)
            val invalid = draft.copy(jdwpEnabled = true, jdwpParam = "")
            service.handle("packOptions.action", buildJsonObject { put("action", "update"); put("draft", Json.encodeToJsonElement(invalid)) })
            service.handle("packOptions.action", obj("action" to "save"))
            withTimeout(3000) { while (fixture.optionModel!!.uiState.value.saving) delay(5) }
            assertTrue(fixture.optionModel!!.uiState.value.errorMessage!!.contains("JDWP"))
            assertFalse(fixture.record!!.jdwpEnabled)
        }
    }

    @Test
    fun `task snapshot preserves group paths and terminal errors`() {
        val child = Task2.Leaf("下载文件", id = "file") {}
        val task = Task2.Sequence("安装", listOf(child), id = "root")
        val snapshot = Task2Snapshot(status = Task2Status.FAILED, errorMessage = "下载失败",
            progressByPath = mapOf("root / file#0" to Task2Progress("下载中", .4f)))
        val state = taskSnapshot(Task2Entry("run", task, snapshot = snapshot))
        assertTrue(state["terminal"]!!.jsonPrimitive.boolean)
        assertEquals("下载失败", state.text("error"))
        val rows = state["rows"]!!.jsonArray
        assertEquals("root / file#0", rows[1].jsonObject.text("key"))
        assertEquals(1, rows[1].jsonObject["depth"]!!.jsonPrimitive.int)
        assertEquals(.4f, rows[1].jsonObject["fraction"]!!.jsonPrimitive.float)
    }

    @Test
    fun `task watcher emits completion then stops its subscription`(): Unit = runBlocking {
        val fixture = Fixture(this)
        val release = CompletableDeferred<Unit>()
        val runId = ClientTaskManager.submit(Task2.Leaf("测试任务") { context ->
            context.emit(Task2Progress("处理中", .5f))
            release.await()
        })
        fixture.service.use { service ->
            service.handle("tasks.watch", obj("runId" to runId))
            withTimeout(3000) { while (fixture.events.none { it.first == "task.state" }) delay(5) }
            release.complete(Unit)
            withTimeout(3000) { while (fixture.events.none { it.first == "task.state" && it.second["terminal"] == JsonPrimitive(true) }) delay(5) }
            val taskEvents = fixture.events.count { it.first == "task.state" }
            delay(90)
            assertEquals(taskEvents, fixture.events.count { it.first == "task.state" })
            assertEquals(2, fixture.events.last { it.first == "task.state" }.second["status"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `shutdown waits for active task cancellation cleanup`(): Unit = runBlocking {
        val fixture = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val runId = ClientTaskManager.submit(Task2.Leaf("待取消的导入") {
            started.complete(Unit)
            try { awaitCancellation() } finally { cleaned.complete(Unit) }
        })
        fixture.service.use { service ->
            withTimeout(3000) { started.await() }
            withTimeout(3000) { service.stopTasks() }
            assertTrue(cleaned.isCompleted)
            assertEquals(Task2Status.CANCELLED, ClientTaskManager.controller(runId)!!.state.value.status)
        }
    }

    @Test
    fun `console snapshots fit within the frame even with heavily escaped lines`() {
        val lines = List(5000) { "\u0001".repeat(4096) }
        val bounded = boundedConsoleLines(lines)
        assertTrue(bounded.isNotEmpty())
        val encoded = buildJsonObject { put("lines", JsonArray(bounded.map(::JsonPrimitive))) }.toString().toByteArray()
        assertTrue(encoded.size < 1_000_100)
        assertEquals(listOf("尾行"), boundedConsoleLines(listOf("a".repeat(100), "尾行"), 30))
    }

    private class Fixture(scope: CoroutineScope) : PackServiceDependencies {
        val root = Files.createTempDirectory("u4a-service")
        val info = LocalModpackInfo("019a55b0-0000-7000-8000-000000000001", "测试包", mcVersion = McVersion.V211,
            modLoader = ModLoader.neoforge, createdAt = 1, source = LocalModpackSourceKind.Empty)
        val pack = ModpackLocalDir(root.resolve("local_${info.id}").toFile(), "1.21.1", null, 1, info)
        val events = mutableListOf<Pair<String, JsonObject>>()
        var submitted: Task2? = null
        var record: ModpackLaunchOptionsRecord? = null
        var optionModel: ModpackOptionViewModel? = null
        val service = PackService(this, scope, { _, _ -> error("No game should start in this test") }) { name, state -> events += name to state }
        override suspend fun loadPacks() = listOf(pack)
        override fun installer(scope: CoroutineScope) = LocalModpackInstallViewModel(
            LocalModpackScanner(object : MinecraftInstallationStore {
                override suspend fun list() = Result.success(emptyList<MinecraftInstallationRecord>())
                override suspend fun upsert(path: Path, seenAt: Long) = Result.success(Unit)
                override suspend fun delete(path: Path) = Result.success(Unit)
                override suspend fun lastFullScanAt() = Result.success<Long?>(null)
                override suspend fun setLastFullScanAt(scannedAt: Long) = Result.success(Unit)
                override fun close() {}
            }, root),
            LocalModpackInstaller(root.resolve("versions"), taskSubmitter = object : LocalModpackTaskSubmitter {
                override fun submit(task: Task2, dedupeKey: String): String { submitted = task; return "test-run" }
            }),
            ModpackArchiveReader(object : ModpackArchiveCatalog {
                override suspend fun resolveCurseForgeFiles(refs: List<CurseForgeArchiveRef>) = Result.success(emptyMap<Int, ArchiveCatalogFile>())
            }),
            ModpackArchiveContentResolutionSource { _, _ -> flowOf(ContentResolutionUpdate.Complete) }, scope,
        )
        override fun options(versionId: String, scope: CoroutineScope) = ModpackOptionViewModel(versionId,
            object : ModpackLaunchOptionsStore {
                override suspend fun find(versionId: String) = Result.success(record)
                override suspend fun upsert(record: ModpackLaunchOptionsRecord): Result<Unit> { this@Fixture.record = record; return Result.success(Unit) }
                override suspend fun delete(versionId: String): Result<Unit> { record = null; return Result.success(Unit) }
                override fun close() {}
            }, object : ModpackOptionRuntime {
                override fun getTotalPhysicalMemoryMb() = 16384
                override fun validateJdkPath(rawPath: String) = Result.success(rawPath)
                override fun validateMaxMemory(maxMemoryText: String, totalMemoryMb: Int) = runCatching { maxMemoryText.toInt() }
            }, scope).also { optionModel = it }
    }

    private fun obj(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (key, value) -> when (value) { is Int -> put(key, value); is Boolean -> put(key, value); else -> put(key, value.toString()) } }
    }
}
