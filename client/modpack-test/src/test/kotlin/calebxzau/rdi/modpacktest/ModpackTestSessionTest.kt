package calebxzau.rdi.modpacktest

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType
import calebxzhou.rdi.common.util.sha1
import calebxzau.rdi.client.packproc.LoadedLocalModpack
import calebxzau.rdi.client.packproc.LocalModpackSourceType
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModpackTestSessionTest {
    @Test
    fun `client session resolves extras to instance root after preparing client content`() = runBlocking {
        val fixture = TestFixture(clientLine = CLIENT_TEST_SUCCESS_MARKER)
        val extra = fixture.extra("resourcepacks/test.zip", ContentType.ResPack)
        val bytes = "test-pack".encodeToByteArray()
        var calls = 0
        val resolver = ModpackTestClientExtraResolver { extras, target ->
            calls++
            assertEquals(listOf(extra), extras)
            Files.createDirectories(target.toPath().resolve("resourcepacks"))
            Files.write(target.toPath().resolve("resourcepacks/test.zip"), bytes)
            Result.success(Unit)
        }
        val session = fixture.session(ModpackTestTarget.CLIENT, clientExtras = listOf(extra), extraResolver = resolver)
        try {
            session.start(emptyList()).getOrThrow()
            awaitStatus(session, ModpackTestStatus.PASSED)
            assertEquals(1, calls)
            assertContentEquals(bytes, checkNotNull(fixture.launcher.clientVersionDir).resolve("resourcepacks/test.zip").readBytes())
        } finally {
            session.close()
            fixture.close()
        }
    }

    @Test
    fun `client session fails when extra resolver fails or is missing`() = runBlocking {
        val extra = Content(ContentPlatform.Modrinth, ContentType.ShaderPack, "p", "f", "s", "a".repeat(40), "shaderpacks/s.zip", ContentSide.Client)
        val fixture = TestFixture()
        val failed = fixture.session(
            ModpackTestTarget.CLIENT,
            clientExtras = listOf(extra),
            extraResolver = ModpackTestClientExtraResolver { _, _ -> Result.failure(IllegalStateException("resolver failed")) },
        )
        try {
            failed.start(emptyList()).getOrThrow()
            assertTrue(awaitStatus(failed, ModpackTestStatus.FAILED).errorMessage.orEmpty().contains("resolver failed"))
            assertEquals(0, fixture.launcher.clientLaunchCount)
        } finally {
            failed.close()
        }
        val missing = fixture.session(ModpackTestTarget.CLIENT, clientExtras = listOf(extra))
        try {
            missing.start(emptyList()).getOrThrow()
            assertTrue(awaitStatus(missing, ModpackTestStatus.FAILED).errorMessage.orEmpty().contains("缺少资源包和光影包解析器"))
            assertEquals(0, fixture.launcher.clientLaunchCount)
        } finally {
            missing.close()
            fixture.close()
        }
    }

    @Test
    fun `server session never invokes client extra resolver`() = runBlocking {
        val fixture = TestFixture(serverLine = "Done (1.0s)! For help")
        val extra = fixture.extra("shaderpacks/client.zip", ContentType.ShaderPack)
        var calls = 0
        val session = fixture.session(
            ModpackTestTarget.SERVER,
            clientExtras = listOf(extra),
            extraResolver = ModpackTestClientExtraResolver { _, _ -> calls++; Result.success(Unit) },
        )
        try {
            session.start(emptyList()).getOrThrow()
            awaitStatus(session, ModpackTestStatus.PASSED)
            assertEquals(0, calls)
            assertFalse(checkNotNull(fixture.launcher.serverWorkDir).resolve("shaderpacks/client.zip").exists())
        } finally {
            session.close()
            fixture.close()
        }
    }

    @Test
    fun `server success line marks session passed`() = runBlocking {
        val fixture = TestFixture(serverLine = "Done (3.25s)! For help")
        val session = fixture.session(ModpackTestTarget.SERVER)
        try {
            session.start(emptyList()).getOrThrow()
            val state = withTimeout(2_000) {
                session.state.filter { it.status == ModpackTestStatus.PASSED }.first()
            }

            assertEquals("3.25", state.passSeconds)
        } finally {
            session.close()
            fixture.close()
        }
    }

    @Test
    fun `client success marker marks session passed`() = runBlocking {
        val fixture = TestFixture(clientLine = CLIENT_TEST_SUCCESS_MARKER)
        val session = fixture.session(ModpackTestTarget.CLIENT)
        try {
            session.start(emptyList()).getOrThrow()
            val state = withTimeout(2_000) {
                session.state.filter { it.status == ModpackTestStatus.PASSED }.first()
            }

            assertEquals(ModpackTestStatus.PASSED, state.status)
        } finally {
            session.close()
            fixture.close()
        }
    }

    @Test
    fun `server staging preserves source jar whose filename contains another mod slug`() = runBlocking {
        val fixture = TestFixture(serverLine = "Done (3.25s)! For help")
        val kubejs = fixture.mod(slug = "kubejs", hash = "kubejs-hash")
        val farmingBytes = "farming-tales-mod".encodeToByteArray()
        val fragmentBytes = "filename-fragment-mod".encodeToByteArray()
        fixture.addSourceMod("farmingtales-1.0.11-kubejs.jar", farmingBytes)
        fixture.addSourceMod("unrelated-kubejs-kubejs-hash.jar", fragmentBytes)
        val downloadedBytes = "real-kubejs".encodeToByteArray()
        fixture.addDownloadedMod(kubejs.fileName, downloadedBytes)
        val session = fixture.session(ModpackTestTarget.SERVER, listOf(kubejs))
        try {
            session.start(listOf(kubejs)).getOrThrow()
            awaitStatus(session, ModpackTestStatus.PASSED)

            val modsDir = checkNotNull(fixture.launcher.serverWorkDir).resolve("mods")
            assertContentEquals(farmingBytes, modsDir.resolve("farmingtales-1.0.11-kubejs.jar").readBytes())
            assertContentEquals(fragmentBytes, modsDir.resolve("unrelated-kubejs-kubejs-hash.jar").readBytes())
            assertContentEquals(downloadedBytes, modsDir.resolve(kubejs.fileName).readBytes())
        } finally {
            session.close()
            fixture.close()
        }
    }

    @Test
    fun `server staging reuses identical source and downloaded target`() = runBlocking {
        val fixture = TestFixture(serverLine = "Done (1.0s)! For help")
        val kubejs = fixture.mod(slug = "kubejs", hash = "kubejs-hash")
        val bytes = "same-kubejs".encodeToByteArray()
        fixture.addSourceMod(kubejs.fileName, bytes)
        fixture.addDownloadedMod(kubejs.fileName, bytes)
        val session = fixture.session(ModpackTestTarget.SERVER, listOf(kubejs))
        try {
            session.start(listOf(kubejs)).getOrThrow()
            awaitStatus(session, ModpackTestStatus.PASSED)
            assertContentEquals(bytes, checkNotNull(fixture.launcher.serverWorkDir).resolve("mods/${kubejs.fileName}").readBytes())
        } finally {
            session.close()
            fixture.close()
        }
    }

    @Test
    fun `server staging fails before launch when source and download collide`() = runBlocking {
        val fixture = TestFixture()
        val kubejs = fixture.mod(slug = "kubejs", hash = "kubejs-hash")
        val sourceBytes = "source-kubejs".encodeToByteArray()
        val downloadedBytes = "different-kubejs".encodeToByteArray()
        val source = fixture.addSourceMod(kubejs.fileName, sourceBytes)
        val downloaded = fixture.addDownloadedMod(kubejs.fileName, downloadedBytes)
        val session = fixture.session(ModpackTestTarget.SERVER, listOf(kubejs))
        try {
            session.start(listOf(kubejs)).getOrThrow()
            val state = awaitStatus(session, ModpackTestStatus.FAILED)

            assertEquals(0, fixture.launcher.serverLaunchCount)
            assertContentEquals(sourceBytes, source.readBytes())
            assertContentEquals(downloadedBytes, downloaded.readBytes())
            assertTrue(state.errorMessage.orEmpty().contains(kubejs.fileName))
            assertTrue(state.errorMessage.orEmpty().contains(fixture.resolvedDownloadedPath(kubejs.fileName).absolutePath))
            assertTrue(state.errorMessage.orEmpty().contains("${File.separator}mods${File.separator}${kubejs.fileName}"))
        } finally {
            session.close()
            fixture.close()
        }
    }

    @Test
    fun `client staging matches exact and sha1 names while filtering server mods`() = runBlocking {
        val fixture = TestFixture(clientLine = CLIENT_TEST_SUCCESS_MARKER)
        val exactClient = fixture.mod(slug = "exact-client", hash = "exact-hash", side = Mod.Side.CLIENT)
        val hashBytes = "renamed-client".encodeToByteArray()
        val hashClient = fixture.mod(slug = "hash-client", hash = hashBytes.sha1, side = Mod.Side.CLIENT)
        val legacyClient = fixture.mod(slug = "true-ending", hash = "legacy-hash", side = Mod.Side.CLIENT)
        val server = fixture.mod(slug = "server-only", hash = "server-hash", side = Mod.Side.SERVER)
        val exactBytes = "exact-bytes".encodeToByteArray()
        val legacyBytes = "legacy-bytes".encodeToByteArray()
        fixture.addSourceMod(exactClient.fileName, exactBytes)
        fixture.addSourceMod(legacyClient.legacyFileName, legacyBytes)
        fixture.addSourceMod("renamed-client.jar", hashBytes)
        fixture.addSourceMod(server.fileName, "server-bytes".encodeToByteArray())
        val clientMods = listOf(exactClient, hashClient, legacyClient, server)
        val session = fixture.session(ModpackTestTarget.CLIENT, clientMods)
        try {
            session.start(clientMods).getOrThrow()
            awaitStatus(session, ModpackTestStatus.PASSED)

            val modsDir = checkNotNull(fixture.launcher.clientVersionDir).resolve("mods")
            assertContentEquals(exactBytes, modsDir.resolve(exactClient.fileName).readBytes())
            assertContentEquals(legacyBytes, modsDir.resolve(legacyClient.fileName).readBytes())
            assertFalse(modsDir.resolve(legacyClient.legacyFileName).exists())
            assertTrue(modsDir.resolve(hashClient.fileName).isFile)
            assertFalse(modsDir.resolve("renamed-client.jar").exists())
            assertFalse(modsDir.resolve(server.fileName).exists())
        } finally {
            session.close()
            fixture.close()
        }
    }
}

private suspend fun awaitStatus(session: ModpackTestSession, status: ModpackTestStatus): ModpackTestState =
    withTimeout(2_000) { session.state.filter { it.status == status }.first() }

private class TestFixture(
    clientLine: String? = null,
    serverLine: String? = null,
) : AutoCloseable {
    private val root = Files.createTempDirectory("modpack-test-").toFile()
    private val sourceDir = root.resolve("source").apply { mkdirs() }
    val launcher = FakeModpackTestLauncher(clientLine, serverLine)
    private val downloadedMods = mutableMapOf<String, ByteArray>()
    private val resolvedDownloadedPaths = mutableMapOf<String, File>()
    private val environment = ModpackTestEnvironment(
        paths = ModpackTestPaths(
            workDir = root.resolve("work"),
            librariesDir = root.resolve("libraries"),
        ),
        launcher = launcher,
    )

    fun session(
        target: ModpackTestTarget,
        mods: List<Mod> = emptyList(),
        clientExtras: List<Content> = emptyList(),
        extraResolver: ModpackTestClientExtraResolver? = null,
    ): ModpackTestSession = ModpackTestSession(
        loadedModpack = LoadedLocalModpack(
            sourceType = LocalModpackSourceType.CURSEFORGE,
            sourceDir = sourceDir,
            packName = "test",
            packVersion = "1",
            mcVersion = McVersion.V201,
            modloader = ModLoader.forge,
            mods = mods,
            clientExtras = clientExtras,
        ),
        target = target,
        environment = environment,
        modSourceResolver = ModpackTestModSourceResolver { _, testDir ->
            val sourceDir = testDir.resolve(".rdi-mod-sources").apply { mkdirs() }
            downloadedMods.forEach { (name, bytes) ->
                val file = sourceDir.resolve(name)
                Files.write(file.toPath(), bytes)
                resolvedDownloadedPaths[name] = file
            }
            Result.success(sourceDir)
        },
        clientExtraResolver = extraResolver,
    )

    fun extra(path: String, type: ContentType): Content = Content(
        platform = ContentPlatform.Modrinth,
        type = type,
        projectId = "extra",
        fileId = "file",
        slug = "extra",
        hash = "a".repeat(40),
        path = path,
        side = ContentSide.Client,
    )

    fun mod(
        slug: String,
        hash: String,
        side: Mod.Side = Mod.Side.BOTH,
    ): Mod = Mod(
        platform = "mr",
        projectId = slug,
        slug = slug,
        fileId = "$slug-file",
        hash = hash,
        side = side,
    )

    fun addSourceMod(name: String, bytes: ByteArray): File {
        val file = sourceDir.resolve("mods/$name")
        file.parentFile.mkdirs()
        Files.write(file.toPath(), bytes)
        return file
    }

    fun addDownloadedMod(name: String, bytes: ByteArray): File {
        downloadedMods[name] = bytes
        return root.resolve("downloaded/$name").also {
            it.parentFile.mkdirs()
            Files.write(it.toPath(), bytes)
        }
    }

    fun resolvedDownloadedPath(name: String): File = checkNotNull(resolvedDownloadedPaths[name])

    override fun close() {
        root.deleteRecursively()
    }
}

private class FakeModpackTestLauncher(
    private val clientLine: String?,
    private val serverLine: String?,
) : ModpackTestLauncher {
    var serverWorkDir: File? = null
        private set
    var clientVersionDir: File? = null
        private set
    var serverLaunchCount = 0
        private set
    var clientLaunchCount = 0
        private set

    override suspend fun prepareClientLoader(
        mcVersion: McVersion,
        loader: ModLoader,
        onProgress: (String) -> Unit,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun prepareClientLibraries(
        mcVersion: McVersion,
        versionId: String,
        versionDir: File,
        onProgress: (String) -> Unit,
    ): Result<Unit> = Result.success(Unit)

    override fun launchClient(
        mcVersion: McVersion,
        versionId: String,
        versionDir: File,
        onLine: (String) -> Unit,
    ): Result<ModpackTestProcess> {
        clientLaunchCount++
        clientVersionDir = versionDir
        clientLine?.let(onLine)
        return Result.success(FakeModpackTestProcess())
    }

    override fun launchServer(
        mcVersion: McVersion,
        loaderVersion: ModLoader.Version,
        workDir: File,
        onLine: (String) -> Unit,
    ): Result<ModpackTestProcess> {
        serverLaunchCount++
        serverWorkDir = workDir
        serverLine?.let(onLine)
        return Result.success(FakeModpackTestProcess())
    }
}

private class FakeModpackTestProcess : ModpackTestProcess {
    private var alive = true

    override fun isAlive(): Boolean = alive

    override fun stop(): Result<Unit> = runCatching { alive = false }

    override suspend fun waitFor(): Result<Int> = Result.success(0)
}
