package calebxzhou.rdi.client.service.content

import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Entry
import calebxzhou.rdi.common.model.Task2Snapshot
import calebxzhou.rdi.common.model.Task2Status
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientContentMigrationStartupTest {
    @Test
    fun `mod migration progress only follows active mod migration task`() {
        for (status in listOf(Task2Status.QUEUED, Task2Status.RUNNING)) {
            assertTrue(
                isClientModMigrationInProgress(
                    listOf(entry("mod", CLIENT_CONTENT_MOD_MIGRATION_DEDUPE_KEY, status))
                )
            )
        }
        for (status in listOf(Task2Status.DONE, Task2Status.FAILED, Task2Status.CANCELLED)) {
            assertFalse(
                isClientModMigrationInProgress(
                    listOf(entry("mod", CLIENT_CONTENT_MOD_MIGRATION_DEDUPE_KEY, status))
                )
            )
        }
        assertFalse(
            isClientModMigrationInProgress(
                listOf(entry("pack", CLIENT_CONTENT_MIGRATION_DEDUPE_KEY, Task2Status.RUNNING))
            )
        )
    }

    private fun entry(runId: String, dedupeKey: String, status: Task2Status) = Task2Entry(
        runId = runId,
        task = Task2.Leaf(title = runId, id = runId) { _ -> },
        dedupeKey = dedupeKey,
        snapshot = Task2Snapshot(status = status),
    )
}
