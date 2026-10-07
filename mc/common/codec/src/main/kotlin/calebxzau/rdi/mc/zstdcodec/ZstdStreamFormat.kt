package calebxzau.rdi.mc.zstdcodec

/**
 * Wire layout of the RDI Zstd stream extension, protocol version 1.
 *
 * The server starts the stream with a control frame that shares [PacketRefFormat.CONTROL_MARKER]:
 *
 *   STREAM_START (2)  VarInt -3, Byte 2, VarInt version, VarInt windowLog
 *
 * From STREAM_START onwards the compressed payload of every legacy envelope and of every non-raw batch
 * block is the next flushed segment of one magicless Zstd frame that lives as long as the connection.
 * Each segment may refer back to anything the earlier segments carried within the window, so a segment
 * only decodes after every earlier one in wire order, and a decoding failure ends the connection.
 *
 * Envelope layouts stay unchanged; only the compressed bytes change meaning, from one independent Zstd
 * frame per payload to one segment of the shared frame. Raw envelopes, raw batch blocks and packet
 * references stay outside the stream. The stream cannot be restarted or stopped.
 *
 * On a stream the compression threshold no longer bounds what leaves compressed: payloads from
 * [MINIMUM_SEGMENT_BYTES] up are compressed, so a compressed envelope or batch block may declare a size
 * below the threshold. Clients never enforce the threshold on inbound packets, and a decoder with an
 * active stream skips that check as well.
 */
internal object ZstdStreamFormat {
    const val OPCODE_STREAM_START: Int = 2
    const val VERSION: Int = 1

    /** 1 MiB of history. */
    const val MINIMUM_WINDOW_LOG: Int = 20

    /** 32 MiB of history, which each end keeps per connection outside the Java heap. */
    const val MAXIMUM_WINDOW_LOG: Int = 25

    const val DEFAULT_WINDOW_LOG: Int = MAXIMUM_WINDOW_LOG

    /**
     * Smallest payload the encoder compresses on a stream when the threshold is higher. Below it a
     * segment's block header alone outweighs what a repeat can save, so tiny packets such as entity
     * moves stay raw. Encoder policy only; decoders accept any compressed size.
     */
    const val MINIMUM_SEGMENT_BYTES: Int = 32

    fun isWindowLogAccepted(windowLog: Int): Boolean = windowLog in MINIMUM_WINDOW_LOG..MAXIMUM_WINDOW_LOG

    /** STREAM_START frame bytes without the outer length prefix. */
    fun startFrameBytes(windowLog: Int): Int =
        ZstdBatchFormat.varIntSize(PacketRefFormat.CONTROL_MARKER) + 1 +
            ZstdBatchFormat.varIntSize(VERSION) + ZstdBatchFormat.varIntSize(windowLog)
}
