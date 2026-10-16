package calebxzau.rdi.client.ui

import calebxzhou.rdi.common.model.LibraryOsArch
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JdkPathValidationTest {
    private fun fakeJdk(root: File, vararg executables: String): File {
        val bin = root.resolve("bin").also { it.mkdirs() }
        executables.forEach { bin.resolve(it).writeText("") }
        return root
    }

    @Test
    fun `linux jdk directory resolves bin java`(@TempDir tempDir: Path) {
        val jdk = fakeJdk(tempDir.resolve("jdk-25").toFile(), "java", "javac")

        assertEquals(jdk.resolve("bin/java"), resolveJavaExecutable(jdk.absolutePath, LibraryOsArch.LINUX_X64))
        assertEquals(
            jdk.resolve("bin/java"),
            resolveJavaExecutable(jdk.resolve("bin/java").absolutePath, LibraryOsArch.LINUX_X64)
        )
    }

    @Test
    fun `linux does not accept windows executables or the exe suffix fallback`(@TempDir tempDir: Path) {
        val jdk = fakeJdk(tempDir.resolve("jdk-25").toFile(), "java.exe", "javac.exe")

        assertNull(resolveJavaExecutable(jdk.absolutePath, LibraryOsArch.LINUX_X64))
        assertNull(resolveJavaExecutable(jdk.resolve("bin/java.exe").absolutePath, LibraryOsArch.LINUX_X64))
        assertNull(resolveJavaExecutable(jdk.resolve("bin/java").absolutePath, LibraryOsArch.LINUX_X64))
    }

    @Test
    fun `windows keeps java exe javaw and suffix fallback`(@TempDir tempDir: Path) {
        val jdk = fakeJdk(tempDir.resolve("jdk-25").toFile(), "java.exe", "javaw.exe", "javac.exe")
        val javaExe = jdk.resolve("bin/java.exe")

        assertEquals(javaExe, resolveJavaExecutable(jdk.absolutePath, LibraryOsArch.WIN_X64))
        assertEquals(javaExe, resolveJavaExecutable(jdk.resolve("bin/javaw.exe").absolutePath, LibraryOsArch.WIN_X64))
        assertEquals(javaExe, resolveJavaExecutable(jdk.resolve("bin/java").absolutePath, LibraryOsArch.WIN_X64))
    }
}
