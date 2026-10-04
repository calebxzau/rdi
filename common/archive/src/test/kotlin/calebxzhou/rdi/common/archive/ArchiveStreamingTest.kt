package calebxzhou.rdi.common.archive

import java.nio.file.Files
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveStreamingTest {
    @Test
    fun `budgets count skipped files and include exact boundaries for both formats`() {
        val root = Files.createTempDirectory("archive-budget").toFile()
        try {
            val entries = linkedMapOf("overrides/a.bin" to byteArrayOf(1, 2, 3), "ignored.bin" to byteArrayOf(4, 5))
            val tar = root.resolve("pack.tar.zst")
            TarZstArchiveWriter(tar).use { writer -> entries.forEach { (path, bytes) -> writer.addFile(path, bytes) } }
            val zip = root.resolve("pack.zip")
            ZipOutputStream(zip.outputStream()).use { writer ->
                entries.forEach { (path, bytes) ->
                    writer.putNextEntry(ZipEntry(path))
                    writer.write(bytes)
                    writer.closeEntry()
                }
            }
            listOf(tar, zip).forEach { file ->
                var count = 0
                forEachArchiveEntryStreaming(file, ArchiveReadLimits(3, 5, 2)) { _, _ -> count++ }
                assertEquals(2, count)
                assertFailsWith<IllegalArgumentException> {
                    forEachArchiveEntryStreaming(file, ArchiveReadLimits(2, 5, 2)) { _, _ -> }
                }
                assertFailsWith<IllegalArgumentException> {
                    forEachArchiveEntryStreaming(file, ArchiveReadLimits(3, 4, 2)) { _, _ -> }
                }
                assertFailsWith<IllegalArgumentException> {
                    forEachArchiveEntryStreaming(file, ArchiveReadLimits(3, 5, 1)) { _, _ -> }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `callback partial reads and skip cannot evade total accounting`() {
        val root = Files.createTempDirectory("archive-partial").toFile()
        try {
            val archive = root.resolve("pack.tar.zst")
            TarZstArchiveWriter(archive).use {
                it.addFile("a.bin", byteArrayOf(1, 2, 3))
                it.addFile("b.bin", byteArrayOf(4, 5))
            }
            forEachArchiveEntryStreaming(archive, ArchiveReadLimits(3, 5, 2)) { entry, input ->
                assertEquals(if (entry.path == "a.bin") 1 else 4, input.read())
                assertEquals(entry.size - 1, input.skip(Long.MAX_VALUE))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `validation rejects traversal and original file directory conflicts`() {
        val root = Files.createTempDirectory("archive-paths").toFile()
        try {
            listOf("overrides/../escaped", "/absolute", "C:/escaped").forEachIndexed { index, path ->
                val archive = root.resolve("unsafe-$index.zip")
                ZipOutputStream(archive.outputStream()).use {
                    it.putNextEntry(ZipEntry(path))
                    it.write(byteArrayOf(1))
                    it.closeEntry()
                }
                assertFailsWith<IllegalArgumentException> { validateModpackArchive(archive) }
                assertFailsWith<IllegalArgumentException> { normalizeSafeArchivePath(path) }
            }
            val archive = root.resolve("collision.tar.zst")
            TarZstArchiveWriter(archive).use {
                it.addFile("overrides/config", byteArrayOf(1))
                it.addFile("overrides/config/file", byteArrayOf(2))
            }
            assertFailsWith<IllegalArgumentException> { validateModpackArchive(archive) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `client overlay wins regardless of archive order and never installs server files`() {
        val root = Files.createTempDirectory("archive-overlay").toFile()
        try {
            listOf(false, true).forEach { reversed ->
                val entries = listOf(
                    "overrides/config/a.txt" to byteArrayOf(1),
                    "client-overrides/config/a.txt" to byteArrayOf(2),
                    "client-overrides/config/client.txt" to byteArrayOf(3),
                    "server/config/server.txt" to byteArrayOf(4),
                    "modrinth.index.json" to byteArrayOf(5),
                ).let { if (reversed) it.reversed() else it }
                val archive = root.resolve("pack-$reversed.tar.zst")
                TarZstArchiveWriter(archive).use { writer -> entries.forEach { (path, bytes) -> writer.addFile(path, bytes) } }
                val target = root.resolve("target-$reversed")
                extractClientPackArchiveToDir(archive, target, layered = true)
                assertContentEquals(byteArrayOf(2), target.resolve("config/a.txt").readBytes())
                assertTrue(target.resolve("config/client.txt").isFile)
                assertFalse(target.resolve("server").exists())
                assertFalse(target.resolve("modrinth.index.json").exists())
                assertFalse(target.resolve("client-overrides").exists())
            }
            val flat = root.resolve("flat.tar.zst")
            TarZstArchiveWriter(flat).use { it.addFile("config/flat.txt", byteArrayOf(6)) }
            val target = root.resolve("flat")
            extractClientPackArchiveToDir(flat, target)
            assertContentEquals(byteArrayOf(6), target.resolve("config/flat.txt").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `tar declared oversized entry is rejected before callback and links are rejected`() {
        val root = Files.createTempDirectory("archive-headers").toFile()
        try {
            val oversized = root.resolve("oversized.tar.zst")
            TarArchiveOutputStream(ZstdOutputStream(oversized.outputStream())).use { output ->
                val entry = TarArchiveEntry("overrides/large.bin").apply { size = 16 }
                output.putArchiveEntry(entry)
                output.write(ByteArray(16))
                output.closeArchiveEntry()
            }
            var called = false
            assertFailsWith<IllegalArgumentException> {
                forEachArchiveEntryStreaming(oversized, ArchiveReadLimits(8, 32, 2)) { _, _ -> called = true }
            }
            assertFalse(called)
            val link = root.resolve("link.tar.zst")
            TarArchiveOutputStream(ZstdOutputStream(link.outputStream())).use { output ->
                val entry = TarArchiveEntry("overrides/link", '2'.code.toByte()).apply { linkName = "../outside" }
                output.putArchiveEntry(entry)
                output.closeArchiveEntry()
            }
            assertFailsWith<IllegalArgumentException> { validateModpackArchive(link) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `oversized and recursive tar metadata is rejected before commons processes it`() {
        val root = Files.createTempDirectory("archive-metadata").toFile()
        try {
            val largeMetadata = root.resolve("large-metadata.tar.zst")
            TarArchiveOutputStream(ZstdOutputStream(largeMetadata.outputStream())).use { output ->
                output.putArchiveEntry(TarArchiveEntry("././@LongLink", 'L'.code.toByte()).apply { size = 2L * 1024 * 1024 })
                val buffer = ByteArray(8192)
                repeat(256) { output.write(buffer) }
                output.closeArchiveEntry()
            }
            assertFailsWith<IllegalArgumentException> { validateModpackArchive(largeMetadata) }
            val recursive = root.resolve("recursive.tar.zst")
            TarArchiveOutputStream(ZstdOutputStream(recursive.outputStream())).use { output ->
                repeat(17) {
                    val bytes = "file.txt\u0000".toByteArray()
                    output.putArchiveEntry(TarArchiveEntry("././@LongLink", 'L'.code.toByte()).apply { size = bytes.size.toLong() })
                    output.write(bytes)
                    output.closeArchiveEntry()
                }
                output.putArchiveEntry(TarArchiveEntry("file.txt").apply { size = 1 })
                output.write(1)
                output.closeArchiveEntry()
            }
            assertFailsWith<IllegalArgumentException> { validateModpackArchive(recursive) }
            val valid = root.resolve("long-name.tar.zst")
            val path = "overrides/config/${"a".repeat(200)}.txt"
            TarZstArchiveWriter(valid).use { it.addFile(path, byteArrayOf(1)) }
            validateModpackArchive(valid)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `flat client artifact retains literal overrides and server directory names`() {
        val root = Files.createTempDirectory("archive-flat").toFile()
        try {
            val archive = root.resolve("flat.tar.zst")
            TarZstArchiveWriter(archive).use {
                it.addFile("config/example/overrides/rules.json", byteArrayOf(1))
                it.addFile("config/other.json", byteArrayOf(2))
                it.addFile("server/data.txt", byteArrayOf(3))
            }
            val target = root.resolve("instance")
            extractClientPackArchiveToDir(archive, target)
            assertContentEquals(byteArrayOf(1), target.resolve("config/example/overrides/rules.json").readBytes())
            assertContentEquals(byteArrayOf(2), target.resolve("config/other.json").readBytes())
            assertContentEquals(byteArrayOf(3), target.resolve("server/data.txt").readBytes())
            assertFalse(target.resolve("rules.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `pax size override obeys payload budget before regular entry is read`() {
        val root = Files.createTempDirectory("archive-pax-size").toFile()
        try {
            val archive = root.resolve("pax-size.tar.zst")
            TarArchiveOutputStream(ZstdOutputStream(archive.outputStream())).use { output ->
                val record = "11 size=16\n".toByteArray()
                output.putArchiveEntry(TarArchiveEntry("pax", 'x'.code.toByte()).apply { size = record.size.toLong() })
                output.write(record)
                output.closeArchiveEntry()
                output.putArchiveEntry(TarArchiveEntry("file.bin").apply { size = 16 })
                output.write(ByteArray(16))
                output.closeArchiveEntry()
            }
            assertFailsWith<IllegalArgumentException> {
                forEachArchiveEntryStreaming(archive, ArchiveReadLimits(8, 32, 2)) { _, _ -> }
            }
            forEachArchiveEntryStreaming(archive, ArchiveReadLimits(16, 16, 1)) { entry, input ->
                assertEquals(16L, entry.size)
                assertEquals(16, input.readBytes().size)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancellation callback aborts while skipped payload is drained`() {
        val root = Files.createTempDirectory("archive-cancel").toFile()
        try {
            val archive = root.resolve("pack.tar.zst")
            TarZstArchiveWriter(archive).use { it.addFile("ignored.bin", ByteArray(512 * 1024)) }
            var reads = 0
            assertFailsWith<java.util.concurrent.CancellationException> {
                validateModpackArchive(archive) {
                    if (++reads > 4) throw java.util.concurrent.CancellationException("cancelled")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
