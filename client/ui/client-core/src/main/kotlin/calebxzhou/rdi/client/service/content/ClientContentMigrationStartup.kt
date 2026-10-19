package calebxzhou.rdi.client.service.content

import calebxzhou.rdi.client.service.ClientDirs
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Entry
import calebxzhou.rdi.common.model.Task2Progress
import calebxzau.rdi.common.logging.Loggers
import java.util.concurrent.atomic.AtomicBoolean

private val lgr by Loggers

internal const val CLIENT_CONTENT_MIGRATION_DEDUPE_KEY = "client-content-migration-startup"
internal const val CLIENT_CONTENT_MOD_MIGRATION_DEDUPE_KEY = "client-content-mod-migration-startup"

fun isClientModMigrationInProgress(entries: List<Task2Entry>): Boolean =
    entries.any { entry ->
        entry.dedupeKey == CLIENT_CONTENT_MOD_MIGRATION_DEDUPE_KEY && !entry.status.isTerminal
    }

private object ClientContentMigrationStartup {
    private val submitted = AtomicBoolean(false)

    fun submit(): String? {
        if (!submitted.compareAndSet(false, true)) return null

        val migrator = ClientContentMigrator(
            sourceRoot = ClientDirs.dlModsDir.toPath(),
            destinationRoot = ClientDirs.dlcDir.toPath(),
            packSourceRoot = ClientDirs.dlPacksDir.toPath(),
        )
        val dedupeKey = try {
            val availability = migrator.migrationAvailability()
            if (!availability.hasMigratableFiles) return null
            if (availability.hasMigratableModFiles) {
                CLIENT_CONTENT_MOD_MIGRATION_DEDUPE_KEY
            } else {
                CLIENT_CONTENT_MIGRATION_DEDUPE_KEY
            }
        } catch (error: Throwable) {
            lgr.warn(error) { "检查客户端旧内容目录失败，将继续注册整理任务" }
            CLIENT_CONTENT_MIGRATION_DEDUPE_KEY
        }

        return ClientTaskManager.submit(
            task = Task2.Leaf("整理本地文件") { context ->
                val summary = migrator.migrate(context)
                context.emit(
                    Task2Progress(
                        message = summary.message,
                        fraction = 1f,
                        completedItems = summary.totalItems,
                        totalItems = summary.totalItems,
                        completedBytes = summary.completedBytes,
                        totalBytes = summary.totalBytes,
                    )
                )
            },
            dedupeKey = dedupeKey,
        )
    }
}

fun submitClientContentMigrationOnStartup(): String? = ClientContentMigrationStartup.submit()
