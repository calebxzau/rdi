package calebxzhou.rdi.client.service

import calebxzau.rdi.client.RDIClient
import calebxzau.rdi.client.ui.decodeImageBitmap
import calebxzhou.rdi.common.net.httpRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext

class GameWindowService internal constructor(
    private val launcherDir: () -> Path = { RDIClient.DIR.toPath() },
    private val download: suspend (String) -> ByteArray = ::downloadWindowIcon,
    private val encodePng: (ByteArray) -> ByteArray = ::encodeWindowIconPng,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val downloadTimeoutMillis: Long = 3_000,
) {
    suspend fun prepareJvmArgs(
        versionDir: Path,
        modpackName: String,
        hostName: String?,
        iconUrl: String?,
        onIconFailure: (Throwable) -> Unit,
        versionName: String = "",
    ): List<String> {
        val icon = prepareIcon(versionDir, iconUrl).getOrElse { error ->
            onIconFailure(error)
            null
        }
        return buildList {
            add("-Drdi.window.title=${gameWindowTitle(modpackName, hostName, versionName)}")
            icon?.let { add("-Drdi.window.icon=${it}") }
        }
    }

    suspend fun prepareIcon(versionDir: Path, iconUrl: String?): Result<Path?> = withContext(ioDispatcher) {
        try {
            val icon = versionDir.toAbsolutePath().normalize().resolve("icon.png")
            val result = iconLocks.computeIfAbsent(icon) { Mutex() }.withLock {
                val exists = Files.exists(icon, NOFOLLOW_LINKS)
                if (exists && isReadableIcon(icon)) return@withLock icon
                val url = iconUrl?.trim()?.takeIf(String::isNotEmpty)
                if (url == null) {
                    if (exists) throw IOException("本地图标无法解码，且未提供整合包图标地址：${icon}")
                    return@withLock null
                }
                val bytes = withTimeoutOrNull(downloadTimeoutMillis) { download(url) }
                    ?: throw IOException("整合包图标下载超时")
                val png = encodePng(bytes)
                coroutineContext.ensureActive()
                publishIcon(icon, png)
                icon
            }
            Result.success(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun isReadableIcon(icon: Path): Boolean {
        if (!Files.isRegularFile(icon, NOFOLLOW_LINKS)) {
            throw IOException("图标路径不是普通文件：${icon}")
        }
        // Read errors propagate; only a successfully read but undecodable image may be replaced.
        val bytes = Files.readAllBytes(icon)
        return try {
            ImageIO.read(bytes.inputStream())?.let { image -> image.flush(); true } ?: false
        } catch (_: IOException) {
            false
        }
    }

    private fun publishIcon(icon: Path, png: ByteArray) {
        val staging = Files.createTempFile(icon.parent, ".rdi-window-icon-", ".tmp")
        try {
            Files.write(staging, png)
            check(isReadableIcon(staging)) { "转换后的整合包图标无法解码" }
            // Another launcher may have supplied an icon while this one was downloading.
            if (Files.exists(icon, NOFOLLOW_LINKS)) {
                if (isReadableIcon(icon)) return
                archiveInvalidIcon(icon)
            }
            Files.move(staging, icon, ATOMIC_MOVE)
        } finally {
            // Only the private scratch file created above is removed, never an existing pack file.
            Files.deleteIfExists(staging)
        }
    }

    private fun archiveInvalidIcon(icon: Path) {
        val root = launcherDir().toAbsolutePath().normalize()
        val relative = if (icon.startsWith(root)) root.relativize(icon)
            else Path.of("external").resolve(icon.root.relativize(icon))
        val archiveRoot = Files.createDirectories(root.resolve("DEL"))
        val operation = Files.createTempDirectory(archiveRoot, "window-icon-")
        val archived = operation.resolve(relative)
        Files.createDirectories(archived.parent)
        Files.move(icon, archived)
    }

    private companion object {
        val iconLocks = ConcurrentHashMap<Path, Mutex>()
    }
}

internal fun gameWindowTitle(modpackName: String, hostName: String?, versionName: String = ""): String {
    fun clean(value: String) = value.replace(Regex("[\\p{Cntrl}]"), " ").trim()
    fun shorten(value: String, limit: Int): String {
        if (value.length <= limit) return value
        var end = limit - 1
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end) + "…"
    }
    val name = clean(modpackName).ifEmpty { "Minecraft" }
    val version = clean(versionName)
    val pack = if (version.isEmpty()) name else "${name} ${version}"
    val host = hostName?.let(::clean)?.takeIf(String::isNotEmpty) ?: return shorten(pack, 64)
    val separator = " · "
    val budget = 64 - separator.length
    val packLimit = minOf(pack.length, maxOf(budget / 2, budget - host.length))
    return "${shorten(pack, packLimit)}${separator}${shorten(host, budget - packLimit)}"
}

internal fun isGameWindowJvmArg(argument: String): Boolean =
    argument.substringBefore('=') in setOf("-Drdi.window.icon", "-Drdi.window.title")

private suspend fun downloadWindowIcon(iconUrl: String): ByteArray {
    val response = httpRequest {
        url(iconUrl)
        timeout {
            requestTimeoutMillis = 3_000
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 3_000
        }
    }
    if (!response.status.isSuccess()) throw IOException("整合包图标下载失败：HTTP${response.status.value}")
    return response.bodyAsBytes()
}

internal fun encodeWindowIconPng(bytes: ByteArray): ByteArray {
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
