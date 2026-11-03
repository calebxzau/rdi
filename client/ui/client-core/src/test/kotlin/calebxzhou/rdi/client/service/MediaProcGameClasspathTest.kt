package calebxzhou.rdi.client.service

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
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

        assertEquals(4, files.size)
        assertTrue(files.none { it.name.startsWith("javacv") })
        assertTrue(files.any { it.name == "javacpp-1.5.13.jar" })
        assertTrue(files.any { it.name == "ffmpeg-8.0.1-1.5.13.jar" })
        assertTrue(files.any { it.name == "ffmpeg-8.0.1-1.5.13-windows-x86_64-gpl.jar" })
        assertEquals(1, files.count { it.name.startsWith("rdi-mediaproc") })
        assertTrue(files.none { it.name.startsWith("kotlinx-coroutines-core") })
        assertTrue(runtime.nativeLibraryDir.resolve("jniavutil.dll").isFile)
    }

    private fun assumeWindowsX64() {
        val osName = System.getProperty("os.name").orEmpty()
        val osArch = System.getProperty("os.arch").orEmpty().lowercase()
        assumeTrue(osName.contains("windows", ignoreCase = true))
        assumeTrue(osArch == "amd64" || osArch == "x86_64")
    }
}
