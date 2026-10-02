package calebxzhou.rdi.client.service

import calebxzau.rdi.client.modcatalog.CatalogContentType
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import kotlinx.coroutines.runBlocking
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModpackArchiveReaderTest {
    @Test
    fun `reads manifest metadata without resolving archive contents`() = runBlocking {
        val archive = zip(
            "modrinth.index.json" to
                """
                {
                  "formatVersion": 1,
                  "game": "minecraft",
                  "name": "快速显示包",
                  "summary": "先显示这段简介",
                  "dependencies": {"minecraft": "1.21.1", "neoforge": "21.1.100"},
                  "files": [
                    {"path":"mods/example.jar","hashes":{"sha1":"1111111111111111111111111111111111111111"},"downloads":["https://cdn.modrinth.com/data/a/versions/b/example.jar"],"fileSize":10}
                  ]
                }
                """.trimIndent(),
        )

        val metadata = ModpackArchiveReader(EmptyArchiveCatalog).readMetadata(archive).getOrThrow()

        assertEquals(ModpackArchiveFormat.MODRINTH, metadata.format)
        assertEquals("快速显示包", metadata.name)
        assertEquals("先显示这段简介", metadata.summary)
        assertEquals(McVersion.V211, metadata.mcVersion)
        assertEquals(ModLoader.neoforge, metadata.modLoader)
    }

    @Test
    fun `recognizes Fabric metadata and preserves declared loader version`() = runBlocking {
        val archive = zip(
            "modrinth.index.json" to
                """
                {"formatVersion":1,"game":"minecraft","name":"Fabric包","dependencies":{"minecraft":"1.20.1","fabric-loader":"0.16.10","fabric-api":"0.92.2+1.20.1"},"files":[]}
                """.trimIndent(),
        )

        val metadata = ModpackArchiveReader(EmptyArchiveCatalog).readMetadata(archive).getOrThrow()

        assertEquals(McVersion.V201, metadata.mcVersion)
        assertEquals(ModLoader.Fabric, metadata.modLoader)
        assertEquals("0.16.10", metadata.declaredLoaderVersion)
    }

    @Test
    fun `CurseForge metadata selects its primary loader declaration`() = runBlocking {
        val archive = zip(
            "manifest.json" to
                """
                {"name":"Fabric包","minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.2.0"},{"id":"fabric-0.16.10","primary":true}]}}
                """.trimIndent(),
        )

        val metadata = ModpackArchiveReader(EmptyArchiveCatalog).readMetadata(archive).getOrThrow()

        assertEquals(ModLoader.Fabric, metadata.modLoader)
        assertEquals("0.16.10", metadata.declaredLoaderVersion)
    }

    @Test
    fun `full archive inspection rejects recognized Fabric before catalog resolution`() = runBlocking {
        val archive = zip(
            "modrinth.index.json" to
                """
                {"formatVersion":1,"game":"minecraft","name":"Fabric包","dependencies":{"minecraft":"1.20.1","fabric-loader":"0.16.10"},"files":[{"path":"mods/example.jar","hashes":{"sha1":"1111111111111111111111111111111111111111"},"downloads":["https://cdn.modrinth.com/data/a/versions/b/example.jar"],"fileSize":10}]}
                """.trimIndent(),
        )

        val result = ModpackArchiveReader(EmptyArchiveCatalog).inspect(archive)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Minecraft1.20.1／Fabric"))
    }

    @Test
    fun `reads GBK archive entry names`() = runBlocking {
        val archive = Files.createTempFile("rdi-modpack-gbk", ".zip")
        ZipOutputStream(Files.newOutputStream(archive), Charset.forName("GBK")).use { output ->
            output.putNextEntry(ZipEntry("modrinth.index.json"))
            output.write(
                """
                {"formatVersion":1,"game":"minecraft","name":"GBK包","dependencies":{"minecraft":"1.21.1","neoforge":"21.1.100"},"files":[]}
                """.toByteArray()
            )
            output.closeEntry()
            output.putNextEntry(ZipEntry("overrides/配置/中文.txt"))
            output.write("中文内容".toByteArray())
            output.closeEntry()
        }

        assertFailsWith<java.util.zip.ZipException> {
            ZipFile(archive.toFile()).use { }
        }

        val metadata = ModpackArchiveReader(EmptyArchiveCatalog).readMetadata(archive).getOrThrow()
        val preview = ModpackArchiveReader(EmptyArchiveCatalog).inspect(archive).getOrThrow()

        assertEquals("GBK包", metadata.name)
        assertEquals(listOf("配置/中文.txt"), preview.overrides.map(ModpackArchiveOverride::targetPath))
    }

    @Test
    fun `reports archive reading stages with truthful counts`() = runBlocking {
        val archive = zip(
            "modrinth.index.json" to
                """
                {"formatVersion":1,"game":"minecraft","name":"进度包","dependencies":{"minecraft":"1.21.1","neoforge":"21.1.100"},"files":[{"path":"mods/example.jar","hashes":{"sha1":"1111111111111111111111111111111111111111"},"downloads":["https://cdn.modrinth.com/data/a/versions/b/example.jar"],"fileSize":10}]}
                """.trimIndent(),
            "overrides/config/options.txt" to "lang:en_us",
        )
        val progress = mutableListOf<ModpackArchiveReadProgress>()

        ModpackArchiveReader(EmptyArchiveCatalog)
            .inspectWithProgress(archive, progress::add)
            .getOrThrow()

        assertEquals(ModpackArchiveReadStage.SCANNING_ENTRIES, progress.first().stage)
        assertEquals(0, progress.first().completed)
        assertEquals(2, progress.first().total)
        assertTrue(progress.any {
            it.stage == ModpackArchiveReadStage.PARSING_CONTENT && it.completed == 0 && it.total == 1
        })
        assertTrue(progress.any {
            it.stage == ModpackArchiveReadStage.PARSING_CONTENT && it.completed == 1 && it.total == 1
        })
        assertTrue(progress.any {
            it.stage == ModpackArchiveReadStage.READING_OVERRIDES && it.completed == 1 && it.total == 1
        })
    }

    @Test
    fun `reads CurseForge archive with an offline catalog`() = runBlocking {
        val archive = zip(
            "manifest.json" to
                """
                {
                  "name":"离线Forge包",
                  "minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.1.0","primary":true}]},
                  "overrides":"overrides",
                  "files":[
                    {"projectID":12,"fileID":34,"required":true},
                    {"projectID":13,"fileID":35,"required":false}
                  ]
                }
                """.trimIndent(),
            "overrides/config/offline.txt" to "offline=true",
        )
        val catalog = object : ModpackArchiveCatalog {
            override suspend fun resolveCurseForgeFiles(
                refs: List<CurseForgeArchiveRef>,
            ): Result<Map<Int, ArchiveCatalogFile>> = Result.success(
                refs.associate { ref ->
                    ref.fileId to ArchiveCatalogFile(
                        projectId = ref.projectId,
                        displayName = "离线内容${ref.fileId}",
                        fileName = "content-${ref.fileId}.jar",
                        size = 40L + ref.fileId,
                        sha1 = "4444444444444444444444444444444444444444",
                        url = "https://mediafilez.forgecdn.net/files/1/${ref.fileId}/content.jar",
                        contentType = if (ref.projectId == 12) {
                            CatalogContentType.MOD
                        } else {
                            CatalogContentType.RESOURCE_PACK
                        },
                    )
                },
            )
        }

        val preview = ModpackArchiveReader(catalog).inspect(archive).getOrThrow()

        assertEquals(ModpackArchiveFormat.CURSEFORGE, preview.format)
        assertEquals("离线Forge包", preview.name)
        assertEquals(McVersion.V201, preview.mcVersion)
        assertEquals(ModLoader.forge, preview.modLoader)
        assertEquals(
            listOf("mods/content-34.jar", "resourcepacks/content-35.jar"),
            preview.files.map(ModpackArchiveFile::targetPath),
        )
        assertEquals(listOf("config/offline.txt"), preview.overrides.map(ModpackArchiveOverride::targetPath))
    }

    @Test
    fun `classifies modrinth content from each manifest path`() = runBlocking {
        val archive = zip(
            "modrinth.index.json" to
                """
                {
                  "formatVersion": 1,
                  "game": "minecraft",
                  "name": "测试包",
                  "summary": "备注",
                  "dependencies": {"minecraft": "1.21.1", "neoforge": "21.1.100"},
                  "files": [
                    {"path":"mods/required.jar","hashes":{"sha1":"1111111111111111111111111111111111111111"},"downloads":["https://cdn.modrinth.com/data/a/versions/b/required.jar"],"fileSize":10,"env":{"client":"required"}},
                    {"path":"mods/optional.jar","hashes":{"sha1":"2222222222222222222222222222222222222222"},"downloads":["https://cdn.modrinth.com/data/a/versions/c/optional.jar"],"fileSize":20,"env":{"client":"optional"}},
                    {"path":"resourcepacks/resources.zip","hashes":{"sha1":"3333333333333333333333333333333333333333"},"downloads":["https://cdn.modrinth.com/data/a/versions/d/resources.zip"],"fileSize":30},
                    {"path":"shaderpacks/shaders.zip","hashes":{"sha1":"4444444444444444444444444444444444444444"},"downloads":["https://cdn.modrinth.com/data/a/versions/e/shaders.zip"],"fileSize":40},
                    {"path":"saves/world/datapacks/data.zip","hashes":{"sha1":"5555555555555555555555555555555555555555"},"downloads":["https://cdn.modrinth.com/data/a/versions/f/data.zip"],"fileSize":50},
                    {"path":"config/other.txt","hashes":{"sha1":"8888888888888888888888888888888888888888"},"downloads":["https://cdn.modrinth.com/data/a/versions/h/other.txt"],"fileSize":5},
                    {"path":"mods/server.jar","hashes":{"sha1":"6666666666666666666666666666666666666666"},"downloads":["https://cdn.modrinth.com/data/a/versions/g/server.jar"],"fileSize":60,"env":{"client":"unsupported"}}
                  ]
                }
                """.trimIndent(),
            "overrides/config/a.toml" to "a=1",
            "client-overrides/options.txt" to "lang:en_us",
        )

        val preview = ModpackArchiveReader(EmptyArchiveCatalog).inspect(archive).getOrThrow()

        assertEquals(ModpackArchiveFormat.MODRINTH, preview.format)
        assertEquals(McVersion.V211, preview.mcVersion)
        assertEquals(ModLoader.neoforge, preview.modLoader)
        assertEquals(
            listOf(
                "mods/required.jar",
                "resourcepacks/resources.zip",
                "shaderpacks/shaders.zip",
                "saves/world/datapacks/data.zip",
                "config/other.txt",
            ),
            preview.requiredFiles.map { it.targetPath },
        )
        assertEquals(listOf("mods/optional.jar"), preview.optionalFiles.map { it.targetPath })
        assertEquals(
            listOf(
                ModpackArchiveContentType.MOD,
                ModpackArchiveContentType.MOD,
                ModpackArchiveContentType.RESOURCE_PACK,
                ModpackArchiveContentType.SHADER_PACK,
                ModpackArchiveContentType.DATA_PACK,
                ModpackArchiveContentType.OTHER,
            ),
            preview.files.map(ModpackArchiveFile::contentType),
        )
        assertEquals(
            "saves/world/datapacks/data.zip",
            preview.files.single { it.contentType == ModpackArchiveContentType.DATA_PACK }.targetPath,
        )
        assertEquals(
            listOf("config/other.txt"),
            preview.files.filter { it.contentType == ModpackArchiveContentType.OTHER }
                .map(ModpackArchiveFile::targetPath),
        )
        assertEquals(listOf("config/a.toml", "options.txt"), preview.overrides.map { it.targetPath })
    }

    @Test
    fun `routes curseforge content using project class`() = runBlocking {
        val archive = zip(
            "manifest.json" to
                """
                {
                  "name":"Forge包",
                  "minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.1.0","primary":true}]},
                  "overrides":"overrides",
                  "files":[
                    {"projectID":12,"fileID":34,"required":false},
                    {"projectID":13,"fileID":35,"required":true},
                    {"projectID":14,"fileID":36,"required":true}
                  ]
                }
                """.trimIndent(),
        )
        val catalog = object : ModpackArchiveCatalog {
            override suspend fun resolveCurseForgeFiles(
                refs: List<CurseForgeArchiveRef>,
            ): Result<Map<Int, ArchiveCatalogFile>> = Result.success(
                mapOf(
                    34 to ArchiveCatalogFile(
                        projectId = 12,
                        displayName = "示例Mod",
                        fileName = "example.jar",
                        size = 40,
                        sha1 = "4444444444444444444444444444444444444444",
                        url = "https://mediafilez.forgecdn.net/files/1/2/example.jar",
                        contentType = CatalogContentType.MOD,
                    ),
                    35 to ArchiveCatalogFile(
                        projectId = 13,
                        displayName = "示例资源包",
                        fileName = "resources.zip",
                        size = 50,
                        sha1 = "5555555555555555555555555555555555555555",
                        url = "https://mediafilez.forgecdn.net/files/1/3/resources.zip",
                        contentType = CatalogContentType.RESOURCE_PACK,
                    ),
                    36 to ArchiveCatalogFile(
                        projectId = 14,
                        displayName = "示例光影包",
                        fileName = "shaders.zip",
                        size = 60,
                        sha1 = "6666666666666666666666666666666666666666",
                        url = "https://mediafilez.forgecdn.net/files/1/4/shaders.zip",
                        contentType = CatalogContentType.SHADER_PACK,
                    ),
                )
            )
        }

        val preview = ModpackArchiveReader(catalog).inspect(archive).getOrThrow()

        assertEquals(McVersion.V201, preview.mcVersion)
        assertEquals(ModLoader.forge, preview.modLoader)
        assertEquals("示例Mod", preview.optionalFiles.single().displayName)
        assertEquals(
            listOf("mods/example.jar", "resourcepacks/resources.zip", "shaderpacks/shaders.zip"),
            preview.files.map(ModpackArchiveFile::targetPath),
        )
    }

    @Test
    fun `rejects unknown curseforge project class`(): Unit = runBlocking {
        val archive = zip(
            "manifest.json" to
                """
                {"name":"未知内容包","minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.1.0"}]},"files":[{"projectID":12,"fileID":34}]}
                """.trimIndent(),
        )
        val catalog = object : ModpackArchiveCatalog {
            override suspend fun resolveCurseForgeFiles(refs: List<CurseForgeArchiveRef>) = Result.success(
                mapOf(
                    34 to ArchiveCatalogFile(
                        12, "未知内容", "unknown.zip", 1,
                        "7777777777777777777777777777777777777777",
                        "https://mediafilez.forgecdn.net/files/1/5/unknown.zip",
                        contentType = CatalogContentType.OTHER,
                    ),
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ModpackArchiveReader(catalog).inspect(archive).getOrThrow()
        }
    }

    @Test
    fun `accepts modrinth entry whose mirror URL contains raw spaces`() = runBlocking {
        val archive = zip(
            "modrinth.index.json" to
                """
                {"formatVersion":1,"game":"minecraft","name":"空格地址包","dependencies":{"minecraft":"1.21.1","neoforge":"21.1.100"},"files":[
                  {"path":"resourcepacks/DetailedAnimationsReworked - V1.15.zip","hashes":{"sha1":"1d0ac0d5d7514a3df02de06a598ae95697339a21"},"downloads":[
                    "https://cdn.modrinth.com/data/9pR04wDX/versions/dFBidLNv/DetailedAnimationsReworked%20-%20V1.15.zip",
                    "https://edge.forgecdn.net/files/5822/128/DetailedAnimationsReworked - V1.15.zip"
                  ],"fileSize":116175}
                ]}
                """.trimIndent(),
        )

        val preview = ModpackArchiveReader(EmptyArchiveCatalog).inspect(archive).getOrThrow()

        assertEquals(
            listOf(
                "https://cdn.modrinth.com/data/9pR04wDX/versions/dFBidLNv/DetailedAnimationsReworked%20-%20V1.15.zip",
                "https://edge.forgecdn.net/files/5822/128/DetailedAnimationsReworked%20-%20V1.15.zip",
            ),
            preview.files.single().urls,
        )
    }

    @Test
    fun `rejects modrinth entry without any valid download URL`(): Unit = runBlocking {
        val archive = zip(
            "modrinth.index.json" to
                """
                {"formatVersion":1,"game":"minecraft","name":"坏地址包","dependencies":{"minecraft":"1.21.1","neoforge":"21.1.100"},"files":[
                  {"path":"mods/example.jar","hashes":{"sha1":"1111111111111111111111111111111111111111"},"downloads":["https://evil.example.invalid/example.jar"],"fileSize":10}
                ]}
                """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> {
            ModpackArchiveReader(EmptyArchiveCatalog).inspect(archive).getOrThrow()
        }
    }

    @Test
    fun `rejects archive path traversal`(): Unit = runBlocking {
        val archive = zip(
            "modrinth.index.json" to
                """
                {"formatVersion":1,"game":"minecraft","name":"坏包","dependencies":{"minecraft":"1.21.1","neoforge":"1"},"files":[]}
                """.trimIndent(),
            "overrides/../escape.txt" to "bad",
        )

        assertFailsWith<IllegalArgumentException> {
            ModpackArchiveReader(EmptyArchiveCatalog).inspect(archive).getOrThrow()
        }
    }

    @Test
    fun `rejects disallowed curseforge download URL`(): Unit = runBlocking {
        val archive = zip(
            "manifest.json" to
                """
                {"name":"非法地址包","minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.1.0"}]},"files":[{"projectID":12,"fileID":34}]}
                """.trimIndent(),
        )
        val catalog = object : ModpackArchiveCatalog {
            override suspend fun resolveCurseForgeFiles(refs: List<CurseForgeArchiveRef>) = Result.success(
                mapOf(
                    34 to ArchiveCatalogFile(
                        projectId = 12,
                        displayName = "非法地址内容",
                        fileName = "content.jar",
                        size = 1,
                        sha1 = "8888888888888888888888888888888888888888",
                        url = "https://evil.example.invalid/content.jar",
                        contentType = CatalogContentType.MOD,
                    ),
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ModpackArchiveReader(catalog).inspect(archive).getOrThrow()
        }
    }

    private fun zip(vararg entries: Pair<String, String>): Path {
        val file = Files.createTempFile("rdi-modpack-archive", ".zip")
        ZipOutputStream(Files.newOutputStream(file)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }
}

private object EmptyArchiveCatalog : ModpackArchiveCatalog {
    override suspend fun resolveCurseForgeFiles(
        refs: List<CurseForgeArchiveRef>,
    ): Result<Map<Int, ArchiveCatalogFile>> = Result.success(emptyMap())
}
