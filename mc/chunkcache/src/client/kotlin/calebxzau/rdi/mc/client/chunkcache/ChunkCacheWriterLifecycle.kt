package calebxzau.rdi.mc.client.chunkcache

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Supplier

/** Serializes file ownership transitions. Producers never join a disk writer. */
class ChunkCacheWriterLifecycle {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "rdi-chunk-cache-lifecycle").apply { isDaemon = true }
    }
    // Access only on executor. At most one queue worker owns open region files.
    private var previous: ChunkCacheWriterHandle? = null

    fun <T : ChunkCacheWriterHandle> open(
        cancelled: BooleanSupplier,
        create: Supplier<T>,
        publish: Consumer<T>,
        onFailure: Consumer<Throwable>,
    ) {
        executor.execute {
            try {
                previous?.let {
                    it.discardPending()
                    it.closeAsync()
                    it.awaitClosed()
                }
                previous = null
                if (!cancelled.asBoolean) {
                    val queue = create.get()
                    previous = queue
                    publish.accept(queue)
                    // Cancellation may race creation/publication; close only after publishing ownership.
                    if (cancelled.asBoolean) queue.closeAsync()
                }
            } catch (failure: Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                previous?.closeAsync()
                onFailure.accept(failure)
            }
        }
    }

    @Throws(InterruptedException::class, ExecutionException::class)
    fun shutdown(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        val closing = executor.submit {
            previous?.let {
                it.closeAsync()
                it.awaitClosed()
            }
        }
        return try {
            closing.get(timeoutMillis, TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            false
        } finally {
            // A timed-out disk close remains on its daemon owner, never force-closed by the caller.
            executor.shutdown()
        }
    }
}
