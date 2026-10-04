package calebxzau.rdi.mc.zstdcodec

import com.github.luben.zstd.Zstd
import io.netty.channel.Channel
import io.netty.channel.ChannelPipeline
import io.netty.channel.local.LocalChannel
import io.netty.channel.local.LocalServerChannel

/**
 * Installs the RDI Zstd replacement for Minecraft's compression handlers.
 *
 * The outer Minecraft frame remains owned by the vanilla splitter/prepender; these handlers only
 * read and write Minecraft's inner compression envelope.
 *
 * The encoder can additionally buffer the encoded packets of one connection and emit them as a
 * single batch block. Batching stays off until the connection negotiated the block with its peer,
 * so a peer that only knows the legacy envelope keeps working.
 */
object ZstdCompressionPipeline {
    /**
     * Mixin 0.8.5 prepends later HEAD injections. Apply after Krypton's priority-1000 mixin
     * so our cancelling callback executes first; a higher priority would execute last.
     */
    const val SETUP_MIXIN_PRIORITY: Int = 900

    const val MAXIMUM_COMPRESSED_LENGTH: Int = 2 * 1024 * 1024
    const val MAXIMUM_UNCOMPRESSED_LENGTH: Int = 8 * 1024 * 1024

    /** Default uncompressed size of one batch block, including the record length prefixes. */
    const val DEFAULT_BATCH_TARGET_BYTES: Int = 64 * 1024
    const val MINIMUM_BATCH_TARGET_BYTES: Int = 4 * 1024
    const val MAXIMUM_BATCH_TARGET_BYTES: Int = 1024 * 1024

    /** How long the first buffered packet of a batch waits for the flush timeout, at most. */
    const val BATCH_FLUSH_TIMEOUT_MILLIS: Long = 50L

    private const val DECOMPRESS_HANDLER_NAME = "decompress"
    private const val COMPRESS_HANDLER_NAME = "compress"
    private const val SPLITTER_HANDLER_NAME = "splitter"
    private const val PREPENDER_HANDLER_NAME = "prepender"
    private const val ZSTD_MAGIC_NUMBER = -47205080

    /**
     * Verifies native loading during each mod's initialization.
     *
     * zstd-jni 1.5.7-11 does not expose a `versionNumber()` method. Calling
     * the native magic number keeps this self-test independent of that missing
     * convenience API. A broken native setup is fatal because the matching
     * RDI protocol has no Deflate fallback.
     */
    @JvmStatic
    fun verifyNativeLoaded(): Int {
        val magicNumber = Zstd.magicNumber()
        check(magicNumber == ZSTD_MAGIC_NUMBER) {
            "zstd-jni returned an invalid native magic number: $magicNumber"
        }
        return magicNumber
    }

    @JvmStatic
    fun setup(
        channel: Channel,
        threshold: Int,
        validateDecompressed: Boolean,
        varIntCodec: MinecraftVarIntCodec,
    ) {
        val pipeline = channel.pipeline()
        if (channel is LocalChannel || channel is LocalServerChannel) {
            removeOwnedHandlers(pipeline)
            return
        }

        if (threshold < 0) {
            removeOwnedHandlers(pipeline)
            return
        }

        installDecoder(pipeline, threshold, validateDecompressed, varIntCodec)
        installEncoder(pipeline, threshold, varIntCodec)
    }

    /**
     * Enables or disables outbound batching for one connection.
     *
     * Only enable this after the peer confirmed that it decodes the batch block; an older peer would
     * fail on an envelope it does not know.
     */
    @JvmStatic
    @JvmOverloads
    fun setOutboundBatching(channel: Channel, enabled: Boolean, targetBytes: Int = DEFAULT_BATCH_TARGET_BYTES) {
        (channel.pipeline().get(COMPRESS_HANDLER_NAME) as? ZstdCompressionEncoder)
            ?.requestBatching(enabled, targetBytes)
    }

    /** Accepts or rejects inbound batch blocks on one connection. */
    @JvmStatic
    fun setInboundBatching(channel: Channel, enabled: Boolean) {
        (channel.pipeline().get(DECOMPRESS_HANDLER_NAME) as? ZstdCompressionDecoder)
            ?.requestBatching(enabled)
    }

    /** Returns whether this channel has the RDI decoder needed to accept batch blocks. */
    @JvmStatic
    fun isInboundBatchingAvailable(channel: Channel): Boolean =
        channel.pipeline().get(DECOMPRESS_HANDLER_NAME) is ZstdCompressionDecoder

    /** Enables inbound batching only when the RDI decoder is present. */
    @JvmStatic
    fun setInboundBatchingIfAvailable(channel: Channel, enabled: Boolean): Boolean {
        val decoder = channel.pipeline().get(DECOMPRESS_HANDLER_NAME) as? ZstdCompressionDecoder ?: return false
        decoder.requestBatching(enabled)
        return true
    }

    /**
     * Starts outbound packet references, or restarts them with an empty table.
     *
     * Only call this after the peer announced the extension and prepared its decoder; the slot count
     * and entry limit are clamped to the range every peer accepts.
     */
    @JvmStatic
    @JvmOverloads
    fun setOutboundPacketRefs(
        channel: Channel,
        slots: Int = PacketRefFormat.DEFAULT_SLOTS,
        maxEntryBytes: Int = PacketRefFormat.DEFAULT_MAX_ENTRY_BYTES,
    ) {
        (channel.pipeline().get(COMPRESS_HANDLER_NAME) as? ZstdCompressionEncoder)
            ?.requestPacketRefs(slots, maxEntryBytes)
    }

    @JvmStatic
    fun isOutboundPacketRefsEnabled(channel: Channel): Boolean =
        (channel.pipeline().get(COMPRESS_HANDLER_NAME) as? ZstdCompressionEncoder)?.isPacketRefsEnabled() ?: false

    /** Prepares the RDI decoder for the server's START; returns false when that decoder is absent. */
    @JvmStatic
    fun setInboundPacketRefsIfAvailable(channel: Channel, ready: Boolean): Boolean {
        val decoder = channel.pipeline().get(DECOMPRESS_HANDLER_NAME) as? ZstdCompressionDecoder ?: return false
        decoder.requestPacketRefsReady(ready)
        return true
    }

    /** Returns whether this channel has any compression encoder, RDI or not. */
    @JvmStatic
    fun hasCompressionEncoder(channel: Channel): Boolean = channel.pipeline().get(COMPRESS_HANDLER_NAME) != null

    /** Returns whether this channel currently uses the RDI outbound compression encoder. */
    @JvmStatic
    fun hasOutboundEncoder(channel: Channel): Boolean =
        channel.pipeline().get(COMPRESS_HANDLER_NAME) is ZstdCompressionEncoder

    /** Actual handlers, for diagnosing other mods replacing the compression pipeline. */
    @JvmStatic
    fun describeHandlers(channel: Channel): String {
        val pipeline = channel.pipeline()
        val decoder = pipeline.get(DECOMPRESS_HANDLER_NAME)?.javaClass?.name ?: "absent"
        val encoder = pipeline.get(COMPRESS_HANDLER_NAME)?.javaClass?.name ?: "absent"
        return "decompress=$decoder, compress=$encoder"
    }

    @JvmStatic
    fun isOutboundBatchingEnabled(channel: Channel): Boolean =
        (channel.pipeline().get(COMPRESS_HANDLER_NAME) as? ZstdCompressionEncoder)?.isBatchingEnabled() ?: false

    /** Flush that the loader adapters request from their global server tick end hook. */
    @JvmStatic
    fun flushAtTickEnd(channel: Channel) {
        (channel.pipeline().get(COMPRESS_HANDLER_NAME) as? ZstdCompressionEncoder)
            ?.requestTickEnd()
    }

    /** Explicit barrier: the buffered block leaves before the caller keeps writing. */
    @JvmStatic
    fun flushBatched(channel: Channel) {
        (channel.pipeline().get(COMPRESS_HANDLER_NAME) as? ZstdCompressionEncoder)
            ?.requestFlush(ZstdBatchFlushReason.BARRIER)
    }

    /** Attaches block telemetry for one connection; only the metrics module uses this. */
    internal fun setBatchObserver(channel: Channel, observer: ZstdBatchObserver?) {
        (channel.pipeline().get(COMPRESS_HANDLER_NAME) as? ZstdCompressionEncoder)
            ?.requestObserver(observer)
    }

    private fun installDecoder(
        pipeline: ChannelPipeline,
        threshold: Int,
        validateDecompressed: Boolean,
        varIntCodec: MinecraftVarIntCodec,
    ) {
        val handler = pipeline.get(DECOMPRESS_HANDLER_NAME)
        when (handler) {
            is ZstdCompressionDecoder -> handler.updateThreshold(threshold, validateDecompressed)
            null -> pipeline.addAfter(
                SPLITTER_HANDLER_NAME,
                DECOMPRESS_HANDLER_NAME,
                ZstdCompressionDecoder(threshold, validateDecompressed, varIntCodec),
            )
            else -> pipeline.replace(
                DECOMPRESS_HANDLER_NAME,
                DECOMPRESS_HANDLER_NAME,
                ZstdCompressionDecoder(threshold, validateDecompressed, varIntCodec),
            )
        }
    }

    private fun installEncoder(
        pipeline: ChannelPipeline,
        threshold: Int,
        varIntCodec: MinecraftVarIntCodec,
    ) {
        val handler = pipeline.get(COMPRESS_HANDLER_NAME)
        when (handler) {
            is ZstdCompressionEncoder -> handler.updateThreshold(threshold)
            null -> pipeline.addAfter(
                PREPENDER_HANDLER_NAME,
                COMPRESS_HANDLER_NAME,
                ZstdCompressionEncoder(threshold, varIntCodec),
            )
            else -> pipeline.replace(
                COMPRESS_HANDLER_NAME,
                COMPRESS_HANDLER_NAME,
                ZstdCompressionEncoder(threshold, varIntCodec),
            )
        }
    }

    private fun removeOwnedHandlers(pipeline: ChannelPipeline) {
        (pipeline.get(COMPRESS_HANDLER_NAME) as? ZstdCompressionEncoder)?.releaseBuffered()
        if (pipeline.get(DECOMPRESS_HANDLER_NAME) is ZstdCompressionDecoder) {
            pipeline.remove(DECOMPRESS_HANDLER_NAME)
        }
        if (pipeline.get(COMPRESS_HANDLER_NAME) is ZstdCompressionEncoder) {
            pipeline.remove(COMPRESS_HANDLER_NAME)
        }
    }
}
