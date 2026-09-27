package calebxzau.rdi.mediaproc

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.jar.JarFile

object MediaProcNativeRuntime {
    private const val NATIVE_PREFIX = "org/bytedeco/ffmpeg/windows-x86_64-gpl/"
    private const val READY_MARKER = ".complete"
    // FileChannel locks coordinate processes, but overlapping locks in this JVM throw instead of waiting.
    private val extractionLock = Any()

    fun prepare(nativeJar: File, rootDir: File): Result<File> = runCatching {
        require(nativeJar.isFile) { "FFmpeg native runtime does not exist: ${nativeJar.absolutePath}" }
        Files.createDirectories(rootDir.toPath())
        val bundleDir = rootDir.resolve(nativeJar.sha256())
        val lockPath = rootDir.resolve("${bundleDir.name}.lock").toPath()

        synchronized(extractionLock) {
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val marker = bundleDir.resolve(READY_MARKER)
                    if (!marker.isFile) {
                        extractNativeLibraries(nativeJar, bundleDir)
                        marker.writeText(nativeJar.name)
                    }
                }
            }
        }
        check(bundleDir.resolve("jniavutil.dll").isFile) { "FFmpeg native runtime is incomplete" }
        bundleDir
    }

    private fun extractNativeLibraries(nativeJar: File, bundleDir: File) {
        val bundlePath = bundleDir.toPath()
        Files.createDirectories(bundlePath)
        JarFile(nativeJar).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(NATIVE_PREFIX) }
                .forEach { entry ->
                    val target = bundlePath.resolve(entry.name.removePrefix(NATIVE_PREFIX)).normalize()
                    require(target.startsWith(bundlePath)) { "Invalid FFmpeg native entry: ${entry.name}" }
                    Files.createDirectories(target.parent)
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
