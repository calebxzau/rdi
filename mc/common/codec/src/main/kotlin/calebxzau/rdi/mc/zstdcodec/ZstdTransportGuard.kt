package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import java.nio.channels.ClosedChannelException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * Checks provenance, not just a count of decoded records. Queues borrow identities only: the codec
 * output/pipeline still owns the buffers. All queue access is confined to the channel event loop.
 * Protects traffic traversing these guards; it cannot defend against a mod bypassing/removing both.
 */
internal class ZstdTransportGuard private constructor(private val channel: Channel) {
    private val failure = AtomicReference<Throwable?>()
    private val inbound = ArrayDeque<ByteBuf>()
    private val outbound = ArrayDeque<ByteBuf>()
    private var decoder: ChannelHandlerContext? = null
    private var encoder: ChannelHandlerContext? = null
    val inboundArmed: Boolean get() = decoder != null
    val outboundArmed: Boolean get() = encoder != null
    val isArmed: Boolean get() = inboundArmed || outboundArmed
    val error: Throwable? get() = failure.get()

    fun armInbound(context: ChannelHandlerContext) {
        check(channel.eventLoop().inEventLoop())
        checkHealthy()
        check(channel.pipeline().context(context.name()) === context) { "RDI decoder is no longer installed" }
        check(decoder == null || decoder === context) { "RDI decoder changed after arming" }
        if (decoder == null) {
            channel.pipeline().addAfter(context.name(), INBOUND, Inbound())
            decoder = context
        }
        check(channel.pipeline().context(INBOUND)?.handler() is Inbound) { "RDI inbound guard missing" }
    }

    fun armOutbound(context: ChannelHandlerContext) {
        check(channel.eventLoop().inEventLoop())
        checkHealthy()
        check(channel.pipeline().context(context.name()) === context) { "RDI encoder is no longer installed" }
        check(encoder == null || encoder === context) { "RDI encoder changed after activation" }
        if (encoder == null) {
            channel.pipeline().addBefore(context.name(), OUTBOUND, Outbound())
            encoder = context
        }
        check(channel.pipeline().context(OUTBOUND)?.handler() is Outbound) { "RDI outbound guard missing" }
    }

    fun decoded(context: ChannelHandlerContext, buffer: ByteBuf) {
        checkHealthy()
        if (inboundArmed) {
            check(decoder === context && channel.pipeline().context(context.name()) === context)
            inbound.addLast(buffer)
        }
    }

    fun encoded(context: ChannelHandlerContext, buffer: ByteBuf) {
        checkHealthy()
        if (outboundArmed) {
            check(encoder === context && channel.pipeline().context(context.name()) === context)
            outbound.addLast(buffer)
        }
    }

    fun checkHealthy() { failure.get()?.let { throw it } }

    /** Terminal flag is set immediately, including timeout calls from a non-event-loop thread. */
    fun fail(cause: Throwable) {
        if (!failure.compareAndSet(null, cause)) return
        // A peer that already left fails in-flight writes with ClosedChannelException; that is not a fault.
        if (!channel.isActive || cause is ClosedChannelException) {
            LOGGER.log(System.Logger.Level.DEBUG, "RDI transport closed", cause)
        } else {
            LOGGER.log(System.Logger.Level.ERROR, "RDI transport failed; closing connection", cause)
        }
        val close = Runnable {
            inbound.clear()
            outbound.clear()
            (channel.pipeline().get("compress") as? ZstdCompressionEncoder)?.abort(cause)
            channel.close()
        }
        if (channel.eventLoop().inEventLoop()) close.run() else {
            try { channel.eventLoop().execute(close) } catch (_: java.util.concurrent.RejectedExecutionException) {
                channel.close()
            }
        }
    }

    fun removed(context: ChannelHandlerContext, inboundSide: Boolean) {
        if ((if (inboundSide) inboundArmed else outboundArmed) && channel.isActive) {
            fail(IllegalStateException("Armed RDI handler removed: ${context.name()}"))
        }
        if (inboundSide) inbound.clear() else outbound.clear()
    }

    private inner class Inbound : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val owner = decoder
            if (error != null || owner == null || channel.pipeline().context(owner.name()) !== owner ||
                msg !is ByteBuf || inbound.peekFirst() !== msg
            ) {
                ReferenceCountUtil.release(msg)
                fail(IllegalStateException("Inbound bytes bypassed the armed RDI decoder"))
                return
            }
            inbound.removeFirst() // Consume before forwarding: downstream may reenter the pipeline.
            ctx.fireChannelRead(msg)
        }

        override fun handlerRemoved(ctx: ChannelHandlerContext) = removed(ctx, true)
    }

    private inner class Outbound : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            // Protocol configuration events are not wire buffers and must retain vanilla ordering.
            val owner = encoder
            if (error != null || owner == null || channel.pipeline().context(owner.name()) !== owner ||
                (msg is ByteBuf && outbound.peekFirst() !== msg)
            ) {
                val cause = error ?: IllegalStateException("Outbound bytes bypassed the armed RDI encoder")
                ReferenceCountUtil.release(msg)
                fail(cause)
                promise.tryFailure(cause)
                return
            }
            if (msg is ByteBuf) outbound.removeFirst()
            ctx.write(msg, promise)
        }

        override fun handlerRemoved(ctx: ChannelHandlerContext) = removed(ctx, false)
    }

    companion object {
        private const val INBOUND = "rdi_zstd_inbound_guard"
        private const val OUTBOUND = "rdi_zstd_outbound_guard"
        private val KEY = AttributeKey.valueOf<ZstdTransportGuard>("rdi.zstd.transport-guard")
        private val LOGGER = System.getLogger("rdi.zstd.guard")

        fun get(channel: Channel): ZstdTransportGuard {
            val attribute = channel.attr(KEY)
            return attribute.get() ?: ZstdTransportGuard(channel).let { attribute.setIfAbsent(it) ?: it }
        }

        fun existing(channel: Channel): ZstdTransportGuard? = channel.attr(KEY).get()
    }
}
