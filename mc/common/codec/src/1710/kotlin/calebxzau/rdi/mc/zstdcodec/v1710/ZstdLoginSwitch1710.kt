package calebxzau.rdi.mc.zstdcodec.v1710

import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.local.LocalChannel
import io.netty.channel.local.LocalServerChannel
import io.netty.util.ReferenceCountUtil
import org.apache.logging.log4j.LogManager
import java.util.function.Predicate
import java.util.function.Supplier

/** Switches at the unmodified login-success packet on the channel's event loop. */
object ZstdLoginSwitch1710 {
    const val NAME = "rdi_zstd_login_switch"
    private val LOGGER = LogManager.getLogger("rdi")

    /** The server intercepts packet objects before the vanilla encoder. */
    @JvmStatic
    @JvmOverloads
    fun installServer(
        channel: Channel,
        isLoginSuccess: Predicate<Any>,
        playerName: Supplier<String>,
        settings: Zstd1710.StreamSettings = Zstd1710.StreamSettings.read(System::getProperty) { LOGGER.warn(it) },
    ) {
        if (isLocal(channel) || channel.pipeline().get(NAME) != null) return
        if (channel.pipeline().get("encoder") == null || channel.pipeline().get("packet_handler") == null) return
        channel.pipeline().addAfter("encoder", NAME, ServerLoginSwitch(isLoginSuccess, playerName, settings))
    }

    /** The client prepares compression before the priority packet can invoke FML's synchronous replies. */
    @JvmStatic
    fun installClient(channel: Channel, isLoginSuccess: Predicate<Any>) {
        if (isLocal(channel) || channel.pipeline().get(NAME) != null || channel.pipeline().get("decoder") == null) return
        channel.pipeline().addAfter("decoder", NAME, ClientLoginSwitch(isLoginSuccess))
    }

    private fun isLocal(channel: Channel): Boolean = channel is LocalChannel || channel is LocalServerChannel

    private fun abort(channel: Channel, error: Throwable) {
        LOGGER.error("Minecraft1.7.10 Zstd login switch failed", error)
        ZstdInboundPreparation.abort(channel, error)
    }

    /** S02 is encoded raw before compression and STREAM_START are installed in the same write. */
    class ServerLoginSwitch(
        private val isLoginSuccess: Predicate<Any>,
        private val playerName: Supplier<String>,
        private val settings: Zstd1710.StreamSettings,
    ) : ChannelOutboundHandlerAdapter() {
        override fun write(context: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (!isLoginSuccess.test(msg)) {
                context.write(msg, promise)
                return
            }
            try {
                context.write(msg, promise)
                if (promise.isDone && !promise.isSuccess) throw promise.cause()
                val channel = context.channel()
                ZstdCompressionPipeline.setup(channel, Zstd1710.THRESHOLD, true, MinecraftVarIntCodec1710, Zstd1710.LIMITS)
                if (settings.enabled) {
                    ZstdCompressionPipeline.setOutboundStream(channel, settings.windowLog)
                    check(ZstdCompressionPipeline.isOutboundStreamEnabled(channel)) { "Zstd stream did not start" }
                }
                ZstdChunkMetrics1710.install(channel)
                context.pipeline().remove(this)
                LOGGER.info(
                    "Player {} Zstd compression active, stream={}, windowLog={} ({} MiB per side)",
                    playerName.get(), settings.enabled, settings.windowLog, if (settings.enabled) 1 shl (settings.windowLog - 20) else 0,
                )
            } catch (error: Throwable) {
                promise.tryFailure(error)
                abort(context.channel(), error)
                throw error
            }
        }
    }

    /** Installs both directions and arms the inbound stream before forwarding login success. */
    class ClientLoginSwitch(private val isLoginSuccess: Predicate<Any>) : ChannelInboundHandlerAdapter() {
        override fun channelRead(context: ChannelHandlerContext, msg: Any) {
            if (!isLoginSuccess.test(msg)) {
                context.fireChannelRead(msg)
                return
            }
            var forwarded = false
            try {
                val channel = context.channel()
                ZstdCompressionPipeline.setup(channel, Zstd1710.THRESHOLD, false, MinecraftVarIntCodec1710, Zstd1710.LIMITS)
                check(ZstdCompressionPipeline.setInboundStreamIfAvailable(channel, true)) { "Zstd decoder unavailable" }
                forwarded = true
                context.fireChannelRead(msg)
                context.pipeline().remove(this)
            } catch (error: Throwable) {
                if (!forwarded) ReferenceCountUtil.release(msg)
                abort(context.channel(), error)
                throw error
            }
        }
    }
}
