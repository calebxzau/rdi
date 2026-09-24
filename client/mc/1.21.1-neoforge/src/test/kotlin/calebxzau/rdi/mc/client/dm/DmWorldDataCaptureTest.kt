package calebxzau.rdi.mc.client.dm

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmWorldDataCaptureTest {
    @Test
    fun recognizesWindowsReparsePointFlag(): Unit {
        assertTrue(DmWorldDataCapture.hasWindowsReparsePoint(0x400))
        assertTrue(DmWorldDataCapture.hasWindowsReparsePoint(0x401))
        assertFalse(DmWorldDataCapture.hasWindowsReparsePoint(0))
        assertFalse(DmWorldDataCapture.hasWindowsReparsePoint(0x200))
    }

    @Test
    fun rawWindowsReparseCheckFollowsPlatformPathSeparator(): Unit {
        assertTrue(DmWorldDataCapture.shouldCheckWindowsReparsePoint('\\'))
        assertFalse(DmWorldDataCapture.shouldCheckWindowsReparsePoint('/'))
    }

    @Test
    fun capturesUnknownUnicodeHiddenAndEmptyDirectoriesWhileKeepingBackupAndTmp(): Unit {
        val root = Files.createTempDirectory("dm-world-capture-")
        try {
            val world = root.resolve("world")
            val staging = root.resolve("staging")
            Files.createDirectories(world.resolve("mod data/空目录"))
            Files.createDirectories(world.resolve(".hidden"))
            Files.createDirectories(world.resolve("region"))
            Files.createDirectories(world.resolve("tmp"))
            Files.createDirectories(world.resolve("data"))
            writeLevel(world.resolve("level.dat"))
            Files.writeString(world.resolve("mod data/未知.txt"), "unknown")
            Files.writeString(world.resolve(".hidden/value"), "hidden")
            Files.writeString(world.resolve("backup.zip"), "backup")
            Files.writeString(world.resolve("tmp/cache"), "tmp")
            Files.writeString(world.resolve("region/r.0.0.mca"), "excluded")
            Files.writeString(world.resolve("session.lock"), "excluded")
            Files.writeString(world.resolve("data/rdi_firm_sections.dat"), "excluded")

            val capture = DmWorldDataCapture.capture(
                world,
                staging,
                DmWorldSyncPolicy.EMPTY,
                deadlineNanos = System.nanoTime() + 10_000_000_000L,
                cancelled = { false },
            ).getOrThrow()

            assertEquals(
                setOf("level.dat", "mod data/未知.txt", ".hidden/value", "backup.zip", "tmp/cache"),
                capture.files.map { it.path }.toSet(),
            )
            assertTrue("mod data/空目录" in capture.directories)
            assertTrue(".hidden" in capture.directories)
            assertFalse("region/r.0.0.mca" in capture.files.map { it.path })
            assertFalse("session.lock" in capture.files.map { it.path })
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun fixedAndCustomExclusionsDoNotExcludeArbitraryBackupOrTmpFiles(): Unit {
        val root = Files.createTempDirectory("dm-world-policy-")
        try {
            val world = root.resolve("world")
            writeLevel(world.resolve("level.dat"))
            Files.createDirectories(world.resolve("custom"))
            Files.writeString(world.resolve("custom/skip.txt"), "skip")
            Files.writeString(world.resolve("keep.tmp"), "keep")
            Files.writeString(world.resolve("backup.dat"), "keep")
            val capture = DmWorldDataCapture.capture(
                world,
                root.resolve("staging"),
                DmWorldSyncPolicy(setOf("backup.dat"), setOf("custom")),
                deadlineNanos = System.nanoTime() + 10_000_000_000L,
                cancelled = { false },
            ).getOrThrow()
            val paths = capture.files.map { it.path }.toSet()
            assertTrue("keep.tmp" in paths)
            assertFalse("backup.dat" in paths)
            assertFalse("custom/skip.txt" in paths)
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun missingExcludedAndMalformedLevelDatFailCapture(): Unit {
        val root = Files.createTempDirectory("dm-world-level-")
        try {
            val missing = root.resolve("missing")
            Files.createDirectories(missing)
            assertTrue(
                DmWorldDataCapture.capture(
                    missing,
                    root.resolve("missing-stage"),
                    DmWorldSyncPolicy.EMPTY,
                    deadlineNanos = System.nanoTime() + 10_000_000_000L,
                    cancelled = { false },
                ).isFailure,
            )

            val excluded = root.resolve("excluded")
            writeLevel(excluded.resolve("level.dat"))
            assertTrue(
                DmWorldDataCapture.capture(
                    excluded,
                    root.resolve("excluded-stage"),
                    DmWorldSyncPolicy(setOf("level.dat")),
                    deadlineNanos = System.nanoTime() + 10_000_000_000L,
                    cancelled = { false },
                ).isFailure,
            )

            val malformed = root.resolve("malformed")
            Files.createDirectories(malformed)
            Files.writeString(malformed.resolve("level.dat"), "not nbt")
            assertTrue(
                DmWorldDataCapture.capture(
                    malformed,
                    root.resolve("malformed-stage"),
                    DmWorldSyncPolicy.EMPTY,
                    deadlineNanos = System.nanoTime() + 10_000_000_000L,
                    cancelled = { false },
                ).isFailure,
            )
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun sanitizesStagedPlayerWithoutChangingLiveLevelDat(): Unit {
        val root = Files.createTempDirectory("dm-world-sanitize-")
        try {
            val world = root.resolve("world")
            val source = world.resolve("level.dat")
            writeLevel(source)
            val before = Files.readAllBytes(source)
            val capture = DmWorldDataCapture.capture(
                world,
                root.resolve("staging"),
                DmWorldSyncPolicy.EMPTY,
                deadlineNanos = System.nanoTime() + 10_000_000_000L,
                cancelled = { false },
            ).getOrThrow()
            assertContentEquals(before, Files.readAllBytes(source))
            val staged = NbtIo.readCompressed(
                root.resolve("staging/level.dat"),
                NbtAccounter.create(DmWorldDataCapture.MAX_LEVEL_DATA_BYTES),
            )
            assertFalse((staged.get("Data") as CompoundTag).contains("Player"))
            assertEquals(Files.size(root.resolve("staging/level.dat")), capture.files.single { it.path == "level.dat" }.bytes)
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun rejectsStagingPathInsideWorldRoot(): Unit {
        val root = Files.createTempDirectory("dm-world-staging-safety-")
        try {
            val world = root.resolve("world")
            writeLevel(world.resolve("level.dat"))
            assertTrue(
                DmWorldDataCapture.capture(
                    world,
                    world.resolve("rdi-staging"),
                    DmWorldSyncPolicy.EMPTY,
                    deadlineNanos = System.nanoTime() + 10_000_000_000L,
                    cancelled = { false },
                ).isFailure,
            )
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun rejectsCaseInsensitiveAndFilePrefixConflicts(): Unit {
        assertFails { DmWorldDataCapture.validatePathConflicts(listOf("Foo", "foo"), emptyList()) }
        assertFails { DmWorldDataCapture.validatePathConflicts(listOf("foo", "foo/bar"), emptyList()) }
        assertFails { DmWorldDataCapture.validatePathConflicts(listOf("Mods"), listOf("mods/config")) }
        DmWorldDataCapture.validatePathConflicts(listOf("foo/bar"), listOf("foo"))
    }

    private fun writeLevel(path: Path) {
        Files.createDirectories(path.parent)
        val data = CompoundTag().apply {
            put("Player", CompoundTag().apply { putInt("XpLevel", 3) })
            putString("WorldName", "test")
        }
        NbtIo.writeCompressed(CompoundTag().apply { put("Data", data) }, path)
    }
}
