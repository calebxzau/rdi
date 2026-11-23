package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.EXTRA_MOD_PREFIX
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.service.ModpackModProcessor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * J5b step 1: the room mod rules the Rust launcher copies, written to
 * `slint-proto/protocol/fixtures/j5b-sync.json`: the extra-mod prefix, the removed-mod file matches of
 * `RemovedModCleanupService`, and for a few `mods` folders which files the base and extra sync delete
 * and which mods they download. Run with `RDI_WRITE_FIXTURES=1` to rewrite it; otherwise it is checked.
 *
 * The delete lists follow `buildHostBaseModSyncTask2` / `materializeHostExtraMods`, whose filters are
 * private; the shared parts (`prepareHostBaseModSyncInputs`, `missingHostBaseMods`, ...) are called.
 */
class J5bRoomModSyncFixtureTest {
    private val fixture = File("../../slint-proto/protocol/fixtures/j5b-sync.json")

    private fun mod(slug: String, hash: String, side: Mod.Side = Mod.Side.BOTH, platform: String = "cf") =
        Mod(platform = platform, projectId = "p-$slug", slug = slug, fileId = "f-$slug", hash = hash, side = side)

    private val removedNameCases = listOf(
        "spark.jar", "spark-1.10.jar", "spark_forge.jar", "SPARK-1.0.JAR", "sparkle.jar", "spark.zip",
        "lazyyyyy-1.0.jar", "skybox-loader-forge_1.jar", "chunky.jar", "tab-list-2.jar", "jei.jar",
    )

    private data class SyncCase(
        val name: String,
        val loader: ModLoader,
        val active: List<Mod>,
        val disabled: List<Mod>,
        val extra: List<Mod>,
        val existing: List<String>,
    )

    private val keep = mod("jei", "aaa111")
    private val clientOnly = mod("oculus", "bbb222", Mod.Side.CLIENT)
    private val serverOnly = mod("servercore", "ccc333", Mod.Side.SERVER)
    private val unknown = mod("mystery", "ddd444", Mod.Side.UNKNOWN)
    private val removed = mod("spark", "eee555")
    private val alias = mod("true-ending", "fff666")
    private val disabled = mod("journeymap", "abc777", Mod.Side.CLIENT)
    private val legacy = mod(" Create ", "aaa888", platform = "mr")
    private val extraA = mod("waystones", "123aaa")
    private val extraServer = mod("dynmap", "123bbb", Mod.Side.SERVER)

    private val cases = listOf(
        SyncCase(
            "fresh-folder", ModLoader.forge,
            active = listOf(keep, clientOnly, serverOnly, unknown, removed, alias, legacy),
            disabled = listOf(disabled),
            extra = listOf(extraA, extraServer, removed),
            existing = emptyList(),
        ),
        SyncCase(
            "existing-files", ModLoader.neoforge,
            active = listOf(keep, clientOnly, serverOnly, unknown, alias, legacy, keep),
            disabled = listOf(disabled, removed),
            extra = listOf(extraA),
            existing = listOf(
                keep.fileName, legacy.legacyFileName, serverOnly.fileName, unknown.legacyFileName,
                disabled.fileName, removed.fileName, "${EXTRA_MOD_PREFIX}${extraA.fileName}",
                "${EXTRA_MOD_PREFIX}old_cf_999.jar", "other.jar",
            ),
        ),
    )

    private fun names(values: Iterable<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun mods(values: List<Mod>): JsonArray = buildJsonArray {
        values.forEach { m ->
            add(buildJsonObject {
                put("platform", m.platform); put("projectId", m.projectId); put("slug", m.slug)
                put("fileId", m.fileId); put("hash", m.hash); put("side", m.side.name)
            })
        }
    }

    private fun plan(case: SyncCase): JsonObject {
        val root = Files.createTempDirectory("rdi-j5b-sync").toFile()
        val modsDir = root.resolve("mods").apply { mkdirs() }
        case.existing.forEach { modsDir.resolve(it).writeText("x") }

        // buildHostBaseModSyncTask2
        val input = prepareHostBaseModSyncInputs(case.active, case.disabled, case.loader)
        val serverOnly = input.activeMods.filter { it.side == Mod.Side.SERVER }
        val unknownSide = input.activeMods.filter { it.side == Mod.Side.UNKNOWN }
        val toRemove = (input.disabledMods + serverOnly + unknownSide).distinctBy { it.fileName }
        val toKeep = input.activeMods.filter { it.side != Mod.Side.SERVER && it.side != Mod.Side.UNKNOWN }.distinctBy { it.fileName }
        // removeDisabledBaseMods
        val baseDeleted = toRemove.flatMap { it.fileNames }.filter { modsDir.resolve(it).exists() }
        baseDeleted.forEach { modsDir.resolve(it).delete() }
        val baseMissing = missingHostBaseMods(modsDir.toPath(), toKeep)

        // buildHostExtraModSyncTask2 + materializeHostExtraMods
        val extra = prepareHostExtraModSyncMods(case.extra, case.loader).distinctBy { it.fileName }
        val expected = extra.map { EXTRA_MOD_PREFIX + it.fileName }.toSet()
        val extraDeleted = modsDir.listFiles().orEmpty().map { it.name }.sorted()
            .filter { it.startsWith(EXTRA_MOD_PREFIX) && it !in expected }
        extraDeleted.forEach { modsDir.resolve(it).delete() }
        val extraMissing = missingHostExtraMods(modsDir.toPath(), extra)
        root.deleteRecursively()
        return buildJsonObject {
            put("name", case.name)
            put("loader", case.loader.name)
            put("active", mods(case.active))
            put("disabled", mods(case.disabled))
            put("extra", mods(case.extra))
            put("existing", names(case.existing))
            put("baseDeleted", names(baseDeleted))
            put("baseDownload", names(baseMissing.map { it.fileName }))
            put("extraDeleted", names(extraDeleted))
            put("extraDownload", names(extraMissing.map { EXTRA_MOD_PREFIX + it.fileName }))
        }
    }

    @Test
    fun `room mod sync rules match the shared fixture`() {
        val generated = buildJsonObject {
            put("comment", "Written by J5bRoomModSyncFixtureTest (client-core); checked by the room mod sync tests in Rust.")
            put("extraModPrefix", EXTRA_MOD_PREFIX)
            put("removedSlugsForge", names(ModpackModProcessor.removedSlugsFor(ModLoader.forge).sorted()))
            put("removedNames", buildJsonArray {
                removedNameCases.forEach { name ->
                    add(buildJsonObject {
                        put("file", name)
                        put("forge", RemovedModCleanupService.matchesRemovedSlug(name, ModLoader.forge))
                        put("neoforge", RemovedModCleanupService.matchesRemovedSlug(name, ModLoader.neoforge))
                    })
                }
            })
            put("cases", buildJsonArray { cases.forEach { add(plan(it)) } })
        }
        val text = jsonPretty(generated) + "\n"
        if (System.getenv("RDI_WRITE_FIXTURES") == "1") {
            fixture.writeText(text)
        }
        assertTrue(fixture.isFile, "缺少j5b-sync.json，请用RDI_WRITE_FIXTURES=1运行本测试")
        assertEquals(fixture.readText().replace("\r\n", "\n"), text)
    }

    private fun jsonPretty(element: JsonElement, indent: String = ""): String = when (element) {
        is JsonObject -> element.entries.joinToString(",\n", "{\n", "\n$indent}") { (key, value) ->
            "$indent  ${JsonPrimitive(key)}: ${jsonPretty(value, "$indent  ")}"
        }
        is JsonArray -> if (element.isEmpty()) "[]" else element.joinToString(",\n", "[\n", "\n$indent]") {
            "$indent  ${jsonPretty(it, "$indent  ")}"
        }
        else -> element.toString()
    }
}
