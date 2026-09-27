package calebxzau.rdi.mediaproc

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MediaProcNativeRuntimeTest {
    @Test
    fun concurrentColdPreparationSharesNativeBundle(@TempDir tempDir: Path) {
        val nativeJar = tempDir.resolve("ffmpeg-test-windows-x86_64-gpl.jar").toFile()
        val bytes = ByteArray(64 * 1024) { (it * 31).toByte() }
        JarOutputStream(nativeJar.outputStream()).use { jar ->
            repeat(64) { index ->
                val name = if (index == 0) "jniavutil.dll" else "native-${index}.dll"
                jar.putNextEntry(JarEntry("org/bytedeco/ffmpeg/windows-x86_64-gpl/${name}"))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        val workers = Executors.newFixedThreadPool(4)
        val start = CyclicBarrier(4)
        try {
            val preparations = List(4) {
                workers.submit<java.io.File> {
                    start.await(10, TimeUnit.SECONDS)
                    MediaProcNativeRuntime.prepare(nativeJar, tempDir.resolve("runtime").toFile()).getOrThrow()
                }
            }
            val bundles = preparations.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, bundles.distinct().size)
            assertContentEquals(bytes, bundles.first().resolve("jniavutil.dll").readBytes())
            assertContentEquals(bytes, bundles.first().resolve("native-63.dll").readBytes())
        } finally {
            workers.shutdownNow()
            check(workers.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun sameLengthReplacementWithSameTimestampGetsANewBundle(@TempDir tempDir: Path) {
        val nativeJar = tempDir.resolve("ffmpeg-test-windows-x86_64-gpl.jar").toFile()
        fun writeJar(bytes: ByteArray) {
            JarOutputStream(nativeJar.outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("org/bytedeco/ffmpeg/windows-x86_64-gpl/jniavutil.dll").apply {
                    method = JarEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                    time = 0L
                })
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        writeJar(byteArrayOf(1, 2, 3))
        val originalSize = nativeJar.length()
        val originalTime = Files.getLastModifiedTime(nativeJar.toPath())
        val root = tempDir.resolve("runtime").toFile()
        val first = MediaProcNativeRuntime.prepare(nativeJar, root).getOrThrow()

        writeJar(byteArrayOf(3, 2, 1))
        Files.setLastModifiedTime(nativeJar.toPath(), originalTime)
        assertEquals(originalSize, nativeJar.length())
        val second = MediaProcNativeRuntime.prepare(nativeJar, root).getOrThrow()

        assertNotEquals(first, second)
        assertContentEquals(byteArrayOf(1, 2, 3), first.resolve("jniavutil.dll").readBytes())
        assertContentEquals(byteArrayOf(3, 2, 1), second.resolve("jniavutil.dll").readBytes())
    }

    @Test
    fun extractsNativeLibrariesIntoStableBundleDirectory(@TempDir tempDir: Path) {
        val nativeJar = tempDir.resolve("ffmpeg-test-windows-x86_64-gpl.jar").toFile()
        JarOutputStream(nativeJar.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("org/bytedeco/ffmpeg/windows-x86_64-gpl/jniavutil.dll"))
            jar.write(byteArrayOf(1, 2, 3))
            jar.closeEntry()
        }

        val first = MediaProcNativeRuntime.prepare(nativeJar, tempDir.resolve("runtime").toFile()).getOrThrow()
        val second = MediaProcNativeRuntime.prepare(nativeJar, tempDir.resolve("runtime").toFile()).getOrThrow()

        assertEquals(first, second)
        assertContentEquals(byteArrayOf(1, 2, 3), first.resolve("jniavutil.dll").readBytes())
    }
}
