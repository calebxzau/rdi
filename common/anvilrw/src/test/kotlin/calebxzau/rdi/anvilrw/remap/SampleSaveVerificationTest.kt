package calebxzau.rdi.anvilrw.remap

import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the Phase 1 components over a real save when `RDI_SAMPLE_SAVE` points at its folder.
 * Without the variable the test does nothing, so it never depends on local files in CI.
 *
 * Every region file is rewritten with a mapping for two real players, every chunk is validated, and
 * every rewritten chunk is read back with Java `readUTF` semantics.
 */
class SampleSaveVerificationTest {
    private val pcl = UUID.fromString("00000000-0000-300c-9be5-0017dec2993d")
    private val online = UUID.fromString("1512ef02-2b79-4494-a4e9-e35cd8e03923")

    @Test
    fun rewritesTheSampleSave() {
        val save = System.getenv("RDI_SAMPLE_SAVE")?.let { Paths.get(it) } ?: return
        val level = BoundedIo.gunzip(Files.readAllBytes(save.resolve("level.dat")), RemapLimits.STRICT_NBT_FILE_BYTES, "level.dat")
        val metadata = NbtMetadataReader.open(level).getOrThrow().levelMetadata().getOrThrow()
        assertEquals("1.20.1", metadata.versionName)
        assertEquals(pcl, metadata.singleplayerUuid)
        assertTrue("forge" in metadata.serverBrands)
        assertTrue(metadata.fmlModIds.orEmpty().contains("forge"))
        println("level.dat: ${metadata.versionName} data=${metadata.dataVersion} brands=${metadata.serverBrands} mods=${metadata.fmlModIds?.size} hardcore=${metadata.hardcore}")

        val patcher = NbtBytePatcher(
            mapOf(
                pcl to UUID.fromString("00112233-4455-6677-8899-aabb00000000"),
                online to UUID.fromString("66778899-aabb-ccdd-eeff-001100000000"),
            ),
        )
        val replacements = AtomicInteger()
        val transform = ChunkTransform { _, _, nbt ->
            val result = patcher.patch(nbt).getOrThrow()
            if (result.changed) {
                JavaNbt.read(result.bytes)
                replacements.addAndGet(result.replacements.values.sum())
                result.bytes
            } else {
                null
            }
        }
        val out = createTempDirectory("sample-save-out")
        val regions = Files.walk(save).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".mca") }.toList()
        }
        var changedFiles = 0
        var changedChunks = 0
        val started = System.nanoTime()
        for (region in regions) {
            val target = out.resolve(region.relativeTo(save).toString())
            Files.createDirectories(target.parent)
            val result = RawRegionFile.rewrite(region, target, transform).getOrThrow()
            if (!result.copiedUnchanged) changedFiles++
            changedChunks += result.changedChunkCount
            Files.deleteIfExists(target)
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println("regions=${regions.size} changedFiles=${changedFiles} changedChunks=${changedChunks} replacements=${replacements.get()} seconds=${"%.1f".format(seconds)}")
        assertTrue(replacements.get() > 0)
    }

    /**
     * Writes a complete remapped copy of the sample save to `RDI_SAMPLE_SAVE_OUT`, which must not exist
     * yet, for loading in a real server (plan §12.1 manual gate). `RDI_SAMPLE_SAVE_MAPPING` overrides
     * the mapping as `source=target,source=target`.
     */
    @Test
    fun writesARemappedCopy() {
        val save = System.getenv("RDI_SAMPLE_SAVE")?.let { Paths.get(it) } ?: return
        val out = System.getenv("RDI_SAMPLE_SAVE_OUT")?.let { Paths.get(it) } ?: return
        check(Files.notExists(out)) { "${out} already exists" }
        val mapping = System.getenv("RDI_SAMPLE_SAVE_MAPPING")?.split(',')?.associate { pair ->
            val (source, target) = pair.split('=').map { UUID.fromString(it.trim()) }
            source to target
        } ?: mapOf(
            pcl to UUID.fromString("00112233-4455-6677-8899-aabb00000000"),
            online to UUID.fromString("66778899-aabb-ccdd-eeff-001100000000"),
        )

        val snapshot = SaveSnapshot.take(save).getOrThrow()
        val started = System.nanoTime()
        val report = SaveUuidRemapper(mapping).remapTree(save, out, snapshot).getOrThrow()

        val seconds = (System.nanoTime() - started) / 1e9
        println("out=${out} files=${report.filesScanned} rewritten=${report.filesRewritten} renamed=${report.pathsRenamed} replacements=${report.replacementsBySource} seconds=${"%.1f".format(seconds)}")
        report.possiblyUnmigrated.forEach { println("possibly unmigrated: ${it.relativePath} (${it.reason})") }
        assertTrue(report.replacementsBySource.values.sum() > 0)
    }
}
