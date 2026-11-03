package calebxzau.rdi.mc.client.dm

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmRegionScannerTest {
    private val empty = DmRegionTable(List(1024) { DmRegionSlot(0, 0) })

    private fun withWorld(block: (Path) -> Unit) {
        val world = Files.createTempDirectory("dm-region-scan-")
        try {
            Files.createDirectories(world.resolve("region"))
            block(world)
        } finally {
            DmWorldInitArchive.deleteRecursively(world)
        }
    }

    @Test
    fun emptyAndTruncatedRegionFilesScanAsEmpty(): Unit = withWorld { world ->
        Files.write(world.resolve("region/r.0.0.mca"), ByteArray(0))
        Files.write(world.resolve("region/r.1.0.mca"), ByteArray(100))
        val scanned = DmRegionScanner.scan(world).getOrThrow()
        assertEquals(listOf("region/r.0.0.mca", "region/r.1.0.mca"), scanned.map { it.path })
        scanned.forEach { region ->
            assertEquals(empty, region.table)
            assertTrue(region.changes.isEmpty())
        }
    }

    @Test
    fun readsChunksFromCompleteRegionFiles(): Unit = withWorld { world ->
        val payload = byteArrayOf(1, 2, 3, 4)
        val file = ByteBuffer.allocate(8192 + 4096)
        file.putInt(0, (2 shl 8) or 1)
        file.putInt(4096, 123)
        file.putInt(8192, payload.size + 1)
        file.put(8196, 2)
        file.put(8197, payload)
        Files.write(world.resolve("region/r.0.0.mca"), file.array())

        val region = DmRegionScanner.scan(world).getOrThrow().single()
        assertEquals(DmRegionSlot(123, 1), region.table.slots[0])
        val change = region.changes.single()
        assertEquals(DmRegionChunkKey("region/r.0.0.mca", 0), change.key)
        assertEquals(123, change.timestamp)
        assertContentEquals(byteArrayOf(0, 0, 0, 5, 2, 1, 2, 3, 4), change.bytes)
    }

    @Test
    fun externalChunkFilesAreNamedAfterTheChunk() {
        assertEquals("c.33.-31.mcc", DmRegionScanner.externalChunkName(Path.of("region/r.1.-1.mca"), 33))
        assertEquals("c.-1.-1.mcc", DmRegionScanner.externalChunkName(Path.of("region/r.-1.-1.mca"), 1023))
    }
}
