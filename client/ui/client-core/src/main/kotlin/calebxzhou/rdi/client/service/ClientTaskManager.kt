package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Entry
import calebxzhou.rdi.common.service.Task2Controller
import calebxzhou.rdi.common.service.Task2Manager
import kotlinx.coroutines.flow.StateFlow

object ClientTaskManager {
    private val manager = Task2Manager()

    val entries: StateFlow<List<Task2Entry>>
        get() = manager.entries

    fun submit(task: Task2, dedupeKey: String? = null, autoStart: Boolean = true): String =
        manager.submit(task, dedupeKey, autoStart)

    fun start(runId: String) {
        manager.start(runId)
    }

    fun cancel(runId: String, message: String = "任务已取消") {
        manager.cancel(runId, message)
    }

    fun remove(runId: String) {
        manager.remove(runId)
    }

    fun clearFinished() {
        manager.clearFinished()
    }

    fun entry(runId: String): Task2Entry? = manager.entry(runId)

    fun controller(runId: String): Task2Controller? = manager.controller(runId)
}
