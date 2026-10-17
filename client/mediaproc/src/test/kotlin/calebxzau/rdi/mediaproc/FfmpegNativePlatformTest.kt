package calebxzau.rdi.mediaproc

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfmpegNativePlatformTest {
    private val windows = FfmpegNativePlatform("windows-x86_64-gpl")
    private val linux = FfmpegNativePlatform("linux-x86_64-gpl")
    private val mac = FfmpegNativePlatform("macosx-arm64-gpl")

    @Test
    fun namesFollowTheBytedecoJarLayout() {
        assertEquals("org/bytedeco/ffmpeg/linux-x86_64-gpl/", linux.jarPrefix)
        assertEquals("jniavutil.dll", windows.avutilJniLibrary)
        assertEquals("libjniavutil.so", linux.avutilJniLibrary)
        assertEquals("libjniavutil.dylib", mac.avutilJniLibrary)
        assertEquals("ffmpeg.exe", windows.ffmpegExecutable)
        assertEquals("ffmpeg", linux.ffmpegExecutable)
        assertEquals("ffmpeg", mac.ffmpegExecutable)
    }

    @Test
    fun eagainFollowsThePlatformErrno() {
        assertEquals(-11, windows.averrorEagain)
        assertEquals(-11, linux.averrorEagain)
        assertEquals(-35, mac.averrorEagain)
    }

    @Test
    fun nativeJarNamesMatchOnlyTheirOwnPlatform() {
        assertTrue(linux.isNativeJarName("ffmpeg-8.0.1-1.5.13-linux-x86_64-gpl.jar"))
        assertFalse(linux.isNativeJarName("ffmpeg-8.0.1-1.5.13-windows-x86_64-gpl.jar"))
        assertFalse(linux.isNativeJarName("ffmpeg-8.0.1-1.5.13.jar"))
        assertTrue(mac.isNativeJarName("ffmpeg-8.0.1-1.5.13-macosx-arm64-gpl.jar"))
        assertFalse(mac.isNativeJarName("javacpp-1.5.13-macosx-arm64.jar"))
    }

    @Test
    fun currentPlatformMatchesThisJvm() {
        val osName = System.getProperty("os.name").lowercase()
        val current = FfmpegNativePlatform.current
        assertEquals(osName.contains("win"), current.isWindows)
        assertEquals(osName.contains("mac"), current.isMac)
        assertTrue(current.classifier.endsWith("-gpl"))
    }

    @Test
    fun extractsLinuxLayoutAndMarksExecutables(@TempDir tempDir: Path) {
        val nativeJar = tempDir.resolve("ffmpeg-test-linux-x86_64-gpl.jar").toFile()
        JarOutputStream(nativeJar.outputStream()).use { jar ->
            listOf("libjniavutil.so", "libavutil.so.60", "ffmpeg").forEach { name ->
                jar.putNextEntry(JarEntry("org/bytedeco/ffmpeg/linux-x86_64-gpl/${name}"))
                jar.write(name.toByteArray())
                jar.closeEntry()
            }
        }

        val bundle = MediaProcNativeRuntime.prepare(nativeJar, tempDir.resolve("runtime").toFile(), linux).getOrThrow()

        assertContentEquals("libavutil.so.60".toByteArray(), bundle.resolve("libavutil.so.60").readBytes())
        assertTrue(bundle.resolve("ffmpeg").canExecute())
    }

    @Test
    fun windowsLayoutIsIncompleteForLinux(@TempDir tempDir: Path) {
        val nativeJar = tempDir.resolve("ffmpeg-test-windows-x86_64-gpl.jar").toFile()
        JarOutputStream(nativeJar.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("org/bytedeco/ffmpeg/windows-x86_64-gpl/jniavutil.dll"))
            jar.write(byteArrayOf(1))
            jar.closeEntry()
        }

        val result = MediaProcNativeRuntime.prepare(nativeJar, tempDir.resolve("runtime").toFile(), linux)

        assertTrue(result.isFailure)
    }

    @Test
    fun classpathLookupIgnoresOtherPlatformsJars(@TempDir tempDir: Path) {
        val windowsJar = tempDir.resolve("ffmpeg-8.0.1-1.5.13-windows-x86_64-gpl.jar").toFile()
        val linuxJar = tempDir.resolve("ffmpeg-8.0.1-1.5.13-linux-x86_64-gpl.jar").toFile()
        listOf(windowsJar, linuxJar).forEach { JarOutputStream(it.outputStream()).use { } }
        val classpath = listOf(windowsJar, linuxJar).joinToString(java.io.File.pathSeparator) { it.absolutePath }

        assertEquals(linuxJar, MediaProcRuntimeClasspath.findNativeJar(classpath, emptyList(), linux))
        assertEquals(windowsJar, MediaProcRuntimeClasspath.findNativeJar(classpath, emptyList(), windows))
        assertNull(MediaProcRuntimeClasspath.findNativeJar(classpath, emptyList(), mac))
    }
}
