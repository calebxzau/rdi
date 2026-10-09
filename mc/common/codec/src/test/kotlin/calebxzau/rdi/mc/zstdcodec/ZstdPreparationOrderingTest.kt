package calebxzau.rdi.mc.zstdcodec

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import io.netty.handler.flow.FlowControlHandler
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

/** Local TCP only: no external services. Latches force the races; repetition is not the oracle. */
class ZstdPreparationOrderingTest {
    @Test
    fun `acknowledgement follows arming on actual event loops for every capability`() {
        for (mask in listOf(1, 2, 4, 7)) {
            Pair(mask).use { pair ->
                val future = ZstdInboundPreparation.armInbound(pair.client, mask)
                pair.client.writeAndFlush(Unpooled.wrappedBuffer(byteArrayOf(42)))
                assertTrue(future.get(5, TimeUnit.SECONDS))
                assertContentEquals(pair.payload, pair.received.get(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `replacement queued before arming is checked on the event loop`() {
        Pair(1).use { pair ->
            val blocker = block(pair.client)
            try {
                pair.client.eventLoop().execute {
                    pair.client.pipeline().replace("decompress", "decompress", ChannelInboundHandlerAdapter())
                }
                val ready = ZstdInboundPreparation.armInbound(pair.client, 1)
                assertFalse(ready.isDone)
                blocker.countDown()
                assertFails { ready.get(5, TimeUnit.SECONDS) }
                pair.client.closeFuture().syncUninterruptibly()
                assertFalse(pair.client.isActive)
                assertFalse(pair.received.isDone)
            } finally { blocker.countDown() }
        }
    }

    @Test
    fun `timeout terminal flag prevents queued arming from succeeding later`() {
        Pair(1).use { pair ->
            val blocker = block(pair.client)
            try {
                val ready = ZstdInboundPreparation.armInbound(pair.client, 1)
                assertFailsWith<TimeoutException> { ready.get(10, TimeUnit.MILLISECONDS) }
                ZstdInboundPreparation.abort(pair.client, TimeoutException("test timeout"))
                ready.cancel(false)
                blocker.countDown()
                pair.client.closeFuture().syncUninterruptibly()
                assertTrue(ready.isCancelled)
                assertFalse(ZstdTransportGuard.get(pair.client).inboundArmed)
                assertFalse(pair.received.isDone)
            } finally { blocker.countDown() }
        }
    }

    @Test
    fun `negative control observes START failure before allowing any preparation`() {
        Pair(1).use { pair ->
            pair.client.writeAndFlush(Unpooled.wrappedBuffer(byteArrayOf(42))).sync()
            val failure = pair.failed.get(5, TimeUnit.SECONDS)
            assertTrue(generateSequence(failure) { it.cause }.any {
                it.message?.contains("did not negotiate") == true
            })
            pair.client.closeFuture().syncUninterruptibly()
            assertFails { ZstdInboundPreparation.armInbound(pair.client, 1).get(5, TimeUnit.SECONDS) }
        }
    }

    private fun block(channel: Channel): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        channel.eventLoop().execute {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test failed to release event loop" }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        return release
    }

    private class Pair(mask: Int) : AutoCloseable {
        private val group = NioEventLoopGroup(3)
        val received = CompletableFuture<ByteArray>()
        val failed = CompletableFuture<Throwable>()
        val payload = ByteArray(300) { (it % 29).toByte() }
        private val accepted = CompletableFuture<Channel>()
        private val listening: Channel
        val client: Channel

        init {
            listening = ServerBootstrap().group(group).channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(channel: SocketChannel) {
                        install(channel)
                        channel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                            override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                                if (mask and 1 != 0) ZstdCompressionPipeline.setOutboundStream(channel, 20)
                                if (mask and 4 != 0) ZstdCompressionPipeline.setOutboundPacketRefs(channel)
                                if (mask and 2 != 0) ZstdCompressionPipeline.setOutboundBatching(channel, true)
                                ctx.writeAndFlush(Unpooled.wrappedBuffer(payload))
                                ZstdCompressionPipeline.flushBatched(channel)
                            }
                        })
                        accepted.complete(channel)
                    }
                }).bind("127.0.0.1", 0).sync().channel()
            client = Bootstrap().group(group).channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(channel: SocketChannel) {
                        install(channel)
                        channel.pipeline().addLast(object : SimpleChannelInboundHandler<ByteBuf>() {
                            override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                                received.complete(ByteArray(msg.readableBytes()).also(msg::readBytes))
                            }
                            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                                failed.complete(cause)
                                ctx.close()
                            }
                        })
                    }
                }).connect(listening.localAddress() as InetSocketAddress).sync().channel()
            accepted.get(5, TimeUnit.SECONDS)
        }

        override fun close() {
            client.close().syncUninterruptibly()
            accepted.getNow(null)?.close()?.syncUninterruptibly()
            listening.close().syncUninterruptibly()
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly()
        }

        private fun install(channel: Channel) {
            channel.pipeline().addLast("splitter", ProtobufVarint32FrameDecoder())
            channel.pipeline().addLast("flow", FlowControlHandler())
            channel.pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
            channel.pipeline().addLast("prepender", ProtobufVarint32LengthFieldPrepender())
            channel.pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
            ZstdCompressionPipeline.setup(channel, 128, false, ZstdTransportGuardTest.VAR_INT)
        }
    }
}
