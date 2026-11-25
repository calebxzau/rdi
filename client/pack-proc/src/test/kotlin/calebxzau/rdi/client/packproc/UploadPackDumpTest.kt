package calebxzau.rdi.client.packproc

import calebxzau.rdi.client.modcatalog.createModCatalog
import calebxzhou.rdi.common.net.ktorClient
import calebxzhou.rdi.common.serdesJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * Comparison aid for the Rust port of the upload (`client/slint-proto`, `upload::reader`): reads real
 * packs with [ModpackProcessor.loadLocalModpack] and writes the mod and extra lists as JSON, to compare
 * with the Rust reader's dump of the same packs.
 *
 * It asks Modrinth and CurseForge, so it does nothing unless both variables are set:
 * `RDI_UPLOAD_SAMPLE` holds pack paths separated by `|`, and `RDI_UPLOAD_DUMP` names a folder that
 * receives `kotlin-<number>.json`, numbered from 1 in the order of the paths.
 */
class UploadPackDumpTest {
    @Test
    fun `dump loaded packs for comparison with the Rust reader`() {
        val samples = System.getenv("RDI_UPLOAD_SAMPLE")?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        val dumpDir = System.getenv("RDI_UPLOAD_DUMP")?.takeIf { it.isNotBlank() }?.let(::File)
        if (samples.isEmpty() || dumpDir == null) return
        dumpDir.mkdirs()
        val workDir = Files.createTempDirectory("rdi-upload-dump-").toFile()
        try {
            val catalog = createModCatalog(
                httpClient = ktorClient,
                identityDatabaseMaterializationDir = workDir.resolve("mod-catalog").toPath(),
            )
            samples.forEachIndexed { index, sample ->
                val target = dumpDir.resolve("kotlin-${index + 1}.json")
                val result = runBlocking {
                    ModpackProcessor(PackProcessingPaths(workDir.resolve("work-${index + 1}")))
                        .loadLocalModpack(catalog, File(sample), onProgress = {})
                }
                val json = result.fold(
                    onSuccess = { loaded ->
                        buildJsonObject {
                            put("mods", serdesJson.encodeToJsonElement(loaded.mods))
                            put("extras", serdesJson.encodeToJsonElement(loaded.clientExtras))
                            put("embeddedNames", serdesJson.encodeToJsonElement(loaded.embeddedModOriginalFileNames))
                            put("containsMca", loaded.containsExcludedMcaFiles)
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
}
