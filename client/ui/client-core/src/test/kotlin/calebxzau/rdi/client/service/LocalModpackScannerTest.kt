package calebxzau.rdi.client.service

import calebxzhou.rdi.client.database.MinecraftInstallationRecord
import calebxzhou.rdi.client.database.MinecraftInstallationStore
import calebxzhou.rdi.client.service.LocalModpackCandidate
import calebxzhou.rdi.client.service.LocalModpackScanner
import calebxzhou.rdi.client.service.detectLocalModLoader
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalModpackScannerTest {
    @Test
    fun `recognizes both Fabric Knot entrypoints and rejects library-only detection`() {
        val current = detectLocalModLoader(
            "net.fabricmc.loader.impl.launch.knot.KnotClient",
            listOf("net.fabricmc:fabric-loader:0.16.10"),
            McVersion.V201,
        ).getOrThrow()
        val legacy = detectLocalModLoader(
            "net.fabricmc.loader.launch.knot.KnotClient",
            emptyList(),
            McVersion.V201,
        ).getOrThrow()
        val libraryOnly = detectLocalModLoader(
            null,
            listOf("net.fabricmc:fabric-loader:0.16.10"),
            McVersion.V201,
        )

        assertEquals(ModLoader.Fabric, current)
        assertEquals(ModLoader.Fabric, legacy)
        assertTrue(libraryOnly.isFailure)
        assertTrue(libraryOnly.exceptionOrNull()?.message.orEmpty().contains("启动类缺失"))
    }

    @Test
    fun `rejects contradictory runtime library and entrypoint evidence`() {
        val result = detectLocalModLoader(
            "net.fabricmc.loader.impl.launch.knot.KnotClient",
            listOf("net.minecraftforge:forge:1.20.1-47.2.0"),
            McVersion.V201,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("冲突"))
    }

    @Test
    fun `does not infer a supported loader from libraries without a known entrypoint`() {
        val result = detectLocalModLoader(
            "example.CustomLauncher",
            listOf("net.minecraftforge:forge:1.20.1-47.2.0"),
            McVersion.V201,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("启动类example.CustomLauncher"))
    }

    @Test
    fun `scans renamed installations by manifest and ignores Fabric until runtime support exists`() = runBlocking {
        val root = Files.createTempDirectory("rdi-local-fabric-scan")
        try {
            val installation = root.resolve("instance")
            val fabric = installation.resolve("versions/renamed-fabric")
            createInstance(
                fabric,
                "net.fabricmc.loader.impl.launch.knot.KnotClient",
                "net.fabricmc:fabric-loader:0.16.10",
                includeFabricModMetadata = true,
            )
            val forge = installation.resolve("versions/renamed-forge")
            createInstance(
                forge,
                "cpw.mods.bootstraplauncher.BootstrapLauncher",
                "net.minecraftforge:forge:1.20.1-47.2.0",
                includeFabricModMetadata = true,
            )

            val candidates = LocalModpackScanner(
                installationStore = Store(listOf(installation)),
                managedMinecraftDirectory = root.resolve("managed"),
            ).scan().getOrThrow()

            assertEquals(listOf("renamed-forge"), candidates.map(LocalModpackCandidate::name))
            assertEquals(ModLoader.forge, candidates.single().modLoader)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `picked folders find isolated versions, shared root mods, and single version folders`(): Unit = runBlocking {
        val root = Files.createTempDirectory("rdi-local-pick")
        try {
            val scanner = LocalModpackScanner(Store(emptyList()), root.resolve("managed"))
            val installation = root.resolve(".minecraft")
            val isolated = installation.resolve("versions/isolated")
            createInstance(isolated, "cpw.mods.bootstraplauncher.BootstrapLauncher", "net.minecraftforge:forge:1.20.1-47.2.0", false)
            val shared = installation.resolve("versions/shared")
            Files.createDirectories(shared)
            Files.writeString(
                shared.resolve("shared.json"),
                """{"id":"shared","inheritsFrom":"1.21.1","mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher","libraries":[{"name":"net.neoforged:neoforge:21.1.100"}]}""",
            )
            Files.createDirectories(installation.resolve("mods"))
            Files.write(installation.resolve("mods/root.jar"), byteArrayOf(1))
            val oldVersion = installation.resolve("versions/old")
            Files.createDirectories(oldVersion.resolve("mods"))
            Files.writeString(oldVersion.resolve("old.json"), """{"id":"1.16.5","mainClass":"cpw.mods.modlauncher.Launcher"}""")
            Files.write(oldVersion.resolve("mods/a.jar"), byteArrayOf(1))

            val fromRoot = scanner.scanDirectory(installation).getOrThrow().associateBy(LocalModpackCandidate::name)
            val fromVersion = scanner.scanDirectory(isolated).getOrThrow()
            val bare = scanner.scanDirectory(root).getOrThrow()

            assertEquals(setOf("isolated", "shared"), fromRoot.keys)
            assertEquals(isolated, fromRoot.getValue("isolated").gameDirectory)
            assertEquals(installation, fromRoot.getValue("shared").gameDirectory)
            assertEquals(ModLoader.neoforge, fromRoot.getValue("shared").modLoader)
            assertEquals(McVersion.V211, fromRoot.getValue("shared").mcVersion)
            assertEquals(listOf("isolated"), fromVersion.map(LocalModpackCandidate::name))
            assertTrue(bare.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun createInstance(
        directory: Path,
        mainClass: String,
        library: String,
        includeFabricModMetadata: Boolean,
    ) {
        Files.createDirectories(directory.resolve("mods"))
        Files.writeString(
            directory.resolve("${directory.fileName}.json"),
            """{"id":"1.20.1","mainClass":"$mainClass","libraries":[{"name":"$library"}]}""",
        )
        Files.write(directory.resolve("mods/example.jar"), byteArrayOf(1))
        if (includeFabricModMetadata) {
            Files.writeString(directory.resolve("mods/fabric.mod.json"), "{}")
        }
    }

    private class Store(private val paths: List<Path>) : MinecraftInstallationStore {
        override suspend fun list(): Result<List<MinecraftInstallationRecord>> = Result.success(
            paths.map { MinecraftInstallationRecord(it, 0, 0) },
        )

        override suspend fun upsert(path: Path, seenAt: Long): Result<Unit> = error("unused")
        override suspend fun delete(path: Path): Result<Unit> = error("unused")
        override suspend fun lastFullScanAt(): Result<Long?> = error("unused")
        override suspend fun setLastFullScanAt(scannedAt: Long): Result<Unit> = error("unused")
        override fun close() = Unit
    }
}
