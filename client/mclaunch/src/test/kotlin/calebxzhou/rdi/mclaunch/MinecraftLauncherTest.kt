package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzau.rdi.mclaunch.model.MojangDownloadArtifact
import calebxzau.rdi.mclaunch.model.MojangLibrary
import calebxzau.rdi.mclaunch.model.MojangLibraryDownloads
import calebxzau.rdi.mclaunch.model.MojangVersionDownloads
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzau.rdi.mclaunch.model.MojangArguments
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import calebxzhou.rdi.common.util.sha1
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinecraftLauncherTest {
    @Test
    fun cancellationDuringMediaPreparationDoesNotReturnALaunch() = runBlocking {
        val root = Files.createTempDirectory("mclaunch-media-cancel").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var started = false
        try {
            val launcher = launcher(
                root = root,
                java = MinecraftJava25Config("/current/java", 25, 1024),
                onStart = { started = true },
                mediaRuntimeResolver = { nativeRoot ->
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    Result.success(MediaProcGameRuntime(emptyList(), nativeRoot))
                },
            )
            val preparation = async { launcher.prepareLaunch(request(root)) }
            withTimeout(5_000) { entered.await() }
            preparation.cancel()
            release.countDown()
            withTimeout(5_000) { preparation.join() }
            assertTrue(preparation.isCancelled)
            assertTrue(!started)
            Unit
        } finally {
            release.countDown()
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun preparedLaunchResolvesMediaOnceAndOnlyStartsWhenConsumed() = runBlocking {
        val root = Files.createTempDirectory("mclaunch-prepared-media").toFile()
        try {
            var resolved = 0
            var started = 0
            val launcher = launcher(
                root = root,
                java = MinecraftJava25Config("/current/java", 25, 1024),
                onStart = { started++ },
                mediaRuntimeResolver = { nativeRoot ->
                    resolved++
                    Result.success(MediaProcGameRuntime(emptyList(), nativeRoot))
                },
            )

            val prepared = launcher.prepareLaunch(request(root)).getOrThrow()
            assertEquals(1, resolved)
            assertEquals(0, started)
            prepared.launch {}.getOrThrow()
            assertEquals(1, resolved)
            assertEquals(1, started)
            assertTrue(prepared.launch {}.isFailure)
            assertEquals(1, started)

            launcher.launch(request(root)) {}.getOrThrow()
            assertEquals(2, resolved, "Standalone launch must still prepare its own media")
            assertEquals(2, started)
            Unit
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun preparedLaunchStillRejectsLibraryChangedAfterPreparation() = runBlocking {
        val root = Files.createTempDirectory("mclaunch-prepared-integrity").toFile()
        try {
            var started = false
            val launcher = launcher(
                root = root,
                java = MinecraftJava25Config("/current/java", 25, 1024),
                onStart = { started = true },
                mediaRuntimeResolver = { Result.success(MediaProcGameRuntime(emptyList(), it)) },
            )
            val prepared = launcher.prepareLaunch(request(root)).getOrThrow()
            val library = root.resolve("libraries/test/library/1.0/library-1.0.jar")
            val originalTime = library.lastModified()
            val bytes = library.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            library.writeBytes(bytes)
            check(library.setLastModified(originalTime))

            val result = prepared.launch {}
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("校验失败"))
            assertTrue(!started)
            Unit
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun failedMediaPreparationCannotStartAProcess() = runBlocking {
        val root = Files.createTempDirectory("mclaunch-media-failure").toFile()
        try {
            var started = false
            val launcher = launcher(
                root = root,
                java = MinecraftJava25Config("/current/java", 25, 1024),
                onStart = { started = true },
                mediaRuntimeResolver = { Result.failure(IllegalStateException("broken media")) },
            )
            assertTrue(launcher.prepareLaunch(request(root)).isFailure)
            assertTrue(!started)
            Unit
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun usesCurrentJava25Path() {
        val root = Files.createTempDirectory("mclaunch-java25").toFile()
        try {
            val command = captureLaunchCommand(root, currentJavaMajor = 25)
            assertEquals("/current/java", command.first())
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun rejectsNonJava25CurrentRuntime() {
        val root = Files.createTempDirectory("mclaunch-java25-required").toFile()
        try {
            var started = false
            val launcher = launcher(
                root = root,
                java = MinecraftJava25Config(
                    currentJavaPath = "/current/java",
                    currentJavaMajor = 21,
                    maxMemoryMb = 1024,
                ),
                onStart = { started = true },
            )
            val result = launcher.launch(request(root), onLine = {})
            assertTrue(result.isFailure)
            assertNull(result.getOrNull())
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Java25"))
            assertTrue(!started)
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun extraGameArgsFollowTheStandardArgsAndAccountIsSubstituted() {
        val root = Files.createTempDirectory("mclaunch-extra-game-args").toFile()
        try {
            val command = captureLaunchCommand(
                root = root,
                currentJavaMajor = 25,
                vanillaArguments = MojangArguments(game = listOf("--uuid", "\${auth_uuid}", "--username", "\${auth_player_name}").map(::JsonPrimitive)),
                account = MinecraftAccount("房主", "00000000-0000-300c-9be5-0017dec2993d", "placeholder"),
                extraGameArgs = listOf("--quickPlaySingleplayer", "新的世界-RDI导入"),
            )
            assertEquals(listOf("--quickPlaySingleplayer", "新的世界-RDI导入"), command.takeLast(2))
            assertEquals("000000000000300c9be50017dec2993d", command[command.indexOf("--uuid") + 1])
            assertEquals("房主", command[command.indexOf("--username") + 1])
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun launchOverridesUsePackJavaAndMaxMemory() {
        val root = Files.createTempDirectory("mclaunch-pack-options").toFile()
        try {
            val command = captureLaunchCommand(
                root = root,
                currentJavaMajor = 25,
                launchOverrides = MinecraftLaunchOverrides(
                    javaPath = "/custom/jdk/bin/java.exe",
                    maxMemoryMb = 8192,
                ),
            )
            assertEquals("/custom/jdk/bin/java.exe", command.first())
            assertTrue(command.contains("-Xmx8192M"))
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun addsEarlyDisplayFfmpegPathForSupportedMinecraftVersions() {
        val nativeDir = Files.createTempDirectory("early-display-native").toFile()
        try {
            val expected = listOf("-Drdi.earlyDisplay.ffmpeg=${nativeDir.resolve("ffmpeg.exe").absolutePath}")
            assertEquals(expected, earlyDisplayJvmArgs(McVersion.V201, nativeDir))
            assertEquals(expected, earlyDisplayJvmArgs(McVersion.V211, nativeDir))
        } finally {
            nativeDir.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun neoForgeLoggingArgsOnlyApplyToNeoForge1211() {
        assertEquals(
            listOf(
                "-Dforge.logging.marker.loading=NEUTRAL",
                "-Dforge.logging.marker.core=NEUTRAL",
                "-Dforge.logging.marker.forgemod=NEUTRAL",
                "-Drdi.logging.rootLevel=DEBUG",
            ),
            neoForgeLoggingJvmArgs(McVersion.V211, ModLoader.neoforge, emptyList()),
        )
        assertEquals(emptyList(), neoForgeLoggingJvmArgs(McVersion.V201, ModLoader.forge, emptyList()))
        assertEquals(emptyList(), neoForgeLoggingJvmArgs(McVersion.V211, ModLoader.Fabric, emptyList()))
    }

    @Test
    fun neoForgeLoggingArgsKeepUserOverrides() {
        val args = neoForgeLoggingJvmArgs(
            McVersion.V211,
            ModLoader.neoforge,
            listOf("-Dforge.logging.marker.loading=ACCEPT", "-Drdi.logging.rootLevel=ALL"),
        )
        assertEquals(listOf("-Dforge.logging.marker.core=NEUTRAL", "-Dforge.logging.marker.forgemod=NEUTRAL"), args)
    }

    @Test
    fun fabricLaunchPreservesProfileArgsAndUsesVanillaJarWithoutForgeRuntimeInjection() {
        val root = Files.createTempDirectory("mclaunch fabric profile ").toFile()
        try {
            val fabricMcEmu = "-DFabricMcEmu= net.minecraft.client.main.Main "
            val baseGameArg = "base argument with spaces"
            val profileGameArg = "Fabric argument with spaces"
            val fabricLibraries = listOf(
                "net.fabricmc:fabric-loader:0.19.5",
                "net.fabricmc:intermediary:1.20.1",
            ).mapIndexed { index, coordinate ->
                val parts = coordinate.split(':')
                val path = "${parts[0].replace('.', '/')}/${parts[1]}/${parts[2]}/${parts[1]}-${parts[2]}.jar"
                val libraryFile = root.resolve("libraries/$path").apply {
                    parentFile.mkdirs()
                    ZipOutputStream(outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("fabric-marker"))
                        zip.write(index + 1)
                        zip.closeEntry()
                    }
                }
                MojangLibrary(
                    name = coordinate,
                    downloads = MojangLibraryDownloads(
                        artifact = MojangDownloadArtifact(
                            sha1 = libraryFile.sha1,
                            size = libraryFile.length(),
                            url = "https://maven.fabricmc.net/$path",
                            path = path,
                        ),
                    ),
                    url = "https://maven.fabricmc.net/",
                )
            }
            val fabricProfile = MojangVersionManifest(
                id = "fabric-loader-0.19.5-1.20.1",
                inheritsFrom = "1.20.1",
                mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
                arguments = MojangArguments(
                    game = listOf(JsonPrimitive("--profile-argument"), JsonPrimitive(profileGameArg)),
                    jvm = listOf(JsonPrimitive(fabricMcEmu)),
                ),
                libraries = fabricLibraries,
            )
            val vanillaJar = root.resolve("versions/1.20.1/1.20.1.jar").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            val command = captureLaunchCommand(
                root = root,
                currentJavaMajor = 25,
                mcVersion = McVersion.V201,
                loader = ModLoader.Fabric,
                manifestId = "1.20.1",
                loaderManifestOverride = fabricProfile,
                vanillaArguments = MojangArguments(
                    game = listOf(
                        JsonPrimitive("--username"),
                        JsonPrimitive("\${auth_player_name}"),
                        JsonPrimitive("--base-argument"),
                        JsonPrimitive(baseGameArg),
                    ),
                ),
            )

            assertTrue(command.contains("net.fabricmc.loader.impl.launch.knot.KnotClient"))
            assertTrue(command.contains(fabricMcEmu))
            assertTrue(command.contains("--base-argument"))
            assertTrue(command.contains(baseGameArg))
            assertTrue(command.contains("player"))
            assertTrue(command.contains("--profile-argument"))
            assertTrue(command.contains(profileGameArg))
            assertTrue(command.any { it.split(File.pathSeparator).contains(vanillaJar.absolutePath) })
            assertTrue(command.none { it.contains("fabric-loader-0.19.5-1.20.1.jar") })
            assertTrue(command.any { it.split(File.pathSeparator).any { entry -> entry.contains("fabric-loader-0.19.5.jar") } })
            assertTrue(command.any { it.split(File.pathSeparator).any { entry -> entry.contains("intermediary-1.20.1.jar") } })
            assertTrue(command.contains("-Dorg.bytedeco.javacpp.pathsFirst=true"))
            assertTrue(command.contains("-Dorg.bytedeco.javacpp.findLibraries=false"))
            assertTrue(command.any { it.startsWith("-Dorg.bytedeco.javacpp.platform.preloadpath=") })
            assertTrue(command.none { it.contains("kotlin-runtime") })
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun prepareRepairsMissingMinecraftClientJar() = runBlocking {
        val root = Files.createTempDirectory("mclaunch-client-jar").toFile()
        try {
            val launcher = launcher(
                root = root,
                java = MinecraftJava25Config(
                    currentJavaPath = "/current/java",
                    currentJavaMajor = 25,
                    maxMemoryMb = 1024,
                ),
                manifestId = "1.21.1",
                clientArtifact = MojangDownloadArtifact(url = "https://example.invalid/client.jar"),
            )

            launcher.prepare(request(root, mcVersion = McVersion.V211), onProgress = {}).getOrThrow()

            assertTrue(root.resolve("versions/1.21.1/1.21.1.jar").isFile)
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    private fun captureLaunchCommand(
        root: File,
        currentJavaMajor: Int,
        launchOverrides: MinecraftLaunchOverrides = MinecraftLaunchOverrides(),
        mcVersion: McVersion = McVersion.V201,
        loader: ModLoader = ModLoader.Fabric,
        manifestId: String = "1.7.10",
        loaderManifestOverride: MojangVersionManifest? = null,
        vanillaArguments: MojangArguments = MojangArguments(),
        account: MinecraftAccount = MinecraftAccount("player", "00000000-0000-0000-0000-000000000000", "token"),
        extraGameArgs: List<String> = emptyList(),
    ): List<String> {
        var command: List<String>? = null
        val launcher = launcher(
            root = root,
            java = MinecraftJava25Config(
                currentJavaPath = "/current/java",
                currentJavaMajor = currentJavaMajor,
                maxMemoryMb = 1024,
            ),
            manifestId = manifestId,
            loaderManifestOverride = loaderManifestOverride,
            vanillaArguments = vanillaArguments,
            onCommand = { command = it },
        )
        launcher.launch(
            request(root, mcVersion = mcVersion, loader = loader, launchOverrides = launchOverrides, account = account, extraGameArgs = extraGameArgs),
            onLine = {},
        ).getOrThrow()
        return command ?: error("未捕获Minecraft启动命令")
    }

    private fun launcher(
        root: File,
        java: MinecraftJava25Config,
        onCommand: (List<String>) -> Unit = {},
        onStart: () -> Unit = {},
        manifestId: String = "1.7.10",
        clientArtifact: MojangDownloadArtifact? = null,
        loaderManifestOverride: MojangVersionManifest? = null,
        vanillaArguments: MojangArguments = MojangArguments(),
        mediaRuntimeResolver: (File) -> Result<MediaProcGameRuntime> = {
            MediaProcGameClasspath.resolve(nativeRoot = it)
        },
    ): MinecraftLauncher {
        val directories = MinecraftDirectories(
            mcDir = root,
            versionsDir = root.resolve("versions"),
            librariesDir = root.resolve("libraries"),
            assetsDir = root.resolve("assets"),
            toolsDir = root.resolve("tools"),
        )
        directories.versionsDir.mkdirs()
        directories.librariesDir.mkdirs()
        val artifactPath = "test/library/1.0/library-1.0.jar"
        val artifactFile = directories.librariesDir.resolve(artifactPath)
        artifactFile.parentFile.mkdirs()
        ZipOutputStream(artifactFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("marker"))
            zip.write(0)
            zip.closeEntry()
        }
        val library = MojangLibrary(
            name = "test:library:1.0",
            downloads = MojangLibraryDownloads(
                artifact = MojangDownloadArtifact(path = artifactPath, sha1 = artifactFile.sha1),
            ),
        )
        val manifest = MojangVersionManifest(
            id = manifestId,
            mainClass = "example.Main",
            downloads = clientArtifact?.let { MojangVersionDownloads(client = it) },
            arguments = vanillaArguments,
            libraries = listOf(library),
        )
        val loaderManifest = loaderManifestOverride ?: manifest.copy(
            id = "loader",
            minecraftArguments = "--username \${auth_player_name}",
        )
        val versionDir = directories.versionsDir.resolve("test-version").apply { mkdirs() }
        return MinecraftLauncher(
            MinecraftLaunchEnvironment(
                directories = directories,
                java = java,
                launcherBrand = "test",
                launcherVersion = "test",
                debug = false,
                manifestProvider = MinecraftManifestProvider { _, _, _, _ ->
                    Result.success(MinecraftManifestPair(manifest, loaderManifest))
                },
                artifactDownloader = MinecraftArtifactDownloader { _, _, target, _ ->
                    if (clientArtifact != null && target.name == "${manifest.id}.jar") {
                        target.parentFile.mkdirs()
                        ZipOutputStream(target.outputStream()).use { zip ->
                            zip.putNextEntry(ZipEntry("client-marker"))
                            zip.write(1)
                            zip.closeEntry()
                        }
                    }
                    Result.success(target)
                },
                processStarter = MinecraftProcessStarter { captured, _ ->
                    onCommand(captured)
                    onStart()
                    Result.success(FakeProcess())
                },
                mediaRuntimeResolver = mediaRuntimeResolver,
            )
        )
    }

    private fun request(
        root: File,
        mcVersion: McVersion = McVersion.V201,
        loader: ModLoader = ModLoader.Fabric,
        launchOverrides: MinecraftLaunchOverrides = MinecraftLaunchOverrides(),
        account: MinecraftAccount = MinecraftAccount("player", "00000000-0000-0000-0000-000000000000", "token"),
        extraGameArgs: List<String> = emptyList(),
    ): MinecraftLaunchRequest = MinecraftLaunchRequest(
        mcVersion = mcVersion,
        loader = loader,
        versionId = "test-version",
        versionDir = root.resolve("versions/test-version"),
        account = account,
        windowSize = MinecraftWindowSize(854, 480),
        launchOverrides = launchOverrides,
        extraGameArgs = extraGameArgs,
    )

    private class FakeProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun isAlive(): Boolean = false
        override fun destroyForcibly(): Process = this
        override fun supportsNormalTermination(): Boolean = true
    }
}
