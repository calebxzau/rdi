package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.model.LibraryOsArch
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.serdesJson
import calebxzau.rdi.mclaunch.model.MojangLibrary
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * J5 decision D6: the full launch command for local packs, written to `slint-proto/protocol/fixtures/j5a.json`
 * with the account token masked and the temporary root replaced by `${ROOT}`. The Rust launcher must build the
 * same list from the same inputs. Run with `RDI_WRITE_FIXTURES=1` to rewrite it; otherwise the test checks it.
 *
 * Library checksums are dropped from the inputs so empty placeholder jars pass the launch-time library check;
 * the command itself does not depend on them.
 */
class J5aLaunchCommandFixtureTest {
    private val root = Files.createTempDirectory("rdi-j5a-fixture").toFile()
    private val fixtures = File("../slint-proto/protocol/fixtures")
    private val fixture = fixtures.resolve("j5a.json")

    private data class Case(
        val name: String,
        val mcVersion: McVersion,
        val loader: ModLoader,
        val loaderManifest: String,
        val versionId: String,
        val nickname: String,
        val javaPath: String?,
        val maxMemoryMb: Int?,
        val extraJvmArgs: List<String>,
    )

    private val cases = listOf(
        Case("forge-1.20.1-defaults", McVersion.V201, ModLoader.forge, "1.20.1-forge-47.4.29.json",
            "local_0193d3a4-1111-7000-8000-000000000001", "Steve", null, null, emptyList()),
        Case("neoforge-1.21.1-overrides", McVersion.V211, ModLoader.neoforge, "neoforge-21.1.265.json",
            "local_0193d3a4-2222-7000-8000-000000000002", "玩家_1", "\${ROOT}/jdk17/bin/java.exe", 8192,
            listOf("-Dfoo=bar baz", "-XX:+UseZGC")),
    )

    private fun stripChecksums(manifest: MojangVersionManifest): MojangVersionManifest = manifest.copy(
        libraries = manifest.libraries.map { library ->
            library.copy(
                sha1 = null,
                checksums = emptyList(),
                downloads = library.downloads.copy(
                    artifact = library.downloads.artifact?.copy(sha1 = ""),
                    classifiers = library.downloads.classifiers?.mapValues { (_, value) -> value.copy(sha1 = "") },
                ),
            )
        },
    )

    private fun placeholderJar(file: File) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("placeholder.txt"))
            zip.write("rdi".toByteArray())
            zip.closeEntry()
        }
    }

    /** Every library with a main artifact gets a placeholder, whatever its rules say. */
    private fun createLibraries(libraries: List<MojangLibrary>, librariesDir: File) {
        libraries.forEach { library ->
            val path = library.downloads.artifact?.path?.takeIf(String::isNotBlank)
                ?: runCatching { descriptorToLibraryPath(library.name) }.getOrNull()
                ?: return@forEach
            placeholderJar(librariesDir.resolve(path))
        }
    }

    private fun normalize(argument: String): String =
        argument.replace(root.absolutePath, "\${ROOT}").replace('\\', '/')

    private fun command(case: Case): List<String> {
        val directories = MinecraftDirectories(
            mcDir = root,
            versionsDir = root.resolve("versions"),
            librariesDir = root.resolve("libraries"),
            assetsDir = root.resolve("assets"),
            toolsDir = root.resolve("tools"),
        )
        val vanilla = stripChecksums(serdesJson.decodeFromString<MojangVersionManifest>(
            javaClass.classLoader.getResource("mcmeta/${case.mcVersion.mcVer}.json")!!.readText()
        ))
        val raw = serdesJson.decodeFromString<MojangVersionManifest>(fixtures.resolve("j5a/${case.loaderManifest}").readText())
        // McInstall.normalizeLoaderManifest, as readLoaderLaunchState applies it before launch.
        val loader = stripChecksums(raw.copy(
            id = case.mcVersion.loaderVersions.getValue(case.loader).dirName,
            inheritsFrom = raw.inheritsFrom?.takeIf(String::isNotBlank) ?: case.mcVersion.mcVer,
            jar = raw.jar?.takeIf(String::isNotBlank) ?: case.mcVersion.mcVer,
        ))
        createLibraries(vanilla.libraries + loader.libraries, directories.librariesDir)
        placeholderJar(directories.versionsDir.resolve(case.mcVersion.mcVer).resolve("${case.mcVersion.mcVer}.jar"))
        root.resolve("jdk17").mkdirs()
        root.resolve("jdk17/release").writeText("JAVA_VERSION=\"17.0.2\"\nOS_ARCH=\"amd64\"\n")
        var captured: List<String>? = null
        val launcher = MinecraftLauncher(
            MinecraftLaunchEnvironment(
                directories = directories,
                java = MinecraftJava25Config(currentJavaPath = "JAVA", currentJavaMajor = 25, maxMemoryMb = 6144),
                launcherBrand = "rdi",
                launcherVersion = "6.7.9",
                debug = false,
                manifestProvider = { _, _, _, _ -> Result.success(MinecraftManifestPair(vanilla, loader)) },
                artifactDownloader = { _, _, _, _ -> Result.failure(IllegalStateException("no download in tests")) },
                processStarter = { command, _ ->
                    captured = command
                    Result.failure(IllegalStateException("captured"))
                },
                mediaRuntimeResolver = { Result.failure(IllegalStateException("not for local packs")) },
            )
        )
        val versionDir = directories.versionsDir.resolve(case.versionId).apply { mkdirs() }
        val name = case.nickname
        val account = MinecraftAccount(
            name = name,
            // OfflineGameAccount.uuid in client-core.
            uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)).toString(),
            accessToken = "0",
            userType = "legacy",
        )
        val failure = launcher.launch(
            MinecraftLaunchRequest(
                mcVersion = case.mcVersion,
                loader = case.loader,
                versionId = case.versionId,
                versionDir = versionDir,
                account = account,
                windowSize = MinecraftWindowSize(1280, 720),
                launchOverrides = MinecraftLaunchOverrides(
                    javaPath = case.javaPath?.replace("\${ROOT}", root.absolutePath),
                    maxMemoryMb = case.maxMemoryMb,
                ),
                extraJvmArgs = case.extraJvmArgs,
                rdiRuntime = false,
            )
        ) {}.exceptionOrNull()
        assertEquals("captured", failure?.message, "启动命令未生成: ${failure?.message}")
        return maskLaunchSecrets(captured!!).map(::normalize)
    }

    private fun strings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    @Test
    fun `local pack launch commands match the shared fixture`() {
        val generated = buildJsonObject {
            put("comment", "Written by J5aLaunchCommandFixtureTest (client/mclaunch); checked by mc_launch tests in Rust.")
            put("host", buildJsonObject {
                put("osName", LibraryOsArch.detectHostOs().ruleOsName)
                put("osArch", System.getProperty("os.arch").lowercase())
                put("osVersion", System.getProperty("os.version"))
                put("pathSeparator", File.pathSeparator)
            })
            put("cases", buildJsonArray {
                cases.forEach { case ->
                    add(buildJsonObject {
                        put("name", case.name)
                        put("mcVersion", case.mcVersion.mcVer)
                        put("loader", case.loader.name)
                        put("loaderManifest", case.loaderManifest)
                        put("versionId", case.versionId)
                        put("nickname", case.nickname)
                        put("offlineUuid", UUID.nameUUIDFromBytes("OfflinePlayer:${case.nickname}".toByteArray(Charsets.UTF_8)).toString())
                        put("javaPath", case.javaPath?.let(::JsonPrimitive) ?: JsonNull)
                        put("maxMemoryMb", case.maxMemoryMb?.let(::JsonPrimitive) ?: JsonNull)
                        put("defaultMaxMemoryMb", 6144)
                        put("launcherVersion", "6.7.9")
                        put("window", strings(listOf("1280", "720")))
                        put("extraJvmArgs", strings(case.extraJvmArgs))
                        put("command", strings(command(case)))
                    })
                }
            })
        }
        val text = jsonPretty(generated) + "\n"
        if (System.getenv("RDI_WRITE_FIXTURES") == "1") {
            fixture.writeText(text)
        }
        assertTrue(fixture.isFile, "缺少j5a.json，请用RDI_WRITE_FIXTURES=1运行本测试")
        assertEquals(fixture.readText().replace("\r\n", "\n"), text)
        root.deleteRecursively()
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
