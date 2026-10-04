package calebxzau.rdi.mc.zstdcodec

/** Why an encoded frame left the connection's buffer. */
internal enum class ZstdBatchFlushReason {
    /** The buffered records reached the size target. */
    SIZE,

    /** The flush timeout scheduled for the first buffered record expired. */
    TIMEOUT,

    /** The loader adapter flushed at the end of a global server tick. */
    TICK,

    /** An explicit barrier: a non-batch message, a disabled batcher or a closing channel. */
    BARRIER,

    /** The connection is going away. */
    SHUTDOWN,
}

/** Selects the maximum batching window for one encoded packet. */
internal enum class ZstdBatchPolicy(val tickBudget: Long, val timeBudgetMillis: Long) {
    Immediate(0, 0),
    OneTick(1, 50),
    FourTicks(4, 200),
}

/** Packet details captured before vanilla's packet encoder loses the packet type. */
internal data class ZstdPacketIdentity(
    val packetType: String,
    val namespace: String? = null,
    val path: String? = null,
)

/** The kind of inner frame emitted by the codec. */
internal enum class ZstdBatchFrameKind {
    Legacy,
    RawBatch,
    ZstdBatch,

    /** A packet reference that replaced a legacy envelope; see [PacketRefFormat]. */
    Ref,
}

/** One encoded frame handed to the downstream pipeline. Byte counts exclude the outer prefix except where named. */
internal class ZstdBatchSample(
    val recordCount: Int,
    val payloadBytes: Int,
    val blockBytes: Int,
    val raw: Boolean,
    val flushReason: ZstdBatchFlushReason,
    val waitNanos: Long,
    val compressionNanos: Long,
    val frameKind: ZstdBatchFrameKind = if (raw) ZstdBatchFrameKind.RawBatch else ZstdBatchFrameKind.ZstdBatch,
    val outerPrefixBytes: Int = 0,
    val singleRecordFallback: Boolean = false,
    val noCompressionBytes: Int = blockBytes + outerPrefixBytes,
    val compressedBytes: Int = 0,
    val fallbackBytes: Int = 0,
    val bufferedFlush: Boolean = false,
    val firstFrameOfFlush: Boolean = false,
    /** For [ZstdBatchFrameKind.Ref]: the legacy frame it replaced, including that frame's outer prefix. */
    val replacedFrameBytes: Int = 0,
)

/**
 * Per-connection observation of outbound encoding, delivered on the connection's event loop.
 */
internal interface ZstdBatchObserver {
    fun recordEncoded(identity: ZstdPacketIdentity?, encodedBytes: Int, policy: ZstdBatchPolicy) {}

    fun recordBuffered(encodedBytes: Int) {}

    fun batchFlushed(sample: ZstdBatchSample) {}

    fun writeCompleted(sample: ZstdBatchSample, success: Boolean) {}

    fun encodingFailed(recordCount: Int) {}
}
