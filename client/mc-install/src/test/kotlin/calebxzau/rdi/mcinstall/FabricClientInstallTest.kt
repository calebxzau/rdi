package calebxzau.rdi.mcinstall

import calebxzau.rdi.mclaunch.ClientLoaderRuntime
import calebxzau.rdi.mclaunch.MinecraftArtifactDownloader
import calebxzau.rdi.mclaunch.MinecraftDownloadProgress
import calebxzau.rdi.mclaunch.MinecraftLaunchLibraryPreparer
import calebxzau.rdi.mclaunch.model.MojangLibrary
import calebxzau.rdi.mclaunch.model.MojangVersionManifest
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.serdesJson
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FabricClientInstallTest {
    private val runtime = ClientLoaderRuntime.resolve(McVersion.V201, ModLoader.Fabric)
    private val artifactBytes = mapOf(
        "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar" to "loader-jar".toByteArray(),
        "https://maven.fabricmc.net/net/fabricmc/intermediary/1.20.1/intermediary-1.20.1.jar" to "intermediary-jar".toByteArray(),
    )

    @Test
    fun fetchesOfficialProfileAndMissingSidecarChecksumsThenPublishesVerifiedProfile(): Unit = runBlocking {
        val fetchCount = AtomicInteger()
        val metadata = metadataFetcher { url ->
            fetchCount.incrementAndGet()
            when {
                url == runtime.profileUrl -> serdesJson.encodeToString(profileWithMissingChecksums())
                else -> artifactBytes.getValue(url.removeSuffix(".sha1")).sha1().toByteArray().decodeToString()
            }
        }
        val downloader = downloader(artifactBytes)
        val install = createTestMcInstall(fabricMetadataFetcher = metadata, fabricArtifactDownloader = downloader)

        val result = install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {})

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString())
        assertEquals(3, fetchCount.get())
        val saved = runtime.manifestFile(install.versionListDir)
        assertTrue(saved.isFile)
        val manifest = serdesJson.decodeFromString<MojangVersionManifest>(saved.readText())
        assertTrue(manifest.libraries.all { it.mainArtifactForTest()?.sha1?.matches(Regex("[0-9a-f]{40}")) == true })
        assertTrue(artifactBytes.all { (url, bytes) ->
            val libraryPath = url.removePrefix("https://maven.fabricmc.net/")
            install.environment.directories.librariesDir.resolve(libraryPath).readBytes().contentEquals(bytes)
        })
    }

    @Test
    fun completeProfileAndLibrariesAreReusedWithoutNetwork(): Unit = runBlocking {
        val profile = profileWithResolvedChecksums()
        val install = createTestMcInstall(
            fabricMetadataFetcher = { Result.failure(IllegalStateException("offline profile must be reused")) },
            fabricArtifactDownloader = downloader(artifactBytes),
        )
        publishFixture(install, profile)

        val result = install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {})

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString())
        assertTrue(artifactBytes.all { (url, bytes) ->
            val path = url.removePrefix("https://maven.fabricmc.net/")
            install.environment.directories.librariesDir.resolve(path).readBytes().contentEquals(bytes)
        })
    }

    @Test
    fun corruptLibraryIsRepairedFromPersistedProfileWithoutFetchingMetadata(): Unit = runBlocking {
        val profile = profileWithResolvedChecksums()
        val install = createTestMcInstall(
            fabricMetadataFetcher = { Result.failure(IllegalStateException("persisted profile should be enough")) },
            fabricArtifactDownloader = downloader(artifactBytes),
        )
        publishFixture(install, profile)
        val broken = install.environment.directories.librariesDir.resolve(
            "net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar",
        )
        Files.writeString(broken.toPath(), "corrupt")

        val result = install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {})

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString())
        assertTrue(broken.readBytes().contentEquals(artifactBytes.getValue("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar")))
    }

    @Test
    fun profilePreparationSkipsLibraryHashingAndLeavesRepairForLaunchPreparation(): Unit = runBlocking {
        val profile = profileWithResolvedChecksums()
        val install = createTestMcInstall(
            fabricMetadataFetcher = { Result.failure(IllegalStateException("persisted profile should be reused")) },
            fabricArtifactDownloader = downloader(artifactBytes),
        )
        publishFixture(install, profile)
        val broken = install.environment.directories.librariesDir.resolve(
            "net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar",
        )
        Files.writeString(broken.toPath(), "corrupt")

        val profileResult = install.ensureDesktopLaunchProfile(McVersion.V201, ModLoader.Fabric, {})

        assertTrue(profileResult.isSuccess, profileResult.exceptionOrNull()?.stackTraceToString())
        assertEquals("corrupt", broken.readText())

        val libraryPreparer = MinecraftLaunchLibraryPreparer(
            librariesDir = install.environment.directories.librariesDir,
            downloader = downloader(artifactBytes),
        )
        val launchResult = libraryPreparer.ensure(emptyList(), profile.libraries, {})

        assertTrue(launchResult.isSuccess, launchResult.exceptionOrNull()?.stackTraceToString())
        assertTrue(broken.readBytes().contentEquals(artifactBytes.getValue("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar")))
    }

    @Test
    fun missingOrInvalidProfileFallsBackToFullFabricInstall(): Unit = runBlocking {
        val fetchCount = AtomicInteger()
        val install = createTestMcInstall(
            fabricMetadataFetcher = metadataFetcher { url ->
                fetchCount.incrementAndGet()
                when {
                    url == runtime.profileUrl -> serdesJson.encodeToString(profileWithMissingChecksums())
                    else -> artifactBytes.getValue(url.removeSuffix(".sha1")).sha1()
                }
            },
            fabricArtifactDownloader = downloader(artifactBytes),
        )
        val invalidProfile = profileWithResolvedChecksums().copy(id = "fabric-loader-invalid")
        val profileFile = runtime.manifestFile(install.versionListDir)
        profileFile.parentFile.mkdirs()
        profileFile.writeText(serdesJson.encodeToString(invalidProfile))

        val result = install.ensureDesktopLaunchProfile(McVersion.V201, ModLoader.Fabric, {})

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString())
        assertEquals(3, fetchCount.get())
        assertEquals(runtime.profileId, serdesJson.decodeFromString<MojangVersionManifest>(profileFile.readText()).id)
        assertTrue(artifactBytes.all { (url, bytes) ->
            install.environment.directories.librariesDir
                .resolve(url.removePrefix("https://maven.fabricmc.net/"))
                .readBytes()
                .contentEquals(bytes)
        })
    }

    @Test
    fun profilePreparationPreservesCancellation(): Unit = runBlocking {
        val install = createTestMcInstall()

        assertFailsWith<CancellationException> {
            install.ensureDesktopLaunchProfile(
                McVersion.V201,
                ModLoader.Fabric,
                {},
                isCancelled = { true },
            )
        }
    }

    @Test
    fun fabricLibraryDownloadProgressIncludesExistingDownloadSpeed(): Unit = runBlocking {
        val profile = profileWithResolvedChecksums()
        val progressMessages = mutableListOf<String>()
        val reportingDownloader = MinecraftArtifactDownloader { _, artifact, target, reportProgress ->
            runCatching {
                Files.write(target.toPath(), artifactBytes.getValue(artifact.url))
                reportProgress(MinecraftDownloadProgress(512, -1, -1f, speedBytesPerSecond = 2048.0))
                target
            }
        }
        val install = createTestMcInstall(
            fabricMetadataFetcher = { Result.failure(IllegalStateException("persisted profile should be reused")) },
            fabricArtifactDownloader = reportingDownloader,
        )
        publishFixture(install, profile)
        val broken = install.environment.directories.librariesDir.resolve(
            "net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar",
        )
        broken.delete()

        val result = install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, progressMessages::add)

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString())
        assertTrue(progressMessages.any { it.contains("2.0KB/s") && it.contains("未知") }, progressMessages.joinToString("\n"))
    }

    @Test
    fun wrongArtifactHashFailsWithoutPublishingProfile(): Unit = runBlocking {
        val install = createTestMcInstall(
            fabricMetadataFetcher = metadataFetcher { url ->
                if (url == runtime.profileUrl) serdesJson.encodeToString(profileWithMissingChecksums())
                else "0".repeat(40)
            },
            fabricArtifactDownloader = downloader(artifactBytes),
        )

        val result = install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {})

        assertTrue(result.isFailure)
        assertFalse(runtime.manifestFile(install.versionListDir).exists())
    }

    @Test
    fun concurrentInstallsShareMetadataAndArtifactWork(): Unit = runBlocking {
        val fetchCount = AtomicInteger()
        val downloadCount = AtomicInteger()
        val metadata = metadataFetcher { url ->
            fetchCount.incrementAndGet()
            delay(25)
            when {
                url == runtime.profileUrl -> serdesJson.encodeToString(profileWithMissingChecksums())
                else -> artifactBytes.getValue(url.removeSuffix(".sha1")).sha1()
            }
        }
        val baseDownloader = downloader(artifactBytes)
        val countedDownloader = MinecraftArtifactDownloader { label, artifact, target, progress ->
            downloadCount.incrementAndGet()
            baseDownloader.download(label, artifact, target, progress)
        }
        val install = createTestMcInstall(fabricMetadataFetcher = metadata, fabricArtifactDownloader = countedDownloader)

        val first = async { install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {}) }
        val second = async { install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {}) }
        val results = listOf(first.await(), second.await())

        assertTrue(results.all { it.isSuccess })
        assertEquals(3, fetchCount.get())
        assertEquals(2, downloadCount.get())
    }

    @Test
    fun invalidProfileIdentityIsRejectedBeforeLibrariesAreDownloaded(): Unit = runBlocking {
        val downloaderCount = AtomicInteger()
        val downloader = MinecraftArtifactDownloader { _, _, target, _ ->
            downloaderCount.incrementAndGet()
            Result.success(target)
        }
        val wrongProfile = profileWithMissingChecksums().copy(id = "fabric-loader-0.19.5-1.21.1")
        val install = createTestMcInstall(
            fabricMetadataFetcher = { Result.success(serdesJson.encodeToString(wrongProfile)) },
            fabricArtifactDownloader = downloader,
        )

        val result = install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {})

        assertTrue(result.isFailure)
        assertEquals(0, downloaderCount.get())
    }

    @Test
    fun concurrentOwnerFailureReachesWaiterAndLaterRetrySucceeds(): Unit = runBlocking {
        val badChecksums = java.util.concurrent.atomic.AtomicBoolean(true)
        val profileRequested = CompletableDeferred<Unit>()
        val releaseProfile = CompletableDeferred<Unit>()
        val metadata: suspend (String) -> Result<String> = { url ->
            if (url == runtime.profileUrl) {
                profileRequested.complete(Unit)
                releaseProfile.await()
                Result.success(serdesJson.encodeToString(profileWithMissingChecksums()))
            } else if (badChecksums.get()) {
                Result.success("0".repeat(40))
            } else {
                Result.success(artifactBytes.getValue(url.removeSuffix(".sha1")).sha1())
            }
        }
        val install = createTestMcInstall(fabricMetadataFetcher = metadata, fabricArtifactDownloader = downloader(artifactBytes))

        val owner = async { install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {}) }
        profileRequested.await()
        val waiter = async { install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {}) }
        delay(25)
        releaseProfile.complete(Unit)
        assertTrue(owner.await().isFailure)
        assertTrue(waiter.await().isFailure)
        assertFalse(runtime.manifestFile(install.versionListDir).exists())

        badChecksums.set(false)
        val retry = install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {})
        assertTrue(retry.isSuccess, retry.exceptionOrNull()?.stackTraceToString())
    }

    @Test
    fun ownerCancellationReleasesWaiterAndAllowsRetry(): Unit = runBlocking {
        val profileRequested = CompletableDeferred<Unit>()
        val profileRequestCount = AtomicInteger()
        val metadata: suspend (String) -> Result<String> = { url ->
            if (url == runtime.profileUrl && profileRequestCount.incrementAndGet() == 1) {
                profileRequested.complete(Unit)
                delay(Long.MAX_VALUE)
                Result.success(serdesJson.encodeToString(profileWithMissingChecksums()))
            } else if (url == runtime.profileUrl) {
                Result.success(serdesJson.encodeToString(profileWithMissingChecksums()))
            } else {
                Result.success(artifactBytes.getValue(url.removeSuffix(".sha1")).sha1())
            }
        }
        val install = createTestMcInstall(fabricMetadataFetcher = metadata, fabricArtifactDownloader = downloader(artifactBytes))

        val owner = async { install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {}) }
        profileRequested.await()
        val waiter = async { install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {}) }
        delay(25)
        owner.cancel()
        runCatching { owner.await() }
        assertTrue(waiter.await().isFailure)
        assertFalse(runtime.manifestFile(install.versionListDir).exists())

        val retry = install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {})
        assertTrue(retry.isSuccess, retry.exceptionOrNull()?.stackTraceToString())
    }

    @Test
    fun cancellingWaiterDoesNotCancelOwner(): Unit = runBlocking {
        val profileRequested = CompletableDeferred<Unit>()
        val releaseProfile = CompletableDeferred<Unit>()
        val metadata: suspend (String) -> Result<String> = { url ->
            if (url == runtime.profileUrl) {
                profileRequested.complete(Unit)
                releaseProfile.await()
                Result.success(serdesJson.encodeToString(profileWithMissingChecksums()))
            } else {
                Result.success(artifactBytes.getValue(url.removeSuffix(".sha1")).sha1())
            }
        }
        val install = createTestMcInstall(fabricMetadataFetcher = metadata, fabricArtifactDownloader = downloader(artifactBytes))

        val owner = async { install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {}) }
        profileRequested.await()
        val waiter = async { install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {}) }
        delay(25)
        waiter.cancel()
        runCatching { waiter.await() }
        releaseProfile.complete(Unit)

        assertTrue(owner.await().isSuccess)
        assertTrue(runtime.manifestFile(install.versionListDir).isFile)
    }

    @Test
    fun callbackCancellationAfterDownloadDoesNotPromoteLibraryOrProfile(): Unit = runBlocking {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val wrongTimingDownloader = MinecraftArtifactDownloader { _, artifact, target, _ ->
            val bytes = artifactBytes.getValue(artifact.url)
            Files.write(target.toPath(), bytes)
            cancelled.set(true)
            Result.success(target)
        }
        val install = createTestMcInstall(
            fabricMetadataFetcher = metadataFetcher { url ->
                when {
                    url == runtime.profileUrl -> serdesJson.encodeToString(profileWithMissingChecksums())
                    else -> artifactBytes.getValue(url.removeSuffix(".sha1")).sha1()
                }
            },
            fabricArtifactDownloader = wrongTimingDownloader,
        )

        assertFailsWith<CancellationException> {
            install.ensureDesktopLaunchLoader(
                McVersion.V201,
                ModLoader.Fabric,
                {},
                isCancelled = { cancelled.get() },
            )
        }

        assertFalse(runtime.manifestFile(install.versionListDir).exists())
        assertFalse(install.environment.directories.librariesDir.resolve("net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar").exists())
    }

    @Test
    fun malformedProfileIsRefetchedAndRepaired(): Unit = runBlocking {
        val install = createTestMcInstall(
            fabricMetadataFetcher = metadataFetcher { url ->
                when {
                    url == runtime.profileUrl -> serdesJson.encodeToString(profileWithMissingChecksums())
                    else -> artifactBytes.getValue(url.removeSuffix(".sha1")).sha1()
                }
            },
            fabricArtifactDownloader = downloader(artifactBytes),
        )
        val path = runtime.manifestFile(install.versionListDir)
        path.parentFile.mkdirs()
        path.writeText("{ malformed")

        val result = install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {})

        assertTrue(result.isSuccess, result.exceptionOrNull()?.stackTraceToString())
        assertEquals(runtime.profileId, serdesJson.decodeFromString<MojangVersionManifest>(path.readText()).id)
    }

    @Test
    fun failedRepairPreservesOldProfileAndAlreadyValidLibraries(): Unit = runBlocking {
        val profile = profileWithResolvedChecksums()
        val wrongBytesDownloader = MinecraftArtifactDownloader { _, _, target, _ ->
            Files.writeString(target.toPath(), "wrong replacement")
            Result.success(target)
        }
        val install = createTestMcInstall(
            fabricMetadataFetcher = { Result.failure(IllegalStateException("persisted profile must be reused")) },
            fabricArtifactDownloader = wrongBytesDownloader,
        )
        publishFixture(install, profile)
        val profileFile = runtime.manifestFile(install.versionListDir)
        val originalProfileBytes = profileFile.readBytes()
        val validLibrary = install.environment.directories.librariesDir.resolve(
            "net/fabricmc/intermediary/1.20.1/intermediary-1.20.1.jar",
        )
        val originalLibraryBytes = validLibrary.readBytes()
        val missingLibrary = install.environment.directories.librariesDir.resolve(
            "net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar",
        )
        missingLibrary.delete()

        val result = install.ensureDesktopLaunchLoader(McVersion.V201, ModLoader.Fabric, {})

        assertTrue(result.isFailure)
        assertTrue(profileFile.readBytes().contentEquals(originalProfileBytes))
        assertTrue(validLibrary.readBytes().contentEquals(originalLibraryBytes))
        assertFalse(missingLibrary.exists())
    }

    @Test
    fun automaticLaunchPreparationForwardsSelectedLoader(): Unit = runBlocking {
        val forwardedLoader = AtomicReference<ModLoader?>()
        val install = createTestMcInstall(
            launchPreparer = McLaunchPreparer { _, loader, _, _, _ ->
                forwardedLoader.set(loader)
                Result.failure(IllegalStateException("stop before assets stage"))
            },
        )
        publishFixture(install, profileWithResolvedChecksums())

        val result = install.prepareForLaunch(
            McLaunchPreparationRequest(McVersion.V201, ModLoader.Fabric, "instance-id"),
            onProgress = {},
        )

        assertTrue(result.isFailure)
        assertEquals(ModLoader.Fabric, forwardedLoader.get())
    }

    private fun profileWithMissingChecksums(): MojangVersionManifest = MojangVersionManifest(
        id = runtime.profileId,
        mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
        inheritsFrom = "1.20.1",
        libraries = listOf(
            MojangLibrary(name = "net.fabricmc:fabric-loader:0.19.5", url = "https://maven.fabricmc.net/"),
            MojangLibrary(name = "net.fabricmc:intermediary:1.20.1", url = "https://maven.fabricmc.net/"),
        ),
    )

    private fun profileWithResolvedChecksums(): MojangVersionManifest = profileWithMissingChecksums().copy(
        libraries = profileWithMissingChecksums().libraries.map { library ->
            val artifactUrl = library.url!!.trimEnd('/') + "/" + library.name.split(':').let { parts ->
                "${parts[0].replace('.', '/')}/${parts[1]}/${parts[2]}/${parts[1]}-${parts[2]}.jar"
            }
            val relativePath = artifactUrl.removePrefix("https://maven.fabricmc.net/")
            val bytes = artifactBytes.getValue(artifactUrl)
            library.copy(
                downloads = library.downloads.copy(
                    artifact = calebxzau.rdi.mclaunch.model.MojangDownloadArtifact(
                        sha1 = bytes.sha1(),
                        size = bytes.size.toLong(),
                        url = artifactUrl,
                        path = relativePath,
                    ),
                ),
            )
        },
    )

    private fun metadataFetcher(response: suspend (String) -> String): suspend (String) -> Result<String> =
        { url -> runCatching { response(url) } }

    private fun downloader(bytesByUrl: Map<String, ByteArray>): MinecraftArtifactDownloader =
        MinecraftArtifactDownloader { _, artifact, target, progress ->
            runCatching {
                val bytes = bytesByUrl.getValue(artifact.url)
                Files.write(target.toPath(), bytes)
                progress(MinecraftDownloadProgress(bytes.size.toLong(), bytes.size.toLong(), 1f))
                target
            }
        }

    private fun publishFixture(install: McInstall, profile: MojangVersionManifest) {
        profile.libraries.forEach { library ->
            val artifact = checkNotNull(library.mainArtifactForTest())
            val file = install.environment.directories.librariesDir.resolve(checkNotNull(artifact.path))
            file.parentFile.mkdirs()
            file.writeBytes(artifactBytes.getValue(artifact.url))
        }
        val manifestFile = runtime.manifestFile(install.versionListDir)
        manifestFile.parentFile.mkdirs()
        manifestFile.writeText(serdesJson.encodeToString(profile))
    }

    private fun MojangLibrary.mainArtifactForTest() =
        this.downloads.artifact

    private fun ByteArray.sha1(): String = MessageDigest.getInstance("SHA-1").digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }
}
