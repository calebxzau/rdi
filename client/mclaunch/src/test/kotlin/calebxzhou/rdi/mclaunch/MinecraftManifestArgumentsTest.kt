package calebxzau.rdi.mclaunch

import calebxzau.rdi.mclaunch.model.MojangArguments
import calebxzhou.rdi.common.model.LibraryOsArch
import calebxzhou.rdi.common.serdesJson
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinecraftManifestArgumentsTest {
    // The head of the vanilla 1.21.1 (and 1.20.1) client manifest's jvm arguments.
    private val vanillaJvmArguments = serdesJson.parseToJsonElement(
        """
        [
          {"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": ["-XstartOnFirstThread"]},
          {"rules": [{"action": "allow", "os": {"name": "windows"}}],
           "value": "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump"},
          "-Djava.library.path=${'$'}{natives_directory}"
        ]
        """.trimIndent()
    ).jsonArray

    @Test
    fun macGameProcessStartsOnFirstThread() {
        val args = resolveArgumentList(vanillaJvmArguments, LibraryOsArch.MAC_ARM64)
        assertTrue("-XstartOnFirstThread" in args)
        assertFalse(args.any { it.startsWith("-XX:HeapDumpPath=") })
    }

    @Test
    fun windowsAndLinuxGameProcessesDoNotStartOnFirstThread() {
        listOf(LibraryOsArch.WIN_X64, LibraryOsArch.LINUX_X64).forEach { osArch ->
            assertFalse("-XstartOnFirstThread" in resolveArgumentList(vanillaJvmArguments, osArch), osArch.name)
        }
        assertTrue(resolveArgumentList(vanillaJvmArguments, LibraryOsArch.WIN_X64).any { it.startsWith("-XX:HeapDumpPath=") })
    }
    @Test
    fun resolvesLegacyArgumentsAndLaunchTokens() {
        val manifest = MojangVersionManifest(
            id = "1.7.10",
            minecraftArguments = "--username \${auth_player_name} --version \${version_name}",
        )
        assertEquals(
            listOf("--username", "\${auth_player_name}", "--version", "\${version_name}"),
            manifest.resolveGameArgumentList(),
        )
        assertEquals(
            "${File("/natives/game").absolutePath}/lib${File.pathSeparator}ignored",
            "\${natives_directory}/lib\${classpath_separator}ignored".replaceLaunchTokens(
                nativesDir = File("/natives/game"),
                versionDir = File("/game"),
                librariesDir = File("/libraries"),
                launcherBrand = "rdi",
                launcherVersion = "test",
                versionId = "test-version",
                classpath = "ignored",
            ),
        )
    }

    @Test
    fun bootstrapLaunchIncludesInheritedMinecraftJar() {
        val manifest = MojangVersionManifest(id = "1.21.1")
        val loaderManifest = MojangVersionManifest(
            id = "launch-version",
            mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher",
            inheritsFrom = "1.21.1",
            arguments = MojangArguments(
                jvm = listOf(JsonPrimitive("-p"), JsonPrimitive("ALL-MODULE-PATH")),
            ),
        )

        val candidates = resolveLaunchVersionJarCandidates(
            manifest = manifest,
            loaderManifest = loaderManifest,
            versionDir = File("/minecraft/versions/launch-version"),
            versionsDir = File("/minecraft/versions"),
            versionId = "launch-version",
        )

        assertTrue(candidates.contains(File("/minecraft/versions/1.21.1/1.21.1.jar")))
    }

    @Test
    fun bootstrapLaunchCandidateResolvesExistingMinecraftJar() {
        val root = Files.createTempDirectory("minecraft-version-jar").toFile()
        try {
            val baseJar = root.resolve("versions/1.21.1/1.21.1.jar").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            val candidates = resolveLaunchVersionJarCandidates(
                manifest = MojangVersionManifest(id = "1.21.1"),
                loaderManifest = MojangVersionManifest(
                    id = "launch-version",
                    mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher",
                    inheritsFrom = "1.21.1",
                    arguments = MojangArguments(
                        jvm = listOf(JsonPrimitive("-p"), JsonPrimitive("ALL-MODULE-PATH")),
                    ),
                ),
                versionDir = root.resolve("versions/launch-version"),
                versionsDir = root.resolve("versions"),
                versionId = "launch-version",
            ).filter(File::exists)

            assertEquals(listOf(baseJar), candidates.filter { it == baseJar })
        } finally {
            root.deleteRecursively()
        }
    }
}
