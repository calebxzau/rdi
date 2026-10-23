package calebxzau.rdi.mc.zstdcodec

/** Per-connection single-packet limits; defaults preserve the 1.20.1 and 1.21.1 protocol. */
data class ZstdPacketLimits(val maximumUncompressedBytes: Int, val maximumCompressedBytes: Int) {
    init {
        require(maximumUncompressedBytes >= ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH)
        require(maximumCompressedBytes >= ZstdCompressionPipeline.MAXIMUM_COMPRESSED_LENGTH)
    }

    companion object {
        @JvmField
        val DEFAULT = ZstdPacketLimits(
            ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH,
            ZstdCompressionPipeline.MAXIMUM_COMPRESSED_LENGTH,
        )
    }
}
