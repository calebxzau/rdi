package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzau.rdi.mclaunch.model.MojangDownloadArtifact
import calebxzau.rdi.mclaunch.model.MojangLibrary
import calebxzau.rdi.mclaunch.model.MojangLibraryDownloads
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertTrue

class MinecraftLaunchLibraryPreparerTest {
    @Test
    fun repairsMissingLoaderRuntimeLibrary() = runBlocking {
        val root = Files.createTempDirectory("mclaunch-loader-library").toFile()
        try {
            val librariesDir = root.resolve("libraries")
            val artifactPath = "net/neoforged/fancymodloader/loader/4.0.43/loader-4.0.43.jar"
            val progressMessages = mutableListOf<String>()
            val loaderLibrary = MojangLibrary(
                name = "net.neoforged.fancymodloader:loader:4.0.43",
                downloads = MojangLibraryDownloads(
                    artifact = MojangDownloadArtifact(path = artifactPath),
                ),
            )
            val preparer = MinecraftLaunchLibraryPreparer(
                librariesDir = librariesDir,
                downloader = MinecraftArtifactDownloader { _, _, target, reportProgress ->
                    target.parentFile.mkdirs()
                    ZipOutputStream(target.outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("marker"))
                        zip.write(1)
                        zip.closeEntry()
                    }
                    reportProgress(MinecraftDownloadProgress(512, -1, -1f, speedBytesPerSecond = 1024.0))
                    Result.success(target)
                },
            )

            preparer.ensure(
                baseLibraries = emptyList(),
                overrideLibraries = listOf(loaderLibrary),
                onProgress = progressMessages::add,
            ).getOrThrow()

            assertTrue(librariesDir.resolve(artifactPath).isFile)
            assertTrue(progressMessages.any { it.contains("1.0KB/s") && it.contains("未知") }, progressMessages.joinToString("\n"))
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun concurrentPreparersShareRepairAndDoNotDeleteTheRepairedArtifact() = runBlocking {
        val root = Files.createTempDirectory("mclaunch-concurrent-library").toFile()
        try {
            val librariesDir = root.resolve("libraries")
            val artifactPath = "example/library/1.0/library-1.0.jar"
            val artifactBytes = "verified-library-bytes".toByteArray()
            val library = MojangLibrary(
                name = "example:library:1.0",
                downloads = MojangLibraryDownloads(
                    artifact = MojangDownloadArtifact(
                        sha1 = artifactBytes.sha1(),
                        size = artifactBytes.size.toLong(),
                        path = artifactPath,
                    ),
                ),
            )
            val downloadStarted = CompletableDeferred<Unit>()
            val finishDownload = CompletableDeferred<Unit>()
            val downloadCount = AtomicInteger()
            val downloader = MinecraftArtifactDownloader { _, _, target, _ ->
                val attempt = downloadCount.incrementAndGet()
                if (attempt == 1) {
                    downloadStarted.complete(Unit)
                    finishDownload.await()
                }
                runCatching {
                    target.parentFile.mkdirs()
                    target.writeBytes(artifactBytes)
                    target
                }
            }
            val firstPreparer = MinecraftLaunchLibraryPreparer(librariesDir, downloader)
            val secondPreparer = MinecraftLaunchLibraryPreparer(librariesDir, downloader)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                firstPreparer.ensure(emptyList(), listOf(library), {})
            }
            downloadStarted.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                secondPreparer.ensure(emptyList(), listOf(library), {})
            }
            finishDownload.complete(Unit)

            val results = listOf(first.await(), second.await())
            assertTrue(results.all { it.isSuccess }, results.joinToString { it.exceptionOrNull()?.stackTraceToString().orEmpty() })
            assertEquals(1, downloadCount.get())
            assertTrue(librariesDir.resolve(artifactPath).readBytes().contentEquals(artifactBytes))
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun failedRepairKeepsExistingArtifactBytes() = runBlocking {
        val root = Files.createTempDirectory("mclaunch-failed-library-repair").toFile()
        try {
            val librariesDir = root.resolve("libraries")
            val artifactPath = "example/library/1.0/library-1.0.jar"
            val expectedBytes = "expected-library-bytes".toByteArray()
            val existingBytes = "previous-library-bytes".toByteArray()
            val library = MojangLibrary(
                name = "example:library:1.0",
                downloads = MojangLibraryDownloads(
                    artifact = MojangDownloadArtifact(
                        sha1 = expectedBytes.sha1(),
                        size = expectedBytes.size.toLong(),
                        path = artifactPath,
                    ),
                ),
            )
            val target = librariesDir.resolve(artifactPath).apply {
                parentFile.mkdirs()
                writeBytes(existingBytes)
            }
            val preparer = MinecraftLaunchLibraryPreparer(
                librariesDir,
                MinecraftArtifactDownloader { _, _, _, _ ->
                    Result.failure(IllegalStateException("fixture download failed"))
                },
            )

            val result = preparer.ensure(emptyList(), listOf(library), {})

            assertTrue(result.isFailure)
            assertTrue(target.readBytes().contentEquals(existingBytes))
        } finally {
            root.deleteRecursivelyNoSymlink()
        }
    }

    private fun ByteArray.sha1(): String = MessageDigest.getInstance("SHA-1").digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }
}
