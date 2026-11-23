package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.serdesJson
import calebxzau.rdi.mclaunch.model.MojangLibrary
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * J5b step 1 (J5 decision D6): the full launch command of a room game (`rdiRuntime = true`), written to
 * `slint-proto/protocol/fixtures/j5b-launch.json`. The account token is masked and the temporary root is
 * `${ROOT}`. The runtime jars (Kotlin, zstd-jni, mediaproc) are recorded as `${RUNTIME}/<file>` in class
 * path order; the Rust test passes the same list, so the fixture checks how the command is assembled,
 * not which jar versions the `game-runtime` folder holds (that is the manifest test's job).
 *
 * The extra JVM arguments are what `McPlayController.startSession` adds for a room. Run with
 * `RDI_WRITE_FIXTURES=1` to rewrite the fixture; otherwise the test checks it.
 */
class J5bLaunchCommandFixtureTest {
    private val root = Files.createTempDirectory("rdi-j5b-fixture").toFile()
    private val fixtures = File("../slint-proto/protocol/fixtures")
    private val fixture = fixtures.resolve("j5b-launch.json")

    private data class Case(
        val name: String,
        val mcVersion: McVersion,
        val loader: ModLoader,
        val loaderManifest: String,
        val versionId: String,
        val maxMemoryMb: Int?,
        val extraJvmArgs: List<String>,
    )

    private val playArg = "-Drdi.play=aHR0cHM6Ly9yZGkuZXhhbXBsZTo2NTMzMQoxMjcuMC4wLjE6NTU2NjcK5oiR55qE5oi/6Ze0CjIwMDAxCjY2YTAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDEwMDAwMDAwMArnjqnlrrbnlLI="

    private val cases = listOf(
        Case(
            "forge-1.20.1-room-forgeguard", McVersion.V201, ModLoader.forge, "1.20.1-forge-47.4.29.json",
            "66b000000000000000000001_1.0", null,
            listOf(
                "-Drdi.window.title=测试包GTO 1.0 · 我的房间",
                "-Drdi.window.icon=\${ROOT}/versions/66b000000000000000000001_1.0/icon.png",
                "\"-javaagent:\${ROOT}/lib/forgeguard.jar\"",
                playArg,
                "-Drdi.host.id=66c000000000000000000001",
            ),
        ),
        Case(
            "neoforge-1.21.1-room-user-args", McVersion.V211, ModLoader.neoforge, "neoforge-21.1.265.json",
            "66b000000000000000000002_2.3", 8192,
            listOf(
                "-Dforge.logging.marker.core=ACCEPT",
                "-Drdi.window.title=整合包 2.3 · 房间",
                playArg,
                "-Drdi.host.id=66c000000000000000000002",
                "-Drdi.dm.server=http://rdi.example:15566",
            ),
        ),
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

    private fun createLibraries(libraries: List<MojangLibrary>, librariesDir: File) {
        libraries.forEach { library ->
            val path = library.downloads.artifact?.path?.takeIf(String::isNotBlank)
                ?: runCatching { descriptorToLibraryPath(library.name) }.getOrNull()
                ?: return@forEach
            placeholderJar(librariesDir.resolve(path))
        }
    }

    /** The media runtime is not on this test's class path, so placeholders stand in for its four jars. */
    private val mediaJars = listOf("rdi-mediaproc.jar", "javacpp-1.5.12.jar", "ffmpeg-8.0-1.5.12.jar", "ffmpeg-8.0-1.5.12-windows-x86_64-gpl.jar")

    private fun command(case: Case, runtimeNames: MutableList<String>): List<String> {
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
        val loader = stripChecksums(raw.copy(
            id = case.mcVersion.loaderVersions.getValue(case.loader).dirName,
            inheritsFrom = raw.inheritsFrom?.takeIf(String::isNotBlank) ?: case.mcVersion.mcVer,
            jar = raw.jar?.takeIf(String::isNotBlank) ?: case.mcVersion.mcVer,
        ))
        createLibraries(vanilla.libraries + loader.libraries, directories.librariesDir)
        placeholderJar(directories.versionsDir.resolve(case.mcVersion.mcVer).resolve("${case.mcVersion.mcVer}.jar"))
        val media = mediaJars.map { root.resolve("media/$it").also(::placeholderJar) }
        val natives = root.resolve("tools/mediaproc-natives/0123abcd").apply { mkdirs() }
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
                mediaRuntimeResolver = { Result.success(MediaProcGameRuntime(media, natives)) },
            )
        )
        val versionDir = directories.versionsDir.resolve(case.versionId).apply { resolve("mods").mkdirs() }
        val account = MinecraftAccount(
            name = "玩家甲",
            uuid = "66a00000-0000-0000-0000-000100000000",
            accessToken = "jwt-secret",
        )
        val failure = launcher.launch(
            MinecraftLaunchRequest(
                mcVersion = case.mcVersion,
                loader = case.loader,
                versionId = case.versionId,
                versionDir = versionDir,
                account = account,
                windowSize = MinecraftWindowSize(1920, 1080),
                launchOverrides = MinecraftLaunchOverrides(maxMemoryMb = case.maxMemoryMb),
                extraJvmArgs = case.extraJvmArgs.map { it.replace("\${ROOT}", root.absolutePath) },
                rdiRuntime = true,
            )
        ) {}.exceptionOrNull()
        assertEquals("captured", failure?.message, "启动命令未生成: ${failure?.message}")
        val command = maskLaunchSecrets(captured!!)
        // The class path starts with the runtime jars (LinkedHashSet in buildLaunchClasspath).
        val classpath = command[command.indexOf("-cp") + 1].split(File.pathSeparator)
        val runtime = classpath.takeWhile { !it.startsWith(directories.librariesDir.absolutePath) }
        runtimeNames.clear()
        runtimeNames += runtime.map(::runtimeName)
        return command.map { argument ->
            runtime.fold(argument) { text, jar -> text.replace(jar, runtimeName(jar)) }
        }.map(::normalize)
    }

    private fun runtimeName(jar: String) = "\${RUNTIME}/" + File(jar).name

    private fun normalize(argument: String): String =
        argument.replace(root.absolutePath, "\${ROOT}").replace('\\', '/')

    private fun strings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    @Test
    fun `room launch commands match the shared fixture`() {
        val generated = buildJsonObject {
            put("comment", "Written by J5bLaunchCommandFixtureTest (client/mclaunch); checked by mc_launch tests in Rust.")
            put("pathSeparator", File.pathSeparator)
            put("cases", buildJsonArray {
                cases.forEach { case ->
                    val runtime = mutableListOf<String>()
                    val command = command(case, runtime)
                    add(buildJsonObject {
                        put("name", case.name)
                        put("mcVersion", case.mcVersion.mcVer)
                        put("loader", case.loader.name)
                        put("loaderManifest", case.loaderManifest)
                        put("versionId", case.versionId)
                        put("accountName", "玩家甲")
                        put("accountUuid", "66a00000-0000-0000-0000-000100000000")
                        put("maxMemoryMb", case.maxMemoryMb?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                        put("defaultMaxMemoryMb", 6144)
                        put("launcherVersion", "6.7.9")
                        put("window", strings(listOf("1920", "1080")))
                        put("extraJvmArgs", strings(case.extraJvmArgs))
                        put("runtimeClasspath", strings(runtime))
                        put("mediaNatives", "\${ROOT}/tools/mediaproc-natives/0123abcd")
                        put("command", strings(command))
                    })
                }
            })
        }
        val text = jsonPretty(generated) + "\n"
        if (System.getenv("RDI_WRITE_FIXTURES") == "1") {
            fixture.writeText(text)
        }
        assertTrue(fixture.isFile, "缺少j5b-launch.json，请用RDI_WRITE_FIXTURES=1运行本测试")
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
