package calebxzau.rdi.mc.client.preview

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ReceivingLevelScreen
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.core.registries.BuiltInRegistries
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory

/** Coordinates item enumeration, render-thread work, and bounded PNG I/O. */
object ItemPreviewExporter {
    private val logger = LoggerFactory.getLogger(ItemPreviewExporter::class.java)
    private const val FRAME_BUDGET_NANOS = 2_000_000L
    private const val MAX_ITEMS_PER_FRAME = 32
    private const val MAX_RECIPES_PER_FRAME = 32
    private const val PROGRESS_INTERVAL_NANOS = 250_000_000L

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor(ThreadFactory { task ->
        Thread(task, "rdi-item-preview-io").apply { isDaemon = true }
    })
    private val requests = PreviewExportRequests()

    private var minecraft: Minecraft? = null
    @Volatile
    private var store: PreviewExportStore? = null
    private var initialized = false
    private var shuttingDown = false
    private var ioOperation: IoOperation? = null
    private var job: Job? = null
    private var renderer: PreviewAtlasRenderer? = null
    private var readbackQueued = false

    private enum class IoKind { Reuse, PageWrite, Publish }

    private data class IoOperation(
        val epoch: Long,
        val exportId: String?,
        val kind: IoKind,
        val future: Future<Any?>,
        val page: PreviewPagePlan? = null,
        val pagePath: String? = null
    )

    private data class Entry(val id: String, val stack: ItemStack, val translationKey: String)

    private class Job(
        val id: String,
        val epoch: Long,
        val entries: List<Entry>,
        val pagePlans: List<PreviewPagePlan>,
        val language: Map<String, String>?,
        val recipes: PreviewRecipeExport,
        val startedAtNanos: Long = System.nanoTime(),
        val pages: ArrayList<PreviewPage> = ArrayList(),
        val items: LinkedHashMap<String, PreviewItem> = LinkedHashMap(),
        val failedItems: LinkedHashMap<String, String> = LinkedHashMap(),
        var itemIndex: Int = 0,
        var lastProgressAtNanos: Long = 0L
    ) {
        val pipeline = PreviewPagePipeline(pagePlans.size)
    }

    fun forceExport() = onRenderThread {
        if (shuttingDown) return@onRenderThread
        ensureInitialized()
        store?.invalidate()
        requests.forceRequested()
        retireCurrentJob()
    }

    fun onWorldEntered() = onRenderThread {
        if (shuttingDown) return@onRenderThread
        ensureInitialized()
        requests.worldEntered()
    }

    fun onWorldLeft() = onRenderThread {
        if (shuttingDown) return@onRenderThread
        ensureInitialized()
        store?.invalidate()
        requests.worldLeft()
        retireCurrentJob()
    }

    fun onResourceReloadStarted() = onRenderThread {
        if (shuttingDown) return@onRenderThread
        ensureInitialized()
        requests.reloadStarted()
        store?.invalidate()
        retireCurrentJob()
    }

    fun onResourceLoadFinished() = onRenderThread {
        if (shuttingDown) return@onRenderThread
        ensureInitialized()
        if (requests.snapshot().reloadInProgress) {
            requests.reloadFinished(success = true)
        } else {
            requests.initialResourcesReady()
        }
    }

    fun advance() = onRenderThread {
        if (shuttingDown) return@onRenderThread
        ensureInitialized()
        consumeIoOperation()
        if (!resourcesUsable()) return@onRenderThread

        val frameDeadline = System.nanoTime() + FRAME_BUDGET_NANOS
        pollReadback(frameDeadline)
        if (ioOperation == null && job == null) {
            requests.consumeStartIfReady(clientReady = true)?.let(::beginStart)
        }
        if (job != null && !readbackQueued) renderSomeItems(frameDeadline)
        job?.let { currentJob ->
            if (currentJob.pipeline.readyToPublish && ioOperation == null) encodeSomeRecipes(currentJob, frameDeadline)
        }
        job?.let { showProgress(it) }
    }

    fun shutdown() = onRenderThread {
        if (shuttingDown) return@onRenderThread
        shuttingDown = true
        try {
            store?.invalidate()
            retireCurrentJob()
        } finally {
            ioExecutor.shutdown()
        }
    }

    private fun onRenderThread(action: () -> Unit) {
        if (RenderSystem.isOnRenderThread()) {
            action()
        } else {
            store?.invalidate()
            val current = minecraft ?: Minecraft.getInstance()
            current.execute(action)
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        val current = Minecraft.getInstance()
        minecraft = current
        val currentStore = PreviewExportStore(current.gameDirectory.toPath().resolve("rdi").resolve("preview"))
        store = currentStore
        initialized = true
        // Recovery restores an interrupted directory switch without requesting an export.
        ioExecutor.execute {
            currentStore.recover().onFailure { logger.error("Failed to recover item preview export", it) }
        }
    }

    private fun resourcesUsable(): Boolean {
        val current = minecraft ?: return false
        val request = requests.snapshot()
        val level = current.level ?: return false
        val player = current.player ?: return false
        return request.worldJoined && request.resourcesReady && !request.reloadInProgress &&
            player.level() === level && player.tickCount > 0 && current.overlay == null &&
            current.screen !is ReceivingLevelScreen
    }

    private fun beginStart(start: PreviewExportRequests.Start) {
        when (start.kind) {
            PreviewExportRequests.StartKind.ReuseCheck -> beginReusableCheck(start.epoch)
            PreviewExportRequests.StartKind.FreshExport -> beginFreshExport(start.epoch)
        }
    }

    private fun beginReusableCheck(epoch: Long) {
        val currentStore = store ?: return
        submitIo(epoch, null, IoKind.Reuse) {
            currentStore.readReusable()
        }
    }

    private fun beginFreshExport(epoch: Long) {
        val currentStore = store ?: return
        val currentMinecraft = minecraft ?: return
        try {
            val id = currentStore.beginGeneration()
            val failed = LinkedHashMap<String, String>()
            val entries = BuiltInRegistries.ITEM.asSequence()
                .map { item ->
                    val idString = BuiltInRegistries.ITEM.getKey(item).toString()
                    try {
                        val stack = item.defaultInstance
                        if (item === Items.AIR || stack.isEmpty) null else Entry(idString, stack, stack.descriptionId)
                    } catch (throwable: Exception) {
                        failed[idString] = reasonFor(throwable)
                        logger.warn("Failed to prepare item preview {}", idString, throwable)
                        null
                    }
                }
                .sortedBy { it?.id }
                .filterNotNull()
                .toList()
            val selectedLanguage = if (currentMinecraft.options.languageCode == "zh_cn") {
                Language.getInstance().languageData.toSortedMap()
            } else {
                null
            }
            val level = checkNotNull(currentMinecraft.level)
            val recipes = PreviewRecipeExport(level.recipeManager.recipes, level.registryAccess())
            val atlas = if (entries.isEmpty()) null else PreviewAtlasRenderer.create(entries.size)
            val selectedPlans = atlas?.pagePlans ?: emptyList()
            val newJob = Job(id, epoch, entries, selectedPlans, selectedLanguage, recipes)
            newJob.failedItems.putAll(failed)
            job = newJob
            renderer = atlas
            logger.info(
                "Started item preview export {}: Minecraft={}, NeoForge={}, packs={}, launchedVersion={}, items={}, atlas={}x{}",
                id,
                SharedConstants.getCurrentVersion().getName(),
                NeoForgeVersion.getVersion(),
                currentMinecraft.resourcePackRepository.selectedIds,
                currentMinecraft.launchedVersion,
                entries.size,
                atlas?.targetWidth ?: 0,
                atlas?.targetHeight ?: 0
            )
            showProgress(newJob, force = true)
        } catch (throwable: Throwable) {
            abortGeneration(throwable)
        }
    }

    private fun renderSomeItems(frameDeadline: Long) {
        val currentJob = job ?: return
        val atlas = renderer ?: return
        val pageIndex = currentJob.pipeline.renderPageIndex
        val plan = currentJob.pagePlans.getOrNull(pageIndex) ?: return
        val pageEnd = plan.firstItemIndex + plan.itemCount
        if (currentJob.itemIndex >= pageEnd) {
            if (!readbackQueued && !currentJob.pipeline.writerBusy) queueReadback(plan)
            return
        }
        if (System.nanoTime() >= frameDeadline) return
        var processed = 0
        try {
            atlas.beginBatch().use { batch ->
                while (currentJob.itemIndex < pageEnd && processed < MAX_ITEMS_PER_FRAME &&
                    System.nanoTime() < frameDeadline
                ) {
                    val entry = currentJob.entries[currentJob.itemIndex]
                    val position = plan.position(currentJob.itemIndex - plan.firstItemIndex)
                    try {
                        batch.renderItem(entry.stack, position.x, position.y, plan.height)
                        currentJob.items[entry.id] = PreviewItem(position.page, position.x, position.y, entry.translationKey)
                    } catch (throwable: PreviewItemRenderException) {
                        currentJob.failedItems[entry.id] = reasonFor(throwable)
                        logger.warn("Failed to render item preview {}", entry.id, throwable)
                    }
                    currentJob.itemIndex++
                    processed++
                }
            }
        } catch (throwable: Throwable) {
            abortGeneration(throwable)
            return
        }
        if (currentJob.itemIndex >= pageEnd && !readbackQueued && !currentJob.pipeline.writerBusy) queueReadback(plan)
    }

    private fun queueReadback(plan: PreviewPagePlan) {
        val atlas = renderer ?: return
        val currentJob = job ?: return
        if (currentJob.entries.isEmpty()) return
        try {
            atlas.beginReadback(plan)
            readbackQueued = true
        } catch (throwable: Throwable) {
            abortGeneration(throwable)
        }
    }

    private fun pollReadback(frameDeadline: Long) {
        if (!readbackQueued) return
        val image = try {
            renderer?.pollReadback(frameDeadline)
        } catch (throwable: Throwable) {
            abortGeneration(throwable)
            return
        } ?: return
        readbackQueued = false
        val currentJob = job ?: run {
            image.close()
            return
        }
        val pageIndex = currentJob.pipeline.renderPageIndex
        val plan = currentJob.pagePlans[pageIndex]
        val path = "texture_pages/$pageIndex.png"
        submitPageWrite(currentJob, plan, image, path)
    }

    private fun submitPageWrite(currentJob: Job, plan: PreviewPagePlan, image: NativeImage, path: String) {
        var owned: NativeImage? = image
        try {
            val transferredImage = owned!!
            val future = ioExecutor.submit<Any?> {
                try {
                    val destination = store!!.pageDestination(currentJob.id, plan.page)
                    transferredImage.writeToFile(destination)
                    Result.success(Unit)
                } catch (throwable: Throwable) {
                    Result.failure(throwable)
                } finally {
                    transferredImage.close()
                }
            }
            owned = null
            currentJob.pipeline.submitPage(plan.page)
            ioOperation = IoOperation(currentJob.epoch, currentJob.id, IoKind.PageWrite, future, plan, path)
            currentJob.itemIndex = plan.firstItemIndex + plan.itemCount
            if (currentJob.pipeline.renderPageIndex < currentJob.pagePlans.size) {
                try {
                    renderer?.clearPage()
                } catch (throwable: Throwable) {
                    abortGeneration(throwable)
                }
            }
        } catch (throwable: Throwable) {
            owned?.close()
            abortGeneration(throwable)
        }
    }

    private fun encodeSomeRecipes(currentJob: Job, frameDeadline: Long) {
        var processed = 0
        while (!currentJob.recipes.complete && processed < MAX_RECIPES_PER_FRAME && System.nanoTime() < frameDeadline) {
            val id = currentJob.recipes.nextId
            currentJob.recipes.encodeNext().onFailure { logger.warn("Failed to export recipe {}", id, it) }
            processed++
        }
        if (currentJob.recipes.complete) schedulePublish(currentJob)
    }

    private fun schedulePublish(currentJob: Job) {
        val currentStore = store ?: return
        val manifestWithoutLanguage = PreviewManifest(
            pages = currentJob.pages.toList(),
            languageFile = null,
            items = currentJob.items.toMap(),
            failedItems = currentJob.failedItems.toMap()
        )
        val language = currentJob.language
        submitIo(currentJob.epoch, currentJob.id, IoKind.Publish) {
            runCatching {
                val languagePath = language?.let { currentStore.writeLanguage(currentJob.id, it).getOrThrow() }
                val recipePath = currentStore.writeRecipes(currentJob.id, currentJob.recipes.toJson()).getOrThrow()
                val manifest = manifestWithoutLanguage.copy(languageFile = languagePath, recipeFile = recipePath)
                currentStore.publish(currentJob.id, manifest).getOrThrow()
            }
        }
    }

    private fun consumeIoOperation() {
        val operation = ioOperation ?: return
        if (!operation.future.isDone) return
        ioOperation = null
        val result = runCatching { operation.future.get() }.getOrElse { Result.failure<Any?>(it) }
        val currentJob = job
        if (operation.epoch != requests.snapshot().epoch ||
            (operation.exportId != null && operation.exportId != currentJob?.id)
        ) return
        when (operation.kind) {
            IoKind.Reuse -> {
                @Suppress("UNCHECKED_CAST")
                val reusable = result as Result<PreviewManifest?>
                if (reusable.isSuccess && reusable.getOrNull() != null) {
                    logger.info("Reused existing item preview export")
                } else {
                    if (reusable.isFailure) logger.warn("Existing item preview export is invalid", reusable.exceptionOrNull())
                }
                requests.reusableCompleted(operation.epoch, found = reusable.isSuccess && reusable.getOrNull() != null)
            }
            IoKind.PageWrite -> {
                @Suppress("UNCHECKED_CAST")
                val written = result as Result<Unit>
                if (written.isFailure) {
                    abortGeneration(written.exceptionOrNull()!!)
                } else if (currentJob != null) {
                    val page = checkNotNull(operation.page)
                    check(currentJob.pipeline.completePage(page.page)) {
                        "duplicate or out-of-order preview page completion: ${page.page}"
                    }
                    currentJob.pages += PreviewPage(
                        checkNotNull(operation.pagePath),
                        page.width,
                        page.height
                    )
                    logger.info(
                        "Wrote item preview page {}/{} for {}",
                        page.page + 1,
                        currentJob.pagePlans.size,
                        currentJob.id
                    )
                }
            }
            IoKind.Publish -> {
                @Suppress("UNCHECKED_CAST")
                val published = result as Result<Boolean>
                val success = published.getOrElse {
                    abortGeneration(it)
                    false
                }
                if (job != null) {
                    if (success) {
                        val completed = job!!
                        logger.info(
                            "Published item preview export {}: pages={}, rendered={}, failed={}, elapsedMs={}",
                            completed.id,
                            completed.pages.size,
                            completed.items.size,
                            completed.failedItems.size,
                            (System.nanoTime() - completed.startedAtNanos) / 1_000_000L
                        )
                        minecraft?.player?.displayClientMessage(Component.literal("物品预览导出完成"), true)
                        minecraft?.gui?.chat?.addMessage(Component.literal(
                            "物品预览导出完成：${completed.items.size}个物品，${completed.pages.size}张图集，失败${completed.failedItems.size}个；配方${completed.recipes.successfulCount}条，失败${completed.recipes.failedCount}条。已保存至rdi/preview/"
                        ))
                    }
                    requests.freshExportSettled()
                    retireCurrentJob()
                }
            }
        }
    }

    private fun submitIo(epoch: Long, exportId: String?, kind: IoKind, action: () -> Any?) {
        check(ioOperation == null) { "preview I/O operation already in flight" }
        try {
            val future = ioExecutor.submit(Callable { action() })
            ioOperation = IoOperation(epoch, exportId, kind, future)
        } catch (throwable: RejectedExecutionException) {
            abortGeneration(throwable)
        }
    }

    private fun showProgress(currentJob: Job, force: Boolean = false) {
        val player = minecraft?.player ?: return
        val now = System.nanoTime()
        if (!force && now - currentJob.lastProgressAtNanos < PROGRESS_INTERVAL_NANOS) return
        currentJob.lastProgressAtNanos = now
        val message = if (currentJob.pipeline.readyToPublish) {
            val recipes = currentJob.recipes
            val percent = if (recipes.total == 0) 100L else recipes.processed.toLong() * 100 / recipes.total
            val status = if (recipes.complete) "，正在保存导出" else ""
            "配方导出：${recipes.processed}/${recipes.total}（${percent}%）${status}"
        } else {
            val total = currentJob.entries.size
            val processed = currentJob.itemIndex
            val percent = if (total == 0) 100L else processed.toLong() * 100 / total
            val status = if (processed == total) "，正在保存图集" else ""
            "物品预览导出：${processed}/${total}（${percent}%）${status}"
        }
        player.displayClientMessage(Component.literal(message), true)
    }

    private fun retireCurrentJob() {
        val oldRenderer = renderer
        renderer = null
        job = null
        readbackQueued = false
        runCatching { oldRenderer?.close() }
            .onFailure { logger.error("Failed to release item preview renderer", it) }
    }

    private fun abortGeneration(throwable: Throwable) {
        logger.error("Item preview export aborted", throwable)
        val message = Component.literal("物品预览导出失败，请查看日志")
        minecraft?.player?.displayClientMessage(message, true)
        minecraft?.gui?.chat?.addMessage(message)
        store?.invalidate()
        requests.freshExportSettled()
        retireCurrentJob()
    }

    private fun reasonFor(throwable: Throwable): String = throwable.javaClass.simpleName
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .lowercase()
        .ifBlank { "render_failed" }
}
