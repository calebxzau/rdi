package calebxzhou.rdi.common.exception

/** [RequestError.errorCode] values shared by chunked upload servers and clients. */
object ChunkedUploadErrorCodes {
    /** The part was not stored this time; the client may upload the same part again later. */
    const val PART_RETRYABLE = "chunked-upload-part-retryable"
}
