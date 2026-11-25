package calebxzau.rdi.client.packproc

import calebxzau.rdi.client.modcatalog.createModCatalog
import calebxzhou.rdi.common.archive.forEachArchiveEntryStreaming
import calebxzhou.rdi.common.net.ktorClient
import calebxzhou.rdi.common.serdesJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test

/**
 * Comparison aid for the Rust port of the upload (`client/slint-proto`, `upload::reader`): reads real
 * packs with [ModpackProcessor.loadLocalModpack] and writes the mod and extra lists as JSON, to compare
 * with the Rust reader's dump of the same packs.
 *
 * It asks Modrinth and CurseForge, so it does nothing unless both variables are set:
 * `RDI_UPLOAD_SAMPLE` holds pack paths separated by `|`, and `RDI_UPLOAD_DUMP` names a folder that
 * receives `kotlin-<number>.json`, numbered from 1 in the order of the paths. With `RDI_UPLOAD_ARCHIVE`
 * set, the upload archive is built too and its entries are listed (path, size, and for a zip or jar
 * the names inside), to compare with the Rust archive builder (`upload::archive`).
 */
class UploadPackDumpTest {
    @Test
    fun `dump loaded packs for comparison with the Rust reader`() {
        val samples = System.getenv("RDI_UPLOAD_SAMPLE")?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        val dumpDir = System.getenv("RDI_UPLOAD_DUMP")?.takeIf { it.isNotBlank() }?.let(::File)
        if (samples.isEmpty() || dumpDir == null) return
        val withArchive = !System.getenv("RDI_UPLOAD_ARCHIVE").isNullOrBlank()
        dumpDir.mkdirs()
        val workDir = Files.createTempDirectory("rdi-upload-dump-").toFile()
        try {
            val catalog = createModCatalog(
                httpClient = ktorClient,
                identityDatabaseMaterializationDir = workDir.resolve("mod-catalog").toPath(),
            )
            samples.forEachIndexed { index, sample ->
                val target = dumpDir.resolve("kotlin-${index + 1}.json")
                val processor = ModpackProcessor(PackProcessingPaths(workDir.resolve("work-${index + 1}")))
                val result = runBlocking {
                    processor.loadLocalModpack(catalog, File(sample), onProgress = {})
                }
                val json = result.fold(
                    onSuccess = { loaded ->
                        buildJsonObject {
                            put("mods", serdesJson.encodeToJsonElement(loaded.mods))
                            put("extras", serdesJson.encodeToJsonElement(loaded.clientExtras))
                            put("embeddedNames", serdesJson.encodeToJsonElement(loaded.embeddedModOriginalFileNames))
                            put("containsMca", loaded.containsExcludedMcaFiles)
                            if (withArchive) {
                                val archive = runBlocking { processor.buildUploadArchive(loaded.toUploadPayload()) }
                                put("archive", listArchive(archive))
                            }
                        }
                    },
                    onFailure = { error ->
                        buildJsonObject { put("error", error.stackTraceToString()) }
                    },
                )
                target.writeText(json.toString(), Charsets.UTF_8)
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun listArchive(archive: File) = buildJsonArray {
        forEachArchiveEntryStreaming(archive) { entry, input ->
            add(
                buildJsonObject {
                    put("path", entry.path.trimEnd('/'))
                    put("dir", entry.isDirectory)
                    put("size", entry.size)
                    val lower = entry.path.lowercase()
                    if (!entry.isDirectory && (lower.endsWith(".zip") || lower.endsWith(".jar"))) {
                        put(
                            "inner",
                            buildJsonArray {
                                ZipInputStream(ByteArrayInputStream(input.readBytes())).use { zip ->
                                    while (true) {
                                        val inner = zip.nextEntry ?: break
                                        add(inner.name)
                                    }
                                }
                            },
                        )
                    }
                },
            )
        }
    }
}
