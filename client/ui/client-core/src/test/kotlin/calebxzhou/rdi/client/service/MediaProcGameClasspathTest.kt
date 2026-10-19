package calebxzhou.rdi.client.service

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.lang.module.ModuleFinder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaProcGameClasspathTest {
    @Test
    fun resolvesMinimalWindowsRuntime(@TempDir tempDir: Path) {
        assumeWindowsX64()
        val runtime = MediaProcGameClasspath.resolve(nativeRoot = tempDir.toFile()).getOrThrow()
        val files = runtime.classpath

        assertEquals(5, files.size)
        assertTrue(files.any { it.name.startsWith("javacv-1.5.13") })
        assertTrue(files.any { it.name == "javacpp-1.5.13.jar" })
        assertTrue(files.any { it.name == "ffmpeg-8.0.1-1.5.13.jar" })
        assertTrue(files.any { it.name == "ffmpeg-8.0.1-1.5.13-windows-x86_64-gpl.jar" })
        assertEquals(1, files.count { it.name.startsWith("mediaproc") })
        assertTrue(files.none { it.name.startsWith("kotlinx-coroutines-core") })
        assertTrue(runtime.nativeLibraryDir.resolve("jniavutil.dll").isFile)

        val javaCv = files.single { it.name.startsWith("javacv-1.5.13") }
        val javaCvRequires = ModuleFinder.of(javaCv.toPath())
            .find("org.bytedeco.javacv")
            .orElseThrow()
            .descriptor()
            .requires()
            .map { it.name() }
            .toSet()
        assertEquals(
            setOf("java.base", "java.desktop", "org.bytedeco.javacpp", "org.bytedeco.ffmpeg"),
            javaCvRequires
        )
    }

    private fun assumeWindowsX64() {
        val osName = System.getProperty("os.name").orEmpty()
        val osArch = System.getProperty("os.arch").orEmpty().lowercase()
        assumeTrue(osName.contains("windows", ignoreCase = true))
        assumeTrue(osArch == "amd64" || osArch == "x86_64")
    }
}
