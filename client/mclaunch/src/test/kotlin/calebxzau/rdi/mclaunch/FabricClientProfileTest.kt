package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzau.rdi.mclaunch.model.MojangDownloadArtifact
import calebxzau.rdi.mclaunch.model.MojangLibrary
import kotlin.test.Test
import kotlin.test.assertTrue

class FabricClientProfileTest {
    private val runtime = ClientLoaderRuntime.resolve(McVersion.V201, ModLoader.Fabric)

    @Test
    fun `official profile structure with resolved checksums is accepted`() {
        assertTrue(FabricClientProfile.validate(validFabricManifest(), runtime, requireChecksums = true).isSuccess)
        assertTrue(FabricClientProfile.validate(validFabricManifest().copy(jar = "1.20.1"), runtime).isSuccess)
    }

    @Test
    fun `wrong identity and unexpected runtime metadata are rejected`() {
        val profile = validFabricManifest()
        listOf(
            profile.copy(id = "other"),
            profile.copy(inheritsFrom = "1.20.4"),
            profile.copy(mainClass = "net.minecraftforge.client.loading.ClientModLoader"),
            profile.copy(jar = "other-runtime"),
            profile.copy(libraries = profile.libraries.filterNot { it.name.startsWith("net.fabricmc:intermediary:") }),
            profile.copy(libraries = profile.libraries + profile.libraries.first()),
        ).forEach { assertTrue(FabricClientProfile.validate(it, runtime).isFailure) }
    }

    @Test
    fun `unsafe coordinates malformed checksums duplicates and competing loaders are rejected`() {
        val profile = validFabricManifest()
        val baseLibraries = profile.libraries
        val unsafe = MojangLibrary("../escape:artifact:1", url = "https://maven.fabricmc.net/", sha1 = "c".repeat(40))
        val missingHash = baseLibraries.first().copy(sha1 = "")
        val malformedHash = baseLibraries.first().copy(sha1 = "xyz")
        val conflictingArtifact = MojangLibrary(
            "org.example:artifact:1.0.0",
            url = "https://maven.fabricmc.net/",
            sha1 = "c".repeat(40),
        )
        val conflictingArtifactNewVersion = conflictingArtifact.copy(name = "org.example:artifact:2.0.0")
        val forge = MojangLibrary(
            "net.minecraftforge:forge:1.20.1-47.4.20",
            url = "https://maven.fabricmc.net/",
            sha1 = "d".repeat(40),
        )
        val unsafeArtifactPath = baseLibraries.first().copy(
            downloads = calebxzau.rdi.mclaunch.model.MojangLibraryDownloads(
                artifact = MojangDownloadArtifact(
                    sha1 = "a".repeat(40),
                    url = "https://maven.fabricmc.net/../escape.jar",
                    path = "../escape.jar",
                )
            ),
        )

        listOf(
            profile.copy(libraries = baseLibraries + unsafe),
            profile.copy(libraries = listOf(missingHash) + baseLibraries.drop(1)),
            profile.copy(libraries = listOf(malformedHash) + baseLibraries.drop(1)),
            profile.copy(libraries = baseLibraries + conflictingArtifact + conflictingArtifactNewVersion),
            profile.copy(libraries = baseLibraries + forge),
            profile.copy(libraries = listOf(unsafeArtifactPath) + baseLibraries.drop(1)),
        ).forEach { assertTrue(FabricClientProfile.validate(it, runtime, requireChecksums = true).isFailure) }
    }
}
