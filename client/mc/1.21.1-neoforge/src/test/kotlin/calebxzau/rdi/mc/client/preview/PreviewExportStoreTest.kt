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
        writePage(store, exportId)

        val manifest = manifest()
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
            pages = listOf(PreviewPage("texture_pages/0.png", 64, 64)),
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
        writePage(store, exportId)

        val outOfBounds = manifest().copy(
            items = mapOf("a" to PreviewItem(0, 64, 0, "item.a"))
        )
        assertTrue(store.publish(exportId, outOfBounds).isFailure)

        val duplicate = manifest().copy(
            items = mapOf(
                "a" to PreviewItem(0, 0, 0, "item.a"),
                "b" to PreviewItem(0, 0, 0, "item.b")
            )
        )
        assertTrue(store.publish(exportId, duplicate).isFailure)

        val overflow = manifest().copy(
            items = mapOf("overflow" to PreviewItem(0, Int.MAX_VALUE - 63, 0, "item.overflow"))
        )
        assertTrue(store.publish(exportId, overflow).isFailure)
    }

    @Test
    fun acceptsRectangularPageAndKeepsLegacySquarePagesReadable(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val exportId = store.beginGeneration()
        writePage(store, exportId)
        val rectangular = PreviewManifest(
            pages = listOf(PreviewPage("texture_pages/0.png", 2048, 64)),
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
            pages = listOf(PreviewPage("texture_pages/0.png", 8192, 8192)),
            items = mapOf("a" to PreviewItem(0, 8128, 8128, "item.a"))
        )
        // Existing UUID exports remain readable, but new publication uses fixed paths.
        val legacyManifest = legacy.copy(pages = listOf(PreviewPage("texture_pages/$exportId/0.png", 8192, 8192)))
        root.resolve("texture_pages/$exportId").createDirectories()
        Files.write(root.resolve("texture_pages/$exportId/0.png"), byteArrayOf(0))
        Files.writeString(root.resolve("manifest.json"), Json.encodeToString(legacyManifest))
        assertEquals(legacyManifest, store.readReusable().getOrThrow())
    }

    @Test
    fun staleAndInvalidatedGenerationsCannotPublish(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val first = store.beginGeneration()
        writePage(store, first)
        val second = store.beginGeneration()
        writePage(store, second)

        assertEquals(false, store.publish(first, manifest()).getOrThrow())
        store.invalidate()
        assertEquals(false, store.publish(second, manifest()).getOrThrow())
        assertFalse(Files.exists(root.resolve("manifest.json")))
    }

    @Test
    fun promotionFailureRestoresPreviousCompleteExport(): Unit = withTempRoot { root ->
        var failPromotion = false
        val store = PreviewExportStore(root) { source, target ->
            if (failPromotion && source.fileName.toString() == ".pending") error("injected promotion failure")
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        val first = store.beginGeneration()
        writePage(store, first)
        val language = store.writeLanguage(first, mapOf("a" to "旧语言")).getOrThrow()
        val firstManifest = manifest().copy(languageFile = language)
        assertEquals(true, store.publish(first, firstManifest).getOrThrow())

        val second = store.beginGeneration()
        Files.write(store.pageDestination(second, 0), byteArrayOf(1))
        store.writeLanguage(second, mapOf("a" to "新语言")).getOrThrow()
        failPromotion = true
        assertTrue(store.publish(second, manifest().copy(languageFile = language)).isFailure)
        assertEquals(firstManifest, store.readReusable().getOrThrow())
        assertEquals(listOf<Byte>(0), Files.readAllBytes(root.resolve("texture_pages/0.png")).toList())
        assertEquals("{\"a\":\"旧语言\"}", Files.readString(root.resolve(language)))
    }

    @Test
    fun stagesWholeSnapshotAndReplacesPagesLanguageAndManifestTogether(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val first = store.beginGeneration()
        writePage(store, first)
        val language = store.writeLanguage(first, mapOf("a" to "旧语言")).getOrThrow()
        assertTrue(store.publish(first, manifest().copy(languageFile = language)).getOrThrow())

        val second = store.beginGeneration()
        Files.write(store.pageDestination(second, 0), byteArrayOf(1))
        assertEquals(listOf<Byte>(0), Files.readAllBytes(root.resolve("texture_pages/0.png")).toList())
        assertTrue(Files.exists(root.resolve(language)))
        assertFalse(Files.exists(store.pendingRoot.resolve("manifest.json")))
        assertTrue(store.publish(second, manifest()).getOrThrow())
        assertEquals(listOf<Byte>(1), Files.readAllBytes(root.resolve("texture_pages/0.png")).toList())
        assertFalse(Files.exists(root.resolve(language)))
        assertFalse(Files.exists(store.pendingRoot))
        assertEquals(manifest(), store.readReusable().getOrThrow())
        assertTrue(Files.exists(root.resolveSibling(".preview-backup").resolve(language)))
    }

    @Test
    fun nextGenerationClearsAbandonedPendingFiles(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val first = store.beginGeneration()
        writePage(store, first)
        store.writeLanguage(first, mapOf("a" to "废弃语言")).getOrThrow()
        Files.write(store.pageDestination(first, 1), byteArrayOf(9))
        store.invalidate()
        val second = store.beginGeneration()
        writePage(store, second)
        assertFalse(Files.exists(store.pendingRoot.resolve("texture_pages/1.png")))
        assertFalse(Files.exists(store.pendingRoot.resolve("lang/zh_cn.json")))
        assertTrue(store.publish(second, manifest()).getOrThrow())
    }

    @Test
    fun restartRestoresBackupAfterInterruptedSwitch(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val first = store.beginGeneration()
        writePage(store, first)
        assertTrue(store.publish(first, manifest()).getOrThrow())
        Files.move(root.resolveSibling(".preview-backup"), root.resolveSibling("initial-empty-backup"))
        Files.move(root, root.resolveSibling(".preview-backup"))
        val restarted = PreviewExportStore(root)
        restarted.recover().getOrThrow()
        assertFalse(Files.exists(restarted.pendingRoot))
        assertEquals(manifest(), restarted.readReusable().getOrThrow())
        assertTrue(Files.exists(root.resolve("texture_pages/0.png")))
    }

    @Test
    fun rollbackFailureRemainsRecoverableAfterRestart(): Unit = withTempRoot { root ->
        var failMoveToRoot = false
        val store = PreviewExportStore(root) { source, target ->
            if (failMoveToRoot && target == root) error("injected switch and rollback failure")
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        val first = store.beginGeneration()
        writePage(store, first)
        assertTrue(store.publish(first, manifest()).getOrThrow())
        val second = store.beginGeneration()
        Files.write(store.pageDestination(second, 0), byteArrayOf(1))
        failMoveToRoot = true
        val failure = store.publish(second, manifest()).exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1, failure.suppressed.size)
        assertEquals(manifest(), PreviewExportStore(root).readReusable().getOrThrow())
        assertEquals(listOf<Byte>(0), Files.readAllBytes(root.resolve("texture_pages/0.png")).toList())
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
        writePage(store, first)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publication = executor.submit<Boolean> { store.publish(first, manifest()).getOrThrow() }
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
            assertEquals(false, store.publish(first, manifest()).getOrThrow())
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
        assertEquals("lang/zh_cn.json", relative)
        assertEquals("{\"a\":\"第一\",\"z\":\"最后\"}", Files.readString(store.pendingRoot.resolve(relative)))

        val manifest = PreviewManifest(
            pages = emptyList(),
            languageFile = relative,
            items = emptyMap()
        )
        assertEquals(true, store.publish(exportId, manifest).getOrThrow())
        assertEquals(manifest, store.readReusable().getOrThrow())
    }

    @Test
    fun recipesPublishWithSnapshotAndSurvivePromotionFailure(): Unit = withTempRoot { root ->
        var failPromotion = false
        val store = PreviewExportStore(root) { source, target ->
            if (failPromotion && source.fileName.toString() == ".pending") error("injected promotion failure")
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        val first = store.beginGeneration()
        writePage(store, first)
        val oldJson = "{\"recipes\":{\"test:old\":{}},\"failedRecipes\":{}}"
        val recipeFile = store.writeRecipes(first, oldJson).getOrThrow()
        val firstManifest = manifest().copy(recipeFile = recipeFile)
        assertFalse(Files.exists(root.resolve(recipeFile)))
        assertTrue(store.publish(first, firstManifest).getOrThrow())
        assertEquals(oldJson, Files.readString(root.resolve(recipeFile)))
        assertEquals(firstManifest, store.readReusable().getOrThrow())

        val second = store.beginGeneration()
        writePage(store, second)
        val newJson = "{\"recipes\":{},\"failedRecipes\":{\"test:broken\":\"bad recipe\"}}"
        store.writeRecipes(second, newJson).getOrThrow()
        failPromotion = true
        assertTrue(store.publish(second, firstManifest).isFailure)
        assertEquals(oldJson, Files.readString(root.resolve(recipeFile)))
        assertEquals(firstManifest, store.readReusable().getOrThrow())
        failPromotion = false
        assertTrue(store.publish(second, firstManifest).getOrThrow())
        assertEquals(newJson, Files.readString(root.resolve(recipeFile)))
    }

    @Test
    fun rejectsMissingRecipeFileAndStaleRecipeWriter(): Unit = withTempRoot { root ->
        val store = PreviewExportStore(root)
        val first = store.beginGeneration()
        writePage(store, first)
        assertTrue(store.publish(first, manifest().copy(recipeFile = "recipes.json")).isFailure)
        assertTrue(store.publish(first, manifest().copy(recipeFile = "../recipes.json")).isFailure)
        store.invalidate()
        val second = store.beginGeneration()
        writePage(store, second)
        assertTrue(store.writeRecipes(first, "{}").isFailure)
        assertFalse(Files.exists(store.pendingRoot.resolve("recipes.json")))
    }

    private fun manifest(): PreviewManifest = PreviewManifest(
        pages = listOf(PreviewPage("texture_pages/0.png", 64, 64)),
        items = mapOf("example:item" to PreviewItem(0, 0, 0, "item.example"))
    )

    private fun writePage(store: PreviewExportStore, exportId: String) {
        Files.write(store.pageDestination(exportId, 0), byteArrayOf(0))
    }

    private fun withTempRoot(block: (Path) -> Unit) {
        val container = Files.createTempDirectory("preview-export-test-")
        val root = container.resolve("preview").createDirectories()
        try {
            block(root)
        } finally {
            Files.walk(container).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
