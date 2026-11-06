package calebxzhou.rdi.client.service

import calebxzau.rdi.client.ui.decodeImageBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Preserve the desktop launcher's existing Skia/AVIF icon formats. */
fun encodeComposeWindowIconPng(bytes: ByteArray): ByteArray {
    val bitmap = decodeImageBitmap(bytes).getOrThrow()
    val pixels = IntArray(Math.multiplyExact(bitmap.width, bitmap.height))
    bitmap.readPixels(pixels)
    val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
    try {
        image.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "无法编码整合包PNG图标" }
            output.toByteArray()
        }
    } finally {
        image.flush()
    }
}
