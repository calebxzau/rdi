package calebxzau.rdi.client.platform

import calebxzhou.rdi.common.model.LibraryOsArch
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `mac accepts the jdk bundle, its Contents folder and the home folder`(@TempDir tempDir: Path) {
        val bundle = tempDir.resolve("temurin-25.jdk").toFile()
        val home = fakeJdk(bundle.resolve("Contents/Home"), "java", "javac")
        val javaExe = home.resolve("bin/java")

        assertEquals(javaExe, resolveJavaExecutable(bundle.absolutePath, LibraryOsArch.MAC_ARM64))
        assertEquals(javaExe, resolveJavaExecutable(bundle.resolve("Contents").absolutePath, LibraryOsArch.MAC_ARM64))
        assertEquals(javaExe, resolveJavaExecutable(home.absolutePath, LibraryOsArch.MAC_ARM64))
        assertNull(resolveJavaExecutable(bundle.absolutePath, LibraryOsArch.LINUX_X64))
    }

    private fun javaSettings(arch: String) = """
        Property settings:
            java.specification.version = 25
            os.arch = $arch
            os.name = Mac OS X
        openjdk version "25.0.4.1" 2026-08-18 LTS
    """.trimIndent()

    @Test
    fun `x64 jdk on apple silicon is rejected`() {
        val message = jdkArchMismatchMessage(javaSettings("x86_64"), hostArch = "aarch64", osArch = LibraryOsArch.MAC_ARM64)
        assertNotNull(message)
        assertTrue("arm64" in message)
    }

    @Test
    fun `matching or unknown jdk architecture is accepted`() {
        assertNull(jdkArchMismatchMessage(javaSettings("aarch64"), hostArch = "aarch64", osArch = LibraryOsArch.MAC_ARM64))
        assertNull(jdkArchMismatchMessage(javaSettings("amd64"), hostArch = "x86_64", osArch = LibraryOsArch.LINUX_X64))
        assertNull(jdkArchMismatchMessage("openjdk version \"25\"", hostArch = "amd64", osArch = LibraryOsArch.WIN_X64))
    }
}
