package calebxzau.rdi.service

import calebxzau.rdi.mclaunch.GameKotlinRuntime
import calebxzau.rdi.mclaunch.MediaProcGameClasspath
import calebxzau.rdi.mclaunch.RdiZstdRuntime
import calebxzau.rdi.mediaproc.FfmpegNativePlatform
import calebxzau.rdi.mediaproc.MediaProcRuntimeClasspath
import calebxzhou.rdi.common.model.McVersion
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

/** Version of `manifest.json`; the Rust launcher refuses other values. */
const val GAME_RUNTIME_SCHEMA = 1

/** The jars a room game gets on its class path, as this JVM's own resolvers find them (J5 decision D4). */
data class GameRuntimeFiles(
    val kotlin: List<File>,
    val zstd: List<File>,
    val media: List<File>,
    val mediaNative: File,
    val mediaPlatform: String,
)

fun resolveGameRuntimeFiles(scratch: File): Result<GameRuntimeFiles> = runCatching {
    // An empty mods folder: only the runtime class path is resolved, nothing is thinned.
    val kotlin = GameKotlinRuntime.prepare(McVersion.V211, scratch.resolve("no-mods"), scratch.resolve("kotlin-runtime")).getOrThrow()
    val zstd = RdiZstdRuntime.resolve().getOrThrow()
    val media = MediaProcGameClasspath.resolve(nativeRoot = scratch.resolve("mediaproc-natives")).getOrThrow().classpath
    GameRuntimeFiles(
        kotlin = kotlin,
        zstd = zstd,
        media = media,
        mediaNative = MediaProcRuntimeClasspath.nativeJar(media),
        mediaPlatform = FfmpegNativePlatform.current.classifier,
    )
}

/**
 * Writes the `game-runtime` folder for the Rust launcher: the jars side by side and `manifest.json`
 * naming them in class path order. Arguments: the output folder, then the Forgeguard agent jar.
 * The folder is replaced as a whole.
 */
fun exportGameRuntime(output: File, forgeguard: File): Result<Unit> = runCatching {
    require(forgeguard.isFile) { "缺少Forgeguard启动保护文件: ${forgeguard.absolutePath}" }
    val scratch = Files.createTempDirectory("rdi-game-runtime").toFile()
    try {
        val files = resolveGameRuntimeFiles(scratch).getOrThrow()
        val all = files.kotlin + files.zstd + files.media
        val duplicates = all.groupBy { it.name }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "游戏运行库文件名重复: $duplicates" }
        output.deleteRecursively()
        output.mkdirs()
        (all + forgeguard).forEach { file ->
            Files.copy(file.toPath(), output.resolve(file.name).toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        }
        fun names(list: List<File>) = buildJsonArray { list.forEach { add(JsonPrimitive(it.name)) } }
        val manifest = buildJsonObject {
            put("schema", GAME_RUNTIME_SCHEMA)
            put("kotlin", names(files.kotlin))
            put("zstd", names(files.zstd))
            put("media", names(files.media))
            put("mediaNative", files.mediaNative.name)
            put("mediaPlatform", files.mediaPlatform)
            put("forgeguard", forgeguard.name)
        }
        output.resolve("manifest.json").writeText(manifest.toString() + "\n")
    } finally {
        scratch.deleteRecursively()
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) { "用法: GameRuntimeExport <输出目录> <forgeguard.jar>" }
    exportGameRuntime(File(args[0]), File(args[1])).onFailure { error ->
        System.err.println("导出游戏运行库失败: ${error.message}")
        error.printStackTrace()
        exitProcess(1)
    }
}
