package calebxzau.rdi.server.service.hostworlddownload

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.master.net.pathParam
import calebxzhou.rdi.master.net.response
import calebxzhou.rdi.master.service.host.HostService.hostContext
import calebxzhou.rdi.master.service.host.HostService.needMember
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val downloadService = HostWorldDownloadService()

/** `/host/{hostId}/world-download`, members only (not guests). Mounted inside the host routes. */
fun Route.hostWorldDownloadRoutes() = route("/world-download") {
    get {
        val ctx = call.hostContext().needMember
        response(data = downloadService.status(ctx.host))
    }
    post {
        val ctx = call.hostContext().needMember
        response(data = downloadService.start(ctx.host, ctx.player._id))
    }
    sse("/{buildId}/events") {
        val result = runCatching {
            val ctx = call.hostContext().needMember
            downloadService.job(ctx.host, call.pathParam("buildId")) ?: throw RequestError("存档包已过期，请重新下载")
        }
        val state = result.getOrElse { error ->
            send(ServerSentEvent(event = "error", data = error.message ?: "无法读取打包进度"))
            return@sse
        }
        // Ends after the ready or error event, which closes the stream.
        state.transformWhile { current ->
            emit(current)
            current is HostWorldDownloadJobState.Running
        }.collect { current ->
            when (current) {
                is HostWorldDownloadJobState.Running ->
                    send(ServerSentEvent(event = "progress", data = serdesJson.encodeToString(current.progress)))
                is HostWorldDownloadJobState.Ready ->
                    send(ServerSentEvent(event = "ready", data = serdesJson.encodeToString(current.ready)))
                is HostWorldDownloadJobState.Failed ->
                    send(ServerSentEvent(event = "error", data = current.message))
            }
        }
    }
    get("/{buildId}/file") {
        val ctx = call.hostContext().needMember
        val file = downloadService.archiveFile(ctx.host, call.pathParam("buildId")) ?: throw RequestError("存档包已过期，请重新下载")
        call.respondArchive(file)
    }
}

/** Sends [file], or the requested byte range of it, so a download can resume or run in parts. */
private suspend fun ApplicationCall.respondArchive(file: Path) {
    val length = withContext(Dispatchers.IO) { Files.size(file) }
    val range = byteRange(request.headers[HttpHeaders.Range], length)
    val start = range?.first ?: 0L
    val count = range?.let { it.last - it.first + 1 } ?: length
    respond(object : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.Application.OctetStream
        override val contentLength = count
        override val status = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK
        override val headers = Headers.build {
            append(HttpHeaders.AcceptRanges, "bytes")
            if (range != null) append(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/${length}")
        }

        override suspend fun writeTo(channel: ByteWriteChannel) {
            withContext(Dispatchers.IO) {
                FileChannel.open(file, StandardOpenOption.READ).use { source ->
                    source.position(start)
                    val bytes = ByteArray(64 * 1024)
                    val buffer = ByteBuffer.wrap(bytes)
                    var remaining = count
                    while (remaining > 0) {
                        buffer.clear().limit(minOf(bytes.size.toLong(), remaining).toInt())
                        val read = source.read(buffer)
                        if (read < 0) break
                        channel.writeFully(bytes, 0, read)
                        remaining -= read
                    }
                }
            }
        }
    })
}
