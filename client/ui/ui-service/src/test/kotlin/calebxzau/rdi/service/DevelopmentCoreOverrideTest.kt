package calebxzau.rdi.service

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DevelopmentCoreOverrideTest {
    @Test
    fun archivesOriginalRelativePathAndCopiesWithoutChangingSource(@TempDir root: Path) {
        val source = root.resolve("build/core.jar")
        val target = root.resolve("run/mc/mods/core.jar")
        Files.createDirectories(source.parent)
        Files.createDirectories(target.parent)
        Files.writeString(source, "new-core")
        Files.writeString(target, "old-core")
        DevelopmentCoreOverride.install(source, target, root).getOrThrow()
        assertEquals("new-core", Files.readString(target))
        assertEquals("new-core", Files.readString(source))
        val operation = Files.list(root.resolve("DEL")).use { it.findFirst().orElseThrow() }
        assertEquals("old-core", Files.readString(operation.resolve("run/mc/mods/core.jar")))
        DevelopmentCoreOverride.install(source, target, root).getOrThrow()
        assertEquals(1L, Files.list(root.resolve("DEL")).use { it.count() })
    }

    @Test
    fun rejectsDestinationOutsideRepository(@TempDir root: Path) {
        assertTrue(DevelopmentCoreOverride.install(root.resolve("core.jar"), root.parent.resolve("outside.jar"), root).isFailure)
    }
}
