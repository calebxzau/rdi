// The slim FFmpeg natives jar for the platform of target-platform.gradle.kts, which must be applied first.
// Built by client/mediaproc/ffmpeg/build-natives.sh and published by publish-natives.sh.
// -Prdi.ffmpegNativesJar=<path> uses a local jar instead, with no download.
//
// Applied with apply(from = ...); read the file through `val ffmpegNativesJar: File by extra`.

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

val ffmpegNativesVersion = "8.0.1-rdi1"
val ffmpegNativesSha256 = mapOf(
    "windows-x86_64-gpl" to "1c55ad27bf5f2872623b4f4de822078199b2d177a48174b57d9c7eead0496636",
    "linux-x86_64-gpl" to "7346cae44b84cc6cf5f74a24c8685043d0974e55e53eab88628bd23b116fad8b",
    "macosx-arm64-gpl" to "31fc703fb8ee548cfba44ed16ee73c2f71dc62aa585d5e79d19e78e4b8f2573f",
)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun downloadFfmpegNatives(classifier: String): File {
    val expected = ffmpegNativesSha256[classifier]
        ?: throw GradleException("No FFmpeg natives for $classifier")
    val name = "ffmpeg-$ffmpegNativesVersion-$classifier.jar"
    val target = gradle.gradleUserHomeDir.resolve("caches/rdi-ffmpeg-natives/$ffmpegNativesVersion/$name")
    if (target.isFile && sha256(target) == expected) return target

    if (gradle.startParameter.isOffline) {
        throw GradleException("$name is not in the Gradle cache, and Gradle is offline")
    }
    val url = "https://github.com/calebxzau/rdi/releases/download/ffmpeg-natives-$ffmpegNativesVersion/$name"
    logger.lifecycle("Downloading $url")
    target.parentFile.mkdirs()
    val part = Files.createTempFile(target.parentFile.toPath(), name, ".part")
    try {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            connection.inputStream.use { input ->
                Files.copy(input, part, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            connection.disconnect()
        }
        val actual = sha256(part.toFile())
        if (actual != expected) {
            throw GradleException("$name has SHA-256 $actual, expected $expected")
        }
        Files.move(part, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(part)
    }
    return target
}

val ffmpegNativesJarOverride = providers.gradleProperty("rdi.ffmpegNativesJar").orNull
extra["ffmpegNativesJar"] = if (ffmpegNativesJarOverride != null) {
    file(ffmpegNativesJarOverride)
} else {
    downloadFfmpegNatives(extra["ffmpegClassifier"] as String)
}
