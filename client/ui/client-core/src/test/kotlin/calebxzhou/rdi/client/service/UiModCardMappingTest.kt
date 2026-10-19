package calebxzhou.rdi.client.service

import calebxzau.rdi.client.modcatalog.CatalogModMetadata
import calebxzau.rdi.client.modcatalog.CatalogSlugRef
import calebxzau.rdi.client.modcatalog.ModPlatform
import calebxzhou.rdi.client.model.toUiMod
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzhou.rdi.client.service.content.toClientContentRequest
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.util.sha1
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UiModCardMappingTest {
    @Test
    fun `local first merge preserves jar icon and falls back to local intro`() {
        val local = Mod.CardVo(
            name = "local",
            intro = "jar intro",
            iconData = byteArrayOf(1),
            iconUrls = listOf("https://local/icon.png"),
        )
        val merged = local.mergeLocalFirst(
            Mod.CardVo(
                name = "remote",
                intro = "",
                iconUrls = listOf("https://remote/icon.png"),
            )
        )

        assertEquals("remote", merged.name)
        assertEquals("jar intro", merged.intro)
        assertContentEquals(byteArrayOf(1), merged.iconData)
        assertEquals(
            listOf("https://remote/icon.png", "https://local/icon.png"),
            merged.iconUrls,
        )
    }

    @Test
    fun `local card reads trusted jar from content cache`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-ui-card-cache")
        val jarBytes = jarWithIcon(byteArrayOf(1, 2, 3))
        val hash = sha1(jarBytes)
        Files.write(cacheRoot.resolve("$hash.sha1"), jarBytes)

        val mod = testMod(hash)
        val card = mod.toUiMod().toLocalCardVo(
            metadata = null,
            contentStore = ClientContentStore(cacheRoot)
        )

        assertContentEquals(byteArrayOf(1, 2, 3), assertNotNull(card).iconData)
    }

    @Test
    fun `local card keeps trusted legacy file fallback`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-ui-card-empty-cache")
        val legacyFile = Files.createTempFile("rdi-ui-card-legacy", ".jar")
        val jarBytes = jarWithIcon(byteArrayOf(4, 5, 6))
        val hash = sha1(jarBytes)
        Files.write(legacyFile, jarBytes)

        val mod = testMod(hash)
        val card = mod.toUiMod(file = legacyFile.toFile()).toLocalCardVo(
            metadata = null,
            contentStore = ClientContentStore(cacheRoot)
        )

        assertContentEquals(byteArrayOf(4, 5, 6), assertNotNull(card).iconData)
    }

    @Test
    fun `local cards read multiple trusted jars through one cache projection`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-ui-card-cache-batch")
        val firstBytes = jarWithIcon(byteArrayOf(7, 8, 9))
        val secondBytes = jarWithIcon(byteArrayOf(10, 11, 12))
        val firstHash = sha1(firstBytes)
        val secondHash = sha1(secondBytes)
        Files.write(cacheRoot.resolve("$firstHash.sha1"), firstBytes)
        Files.write(cacheRoot.resolve("$secondHash.sha1"), secondBytes)

        val mods = listOf(
            testMod(firstHash).copy(projectId = "project-one"),
            testMod(secondHash).copy(projectId = "project-two"),
            testMod(firstHash).copy(projectId = "project-one"),
        ).map { it.toUiMod() }
        val requestedIds = mutableListOf<List<String>>()
        val cachedContentReader: LocalCardContentReader = { requests ->
            requestedIds += requests.map { it.id }
            Result.success(
                requests.associate { request ->
                    request.id to cacheRoot.resolve("${request.digests.first().normalizedValue}.sha1")
                }
            )
        }
        val cards = mods.toLocalCardVos(
            metadata = emptyMap(),
            contentStore = ClientContentStore(cacheRoot),
            cachedContentReader = cachedContentReader,
        )

        assertEquals(1, requestedIds.size)
        assertEquals(
            listOf(mods[0].mod.toClientContentRequest().id, mods[1].mod.toClientContentRequest().id),
            requestedIds.single(),
        )
        assertContentEquals(byteArrayOf(7, 8, 9), assertNotNull(cards["project-one"]).iconData)
        assertContentEquals(byteArrayOf(10, 11, 12), assertNotNull(cards["project-two"]).iconData)
        assertEquals(2, cards.size)
    }

    @Test
    fun `local cache miss still produces catalog card while cache hit keeps jar icon`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-ui-card-cache-partial")
        val hitBytes = jarWithIcon(byteArrayOf(13, 14, 15))
        val hitHash = sha1(hitBytes)
        Files.write(cacheRoot.resolve("$hitHash.sha1"), hitBytes)
        val missHash = "f".repeat(40)
        val hit = testMod(hitHash).copy(projectId = "project-hit")
        val miss = testMod(missHash).copy(projectId = "project-miss", slug = "catalog-miss")
        val metadata = mapOf(
            CatalogSlugRef(ModPlatform.MODRINTH, "catalog-miss") to CatalogModMetadata(
                mcmodId = 1,
                name = "Catalog name",
                nameCn = "目录名称",
                intro = "Catalog intro",
                logoUrl = null,
                projects = emptyList(),
            )
        )

        val cards = listOf(hit, miss).map { it.toUiMod() }
            .toLocalCardVos(metadata, ClientContentStore(cacheRoot))

        assertContentEquals(byteArrayOf(13, 14, 15), assertNotNull(cards["project-hit"]).iconData)
        assertEquals("Catalog name", assertNotNull(cards["project-miss"]).name)
        assertEquals("Catalog intro", cards.getValue("project-miss").intro)
    }

    @Test
    fun `local first merge keeps jar version over remote version`() {
        val merged = Mod.CardVo(name = "local", version = "1.2.3")
            .mergeLocalFirst(Mod.CardVo(name = "remote", version = "9.9.9"))

        assertEquals("1.2.3", merged.version)
        val remoteOnly = Mod.CardVo(name = "local")
            .mergeLocalFirst(Mod.CardVo(name = "remote", version = "9.9.9"))
        assertEquals("9.9.9", remoteOnly.version)
    }

    @Test
    fun `local card reads simplified version from fabric metadata`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-ui-card-cache-version")
        val jarBytes = jarWithEntries(
            "fabric.mod.json" to """{"id":"example","version":"0.5.1+mc1.20.1"}""".toByteArray(),
        )
        val hash = sha1(jarBytes)
        Files.write(cacheRoot.resolve("$hash.sha1"), jarBytes)

        val card = testMod(hash).toUiMod().toLocalCardVo(
            metadata = null,
            contentStore = ClientContentStore(cacheRoot)
        )

        assertEquals("0.5.1", assertNotNull(card).version)
    }

    @Test
    fun `local card falls back to manifest version for jarVersion placeholder`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("rdi-ui-card-cache-manifest")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = "1.20.1-forge-15.2.0.27"
        }
        val modsToml = """
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"
            [[mods]]
            modId="example"
            version="${'$'}{file.jarVersion}"
        """.trimIndent()
        val jarBytes = jarWithEntries("META-INF/mods.toml" to modsToml.toByteArray(), manifest = manifest)
        val hash = sha1(jarBytes)
        Files.write(cacheRoot.resolve("$hash.sha1"), jarBytes)

        val card = testMod(hash).toUiMod().toLocalCardVo(
            metadata = null,
            contentStore = ClientContentStore(cacheRoot)
        )

        assertEquals("15.2.0.27", assertNotNull(card).version)
    }

    private fun testMod(hash: String) = Mod(
        platform = "mr",
        projectId = "project",
        slug = "example",
        fileId = "file",
        hash = hash
    )

    private fun jarWithIcon(icon: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        JarOutputStream(output).use { jar ->
            jar.putNextEntry(JarEntry("icon.png"))
            jar.write(icon)
            jar.closeEntry()
        }
        return output.toByteArray()
    }

    private fun jarWithEntries(vararg entries: Pair<String, ByteArray>, manifest: Manifest? = null): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val jar = if (manifest != null) JarOutputStream(output, manifest) else JarOutputStream(output)
        jar.use {
            entries.forEach { (name, bytes) ->
                it.putNextEntry(JarEntry(name))
                it.write(bytes)
                it.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha1(bytes: ByteArray): String = bytes.sha1
}
