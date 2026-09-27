package calebxzhou.rdi.mc.client.texture

import calebxzau.rdi.mediaproc.AvifCodec
import com.mojang.blaze3d.platform.NativeImage
import java.io.IOException
import java.nio.ByteBuffer

object AvifNativeImageAdapter {
    private const val MAX_INPUT_BYTES = 64L * 1024L * 1024L

    @JvmStatic
    @Throws(IOException::class)
    fun decodeIfAvif(format: NativeImage.Format?, input: ByteBuffer): NativeImage? {
        if (!AvifCodec.isAvif(input)) return null
        if (format != null && format != NativeImage.Format.RGBA) {
            throw IOException("AVIF textures only support RGBA output")
        }

        val view = input.slice()
        if (view.remaining().toLong() > MAX_INPUT_BYTES) {
            throw IOException("AVIF texture exceeds ${MAX_INPUT_BYTES / (1024 * 1024)}MiB")
        }
        val bytes = ByteArray(view.remaining()).also(view::get)
        val decoded = AvifCodec.decode(bytes).getOrElse { error ->
            throw IOException("Failed to decode AVIF texture", error)
        }
        val image = NativeImage(NativeImage.Format.RGBA, decoded.width, decoded.height, false)
        return try {
            ((image as Any) as RNativeImagePixels).copyRdiRgba(decoded.pixels)
            image
        } catch (error: Throwable) {
            image.close()
            throw error
        }
    }
}
