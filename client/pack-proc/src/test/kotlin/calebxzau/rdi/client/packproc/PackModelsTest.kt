package calebxzau.rdi.client.packproc

import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.modcatalog.CatalogModMetadata
import calebxzau.rdi.client.modcatalog.CatalogSlugRef
import calebxzhou.rdi.common.archive.forEachArchiveEntry
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzau.rdi.common.model.Content
import calebxzhou.rdi.common.model.LoadProgress
import kotlin.test.assertFailsWith
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType
import calebxzhou.rdi.common.model.CurseForgeFile
import calebxzhou.rdi.common.model.CurseForgeFileHash
import calebxzhou.rdi.common.model.CurseForgeModInfo
import calebxzhou.rdi.common.service.murmur2
import calebxzhou.rdi.common.util.sha1
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackModelsTest {
    @Test
    fun `failed local archive preparation removes temporary pack directory`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-extract-failure").toFile()
        try {
            val archive = root.resolve("invalid.zip")
            archive.outputStream().use { fileOutput ->
                ZipOutputStream(fileOutput).use { zip ->
                    zip.putNextEntry(ZipEntry("modrinth.index.json"))
                    zip.write("{}".toByteArray())
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("overrides/"))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("../escape.txt"))
                    zip.write("must not escape".toByteArray())
                    zip.closeEntry()
                }
            }
            val workDir = root.resolve("work")
            val result = ModpackProcessor(PackProcessingPaths(workDir)).loadLocalModpack(
                modCatalog = noCallModCatalog(),
                file = archive,
                onProgress = {},
            )

            assertTrue(result.isFailure)
            assertTrue(workDir.listFiles().orEmpty().none { it.name.startsWith("pack-") })
            assertFalse(workDir.resolve("escape.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `client mp3 files are replaced in regular nested and resourcepack entries`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-mp3-upload").toFile()
        try {
            root.resolve("sounds/source.MP3").also { it.parentFile.mkdirs() }
                .writeBytes("original top-level audio".toByteArray())
            writeZip(
                root.resolve("nested.zip"),
                mapOf("sounds/nested.mp3" to "original zip audio".toByteArray())
            )
            writeZip(
                root.resolve("mods/example.jar"),
                mapOf("assets/example/nested.MP3" to "original jar audio".toByteArray())
            )
            writeZip(
                root.resolve("resourcepacks/music-pack.zip"),
                mapOf(
                    "assets/music/lower.mp3" to "original resourcepack zip audio".toByteArray(),
                    "assets/music/upper.MP3" to "original resourcepack zip audio".toByteArray(),
                    "assets/music/texture.bin" to byteArrayOf(0x01, 0x02, 0x03, 0x04),
                    "assets/music/original.ogg" to byteArrayOf(0x4f, 0x67, 0x67, 0x53, 0x55, 0x46, 0x46, 0x49, 0x58)
                )
            )
            root.resolve("resourcepacks/test/assets/test.mp3").also { it.parentFile.mkdirs() }
                .writeBytes("original resourcepack audio".toByteArray())

            val processor = ModpackProcessor(PackProcessingPaths(root.resolve("work")))
            val archive = processor.buildUploadArchive(root, "mp3-upload")
            val archiveFiles = mutableMapOf<String, ByteArray>()
            forEachArchiveEntry(archive) { entry ->
                if (!entry.isDirectory) archiveFiles[entry.path] = entry.bytes!!
            }
            val placeholder = javaClass.classLoader.getResourceAsStream("assets/empty.mp3")!!.use { it.readBytes() }

            assertContentEquals(placeholder, archiveFiles["sounds/source.MP3"])
            assertContentEquals(placeholder, archiveFiles["resourcepacks/test/assets/test.mp3"])

            assertNestedZipEntryEquals(archiveFiles["nested.zip"]!!, "sounds/nested.mp3", placeholder)
            assertNestedZipEntryEquals(archiveFiles["mods/example.jar"]!!, "assets/example/nested.MP3", placeholder)
            assertNestedZipEntryEquals(archiveFiles["resourcepacks/music-pack.zip"]!!, "assets/music/lower.mp3", placeholder)
            assertNestedZipEntryEquals(archiveFiles["resourcepacks/music-pack.zip"]!!, "assets/music/upper.MP3", placeholder)
            assertNestedZipEntryEquals(
                archiveFiles["resourcepacks/music-pack.zip"]!!,
                "assets/music/texture.bin",
                byteArrayOf(0x01, 0x02, 0x03, 0x04)
            )
            assertNestedZipEntryEquals(
                archiveFiles["resourcepacks/music-pack.zip"]!!,
                "assets/music/original.ogg",
                byteArrayOf(0x4f, 0x67, 0x67, 0x53, 0x55, 0x46, 0x46, 0x49, 0x58)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `mca files are filtered except exact ftbteambases segments`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-mca-upload").toFile()
        try {
            root.resolve("region/r.0.0.mca").also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(1))
            root.resolve("myftbteambasescopy/r.0.0.mca").also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(2))
            root.resolve("FTBTeamBases/region/r.0.0.MCA").also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(3))
            writeZip(
                root.resolve("mods/example.jar"),
                mapOf(
                    "region/nested.mca" to byteArrayOf(4),
                    "foo/ftbteambases/kept.mca" to byteArrayOf(5),
                    "assets/example.txt" to byteArrayOf(6),
                )
            )
            writeZip(
                root.resolve("resourcepacks/example.zip"),
                mapOf(
                    "region/resourcepack.mca" to byteArrayOf(7),
                    "ftbteambases/resourcepack-kept.mca" to byteArrayOf(8),
                    "assets/texture.bin" to byteArrayOf(9),
                )
            )
            writeZip(
                root.resolve("data/eligible.zip"),
                mapOf("region/eligible.mca" to byteArrayOf(10))
            )
            writeZip(
                root.resolve("data/eligible.jar"),
                mapOf("region/eligible-jar.mca" to byteArrayOf(11))
            )

            val archive = ModpackProcessor(PackProcessingPaths(root.resolve("work")))
                .buildUploadArchive(root, "mca-upload")
            val archiveFiles = mutableMapOf<String, ByteArray>()
            forEachArchiveEntry(archive) { entry ->
                if (!entry.isDirectory) archiveFiles[entry.path] = entry.bytes!!
            }

            assertFalse(archiveFiles.containsKey("region/r.0.0.mca"))
            assertFalse(archiveFiles.containsKey("myftbteambasescopy/r.0.0.mca"))
            assertContentEquals(byteArrayOf(3), archiveFiles["FTBTeamBases/region/r.0.0.MCA"])
            assertNestedZipEntryMissing(archiveFiles["mods/example.jar"]!!, "region/nested.mca")
            assertNestedZipEntryEquals(archiveFiles["mods/example.jar"]!!, "foo/ftbteambases/kept.mca", byteArrayOf(5))
            assertNestedZipEntryMissing(archiveFiles["resourcepacks/example.zip"]!!, "region/resourcepack.mca")
            assertNestedZipEntryEquals(
                archiveFiles["resourcepacks/example.zip"]!!,
                "ftbteambases/resourcepack-kept.mca",
                byteArrayOf(8)
            )
            assertNestedZipEntryEquals(archiveFiles["resourcepacks/example.zip"]!!, "assets/texture.bin", byteArrayOf(9))
            assertNestedZipEntryMissing(archiveFiles["data/eligible.zip"]!!, "region/eligible.mca")
            assertNestedZipEntryMissing(archiveFiles["data/eligible.jar"]!!, "region/eligible-jar.mca")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `local preparation detects excluded mca files after extraction`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-mca-detection").toFile()
        try {
            val archive = root.resolve("mca-detection.zip")
            writeZip(
                archive,
                mapOf(
                    "modrinth.index.json" to modrinthIndexBytes(),
                    "overrides/FTBTeamBases/region/kept.mca" to byteArrayOf(2),
                    "overrides/data/eligible.zip" to zipBytes(
                        mapOf("region/eligible.mca" to byteArrayOf(4))
                    ),
                    "overrides/data/eligible.jar" to zipBytes(
                        mapOf("region/eligible-jar.mca" to byteArrayOf(5))
                    ),
                )
            )
            val loaded = ModpackProcessor(
                paths = PackProcessingPaths(root.resolve("work")),
                embeddedClientExtraMatcher = { _, _ -> emptyList() },
            )
                .loadLocalModpack(noCallModCatalog(), archive, onProgress = {})
                .getOrThrow()
            assertTrue(loaded.containsExcludedMcaFiles)

            val onlyKeptArchive = root.resolve("only-kept.zip")
            writeZip(
                onlyKeptArchive,
                mapOf(
                    "modrinth.index.json" to modrinthIndexBytes(),
                    "overrides/ftbteambases/region/kept.mca" to byteArrayOf(3),
                )
            )
            val onlyKept = ModpackProcessor(PackProcessingPaths(root.resolve("work-kept")))
                .loadLocalModpack(noCallModCatalog(), onlyKeptArchive, onProgress = {})
                .getOrThrow()
            assertFalse(onlyKept.containsExcludedMcaFiles)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `local preparation skips malformed shaderpack archives during mca detection`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-skipped-archive").toFile()
        try {
            val archive = root.resolve("skipped-archive.zip")
            writeZip(
                archive,
                mapOf(
                    "modrinth.index.json" to modrinthIndexBytes(),
                    "overrides/shaderpacks/broken.zip" to "not a zip".toByteArray(),
                )
            )

            val loaded = ModpackProcessor(
                paths = PackProcessingPaths(root.resolve("work")),
                embeddedClientExtraMatcher = { _, _ -> emptyList() },
            )
                .loadLocalModpack(noCallModCatalog(), archive, onProgress = {})
                .getOrThrow()

            assertFalse(loaded.containsExcludedMcaFiles)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `server extra mca files and direct archive entries are filtered`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-server-mca").toFile()
        try {
            val extraRoot = root.resolve("server-files").also { it.mkdirs() }
            val excluded = extraRoot.resolve("world/region/r.0.0.mca").also { it.parentFile.mkdirs() }
            excluded.writeBytes(byteArrayOf(1))
            val kept = extraRoot.resolve("ftbteambases/region/r.0.0.mca").also { it.parentFile.mkdirs() }
            kept.writeBytes(byteArrayOf(2))
            val nested = extraRoot.resolve("data.zip")
            writeZip(
                nested,
                mapOf(
                    "world/region/nested.mca" to byteArrayOf(3),
                    "ftbteambases/kept.mca" to byteArrayOf(4),
                    "sounds/server.mp3" to byteArrayOf(7, 8),
                    "cache/kept.dat" to byteArrayOf(9, 10),
                )
            )

            val archive = ModpackProcessor(PackProcessingPaths(root.resolve("work"))).buildUploadArchive(
                rootDir = root.resolve("empty").also { it.mkdirs() },
                baseName = "server-mca-upload",
                serverExtraFiles = listOf(
                    ServerExtraFile(excluded, "world/region/r.0.0.mca"),
                    ServerExtraFile(kept, "ftbteambases/region/r.0.0.mca"),
                    ServerExtraFile(nested, "data.zip"),
                ),
            )
            val archiveFiles = mutableMapOf<String, ByteArray>()
            forEachArchiveEntry(archive) { entry ->
                if (!entry.isDirectory) archiveFiles[entry.path] = entry.bytes!!
            }
            assertFalse(archiveFiles.containsKey("server/world/region/r.0.0.mca"))
            assertContentEquals(byteArrayOf(2), archiveFiles["server/ftbteambases/region/r.0.0.mca"])
            assertNestedZipEntryMissing(archiveFiles["server/data.zip"]!!, "world/region/nested.mca")
            assertNestedZipEntryEquals(archiveFiles["server/data.zip"]!!, "ftbteambases/kept.mca", byteArrayOf(4))
            assertNestedZipEntryEquals(archiveFiles["server/data.zip"]!!, "sounds/server.mp3", byteArrayOf(7, 8))
            assertNestedZipEntryEquals(archiveFiles["server/data.zip"]!!, "cache/kept.dat", byteArrayOf(9, 10))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `disabled client files are excluded while nested entries are preserved`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-disabled-client").toFile()
        try {
            root.resolve("config/ignored.disabled").also { it.parentFile.mkdirs() }
                .writeBytes(byteArrayOf(1))
            root.resolve("config/UPPER.DISABLED").writeBytes(byteArrayOf(2))
            root.resolve("config/disabled.txt").writeBytes(byteArrayOf(3))
            root.resolve("config/name.disabled.txt").writeBytes(byteArrayOf(4))
            root.resolve("directory.disabled/kept.txt").also { it.parentFile.mkdirs() }
                .writeBytes(byteArrayOf(5))
            writeZip(
                root.resolve("nested.zip"),
                mapOf("inside.disabled" to byteArrayOf(6), "kept.txt" to byteArrayOf(7))
            )

            val archive = ModpackProcessor(PackProcessingPaths(root.resolve("work")))
                .buildUploadArchive(root, "disabled-client")
            val archiveFiles = mutableMapOf<String, ByteArray>()
            forEachArchiveEntry(archive) { entry ->
                if (!entry.isDirectory) archiveFiles[entry.path] = entry.bytes!!
            }

            assertFalse(archiveFiles.containsKey("config/ignored.disabled"))
            assertFalse(archiveFiles.containsKey("config/UPPER.DISABLED"))
            assertContentEquals(byteArrayOf(3), archiveFiles["config/disabled.txt"])
            assertContentEquals(byteArrayOf(4), archiveFiles["config/name.disabled.txt"])
            assertContentEquals(byteArrayOf(5), archiveFiles["directory.disabled/kept.txt"])
            assertNestedZipEntryEquals(archiveFiles["nested.zip"]!!, "inside.disabled", byteArrayOf(6))
            assertNestedZipEntryEquals(archiveFiles["nested.zip"]!!, "kept.txt", byteArrayOf(7))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `disabled selected server files are excluded from extras`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-disabled-server").toFile()
        try {
            val serverMods = root.resolve("mods").also { it.mkdirs() }
            val stagedClient = root.resolve("staged/client.jar")
                .also { it.parentFile.mkdirs() }
            writeModJar(stagedClient, "shared")
            writeModJar(serverMods.resolve("server-shared.jar"), "shared")
            root.resolve("config/ignored.disabled").also { it.parentFile.mkdirs() }
                .writeBytes(byteArrayOf(1))
            root.resolve("config/UPPER.DISABLED").writeBytes(byteArrayOf(2))
            root.resolve("directory.disabled/kept.txt").also { it.parentFile.mkdirs() }
                .writeBytes(byteArrayOf(3))
            root.resolve("server.properties").writeText("level-name=world")

            val clientMod = Mod(
                platform = "mr",
                projectId = "project",
                slug = "shared-mod",
                fileId = "file",
                hash = stagedClient.sha1,
            )
            val loaded = ModpackProcessor(PackProcessingPaths(root.resolve("work")))
                .loadServerPack(
                    file = root,
                    clientMods = listOf(clientMod),
                    clientModSources = mapOf(clientMod to stagedClient),
                    onProgress = {},
                ).getOrThrow()

            val extraPaths = loaded.serverExtraFiles.map { it.relativePath }.toSet()
            assertFalse(extraPaths.contains("config/ignored.disabled"))
            assertFalse(extraPaths.contains("config/UPPER.DISABLED"))
            assertTrue(extraPaths.contains("directory.disabled/kept.txt"))
            assertTrue(extraPaths.contains("server.properties"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `direct disabled server extras are excluded`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-disabled-direct").toFile()
        try {
            val disabledSource = root.resolve("direct.disabled").also { it.writeBytes(byteArrayOf(1)) }
            val normalSource = root.resolve("normal.txt").also { it.writeBytes(byteArrayOf(2)) }
            val keptSource = root.resolve("kept.txt").also { it.writeBytes(byteArrayOf(3)) }
            val archive = ModpackProcessor(PackProcessingPaths(root.resolve("work"))).buildUploadArchive(
                rootDir = root.resolve("empty").also { it.mkdirs() },
                baseName = "disabled-direct",
                serverExtraFiles = listOf(
                    ServerExtraFile(disabledSource, "renamed.txt"),
                    ServerExtraFile(normalSource, "renamed.DISABLED"),
                    ServerExtraFile(keptSource, "kept.txt"),
                ),
            )
            val archiveFiles = mutableMapOf<String, ByteArray>()
            forEachArchiveEntry(archive) { entry ->
                if (!entry.isDirectory) archiveFiles[entry.path] = entry.bytes!!
            }

            assertFalse(archiveFiles.containsKey("server/renamed.txt"))
            assertFalse(archiveFiles.containsKey("server/renamed.DISABLED"))
            assertContentEquals(byteArrayOf(3), archiveFiles["server/kept.txt"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `matched client extras are excluded while matched shader companion survives`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-client-extras").toFile()
        try {
            val matchedResource = root.resolve("resourcepacks/matched.zip")
            writeZip(matchedResource, mapOf("assets/kept.txt" to byteArrayOf(1)))
            val matchedShader = root.resolve("shaderpacks/matched.zip")
            writeZip(matchedShader, mapOf("shader.properties" to byteArrayOf(2)))
            root.resolve("shaderpacks/matched.zip.txt").writeText("profile=high")
            writeZip(root.resolve("shaderpacks/unmatched.zip"), mapOf("shader.properties" to byteArrayOf(3)))
            root.resolve("shaderpacks/unmatched.zip.txt").writeText("drop=true")

            val resourceContent = Content(
                platform = ContentPlatform.Modrinth,
                type = ContentType.ResPack,
                projectId = "resource-project",
                fileId = "resource-file",
                slug = "matched-resource",
                hash = matchedResource.sha1,
                path = "resourcepacks/matched.zip",
                side = ContentSide.Client,
            )
            val shaderContent = Content(
                platform = ContentPlatform.Modrinth,
                type = ContentType.ShaderPack,
                projectId = "shader-project",
                fileId = "shader-file",
                slug = "matched-shader",
                hash = matchedShader.sha1,
                path = "shaderpacks/matched.zip",
                side = ContentSide.Client,
            )
            val resourceStaging = root.resolve("staged/matched-resource.zip").also { it.parentFile.mkdirs() }
            val shaderStaging = root.resolve("staged/matched-shader.zip")
            matchedResource.copyTo(resourceStaging, overwrite = true)
            matchedShader.copyTo(shaderStaging, overwrite = true)
            val archive = ModpackProcessor(PackProcessingPaths(root.resolve("work"))).buildUploadArchive(
                rootDir = root,
                baseName = "client-extras",
                clientExtras = listOf(resourceContent, shaderContent),
                excludedClientExtras = listOf(
                    EmbeddedClientExtraSource(resourceContent, resourceStaging, "resourcepacks/matched.zip", matchedResource.sha1),
                    EmbeddedClientExtraSource(shaderContent, shaderStaging, "shaderpacks/matched.zip", matchedShader.sha1),
                ),
            )
            val entries = mutableMapOf<String, ByteArray>()
            forEachArchiveEntry(archive) { entry ->
                if (!entry.isDirectory) entries[entry.path] = entry.bytes!!
            }
            assertFalse(entries.containsKey("resourcepacks/matched.zip"))
            assertFalse(entries.containsKey("shaderpacks/matched.zip"))
            assertTrue(entries.containsKey("shaderpacks/matched.zip.txt"))
            assertFalse(entries.containsKey("shaderpacks/unmatched.zip"))
            assertFalse(entries.containsKey("shaderpacks/unmatched.zip.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `matched client extra source mutation fails before archive exclusion`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-client-extra-mutation").toFile()
        try {
            val source = root.resolve("resourcepacks/mutable.zip")
            writeZip(source, mapOf("assets/original.txt" to byteArrayOf(1)))
            val content = Content(
                platform = ContentPlatform.Modrinth,
                type = ContentType.ResPack,
                projectId = "project",
                fileId = "file",
                slug = "mutable",
                hash = source.sha1,
                path = "resourcepacks/mutable.zip",
                side = ContentSide.Client,
            )
            source.appendBytes(byteArrayOf(9))
            val error = runCatching {
                ModpackProcessor(PackProcessingPaths(root.resolve("work"))).buildUploadArchive(
                    rootDir = root,
                    baseName = "mutation",
                    excludedClientExtras = listOf(
                        EmbeddedClientExtraSource(content, source, "resourcepacks/mutable.zip", content.hash),
                    ),
                )
            }.exceptionOrNull()
            assertTrue(error != null)
            assertTrue(error.message.orEmpty().contains("发生变化"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unmatched resourcepack size uses strict five mebibyte boundary as one pack`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-resourcepack-boundary").toFile()
        try {
            val exact = root.resolve("resourcepacks/exact/assets/first.bin")
                .also { it.parentFile.mkdirs() }
            RandomAccessFile(exact, "rw").use { it.setLength(5L * 1024L * 1024L) }
            val oversizedFirst = root.resolve("overrides/resourcepacks/oversized/assets/first.bin")
                .also { it.parentFile.mkdirs() }
            val oversizedSecond = root.resolve("overrides/resourcepacks/oversized/assets/second.bin")
            RandomAccessFile(oversizedFirst, "rw").use { it.setLength(2L * 1024L * 1024L) }
            RandomAccessFile(oversizedSecond, "rw").use { it.setLength(3L * 1024L * 1024L + 1L) }
            val exactZip = root.resolve("resourcepacks/exact.zip")
            writeStoredZipOfSize(exactZip, 5L * 1024L * 1024L)
            val oversizedZip = root.resolve("overrides/resourcepacks/oversized.zip")
            writeStoredZipOfSize(oversizedZip, 5L * 1024L * 1024L + 1L)
            root.resolve("shaderpacks/unmatched/assets/shader.properties")
                .also { it.parentFile.mkdirs() }
                .writeText("drop")

            val archive = ModpackProcessor(PackProcessingPaths(root.resolve("work")))
                .buildUploadArchive(root, "resourcepack-boundary")
            val entries = mutableSetOf<String>()
            forEachArchiveEntry(archive) { entry -> if (!entry.isDirectory) entries += entry.path }
            assertTrue("resourcepacks/exact/assets/first.bin" in entries)
            assertFalse(entries.any { it.startsWith("overrides/resourcepacks/oversized/") })
            assertTrue("resourcepacks/exact.zip" in entries)
            assertFalse("overrides/resourcepacks/oversized.zip" in entries)
            assertFalse(entries.any { it.startsWith("shaderpacks/") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resourcepack size policy covers reverse roots and whole unpacked packs`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-resourcepack-reverse-boundary").toFile()
        try {
            val overrideExactZip = root.resolve("overrides/resourcepacks/override-exact.zip")
            writeStoredZipOfSize(overrideExactZip, 5L * 1024L * 1024L)
            val directOverZip = root.resolve("resourcepacks/direct-over.zip")
            writeStoredZipOfSize(directOverZip, 5L * 1024L * 1024L + 1L)
            val directUnderZip = root.resolve("resourcepacks/direct-under.zip")
            writeStoredZipOfSize(directUnderZip, 5L * 1024L * 1024L - 1L)

            val overrideExactFile = root.resolve("overrides/resourcepacks/override-exact-dir/assets/payload.bin")
                .also { it.parentFile.mkdirs() }
            RandomAccessFile(overrideExactFile, "rw").use { it.setLength(5L * 1024L * 1024L) }
            val directOverFirst = root.resolve("resourcepacks/direct-over-dir/assets/first.bin")
                .also { it.parentFile.mkdirs() }
            val directOverSecond = root.resolve("resourcepacks/direct-over-dir/assets/second.bin")
            RandomAccessFile(directOverFirst, "rw").use { it.setLength(2L * 1024L * 1024L) }
            RandomAccessFile(directOverSecond, "rw").use { it.setLength(3L * 1024L * 1024L + 1L) }

            val archive = ModpackProcessor(PackProcessingPaths(root.resolve("work")))
                .buildUploadArchive(root, "resourcepack-reverse-boundary")
            val entries = mutableSetOf<String>()
            forEachArchiveEntry(archive) { if (!it.isDirectory) entries += it.path }
            assertTrue("overrides/resourcepacks/override-exact.zip" in entries)
            assertFalse("resourcepacks/direct-over.zip" in entries)
            assertTrue("resourcepacks/direct-under.zip" in entries)
            assertTrue("overrides/resourcepacks/override-exact-dir/assets/payload.bin" in entries)
            assertFalse(entries.any { it.startsWith("resourcepacks/direct-over-dir/") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `shadowed direct extra archive is omitted while override source remains`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-shadowed-client-extra").toFile()
        try {
            writeZip(root.resolve("resourcepacks/shared.zip"), mapOf("assets/direct.txt" to byteArrayOf(1)))
            writeZip(root.resolve("overrides/resourcepacks/shared.zip"), mapOf("assets/override.txt" to byteArrayOf(2)))
            root.resolve("resourcepacks/folder/assets/old.bin").also { it.parentFile.mkdirs() }
                .writeBytes(byteArrayOf(3))
            root.resolve("overrides/resourcepacks/folder/assets/new.bin").also { it.parentFile.mkdirs() }
                .writeBytes(byteArrayOf(4))
            val archive = ModpackProcessor(PackProcessingPaths(root.resolve("work")))
                .buildUploadArchive(root, "shadowed-client-extra")
            val entries = mutableMapOf<String, ByteArray>()
            forEachArchiveEntry(archive) { if (!it.isDirectory) entries[it.path] = it.bytes!! }
            assertFalse("resourcepacks/shared.zip" in entries)
            assertTrue("overrides/resourcepacks/shared.zip" in entries)
            assertNestedZipEntryEquals(entries.getValue("overrides/resourcepacks/shared.zip"), "assets/override.txt", byteArrayOf(2))
            assertFalse(entries.keys.any { it.startsWith("resourcepacks/folder/") })
            assertContentEquals(byteArrayOf(4), entries["overrides/resourcepacks/folder/assets/new.bin"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `local extra arbitration suppresses same path remote but keeps other and distinct placements`() {
        val root = Files.createTempDirectory("pack-proc-extra-arbitration").toFile()
        try {
            writeZip(root.resolve("resourcepacks/A.zip"), mapOf("assets/local.txt" to byteArrayOf(1)))
            root.resolve("resourcepacks/A").resolve("assets").mkdirs()
            root.resolve("resourcepacks/A/assets/dir.txt").writeBytes(byteArrayOf(2))
            writeZip(root.resolve("resourcepacks/B.zip"), mapOf("assets/b.txt" to byteArrayOf(3)))
            val content = { path: String, hash: String ->
                Content(
                    platform = ContentPlatform.Modrinth,
                    type = ContentType.ResPack,
                    projectId = path,
                    fileId = path,
                    slug = path,
                    hash = hash,
                    path = path,
                    side = ContentSide.Client,
                )
            }
            val declaredA = content("resourcepacks/A.zip", "a".repeat(40))
            val declaredOther = content("resourcepacks/other.zip", "b".repeat(40))
            val matchedB = content("resourcepacks/B.zip", declaredA.hash)
            val matchedZip = content("resourcepacks/A.zip", declaredA.hash)
            val matchedDir = content("resourcepacks/A", declaredA.hash)
            val merged = ModpackProcessor(PackProcessingPaths(root.resolve("work"))).mergeEmbeddedClientExtras(
                sourceDir = root,
                declared = listOf(declaredA, declaredOther),
                matched = listOf(matchedB),
            )
            assertEquals(
                setOf("resourcepacks/other.zip", "resourcepacks/B.zip"),
                merged.map { it.targetRelativePath }.toSet(),
            )
            val distinct = ModpackProcessor(PackProcessingPaths(root.resolve("distinct-work"))).mergeEmbeddedClientExtras(
                sourceDir = root.resolve("empty").also { it.mkdirs() },
                declared = emptyList(),
                matched = listOf(matchedZip, matchedDir),
            )
            assertEquals(setOf("resourcepacks/A.zip", "resourcepacks/A"), distinct.map { it.targetRelativePath }.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `curseforge fingerprints only promote candidates with matching sha1 and preserve placement`() {
        val root = Files.createTempDirectory("pack-proc-cf-extra-match").toFile()
        try {
            val local = root.resolve("local.zip").also { it.writeText("payload with whitespace") }
            val platformBytes = root.resolve("platform.zip").also { it.writeText("payloadwithwhitespace") }
            assertEquals(platformBytes.murmur2, local.murmur2)
            assertTrue(platformBytes.sha1 != local.sha1)
            val project = CurseForgeModInfo(
                id = 17,
                name = "Local Resource",
                slug = "local-resource",
                classId = 12,
            )
            val platformFile = CurseForgeFile(
                id = 23,
                modId = project.id,
                fileName = "remote.zip",
                downloadUrl = "https://example.invalid/remote.zip",
                fileFingerprint = local.murmur2,
                hashes = listOf(CurseForgeFileHash(value = platformBytes.sha1, algo = 1)),
            )

            assertTrue(mapCurseForgeExtraCandidate(local, "resourcepacks/local.zip", platformFile, project) == null)
            val matchingFile = platformFile.copy(hashes = listOf(CurseForgeFileHash(value = local.sha1, algo = 1)))
            val firstPlacement = mapCurseForgeExtraCandidate(local, "resourcepacks/local.zip", matchingFile, project)
            val secondPlacement = mapCurseForgeExtraCandidate(local, "resourcepacks/copy.zip", matchingFile, project)
            assertEquals("resourcepacks/local.zip", firstPlacement?.path)
            assertEquals("resourcepacks/copy.zip", secondPlacement?.path)
            assertEquals(local.murmur2.toString(), firstPlacement?.hash)
            assertEquals(ContentPlatform.CurseForge, firstPlacement?.platform)
            assertTrue(
                mapCurseForgeExtraCandidate(
                    local,
                    "resourcepacks/local.zip",
                    matchingFile.copy(modId = project.id + 1),
                    project,
                ) == null
            )
            assertTrue(
                mapCurseForgeExtraCandidate(
                    local,
                    "resourcepacks/local.zip",
                    matchingFile.copy(fileFingerprint = local.murmur2 + 1),
                    project,
                ) == null
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `captured client extra sha1 rejects mutation before staging`() {
        val root = Files.createTempDirectory("pack-proc-client-extra-stage").toFile()
        try {
            val source = root.resolve("resourcepacks/captured.zip").also {
                it.parentFile.mkdirs()
                it.writeText("captured bytes")
            }
            val expectedSha1 = source.sha1
            val content = Content(
                platform = ContentPlatform.CurseForge,
                type = ContentType.ResPack,
                projectId = "project",
                fileId = "file",
                slug = "captured",
                hash = "123",
                path = "resourcepacks/captured.zip",
                side = ContentSide.Client,
            )
            source.writeText("mutated bytes")
            val staged = root.resolve("staged/captured.zip").also { it.parentFile.mkdirs() }
            assertFailsWith<IllegalArgumentException> {
                stageEmbeddedClientExtra(content, source, "resourcepacks/captured.zip", expectedSha1, staged)
            }
            assertFalse(staged.exists())
            assertEquals("mutated bytes", source.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `matched source path is exact and never falls back across overrides`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-client-extra-exact-path").toFile()
        try {
            val direct = root.resolve("resourcepacks/a.zip")
            val override = root.resolve("overrides/resourcepacks/a.zip")
            writeZip(direct, mapOf("assets/direct.txt" to byteArrayOf(1)))
            writeZip(override, mapOf("assets/override.txt" to byteArrayOf(2)))
            val content = Content(
                platform = ContentPlatform.Modrinth,
                type = ContentType.ResPack,
                projectId = "project",
                fileId = "file",
                slug = "a",
                hash = direct.sha1,
                path = "resourcepacks/a.zip",
                side = ContentSide.Client,
            )
            val missingOverride = root.resolve("staged/a.zip").also { it.parentFile.mkdirs() }
            direct.copyTo(missingOverride, overwrite = true)
            val work = root.resolve("missing-work")
            val missingError = runCatching {
                ModpackProcessor(PackProcessingPaths(work)).buildUploadArchive(
                    rootDir = root,
                    baseName = "missing-override",
                    excludedClientExtras = listOf(
                        EmbeddedClientExtraSource(
                            content,
                            missingOverride,
                            "overrides/resourcepacks/a.zip",
                            direct.sha1,
                        )
                    ),
                )
            }.exceptionOrNull()
            assertTrue(missingError != null)
            assertTrue(work.listFiles().orEmpty().none { it.extension == "zst" })

            val archive = ModpackProcessor(PackProcessingPaths(root.resolve("valid-work"))).buildUploadArchive(
                rootDir = root,
                baseName = "exact-direct",
                excludedClientExtras = listOf(
                    EmbeddedClientExtraSource(content, direct, "resourcepacks/a.zip", direct.sha1)
                ),
            )
            val entries = mutableMapOf<String, ByteArray>()
            forEachArchiveEntry(archive) { if (!it.isDirectory) entries[it.path] = it.bytes!! }
            assertFalse("resourcepacks/a.zip" in entries)
            assertTrue("overrides/resourcepacks/a.zip" in entries)
            assertNestedZipEntryEquals(entries.getValue("overrides/resourcepacks/a.zip"), "assets/override.txt", byteArrayOf(2))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `matched source mutation after preprocessing starts removes partial archive`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-client-extra-late-mutation").toFile()
        try {
            val source = root.resolve("resourcepacks/mutable.zip")
            writeZip(source, mapOf("assets/texture.bin" to byteArrayOf(1, 2, 3)))
            val content = Content(
                platform = ContentPlatform.Modrinth,
                type = ContentType.ResPack,
                projectId = "project",
                fileId = "file",
                slug = "mutable",
                hash = source.sha1,
                path = "resourcepacks/mutable.zip",
                side = ContentSide.Client,
            )
            var mutated = false
            val work = root.resolve("work")
            val error = runCatching {
                ModpackProcessor(PackProcessingPaths(work)).buildUploadArchive(
                    rootDir = root,
                    baseName = "late-mutation",
                    excludedClientExtras = listOf(
                        EmbeddedClientExtraSource(content, source, "resourcepacks/mutable.zip", content.hash)
                    ),
                    onProgress = { progress ->
                        if (!mutated && progress is LoadProgress.Phase && progress.text.contains("预处理")) {
                            mutated = true
                            source.appendBytes(byteArrayOf(9))
                        }
                    },
                )
            }.exceptionOrNull()
            assertTrue(mutated)
            assertTrue(error != null)
            assertTrue(work.listFiles().orEmpty().none { it.extension == "zst" })
            assertTrue(source.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `manifest matched shader config survives without embedded source`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-manifest-shader-config").toFile()
        try {
            root.resolve("shaderpacks/manifest-shader.zip.txt")
                .also { it.parentFile.mkdirs() }
                .writeText("quality=high")
            root.resolve("shaderpacks/unmatched.zip.txt").writeText("quality=low")
            root.resolve("shaderpacks/Dual.ZIP.TXT").writeText("quality=low")
            root.resolve("overrides/shaderpacks/dual.zip.txt").also { it.parentFile.mkdirs() }
                .writeText("quality=high")
            val shader = Content(
                platform = ContentPlatform.Modrinth,
                type = ContentType.ShaderPack,
                projectId = "shader",
                fileId = "shader-file",
                slug = "manifest-shader",
                hash = "a".repeat(40),
                path = "shaderpacks/manifest-shader.zip",
                side = ContentSide.Client,
            )
            val archive = ModpackProcessor(PackProcessingPaths(root.resolve("work"))).buildUploadArchive(
                rootDir = root,
                baseName = "manifest-shader-config",
                clientExtras = listOf(shader, shader.copy(path = "shaderpacks/dual.zip")),
            )
            val paths = mutableSetOf<String>()
            forEachArchiveEntry(archive) { if (!it.isDirectory) paths += it.path }
            assertTrue("shaderpacks/manifest-shader.zip.txt" in paths)
            assertFalse("shaderpacks/unmatched.zip.txt" in paths)
            assertFalse(paths.any { it.equals("shaderpacks/dual.zip.txt", ignoreCase = true) })
            assertTrue("overrides/shaderpacks/dual.zip.txt" in paths)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `server extra media extensions are excluded case insensitively`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-server-media").toFile()
        try {
            val serverMods = root.resolve("mods").also { it.mkdirs() }
            val stagedClient = root.resolve("staged/client.jar")
                .also { it.parentFile.mkdirs() }
            writeModJar(stagedClient, "shared")
            writeModJar(serverMods.resolve("server-shared.jar"), "shared")

            val mediaExtensions = listOf(
                "ogg", "wav", "mp3", "flac", "aac", "m4a", "opus", "wma",
                "mp4", "mov", "avi", "mkv", "webm", "m4v", "mpeg", "mpg", "flv", "wmv",
                "png", "jpg", "jpeg", "webp", "gif", "bmp", "tif", "tiff", "avif", "ico", "svg",
                "psd"
            )
            mediaExtensions.forEachIndexed { index, extension ->
                val suffix = if (index == 0) extension.uppercase() else extension
                root.resolve("media/nested/file.$suffix").also { it.parentFile.mkdirs() }
                    .writeBytes(byteArrayOf(1, 2, 3))
            }
            root.resolve("server.json").writeText("{}")
            root.resolve("server.toml").writeText("enabled = true")
            writeZip(
                root.resolve("server-data.zip"),
                mapOf("world/region/server.mca" to byteArrayOf(11))
            )

            val loaded = ModpackProcessor(
                PackProcessingPaths(root.resolve("work"))
            ).loadServerPack(
                file = root,
                clientMods = listOf(
                    Mod(
                        platform = "mr",
                        projectId = "project",
                        slug = "shared",
                        fileId = "file",
                        hash = stagedClient.sha1,
                    )
                ),
                clientModSources = mapOf(
                    Mod(
                        platform = "mr",
                        projectId = "project",
                        slug = "shared",
                        fileId = "file",
                        hash = stagedClient.sha1,
                    ) to stagedClient
                ),
                onProgress = {}
            ).getOrThrow()

            assertTrue(loaded.containsExcludedMcaFiles)
            val extraPaths = loaded.serverExtraFiles.map { it.relativePath }.toSet()
            mediaExtensions.forEach { extension ->
                assertFalse(extraPaths.any { it.substringAfterLast('.').equals(extension, ignoreCase = true) })
            }
            assertTrue(extraPaths.contains("server.json"))
            assertTrue(extraPaths.contains("server.toml"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `embedded source survives local payload conversion`() {
        val source = EmbeddedModSource(
            mod = Mod(
                platform = "mr",
                projectId = "project",
                slug = "example",
                fileId = "file",
                hash = "0123456789012345678901234567890123456789",
            ),
            stagedFile = File("work/embedded-mods/example.jar"),
            originalFileName = "example-original.jar",
        )
        val loaded = LoadedLocalModpack(
            sourceType = LocalModpackSourceType.MODRINTH,
            sourceDir = File("source"),
            packName = "pack",
            packVersion = "1",
            mcVersion = McVersion.V211,
            modloader = ModLoader.neoforge,
            mods = listOf(source.mod),
            embeddedModSources = listOf(source),
        )

        val roundTrip = loaded.toUploadPayload().toLoadedLocalModpack()

        assertEquals(listOf(source), roundTrip.embeddedModSources)
    }

    @Test
    fun `server matching reads the caller provided staged client source`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-server-match").toFile()
        try {
            val serverMods = root.resolve("mods").also { it.mkdirs() }
            val stagedClient = root.resolve("staged/original-client-name.jar")
                .also { it.parentFile.mkdirs() }
            writeModJar(stagedClient, "shared")
            writeModJar(serverMods.resolve("server-shared.jar"), "shared")

            val clientMod = Mod(
                platform = "mr",
                projectId = "project",
                slug = "shared-mod",
                fileId = "file",
                hash = stagedClient.sha1,
            )
            val loaded = ModpackProcessor(
                PackProcessingPaths(root.resolve("work"))
            ).loadServerPack(
                file = root,
                clientMods = listOf(clientMod),
                clientModSources = mapOf(clientMod to stagedClient),
                onProgress = {},
            ).getOrThrow()

            assertEquals(Mod.Side.BOTH, loaded.mods.single().side)
            assertEquals("shared-mod", loaded.mods.single().slug)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `server modId match does not relabel a different jar as the client mod`() = runBlocking {
        val root = Files.createTempDirectory("pack-proc-server-mismatch").toFile()
        try {
            val serverMods = root.resolve("mods").also { it.mkdirs() }
            val stagedClient = root.resolve("staged/client.jar")
                .also { it.parentFile.mkdirs() }
            writeModJar(stagedClient, "shared", "1.0.0")
            writeModJar(serverMods.resolve("server-shared.jar"), "shared", "2.0.0")

            val clientMod = Mod(
                platform = "mr",
                projectId = "client-project",
                slug = "client-mod",
                fileId = "client-file",
                hash = stagedClient.sha1,
            )
            val loaded = ModpackProcessor(
                PackProcessingPaths(root.resolve("work"))
            ).loadServerPack(
                file = root,
                clientMods = listOf(clientMod),
                clientModSources = mapOf(clientMod to stagedClient),
                onProgress = {},
            ).getOrThrow()

            assertTrue(loaded.mods.none { it.projectId == clientMod.projectId })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `server duplicate mod keeps newest version and excludes discarded jar`() = runBlocking {
        val scenarios = listOf(
            listOf("cc-tweaked-1.113.1.jar" to "1.113.1", "cc-tweaked-1.120.2.jar" to "1.120.2"),
            listOf("cc-tweaked-1.120.2.jar" to "1.120.2", "cc-tweaked-1.113.1.jar" to "1.113.1")
        )
        scenarios.forEachIndexed { index, serverFiles ->
            val root = Files.createTempDirectory("pack-proc-server-duplicate-$index").toFile()
            try {
                val serverMods = root.resolve("mods").also { it.mkdirs() }
                val stagedClient = root.resolve("staged/client.jar")
                    .also { it.parentFile.mkdirs() }
                writeNeoForgeModJar(stagedClient, "computercraft", "1.0.0")
                serverFiles.forEach { (fileName, version) ->
                    writeNeoForgeModJar(serverMods.resolve(fileName), "computercraft", version)
                }

                val clientMod = Mod(
                    platform = "mr",
                    projectId = "project",
                    slug = "computercraft",
                    fileId = "file",
                    hash = "0123456789012345678901234567890123456789",
                )
                val loaded = ModpackProcessor(
                    PackProcessingPaths(root.resolve("work"))
                ).loadServerPack(
                    file = root,
                    clientMods = listOf(clientMod),
                    clientModSources = mapOf(clientMod to stagedClient),
                    onProgress = {},
                ).getOrThrow()

                assertTrue(loaded.embeddedModSources.isEmpty())
                assertTrue(loaded.serverExtraFiles.any { it.sourceFile.name == "cc-tweaked-1.120.2.jar" })
                assertFalse(loaded.serverExtraFiles.any { it.sourceFile.name == "cc-tweaked-1.113.1.jar" })
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun writeModJar(file: File, modId: String, version: String? = null) {
        val versionJson = version?.let { ",\"version\":\"$it\"" }.orEmpty()
        file.outputStream().use { output ->
            JarOutputStream(output).use { jar ->
                jar.putNextEntry(JarEntry("mcmod.info"))
                jar.write("[{\"modid\":\"$modId\"$versionJson}]".toByteArray())
                jar.closeEntry()
            }
        }
    }

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun writeStoredZipOfSize(file: File, targetSize: Long) {
        val entryName = "a"
        val payloadSize = (targetSize - 98L - 2L * entryName.length).toInt()
        require(payloadSize >= 0)
        val bytes = ByteArray(payloadSize)
        val crc = java.util.zip.CRC32().apply { update(bytes) }.value
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                val entry = ZipEntry(entryName).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    this.crc = crc
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        check(file.length() == targetSize) { "Expected stored zip size $targetSize, got ${file.length()}" }
    }

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun modrinthIndexBytes(): ByteArray = """
        {
          "formatVersion": 1,
          "game": "minecraft",
          "versionId": "test",
          "name": "test",
          "files": [],
          "dependencies": {"minecraft": "1.21.1", "neoforge": "21.1.0"}
        }
    """.trimIndent().toByteArray()

    private fun assertNestedZipEntryEquals(archiveBytes: ByteArray, path: String, expected: ByteArray) {
        var found = false
        ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == path) {
                    assertContentEquals(expected, zip.readBytes())
                    found = true
                }
            }
        }
        assertTrue(found, "Missing nested archive entry: $path")
    }

    private fun assertNestedZipEntryMissing(archiveBytes: ByteArray, path: String) {
        ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse(entry.name == path, "Unexpected nested archive entry: $path")
            }
        }
    }

    private fun writeNeoForgeModJar(file: File, modId: String, version: String) {
        file.outputStream().use { output ->
            JarOutputStream(output).use { jar ->
                jar.putNextEntry(JarEntry("META-INF/neoforge.mods.toml"))
                jar.write(
                    """
                    [[mods]]
                    modId = "$modId"
                    version = "$version"
                    """.trimIndent().toByteArray()
                )
                jar.closeEntry()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun noCallModCatalog(): ModCatalog = Proxy.newProxyInstance(
        ModCatalog::class.java.classLoader,
        arrayOf(ModCatalog::class.java),
    ) { _, method, _ ->
        when {
            method.name.takeWhile { it != '-' } == "getMetadata" ->
                emptyMap<CatalogSlugRef, CatalogModMetadata>()
            else -> error("catalog should not be called: ${method.name}")
        }
    } as ModCatalog
}
