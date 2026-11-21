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
    fun `play looks the pack up on demand and moved methods are gone`(): Unit = runBlocking {
        val fixture = Fixture(this)
        fixture.service.use { service ->
            val state = service.handle("packPlay.open", obj("versionId" to fixture.pack.versionId))
            assertEquals(fixture.pack.versionId, state.text("versionId"))
            assertEquals(true, state["local"]!!.jsonPrimitive.boolean)
            assertEquals(listOf(fixture.pack.versionId), fixture.lookups)
            assertFailsWith<IllegalStateException> { service.handle("packPlay.open", obj("versionId" to "../outside")) }
            service.handle("packPlay.cancel", obj())
            for (method in listOf("installedPacks.subscribe", "installedPacks.directory", "packOptions.open", "packOptions.action", "installer.open", "installer.action", "installer.install", "installer.close", "tasks.subscribe", "tasks.watch", "tasks.cancel")) {
                assertFailsWith<IllegalStateException>(method) { service.handle(method, obj("versionId" to fixture.pack.versionId)) }
            }
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
        val lookups = mutableListOf<String>()
        val service = PackService(this, scope, { _, _ -> error("No game should start in this test") }) { name, state -> events += name to state }
        override suspend fun findPack(versionId: String): Result<ModpackLocalDir> {
            lookups += versionId
            return if (versionId == pack.versionId) Result.success(pack)
            else Result.failure(IllegalStateException("找不到这个整合包，请刷新列表"))
        }

    }

    private fun obj(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (key, value) -> when (value) { is Int -> put(key, value); is Boolean -> put(key, value); else -> put(key, value.toString()) } }
    }
}
