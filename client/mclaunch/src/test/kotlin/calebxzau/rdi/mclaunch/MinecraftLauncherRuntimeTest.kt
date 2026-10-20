package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzau.rdi.mclaunch.model.MojangArguments
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinecraftLauncherRuntimeTest {
    private val root = Files.createTempDirectory("rdi-launch-test").toFile()

    private fun launcher(onCommand: (List<String>) -> Unit, mediaResolved: () -> Unit = {}): MinecraftLauncher {
        val directories = MinecraftDirectories(
            mcDir = root,
            versionsDir = root.resolve("versions"),
            librariesDir = root.resolve("libraries"),
            assetsDir = root.resolve("assets"),
            toolsDir = root.resolve("tools"),
        )
        val manifest = MojangVersionManifest(
            id = "1.20.1",
            mainClass = "net.minecraft.client.main.Main",
            arguments = MojangArguments(
                game = listOf(
                    "--username", "\${auth_player_name}",
                    "--uuid", "\${auth_uuid}",
                    "--accessToken", "\${auth_access_token}",
                    "--userType", "\${user_type}",
                ).map(::JsonPrimitive),
            ),
        )
        return MinecraftLauncher(
            MinecraftLaunchEnvironment(
                directories = directories,
                java = MinecraftJava25Config(currentJavaPath = "java25", currentJavaMajor = 25, maxMemoryMb = 6000),
                launcherBrand = "rdi",
                launcherVersion = "test",
                debug = false,
                manifestProvider = { _, _, _, _ -> Result.success(MinecraftManifestPair(manifest, manifest.copy(id = "loader"))) },
                artifactDownloader = { _, _, _, _ -> Result.failure(IllegalStateException("no download in tests")) },
                processStarter = { command, _ ->
                    onCommand(command)
                    Result.failure(IllegalStateException("captured"))
                },
                mediaRuntimeResolver = {
                    mediaResolved()
                    Result.failure(IllegalStateException("media runtime unavailable in tests"))
                },
            )
        )
    }

    private fun request(rdiRuntime: Boolean, account: MinecraftAccount, javaPath: String? = null) = MinecraftLaunchRequest(
        mcVersion = McVersion.V201,
        loader = ModLoader.forge,
        versionId = "local_test",
        versionDir = root.resolve("versions/local_test").apply { mkdirs() },
        account = account,
        windowSize = MinecraftWindowSize(854, 480),
        launchOverrides = MinecraftLaunchOverrides(javaPath = javaPath),
        rdiRuntime = rdiRuntime,
    )

    @Test
    fun `local packs launch without RDI runtimes and with the offline user type`() {
        var command: List<String>? = null
        var mediaResolved = false
        val account = MinecraftAccount("Steve", "8667ba71-b85a-4004-af54-457a9734eed7", "0", userType = "legacy")
        val result = launcher({ command = it }, { mediaResolved = true }).launch(request(rdiRuntime = false, account)) {}

        assertTrue(result.isFailure)
        val launched = checkNotNull(command)
        assertFalse(mediaResolved)
        assertTrue(launched.none { it.startsWith("-Drdi.") || it.startsWith("-Dforge.logging.marker.") })
        assertTrue(launched.none { it.contains("kotlin-runtime") || it.contains("zstd", ignoreCase = true) })
        assertEquals("legacy", launched[launched.indexOf("--userType") + 1])
        assertEquals("8667ba71b85a4004af54457a9734eed7", launched[launched.indexOf("--uuid") + 1])
        assertContains(launched, "-XX:+UseCompactObjectHeaders")
    }

    @Test
    fun `RDI packs still require the media runtime`() {
        var mediaResolved = false
        val account = MinecraftAccount("Steve", "8667ba71-b85a-4004-af54-457a9734eed7", "token")
        val result = launcher({}, { mediaResolved = true }).launch(request(rdiRuntime = true, account)) {}

        assertTrue(result.isFailure)
        assertTrue(mediaResolved)
    }

    @Test
    fun `compact object headers are left out for a Java 17 runtime`() {
        val jdk = root.toPath().resolve("jdk17")
        jdk.resolve("bin").createDirectories()
        jdk.resolve("release").writeText("IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"17.0.12\"\n")
        val java = jdk.resolve("bin/java").toString()
        var command: List<String>? = null
        launcher({ command = it }).launch(
            request(rdiRuntime = false, MinecraftAccount("Steve", "8667ba71-b85a-4004-af54-457a9734eed7", "0"), java),
        ) {}

        val launched = checkNotNull(command)
        assertEquals(java, launched.first())
        assertFalse("-XX:+UseCompactObjectHeaders" in launched)
    }

    @Test
    fun `JDK release majors are parsed for modern and legacy version strings`() {
        fun major(version: String?): Int? {
            val jdk = Files.createTempDirectory(root.toPath(), "jdk").toFile()
            jdk.resolve("bin").mkdirs()
            version?.let { jdk.resolve("release").writeText("JAVA_VERSION=\"$it\"\n") }
            return readJdkReleaseMajor(File(jdk, "bin/java"))
        }
        assertEquals(25, major("25"))
        assertEquals(21, major("21.0.4"))
        assertEquals(8, major("1.8.0_412"))
        assertNull(major(null))
    }
}
