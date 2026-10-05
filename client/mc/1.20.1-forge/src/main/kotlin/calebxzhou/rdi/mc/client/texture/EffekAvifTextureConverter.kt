package calebxzhou.rdi.mc.client.texture

import calebxzau.rdi.mediaproc.AvifCodec
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream

object EffekAvifTextureConverter {
    private const val MAX_INPUT_BYTES = 64 * 1024 * 1024

    /** Returns the original array for other formats; only AVIF receives a new PNG array. */
    @JvmStatic
    @Throws(IOException::class)
    fun toPngIfAvif(data: ByteArray, length: Int): ByteArray {
        require(length in 0..data.size) { "Invalid Effekseer texture length ${length}/${data.size}" }
        if (!AvifCodec.isAvif(ByteBuffer.wrap(data, 0, length))) return data
        if (length > MAX_INPUT_BYTES) throw IOException("Effekseer AVIF texture exceeds 64MiB")

        val decoded = AvifCodec.decode(if (length == data.size) data else data.copyOf(length))
            .getOrElse { error ->
                if (error is Error) throw error
                throw IOException("Failed to decode Effekseer AVIF texture", error)
            }
        val image = BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_ARGB)
        try {
            // TYPE_INT_ARGB is not premultiplied: retain RGB even for transparent pixels.
            val argb = (image.raster.dataBuffer as DataBufferInt).data
            val rgba = decoded.pixels
            for (pixel in argb.indices) {
                val offset = pixel * 4
                argb[pixel] = ((rgba[offset + 3].toInt() and 0xFF) shl 24) or
                    ((rgba[offset].toInt() and 0xFF) shl 16) or
                    ((rgba[offset + 1].toInt() and 0xFF) shl 8) or
                    (rgba[offset + 2].toInt() and 0xFF)
            }
            return ByteArrayOutputStream().use { bytes ->
                // Explicit memory stream avoids ImageIO's default disk cache.
                MemoryCacheImageOutputStream(bytes).use { output ->
                    if (!ImageIO.write(image, "png", output)) {
                        throw IOException("No PNG writer available for Effekseer AVIF texture")
                    }
                }
                bytes.toByteArray()
            }
        } finally {
            image.flush()
        }
    }
}
