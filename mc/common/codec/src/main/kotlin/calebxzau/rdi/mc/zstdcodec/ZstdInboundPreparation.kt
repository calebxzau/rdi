package calebxzau.rdi.mc.zstdcodec

import io.netty.channel.Channel
import io.netty.channel.local.LocalChannel
import io.netty.channel.local.LocalServerChannel
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/** The result completes only after lookup, validation and arming on the actual channel event loop. */
object ZstdInboundPreparation {
    const val STREAM = 1
    const val BATCH = 2
    const val REFERENCES = 4
    const val ALL = STREAM or BATCH or REFERENCES

    @JvmStatic
    fun armInbound(channel: Channel, extensions: Int): CompletableFuture<Boolean> {
        require(extensions and ALL.inv() == 0) { "Unknown RDI extension mask" }
        val result = CompletableFuture<Boolean>()
        val guard = ZstdTransportGuard.get(channel)
        result.whenComplete { _, _ ->
            if (result.isCancelled) guard.fail(CancellationException("RDI decoder preparation cancelled"))
        }
        val task = Runnable {
            try {
                guard.checkHealthy()
                check(!result.isCancelled && channel.isActive) { "Connection closed during decoder preparation" }
                if (extensions == 0 || channel is LocalChannel || channel is LocalServerChannel) {
                    result.complete(false)
                    return@Runnable
                }
                val pipeline = channel.pipeline()
                val handler = pipeline.get("decompress")
                if (handler == null && !guard.inboundArmed) {
                    result.complete(false) // Compression disabled in the login protocol.
                    return@Runnable
                }
                check(handler is ZstdCompressionDecoder) {
                    "Negotiated RDI decoder unavailable: ${ZstdCompressionPipeline.describeHandlers(channel)}"
                }
                val context = requireNotNull(pipeline.context(handler))
                guard.armInbound(context)
                if (extensions and STREAM != 0) handler.requestStreamReady(true)
                if (extensions and BATCH != 0) handler.requestBatching(true)
                if (extensions and REFERENCES != 0) handler.requestPacketRefsReady(true)
                check(pipeline.context("decompress") === context) { "Decoder replaced during preparation" }
                guard.checkHealthy()
                if (!result.complete(true)) guard.fail(CancellationException("RDI preparation completed too late"))
            } catch (error: Throwable) {
                guard.fail(error)
                result.completeExceptionally(error)
            }
        }
        try {
            if (channel.eventLoop().inEventLoop()) task.run() else channel.eventLoop().execute(task)
        } catch (error: Throwable) {
            guard.fail(error)
            result.completeExceptionally(error)
        }
        return result
    }

    /** Sets a terminal flag synchronously, even if a preparation task is still queued. */
    @JvmStatic
    fun abort(channel: Channel, error: Throwable) = ZstdTransportGuard.get(channel).fail(error)
}
