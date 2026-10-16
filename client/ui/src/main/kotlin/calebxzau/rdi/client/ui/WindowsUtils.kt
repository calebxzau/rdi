package calebxzau.rdi.client.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import calebxzhou.rdi.common.model.LibraryOsArch
import calebxzhou.rdi.common.util.jarResource
import calebxzau.rdi.mediaproc.FfmpegAvifDecoder
import calebxzau.rdi.client.RDIClient
import calebxzhou.rdi.client.ui.pickAwtDirectory
import calebxzhou.rdi.client.ui.pickAwtSaveFile
import com.sun.management.OperatingSystemMXBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.io.FilenameFilter
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.net.URI

fun copyToClipboard(text: String) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(StringSelection(text), null)
}

fun openUrl(url: String) {
    browse(URI(url))
}

fun openMsaVerificationUrl(url: String) {
    browse(URI(url))
}

private fun desktopSupports(action: Desktop.Action): Boolean =
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action)

private fun browse(uri: URI) {
    if (desktopSupports(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(uri)
    } else {
        openWithSystemHandler(uri.toString())
    }
}

// Many Linux desktops lack the GNOME libraries AWT Desktop needs; hand the target to the desktop's opener.
private fun openWithSystemHandler(target: String) {
    val osArch = LibraryOsArch.detectHostOs()
    val opener = when {
        osArch.isWindows -> "explorer"
        osArch.isMac -> "open"
        else -> "xdg-open"
    }
    ProcessBuilder(opener, target).start()
}

suspend fun pickSaveFile(suggestedName: String, extension: String): File? =
    withContext(Dispatchers.IO) {
        pickAwtSaveFile(
            title = "选择保存位置",
            defaultFileName = suggestedName,
            defaultDirectory = File(System.getProperty("user.home")),
            requiredExtension = extension,
            filenameFilter = { _, name -> name.endsWith(".$extension", ignoreCase = true) }
        )
    }

suspend fun pickLocalModpackFile(): File? =
    withContext(Dispatchers.IO) {
        val owner = Frame()
        try {
            val dialog = FileDialog(owner, "选择客户端安装包", FileDialog.LOAD).apply {
                directory = File(System.getProperty("user.home"), "Downloads").absolutePath
                file = "*.zip;*.mrpack"
                filenameFilter = FilenameFilter { dir, name ->
                    val target = File(dir, name)
                    target.isDirectory ||
                        name.endsWith(".zip", ignoreCase = true) ||
                        name.endsWith(".mrpack", ignoreCase = true)
                }
            }
            dialog.isVisible = true
            val dir = dialog.directory ?: return@withContext null
            val name = dialog.file ?: return@withContext null
            val selected = File(dir, name)
            selected.takeIf {
                it.isFile && (
                    it.name.endsWith(".zip", ignoreCase = true) ||
                        it.name.endsWith(".mrpack", ignoreCase = true)
                )
            }
        } finally {
            owner.dispose()
        }
    }

suspend fun pickLocalZipFile(title: String): File? =
    withContext(Dispatchers.IO) {
        val owner = Frame()
        try {
            val dialog = FileDialog(owner, title, FileDialog.LOAD).apply {
                directory = File(System.getProperty("user.home"), "Downloads").absolutePath
                file = "*.zip"
                filenameFilter = FilenameFilter { dir, name ->
                    val target = File(dir, name)
                    target.isDirectory || name.endsWith(".zip", ignoreCase = true)
                }
            }
            dialog.isVisible = true
            val dir = dialog.directory ?: return@withContext null
            val name = dialog.file ?: return@withContext null
            val selected = File(dir, name)
            selected.takeIf {
                it.exists() && it.isFile && it.name.endsWith(".zip", ignoreCase = true)
            }
        } finally {
            owner.dispose()
        }
    }

suspend fun pickLocalDirectory(title: String): File? =
    withContext(Dispatchers.IO) {
        pickAwtDirectory(title)
    }

fun loadResourceStream(name: String): InputStream {
    return RDIClient.jarResource(name)
}

fun exportResource(name: String, target: File) {
    loadResourceStream(name).use { input ->
        target.parentFile?.mkdirs()
        target.outputStream().use { output -> input.copyTo(output) }
    }
}

fun loadIconBitmap(resourceName: String): Result<ImageBitmap> = loadImageBitmap("assets/icons/$resourceName")

fun loadImageBitmap(resourceName: String): Result<ImageBitmap> = runCatching {
    loadResourceStream(resourceName).use { stream ->
        decodeImageBitmap(stream.readBytes()).getOrThrow()
    }
}

fun openFolder(path: String) {
    val dir = File(path)
    if (dir.exists()) {
        if (desktopSupports(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(dir)
        } else {
            openWithSystemHandler(dir.absolutePath)
        }
    }
}

fun decodeImageBitmap(bytes: ByteArray): Result<ImageBitmap> = runCatching {
    val image = if (FfmpegAvifDecoder.isAvif(bytes)) {
        val decoded = FfmpegAvifDecoder.decode(bytes).getOrThrow()
        Image.makeRaster(
            ImageInfo(
                decoded.width,
                decoded.height,
                ColorType.RGBA_8888,
                ColorAlphaType.UNPREMUL,
                ColorSpace.sRGB
            ),
            decoded.pixels,
            decoded.width * 4
        )
    } else {
        Image.makeFromEncoded(bytes)
    }
    try {
        image.toComposeImageBitmap()
    } finally {
        image.close()
    }
}

fun imageBitmapFromArgb(
    argb: IntArray,
    width: Int,
    height: Int
): ImageBitmap {
    require(width > 0 && height > 0) { "Invalid bitmap size: ${width}x$height" }
    val size = width * height
    val safePixels = if (argb.size >= size) {
        argb
    } else {
        IntArray(size).also { argb.copyInto(it, endIndex = argb.size) }
    }
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, width, height, safePixels, 0, width)
    }
    // Avoid PNG encode/decode roundtrip (very expensive during frequent map updates).
    return image.toComposeImageBitmap()
}

fun readTotalPhysicalMemoryMb(): Int {
    val osBean = runCatching {
        ManagementFactory.getOperatingSystemMXBean()
    }.getOrNull()
    val totalBytes = (osBean as? OperatingSystemMXBean)
        ?.totalPhysicalMemorySize
        ?: return 0
    return (totalBytes / (1024L * 1024L)).toInt()
}

internal fun resolveJavaExecutable(raw: String, osArch: LibraryOsArch = LibraryOsArch.detectHostOs()): File? {
    val javaName = if (osArch.isWindows) "java.exe" else "java"
    val input = File(raw.trim())
    if (input.isDirectory) {
        val exe = input.resolve("bin").resolve(javaName)
        return exe.takeIf { it.isFile }
    }
    if (input.exists()) {
        val name = input.name.lowercase()
        if (name == javaName) return input
        if (osArch.isWindows && name == "javaw.exe") {
            return input.parentFile?.resolve("java.exe")?.takeIf { it.exists() } ?: input
        }
        return null
    }
    if (!osArch.isWindows) return null
    val exe = File(raw.trim() + ".exe")
    return exe.takeIf { it.exists() }
}

fun validateJdkExecutablePath(rawPath: String): Result<String> = runCatching {
    require(rawPath.isNotBlank()) { "请输入JDK路径" }
    val javaExe = resolveJavaExecutable(rawPath)
        ?: error("JDK路径无效: $rawPath")
    val javacName = if (javaExe.name.equals("java.exe", ignoreCase = true)) "javac.exe" else "javac"
    val javacExe = javaExe.parentFile.resolve(javacName)
    require(javacExe.isFile) { "选择的目录不是完整JDK，缺少${javacName}" }
    val process = ProcessBuilder(javaExe.absolutePath, "-version")
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    require(exitCode == 0) { "JDK无法运行java -version，退出代码$exitCode" }
    javaExe.toPath().toAbsolutePath().normalize().toString()
}

fun currentJavaMajor(): Int = Runtime.version().feature()

