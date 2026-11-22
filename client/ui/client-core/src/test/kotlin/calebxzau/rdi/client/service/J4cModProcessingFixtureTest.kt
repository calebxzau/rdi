package calebxzau.rdi.client.service

import calebxzhou.rdi.client.service.content.toClientContentRequest
import calebxzhou.rdi.common.model.MOD_FILE_SLUG_ALIASES
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.service.ModpackModProcessor
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

/**
 * Writes or checks `slint-proto/protocol/fixtures/j4c.json`, which the Rust room-pack install
 * reads. Run with `RDI_WRITE_FIXTURES=1` to rewrite it after the Kotlin lists change.
 */
class J4cModProcessingFixtureTest {
    private val fixture = File("../../slint-proto/protocol/fixtures/j4c.json")
    private val pretty = Json { prettyPrint = true }

    @Suppress("UNCHECKED_CAST")
    private fun privateSet(name: String): Set<String> =
        ModpackModProcessor::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null) as Set<String>

    private fun mod(
        slug: String,
        side: Mod.Side = Mod.Side.BOTH,
        platform: String = "mr",
        hash: String = slug.hashCode().toUInt().toString(16).padStart(8, '0').repeat(5),
        projectId: String = "p-$slug",
        fileId: String = "f-$slug",
        clientOnlyOverride: Boolean = false,
        clientOverrideReplaced: Boolean = false,
    ) = Mod(platform, projectId, slug, fileId, hash, side, emptyList(), clientOnlyOverride, clientOverrideReplaced)

    /** `installVersionTask2` and the private `isClientInstallableMod` in ModpackService. */
    private fun installable(mods: List<Mod>, loader: ModLoader): List<Mod> {
        val scoped = if (mods.any { it.clientOnlyOverride || it.clientOverrideReplaced }) {
            ModpackModProcessor.processMods(mods, loader)
        } else mods
        return scoped.filter { it.side != Mod.Side.SERVER && it.side != Mod.Side.UNKNOWN }
    }

    private fun Mod.input() = buildJsonObject {
        put("platform", platform); put("projectId", projectId); put("slug", slug); put("fileId", fileId)
        put("hash", hash); put("side", side.name); put("downloadUrls", JsonArray(emptyList()))
        put("clientOnlyOverride", clientOnlyOverride); put("clientOverrideReplaced", clientOverrideReplaced)
    }

    private fun Mod.output() = buildJsonObject {
        put("slug", slug); put("platform", platform); put("side", side.name); put("fileName", fileName)
        put("digest", toClientContentRequest().digests.single().algorithm.suffix)
    }

    private fun cases(): List<Triple<String, ModLoader, List<Mod>>> {
        val trigger = mod("trigger", clientOnlyOverride = true)
        val sha1 = "a".repeat(40)
        val noOverride = listOf(
            mod("spark"), mod("world-backups"), mod("modern-ui"), mod("server-only", Mod.Side.SERVER),
            mod("unknown-side", Mod.Side.UNKNOWN), mod("True-Ending"), mod(" padded-slug "),
            mod("github-sha1", platform = "github", hash = sha1),
            mod("github-sha256", platform = "github", hash = "b".repeat(64)),
            mod("curse", platform = "cf", hash = "3735928559"),
        )
        val overrides = listOf(
            mod("shadow", clientOnlyOverride = true, hash = "c".repeat(40)),
            mod("shadow", hash = "d".repeat(40)),
            mod("shadow-client", clientOnlyOverride = true, hash = "e".repeat(40)),
            mod("shadow-client", Mod.Side.CLIENT, hash = "f".repeat(40)),
            mod("shadow-server", clientOnlyOverride = true, hash = "1".repeat(40)),
            mod("shadow-server", Mod.Side.SERVER, hash = "2".repeat(40)),
            mod("same-hash", clientOnlyOverride = true, hash = "3".repeat(40)),
            mod("same-hash", hash = "3".repeat(40)),
            mod("replaced-client", Mod.Side.CLIENT, clientOverrideReplaced = true),
            mod("replaced-both", clientOverrideReplaced = true),
            mod("replaced-server", Mod.Side.SERVER, clientOverrideReplaced = true),
            mod("lazyyyyy"), mod("skybox-loader-forge"), mod("spark"), mod("my-backup-mod"),
            mod("modern-ui"), mod("loot-beams-refork", Mod.Side.CLIENT), mod("Inventory-Profiles-Next", Mod.Side.SERVER),
        )
        val sameModFallbacks = listOf(
            mod("by-slug", clientOnlyOverride = true, projectId = "", hash = "4".repeat(40)),
            mod("BY-SLUG", projectId = "other", hash = "5".repeat(40)),
            mod("", clientOnlyOverride = true, projectId = "", hash = "6".repeat(40), platform = "github"),
            mod("", projectId = "x", hash = "7".repeat(40)),
            mod("other-platform", clientOnlyOverride = true, platform = "cf", hash = "1", projectId = "42"),
            mod("other-platform-2", platform = "mr", projectId = "42", hash = "8".repeat(40)),
        )
        val out = mutableListOf(
            Triple("no override keeps every listed mod", ModLoader.forge, noOverride),
            Triple("overrides on forge", ModLoader.forge, overrides),
            Triple("overrides on neoforge", ModLoader.neoforge, overrides),
            Triple("overrides on fabric", ModLoader.Fabric, overrides),
            Triple("sameMod fallbacks", ModLoader.forge, sameModFallbacks),
        )
        val lists = mapOf(
            "removed" to ModpackModProcessor.removedSlugs,
            "client" to privateSet("clientSideSlugs"),
            "both" to privateSet("bothSideSlugs"),
            "aliases" to MOD_FILE_SLUG_ALIASES.keys,
        )
        for (loader in listOf(ModLoader.forge, ModLoader.Fabric)) {
            for ((name, slugs) in lists) {
                val sides = Mod.Side.entries.filter { it != Mod.Side.UNKNOWN }
                out += Triple("$name list on ${loader.name}", loader,
                    listOf(trigger) + slugs.sorted().mapIndexed { i, slug -> mod(slug, sides[i % sides.size]) })
            }
        }
        return out
    }

    private fun generate(): JsonObject = buildJsonObject {
        put("lists", buildJsonObject {
            put("removedSlugs", JsonArray(ModpackModProcessor.removedSlugs.sorted().map(::JsonPrimitive)))
            put("forgeOnlyRemovedSlugs", JsonArray(
                (ModpackModProcessor.removedSlugs - ModpackModProcessor.removedSlugsFor(ModLoader.Fabric)).sorted().map(::JsonPrimitive)))
            put("clientSideSlugs", JsonArray(privateSet("clientSideSlugs").sorted().map(::JsonPrimitive)))
            put("bothSideSlugs", JsonArray(privateSet("bothSideSlugs").sorted().map(::JsonPrimitive)))
            put("fileSlugAliases", JsonObject(MOD_FILE_SLUG_ALIASES.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        })
        put("cases", JsonArray(cases().map { (name, loader, mods) ->
            buildJsonObject {
                put("name", name)
                put("loader", loader.name)
                put("mods", JsonArray(mods.map { it.input() }))
                put("expected", JsonArray(installable(mods, loader).map { it.output() }))
            }
        }))
    }

    @Test
    fun `room pack mod processing fixture matches Kotlin`() {
        val generated = generate()
        if (System.getenv("RDI_WRITE_FIXTURES") == "1") {
            fixture.writeText(pretty.encodeToString(JsonObject.serializer(), generated) + "\n")
        }
        assertTrue(fixture.isFile, "缺少j4c.json，请用RDI_WRITE_FIXTURES=1运行本测试")
        assertEquals(generated, Json.parseToJsonElement(fixture.readText()), "j4c.json已过期，请用RDI_WRITE_FIXTURES=1重新生成并更新Rust")
    }
}
