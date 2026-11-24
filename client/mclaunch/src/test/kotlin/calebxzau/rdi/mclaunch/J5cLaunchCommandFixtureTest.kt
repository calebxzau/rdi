package calebxzau.rdi.mclaunch

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * J5c step 1 (J5 decision D6): launch commands for GTNH (1.7.10) and Fabric 1.20.1, as room games and as
 * local packs, written to `slint-proto/protocol/fixtures/j5c-launch.json`; plus Fabric profile validation
 * cases. Same conventions as [J5bLaunchCommandFixtureTest]: token masked, `${ROOT}` for the temporary root,
 * `${RUNTIME}/<file>` for runtime jars. The GTNH patches in `fixtures/j5c/gtnh/patches` are a real pack's.
 *
 * Run with `RDI_WRITE_FIXTURES=1` to rewrite the fixture; otherwise the test checks it.
 */
class J5cLaunchCommandFixtureTest {
    private val root = Files.createTempDirectory("rdi-j5c-fixture").toFile()
    private val fixtures = File("../slint-proto/protocol/fixtures")
    private val fixture = fixtures.resolve("j5c-launch.json")
    private val fabricProfile = fixtures.resolve("j5c/fabric-loader-0.19.5-1.20.1.json")
    private val gtnhPatches = fixtures.resolve("j5c/gtnh/patches")

    private data class Case(
        val name: String,
        val mcVersion: McVersion,
        val loader: ModLoader,
        val versionId: String,
        val rdiRuntime: Boolean,
        val extraJvmArgs: List<String>,
        val user: Triple<String, String, String>,
    )

    private val playArg = "-Drdi.play=aHR0cHM6Ly9yZGkuZXhhbXBsZTo2NTMzMQoxMjcuMC4wLjE6NTU2NjcK5oiR55qE5oi/6Ze0CjIwMDAxCjY2YTAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDEwMDAwMDAwMArnjqnlrrbnlLI="
    private val rdiUser = Triple("玩家甲", "66a00000-0000-0000-0000-000100000000", "msa")
    private val offlineUser = Triple("Steve", "5627dd98-e6be-3c21-b8a8-e92344183641", "legacy")

    private val cases = listOf(
        Case(
            "gtnh-1.7.10-room", McVersion.V071, ModLoader.forge, "69eaf0a3cae162f1a7e8448a_1.0.0", true,
            listOf(
                "-Drdi.window.title=GTNH 1.0.0 · 我的房间",
                "-Drdi.window.icon=\${ROOT}/versions/69eaf0a3cae162f1a7e8448a_1.0.0/icon.png",
                playArg,
                "-Drdi.host.id=66c000000000000000000003",
            ),
            rdiUser,
        ),
        Case("gtnh-1.7.10-local", McVersion.V071, ModLoader.forge, "GTNH-2.8.4", false, emptyList(), offlineUser),
        Case(
            "fabric-1.20.1-room", McVersion.V201, ModLoader.Fabric, "66b000000000000000000004_1.2", true,
            listOf(
                "-Drdi.window.title=Fabric包 1.2 · 房间",
                playArg,
                "-Drdi.host.id=66c000000000000000000004",
            ),
            rdiUser,
        ),
        Case("fabric-1.20.1-local", McVersion.V201, ModLoader.Fabric, "Fabric Pack", false, emptyList(), offlineUser),
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

    /** Main artifacts and every native classifier, so the launch's library check passes. */
    private fun createLibraries(libraries: List<MojangLibrary>, librariesDir: File) {
        libraries.forEach { library ->
            val path = library.downloads.artifact?.path?.takeIf(String::isNotBlank)
                ?: runCatching { descriptorToLibraryPath(library.name) }.getOrNull()
            if (path != null) placeholderJar(librariesDir.resolve(path))
            library.downloads.classifiers?.values?.forEach { native ->
                native.path?.takeIf(String::isNotBlank)?.let { placeholderJar(librariesDir.resolve(it)) }
            }
        }
    }

    /** `GtnhLaunchSupport.gtnhLibraryPath` without the OS filter: extra files are harmless. */
    private fun gtnhLibraryPaths(patch: File): List<String> =
        serdesJson.parseToJsonElement(patch.readText()).jsonObject["libraries"]?.jsonArray.orEmpty().map { element ->
            val library = element.jsonObject
            val name = (library["name"] as JsonPrimitive).content
            val artifact = library["downloads"]?.jsonObject?.get("artifact")?.jsonObject
            val path = (artifact?.get("path") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
            val url = (artifact?.get("url") as? JsonPrimitive)?.content
            path ?: listOf("https://libraries.minecraft.net/", "https://maven.minecraftforge.net/", "https://files.prismlauncher.org/maven/")
                .firstNotNullOfOrNull { base -> url?.takeIf { it.startsWith(base) }?.removePrefix(base) }
            ?: descriptorToLibraryPath(name)
        }

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
        val versionDir = directories.versionsDir.resolve(case.versionId).apply { resolve("mods").mkdirs() }
        val loader = if (case.loader == ModLoader.Fabric) {
            stripChecksums(serdesJson.decodeFromString<MojangVersionManifest>(fabricProfile.readText()))
        } else {
            // GTNH replaces the Forge loader manifest with one built from its patches; any manifest will do.
            vanilla
        }
        createLibraries(vanilla.libraries + loader.libraries, directories.librariesDir)
        if (case.mcVersion == McVersion.V071) {
            val gtnh = versionDir.resolve("gtnh")
            gtnhPatches.listFiles()!!.forEach { it.copyTo(gtnh.resolve("patches/${it.name}"), overwrite = true) }
            placeholderJar(gtnh.resolve("libraries/lwjgl3ify-2.1.16-forgePatches.jar"))
            placeholderJar(gtnh.resolve("libraries/lwjgl3ify-2.1.16.jar"))
            listOf("org.lwjgl3.json", "net.minecraftforge.json").forEach { name ->
                gtnhLibraryPaths(gtnh.resolve("patches/$name")).forEach { placeholderJar(directories.librariesDir.resolve(it)) }
            }
        }
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
        val (name, uuid, userType) = case.user
        val failure = launcher.launch(
            MinecraftLaunchRequest(
                mcVersion = case.mcVersion,
                loader = case.loader,
                versionId = case.versionId,
                versionDir = versionDir,
                account = MinecraftAccount(name = name, uuid = uuid, accessToken = "jwt-secret", userType = userType),
                windowSize = MinecraftWindowSize(1920, 1080),
                launchOverrides = MinecraftLaunchOverrides(),
                extraJvmArgs = case.extraJvmArgs.map { it.replace("\${ROOT}", root.absolutePath) },
                rdiRuntime = case.rdiRuntime,
            )
        ) {}.exceptionOrNull()
        assertEquals("captured", failure?.message, "启动命令未生成: ${failure?.message}")
        if (case.mcVersion == McVersion.V071) {
            assertTrue(versionDir.resolve("mods/lwjgl3ify-2.1.16.jar").isFile, "lwjgl3ify运行Mod未链接到mods")
        }
        val command = maskLaunchSecrets(captured!!)
        // Runtime jars sit outside libraries/, versions/ and the GTNH folder; they come first.
        val classpath = command[command.indexOf("-cp") + 1].split(File.pathSeparator)
        val runtime = classpath.filter { it.startsWith(root.resolve("media").absolutePath) || !it.startsWith(root.absolutePath) }
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

    /** Fabric profile variants and whether `FabricClientProfile.validate` accepts them. */
    private fun fabricCases(): JsonArray {
        val good = serdesJson.parseToJsonElement(fabricProfile.readText()).jsonObject
        val libraries = good["libraries"]!!.jsonArray
        fun withLibraries(change: (List<JsonObject>) -> List<JsonObject>) =
            JsonObject(good + ("libraries" to JsonArray(change(libraries.map { it.jsonObject }))))
        fun JsonObject.withArtifact(vararg entries: Pair<String, JsonElement>): JsonObject {
            val downloads = this["downloads"]!!.jsonObject
            val artifact = JsonObject(downloads["artifact"]!!.jsonObject + entries)
            return JsonObject(this + ("downloads" to JsonObject(downloads + ("artifact" to artifact))))
        }
        fun firstLibrary(change: (JsonObject) -> JsonObject) = withLibraries { list -> listOf(change(list[0])) + list.drop(1) }
        val loaderLibrary = libraries.map { it.jsonObject }.first { (it["name"] as JsonPrimitive).content.startsWith("net.fabricmc:fabric-loader:") }
        val variants: List<Triple<String, JsonObject, Boolean>> = listOf(
            Triple("good", good, true),
            Triple("good-without-checksums", good, false),
            Triple("missing-sha1", firstLibrary { JsonObject(it - "sha1").withArtifact("sha1" to JsonPrimitive("")) }, true),
            Triple("missing-sha1-allowed", firstLibrary { JsonObject(it - "sha1").withArtifact("sha1" to JsonPrimitive("")) }, false),
            Triple("malformed-sha1", firstLibrary { it.withArtifact("sha1" to JsonPrimitive("abc")) }, false),
            Triple("wrong-host", firstLibrary { it.withArtifact("url" to JsonPrimitive("https://evil.example/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar")) }, true),
            Triple("http-scheme", firstLibrary { it.withArtifact("url" to JsonPrimitive("http://maven.fabricmc.net/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar")) }, true),
            Triple("url-query", firstLibrary { it.withArtifact("url" to JsonPrimitive("https://maven.fabricmc.net/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar?x=1")) }, true),
            Triple("path-mismatch", firstLibrary { it.withArtifact("path" to JsonPrimitive("org/ow2/asm/asm/9.10.1/other.jar")) }, true),
            Triple("unsafe-path", firstLibrary { it.withArtifact("path" to JsonPrimitive("../asm-9.10.1.jar")) }, true),
            Triple("forge-inside", withLibraries { it + JsonObject(mapOf("name" to JsonPrimitive("net.minecraftforge:forge:1.20.1-47.4.29"))) }, true),
            Triple("conflicting-coordinates", withLibraries { list ->
                list + JsonObject(mapOf("name" to JsonPrimitive("org.ow2.asm:asm:9.9"), "url" to JsonPrimitive("https://maven.fabricmc.net/")))
            }, true),
            Triple("no-loader", withLibraries { list -> list.filter { it != loaderLibrary } }, true),
            Triple("wrong-main-class", JsonObject(good + ("mainClass" to JsonPrimitive("net.minecraft.client.main.Main"))), true),
            Triple("wrong-id", JsonObject(good + ("id" to JsonPrimitive("fabric-loader-0.19.4-1.20.1"))), true),
            Triple("wrong-inherits", JsonObject(good + ("inheritsFrom" to JsonPrimitive("1.21.1"))), true),
            Triple("unexpected-jar", JsonObject(good + ("jar" to JsonPrimitive("1.21.1"))), true),
        )
        val runtime = ClientLoaderRuntime.resolve(McVersion.V201, ModLoader.Fabric)
        return buildJsonArray {
            variants.forEach { (name, profile, requireChecksums) ->
                val manifest = serdesJson.decodeFromString<MojangVersionManifest>(profile.toString())
                val valid = FabricClientProfile.validate(manifest, runtime, requireChecksums).isSuccess
                add(buildJsonObject {
                    put("name", name)
                    put("requireChecksums", requireChecksums)
                    put("valid", valid)
                    put("profile", profile)
                })
            }
        }
    }

    @Test
    fun `gtnh and fabric launch commands match the shared fixture`() {
        val generated = buildJsonObject {
            put("comment", "Written by J5cLaunchCommandFixtureTest (client/mclaunch); checked by mc_launch tests in Rust.")
            put("pathSeparator", File.pathSeparator)
            put("cases", buildJsonArray {
                cases.forEach { case ->
                    val runtime = mutableListOf<String>()
                    val command = command(case, runtime)
                    add(buildJsonObject {
                        put("name", case.name)
                        put("mcVersion", case.mcVersion.mcVer)
                        put("loader", case.loader.name)
                        put("versionId", case.versionId)
                        put("rdiRuntime", case.rdiRuntime)
                        put("accountName", case.user.first)
                        put("accountUuid", case.user.second)
                        put("userType", case.user.third)
                        put("maxMemoryMb", JsonNull)
                        put("defaultMaxMemoryMb", 6144)
                        put("launcherVersion", "6.7.9")
                        put("window", strings(listOf("1920", "1080")))
                        put("extraJvmArgs", strings(case.extraJvmArgs))
                        put("runtimeClasspath", strings(runtime))
                        put("mediaNatives", if (runtime.any { it.endsWith("rdi-mediaproc.jar") }) JsonPrimitive("\${ROOT}/tools/mediaproc-natives/0123abcd") else JsonNull)
                        put("command", strings(command))
                    })
                }
            })
            put("fabricProfiles", fabricCases())
        }
        val text = jsonPretty(generated) + "\n"
        if (System.getenv("RDI_WRITE_FIXTURES") == "1") {
            fixture.writeText(text)
        }
        assertTrue(fixture.isFile, "缺少j5c-launch.json，请用RDI_WRITE_FIXTURES=1运行本测试")
        assertEquals(fixture.readText().replace("\r\n", "\n"), text)
        root.deleteRecursively()
    }

    private fun jsonPretty(element: JsonElement, indent: String = ""): String = when (element) {
        is JsonObject -> if (element.isEmpty()) "{}" else element.entries.joinToString(",\n", "{\n", "\n$indent}") { (key, value) ->
            "$indent  ${JsonPrimitive(key)}: ${jsonPretty(value, "$indent  ")}"
        }
        is JsonArray -> if (element.isEmpty()) "[]" else element.joinToString(",\n", "[\n", "\n$indent]") {
            "$indent  ${jsonPretty(it, "$indent  ")}"
        }
        else -> element.toString()
    }
}
