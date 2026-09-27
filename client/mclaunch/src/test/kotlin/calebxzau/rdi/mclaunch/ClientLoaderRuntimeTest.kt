package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.supportLoader
import calebxzhou.rdi.common.serdesJson
import calebxzau.rdi.mclaunch.model.MojangDownloadArtifact
import calebxzau.rdi.mclaunch.model.MojangLibrary
import calebxzau.rdi.mclaunch.model.MojangLibraryDownloads
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientLoaderRuntimeTest {
    @Test
    fun `fabric runtime is explicit while common configured loader gate stays unchanged`() {
        val runtime = ClientLoaderRuntime.resolve(McVersion.V201, ModLoader.Fabric)

        assertEquals("fabric-loader-0.19.5-1.20.1", runtime.profileId)
        assertEquals("0.19.5", runtime.loaderVersion)
        assertEquals(
            "https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.19.5/profile/json",
            runtime.profileUrl,
        )
        assertFalse(McVersion.V201.supportLoader(ModLoader.Fabric))
        assertEquals("1.20.1-forge-47.4.20", ClientLoaderRuntime.resolve(McVersion.V201, ModLoader.forge).profileId)
        assertTrue(runCatching { ClientLoaderRuntime.resolve(McVersion.V211, ModLoader.Fabric) }.isFailure)
    }

    @Test
    fun `runtime manifest lookup selects selected loader profile for arbitrary instance ids`() {
        val root = Files.createTempDirectory("rdi-runtime-profile-test").toFile()
        try {
            val fabric = ClientLoaderRuntime.resolve(McVersion.V201, ModLoader.Fabric)
            val forge = ClientLoaderRuntime.resolve(McVersion.V201, ModLoader.forge)
            val fabricManifest = validFabricManifest()
            val forgeManifest = fabricManifest.copy(
                id = forge.profileId,
                mainClass = "net.minecraftforge.client.loading.ClientModLoader",
                libraries = listOf(MojangLibrary("net.minecraftforge:forge:1.20.1-47.4.20", url = "https://maven.fabricmc.net/")),
            )
            fabric.manifestFile(root).apply { parentFile.mkdirs(); writeText(serdesJson.encodeToString(fabricManifest)) }
            forge.manifestFile(root).apply { parentFile.mkdirs(); writeText(serdesJson.encodeToString(forgeManifest)) }

            assertEquals(fabric.profileId, fabric.loadManifest(root).getOrThrow().id)
            assertEquals(forge.profileId, forge.loadManifest(root).getOrThrow().id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `fabric artifact uses explicit download metadata before legacy metadata`() {
        val explicit = MojangDownloadArtifact(
            sha1 = "a".repeat(40),
            size = 12,
            url = "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar",
            path = "net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar",
        )
        val library = MojangLibrary(
            name = "net.fabricmc:fabric-loader:0.19.5",
            downloads = MojangLibraryDownloads(artifact = explicit),
            url = "https://maven.fabricmc.net/",
            sha1 = "b".repeat(40),
            size = 99,
            checksums = listOf("c".repeat(40)),
        )

        assertEquals(explicit, library.mainArtifact())
    }
}

internal fun validFabricManifest() = MojangVersionManifest(
    id = "fabric-loader-0.19.5-1.20.1",
    inheritsFrom = "1.20.1",
    mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
    libraries = listOf(
        MojangLibrary("net.fabricmc:fabric-loader:0.19.5", url = "https://maven.fabricmc.net/", sha1 = "a".repeat(40), size = 1),
        MojangLibrary("net.fabricmc:intermediary:1.20.1", url = "https://maven.fabricmc.net/", sha1 = "b".repeat(40), size = 2),
    ),
)
