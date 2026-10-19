package calebxzhou.rdi.client.ui.screen

import calebxzhou.rdi.client.service.ModpackLocalDir
import calebxzhou.rdi.client.service.prepareRdiPack2ImportTask
import calebxzhou.rdi.client.service.rdiPack2ExportTask
import calebxzhou.rdi.client.service.PreparedRdiPack2Task
import calebxzhou.rdi.client.ui.pickAwtSaveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun isRdiPack2ImportFileName(name: String): Boolean =
    name.endsWith(".rdipack2", ignoreCase = true)

private fun selectRdiPack2File(): File? {
    val owner = Frame()
    try {
        val dialog = FileDialog(owner, "选择RDI整合包", FileDialog.LOAD).apply {
            directory = File(System.getProperty("user.home"), "Downloads").absolutePath
            file = "*.rdipack2"
            filenameFilter = java.io.FilenameFilter { dir, name ->
                val target = File(dir, name)
                target.isDirectory || isRdiPack2ImportFileName(name)
            }
        }
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return File(dir, name)
            .takeIf { it.exists() && it.isFile && isRdiPack2ImportFileName(it.name) }
    } finally {
        owner.dispose()
    }
}

suspend fun prepareRdiPack2ImportFromPicker(): PreparedRdiPack2Task? = withContext(Dispatchers.IO) {
    val file = selectRdiPack2File() ?: return@withContext null
    prepareRdiPack2ImportTask(file)
}

suspend fun exportRdiModpack2(
    packdir: ModpackLocalDir,
): Result<PreparedRdiPack2Task?> = withContext(Dispatchers.IO) {
    runCatching {
        val safeName = packdir.name.ifBlank { "modpack" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val output = pickAwtSaveFile(
            title = "选择整合包保存位置",
            defaultFileName = "${safeName}_${packdir.verName}.rdipack2",
            defaultDirectory = File(System.getProperty("user.home")),
            requiredExtension = "rdipack2",
            filenameFilter = { _, name -> name.endsWith(".rdipack2", ignoreCase = true) },
        ) ?: return@runCatching null
        rdiPack2ExportTask(packdir, output)
    }
}

suspend fun exportLogsPack(packdir: ModpackLocalDir): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val sourceDirs = listOf("logs", "crash-reports")
            .map { packdir.dir.resolve(it) }
            .filter { it.exists() && it.isDirectory }
        // JVM崩溃报告：旧版启动器写在版本目录根部，新版写在logs/里
        val jvmCrashFiles = packdir.dir.listFiles { file ->
            file.isFile && file.name.startsWith("hs_err_pid") && file.name.endsWith(".log")
        }.orEmpty().toList()
        if (sourceDirs.isEmpty() && jvmCrashFiles.isEmpty()) {
            throw IllegalStateException("没有可导出的日志目录")
        }

        val safeName = packdir.name.ifBlank { "modpack" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val defaultName = "${safeName}_${packdir.verName}_logs.zip"
        val outputFile = pickAwtSaveFile(
            title = "选择日志保存位置",
            defaultFileName = defaultName,
            defaultDirectory = File(System.getProperty("user.home")),
            requiredExtension = "zip",
            filenameFilter = { _, name -> name.endsWith(".zip", ignoreCase = true) }
        ) ?: return@runCatching

        ZipOutputStream(FileOutputStream(outputFile)).use { zipOut ->
            sourceDirs.forEach { dir ->
                val baseName = dir.name
                dir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val relative = file.relativeTo(dir).invariantSeparatorsPath
                        val entryName = "$baseName/$relative"
                        zipOut.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
            }
            jvmCrashFiles.forEach { file ->
                zipOut.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            }
        }
    }
}
