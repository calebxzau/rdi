package calebxzau.rdi.mediaproc

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaProcRuntimeClasspathTest {
    @Test
    fun findsNativeJarFromClasspathWrapper(@TempDir tempDir: Path) {
        val nativeJar = tempDir.resolve("ffmpeg-test-windows-x86_64-gpl.jar").toFile()
        JarOutputStream(nativeJar.outputStream()).use { }
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = nativeJar.toURI().toString()
        }
        val classpathWrapper = tempDir.resolve("gradle-javaexec-classpath.jar").toFile()
        JarOutputStream(classpathWrapper.outputStream(), manifest).use { }

        val resolved = MediaProcRuntimeClasspath.findNativeJar(
            classpath = classpathWrapper.absolutePath,
            codeSources = emptyList(),
            platform = FfmpegNativePlatform("windows-x86_64-gpl"),
        )

        assertEquals(nativeJar.canonicalFile, resolved?.canonicalFile)
    }
}
