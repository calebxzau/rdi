package calebxzau.rdi.client.service

import calebxzau.rdi.common.model.HostWorldDownloadProgressVo
import calebxzau.rdi.common.model.HostWorldDownloadReadyVo
import calebxzau.rdi.common.model.HostWorldDownloadStage
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.serdesJson
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostWorldDownloadApiTest {
    private val ready = HostWorldDownloadReadyVo("build", bytes = 10, sha1 = "abc", chunkCount = 3)

    @Test
    fun readyEndsTheWaitEvenThoughTheStreamReconnects(): Unit = runBlocking {
        val progress = mutableListOf<HostWorldDownloadProgressVo>()
        val events = flow {
            emit(ServerSentEvent(event = "progress", data = serdesJson.encodeToString(HostWorldDownloadProgressVo(HostWorldDownloadStage.SyncChunks, 1, 3))))
            emit(ServerSentEvent(event = "ready", data = serdesJson.encodeToString(ready)))
            // The SSE plugin reconnects after the server closes the stream, so it never completes.
            awaitCancellation()
        }

        val result = withTimeout(5_000) { events.awaitReadyEvent { progress += it } }

        assertEquals(ready, result)
        assertEquals(listOf(HostWorldDownloadProgressVo(HostWorldDownloadStage.SyncChunks, 1, 3)), progress)
    }

    @Test
    fun reusedArchiveSendsOnlyReady(): Unit = runBlocking {
        val events = flow {
            emit(ServerSentEvent(event = "ready", data = serdesJson.encodeToString(ready)))
            awaitCancellation()
        }
        assertEquals(ready, withTimeout(5_000) { events.awaitReadyEvent { } })
    }

    @Test
    fun errorAndEarlyEndFail(): Unit = runBlocking {
        val failed = flow {
            emit(ServerSentEvent(event = "error", data = "今天的3次下载已经用完，明天再来"))
            awaitCancellation()
        }
        assertEquals("今天的3次下载已经用完，明天再来", assertFailsWith<RequestError> { withTimeout(5_000) { failed.awaitReadyEvent { } } }.message)

        val ended = flow { emit(ServerSentEvent(event = "heartbeat", data = "ping")) }
        assertFailsWith<IOException> { ended.awaitReadyEvent { } }
    }
}
