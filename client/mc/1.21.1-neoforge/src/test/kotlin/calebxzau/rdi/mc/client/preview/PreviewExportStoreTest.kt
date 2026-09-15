package calebxzau.rdi.mc.client.preview

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PreviewExportStoreTest {
    @Test
    fun serializesOnlyTheCompactManifestShape(): Unit = withTempRoot { _ ->
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }.encodeToString(
            PreviewManifest(
                pages = emptyList(),
                items = emptyMap()
            )
        )

        assertTrue(json.contains("\"formatVersion\""))
        assertTrue(json.contains("\"iconSize\""))
        assertTrue(json.contains("\"pages\""))
        assertTrue(json.contains("\"items\""))
        assertTrue(json.contains("\"failedItems\""))
        assertFalse(json.contains("languageFile"))
        assertFalse(json.contains("padding"))
    }

    @Test
    fun requiresFormatMarkersWhenDecoding(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val complete = Json.encodeToString(PreviewManifest(pages = emptyList(), items = emptyMap()))
        val objectWithoutVersion = Json.parseToJsonElement(complete).jsonObject.toMutableMap().apply {
            remove("formatVersion")
        }
        val objectWithoutIconSize = Json.parseToJsonElement(complete).jsonObject.toMutableMap().apply {
            remove("iconSize")
        }

        Files.writeString(root.resolve("manifest.json"), objectWithoutVersion.toString())
        assertTrue(store.readReusable().isFailure)
        Files.writeString(root.resolve("manifest.json"), objectWithoutIconSize.toString())
        assertTrue(store.readReusable().isFailure)
    }

    @Test
    fun generatesUuidV7AndPublishesReusableSnapshot(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val exportId = store.beginGeneration()
        assertEquals(7, UUID.fromString(exportId).version())
        writePage(root, exportId)

        val manifest = manifest(exportId)
        assertEquals(true, store.publish(exportId, manifest).getOrThrow())
        val reusable = store.readReusable().getOrThrow()
        assertNotNull(reusable)
        assertEquals(manifest, reusable)
    }

    @Test
    fun acceptsAnExportContainingOnlyFailures(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val exportId = store.beginGeneration()
        val manifest = PreviewManifest(
            pages = emptyList(),
            items = emptyMap(),
            failedItems = mapOf("example:broken" to "render_failed")
        )

        assertEquals(true, store.publish(exportId, manifest).getOrThrow())
        assertEquals(manifest, store.readReusable().getOrThrow())
    }

    @Test
    fun rejectsMalformedVersionBoundsAndMissingReferences(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val exportId = store.beginGeneration()
        val missingPage = PreviewManifest(
            pages = listOf(PreviewPage("texture_pages/$exportId/0.png", 64, 64)),
            items = emptyMap()
        )
        assertTrue(store.publish(exportId, missingPage).isFailure)

        val invalidVersion = missingPage.copy(formatVersion = 99)
        assertTrue(store.publish(exportId, invalidVersion).isFailure)

        Files.writeString(
            root.resolve("manifest.json"),
            Json.encodeToString(missingPage),
            StandardCharsets.UTF_8
        )
        assertTrue(store.readReusable().isFailure)
    }

    @Test
    fun rejectsOutOfBoundsAndDuplicateCells(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val exportId = store.beginGeneration()
        writePage(root, exportId)

        val outOfBounds = manifest(exportId).copy(
            items = mapOf("a" to PreviewItem(0, 64, 0, "item.a"))
        )
        assertTrue(store.publish(exportId, outOfBounds).isFailure)

        val duplicate = manifest(exportId).copy(
            items = mapOf(
                "a" to PreviewItem(0, 0, 0, "item.a"),
                "b" to PreviewItem(0, 0, 0, "item.b")
            )
        )
        assertTrue(store.publish(exportId, duplicate).isFailure)

        val overflow = manifest(exportId).copy(
            items = mapOf("overflow" to PreviewItem(0, Int.MAX_VALUE - 63, 0, "item.overflow"))
        )
        assertTrue(store.publish(exportId, overflow).isFailure)
    }

    @Test
    fun acceptsRectangularPageAndKeepsLegacySquarePagesReadable(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val exportId = store.beginGeneration()
        root.resolve("texture_pages/$exportId").createDirectories()
        Files.write(root.resolve("texture_pages/$exportId/0.png"), byteArrayOf(0))
        val rectangular = PreviewManifest(
            pages = listOf(PreviewPage("texture_pages/$exportId/0.png", 2048, 64)),
            items = mapOf("a" to PreviewItem(0, 1984, 0, "item.a"))
        )
        assertTrue(
            store.publish(
                exportId,
                rectangular.copy(items = mapOf("bad-y" to PreviewItem(0, 0, 64, "item.bad_y")))
            ).isFailure
        )
        assertTrue(
            store.publish(
                exportId,
                rectangular.copy(items = mapOf("bad-x" to PreviewItem(0, 2048, 0, "item.bad_x")))
            ).isFailure
        )
        assertEquals(true, store.publish(exportId, rectangular).getOrThrow())
        assertEquals(rectangular, store.readReusable().getOrThrow())

        val legacy = rectangular.copy(
            pages = listOf(PreviewPage("texture_pages/$exportId/0.png", 8192, 8192)),
            items = mapOf("a" to PreviewItem(0, 8128, 8128, "item.a"))
        )
        assertEquals(true, store.publish(exportId, legacy).getOrThrow())
        assertEquals(legacy, store.readReusable().getOrThrow())
    }

    @Test
    fun staleAndInvalidatedGenerationsCannotPublish(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val first = store.beginGeneration()
        writePage(root, first)
        val second = store.beginGeneration()
        writePage(root, second)

        assertEquals(false, store.publish(first, manifest(first)).getOrThrow())
        store.invalidate()
        assertEquals(false, store.publish(second, manifest(second)).getOrThrow())
        assertFalse(Files.exists(root.resolve("manifest.json")))
    }

    @Test
    fun atomicMoveFailurePreservesPreviousManifest(): Unit = withTempRoot { root ->
        var failMove = false
        val store = PreviewExportStore(root) { source, target ->
            if (failMove) error("injected atomic move failure")
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val first = store.beginGeneration()
        writePage(root, first)
        val firstManifest = manifest(first)
        assertEquals(true, store.publish(first, firstManifest).getOrThrow())

        val second = store.beginGeneration()
        writePage(root, second)
        failMove = true
        assertTrue(store.publish(second, manifest(second)).isFailure)
        assertEquals(firstManifest, store.readReusable().getOrThrow())
    }

    @Test
    fun generationChangesWaitForPublicationLock(): Unit = withTempRoot { root ->
        val moveEntered = CountDownLatch(1)
        val releaseMove = CountDownLatch(1)
        val store = PreviewExportStore(root) { source, target ->
            moveEntered.countDown()
            check(releaseMove.await(5, TimeUnit.SECONDS)) { "timed out waiting to release atomic move" }
            Files.move(
                source,
                target,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
        val first = store.beginGeneration()
        writePage(root, first)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publication = executor.submit<Boolean> { store.publish(first, manifest(first)).getOrThrow() }
            assertTrue(moveEntered.await(5, TimeUnit.SECONDS))

            val generationChangeStarted = CountDownLatch(1)
            val generationChangeFinished = CountDownLatch(1)
            val generationChange = executor.submit {
                generationChangeStarted.countDown()
                store.invalidate()
                store.beginGeneration()
                generationChangeFinished.countDown()
            }
            assertTrue(generationChangeStarted.await(5, TimeUnit.SECONDS))
            assertFalse(generationChangeFinished.await(100, TimeUnit.MILLISECONDS))

            releaseMove.countDown()
            assertTrue(publication.get(5, TimeUnit.SECONDS))
            assertTrue(generationChangeFinished.await(5, TimeUnit.SECONDS))
            generationChange.get(5, TimeUnit.SECONDS)
            assertEquals(false, store.publish(first, manifest(first)).getOrThrow())
        } finally {
            releaseMove.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun writesStableLanguageFileAndUsesItsGeneration(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val exportId = store.beginGeneration()
        val relative = store.writeLanguage(exportId, mapOf("z" to "最后", "a" to "第一")).getOrThrow()
        assertEquals("lang/$exportId/zh_cn.json", relative)
        assertEquals("{\"a\":\"第一\",\"z\":\"最后\"}", Files.readString(root.resolve(relative)))

        val manifest = PreviewManifest(
            pages = emptyList(),
            languageFile = relative,
            items = emptyMap()
        )
        assertEquals(true, store.publish(exportId, manifest).getOrThrow())
        assertEquals(manifest, store.readReusable().getOrThrow())
    }

    private fun manifest(exportId: String): PreviewManifest = PreviewManifest(
        pages = listOf(PreviewPage("texture_pages/$exportId/0.png", 64, 64)),
        items = mapOf("example:item" to PreviewItem(0, 0, 0, "item.example"))
    )

    private fun writePage(root: Path, exportId: String) {
        root.resolve("texture_pages/$exportId").createDirectories()
        Files.write(root.resolve("texture_pages/$exportId/0.png"), byteArrayOf(0))
    }

    private fun withTempRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("preview-export-test-")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
