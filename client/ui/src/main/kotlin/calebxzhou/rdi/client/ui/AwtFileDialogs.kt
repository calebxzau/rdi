package calebxzhou.rdi.client.ui

import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private const val DIRECTORY_DIALOG_PROPERTY = "apple.awt.fileDialogForDirectories"
private val directoryDialogLock = Any()

private fun defaultDialogDirectory(): File =
    File(System.getProperty("user.home"), "Downloads").takeIf(File::exists)
        ?: File(System.getProperty("user.home"))

private fun <T> withFileDialog(
    title: String,
    mode: Int,
    defaultDirectory: File? = null,
    defaultFile: String? = null,
    multipleMode: Boolean = false,
    filenameFilter: ((File, String) -> Boolean)? = null,
    onCreated: (FileDialog) -> Unit = {},
    block: (FileDialog) -> T
): T {
    val owner = Frame()
    return try {
        val dialog = FileDialog(owner, title, mode).apply {
            directory = defaultDirectory?.absolutePath
            file = defaultFile
            isMultipleMode = multipleMode
            if (filenameFilter != null) {
                this.filenameFilter = java.io.FilenameFilter { dir, name ->
                    filenameFilter(dir, name)
                }
            }
        }
        onCreated(dialog)
        dialog.isVisible = true
        block(dialog)
    } finally {
        owner.dispose()
    }
}

private fun <T> withDirectoryDialogEnabled(block: () -> T): T = synchronized(directoryDialogLock) {
    val previous = System.getProperty(DIRECTORY_DIALOG_PROPERTY)
    System.setProperty(DIRECTORY_DIALOG_PROPERTY, "true")
    try {
        block()
    } finally {
        if (previous == null) {
            System.clearProperty(DIRECTORY_DIALOG_PROPERTY)
        } else {
            System.setProperty(DIRECTORY_DIALOG_PROPERTY, previous)
        }
    }
}

private fun FileDialog.selectedFileOrNull(): File? {
    val dir = directory ?: return null
    val name = file ?: return null
    return File(dir, name)
}

private fun FileDialog.selectedDirectoryOrNull(): File? {
    val dir = directory?.let(::File)
    val name = file
    return buildList {
        if (!name.isNullOrBlank()) {
            add(File(name))
            dir?.let { add(File(it, name)) }
            dir?.parentFile?.let { add(File(it, name)) }
        }
        dir?.let { add(it) }
    }.firstOrNull { it.exists() && it.isDirectory }
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").contains("windows", ignoreCase = true)

internal fun pickAwtOpenFiles(
    title: String,
    defaultDirectory: File = defaultDialogDirectory(),
    defaultFile: String? = null,
    filenameFilter: ((File, String) -> Boolean)? = null
): List<File>? = withFileDialog(
    title = title,
    mode = FileDialog.LOAD,
    defaultDirectory = defaultDirectory,
    defaultFile = defaultFile,
    multipleMode = true,
    filenameFilter = filenameFilter
) { dialog ->
    dialog.files
        ?.toList()
        ?.filter { it.exists() && it.isFile }
        ?.distinctBy { it.absolutePath }
        ?.takeIf { it.isNotEmpty() }
}

/** Post outside Compose's dispatcher so the modal event loop cannot re-enter its click flush. */
internal suspend fun pickAwtOpenFilesAsync(
    title: String,
    defaultDirectory: File = defaultDialogDirectory(),
    filenameFilter: ((File, String) -> Boolean)? = null
): List<File>? = suspendCancellableCoroutine { continuation ->
    // Accessed only on the AWT event thread, including cancellation cleanup.
    var openDialog: FileDialog? = null
    continuation.invokeOnCancellation {
        EventQueue.invokeLater { openDialog?.dispose() }
    }
    EventQueue.invokeLater {
        if (!continuation.isActive) return@invokeLater
        val result = runCatching {
            withFileDialog(
                title = title,
                mode = FileDialog.LOAD,
                defaultDirectory = defaultDirectory,
                multipleMode = true,
                filenameFilter = filenameFilter,
                onCreated = { openDialog = it }
            ) { dialog ->
                dialog.files
                    ?.toList()
                    ?.filter { it.exists() && it.isFile }
                    ?.distinctBy { it.absolutePath }
                    ?.takeIf { it.isNotEmpty() }
            }
        }
        openDialog = null
        continuation.resumeWith(result)
    }
}

internal fun pickAwtSaveFile(
    title: String,
    defaultFileName: String,
    defaultDirectory: File = defaultDialogDirectory(),
    requiredExtension: String? = null,
    filenameFilter: ((File, String) -> Boolean)? = null
): File? = withFileDialog(
    title = title,
    mode = FileDialog.SAVE,
    defaultDirectory = defaultDirectory,
    defaultFile = defaultFileName,
    filenameFilter = filenameFilter
) { dialog ->
    var selected = dialog.selectedFileOrNull() ?: return@withFileDialog null
    if (!requiredExtension.isNullOrBlank() && !selected.name.endsWith(".$requiredExtension", ignoreCase = true)) {
        selected = File(selected.parentFile, "${selected.name}.$requiredExtension")
    }
    selected
}

internal fun pickAwtDirectory(
    title: String,
    defaultDirectory: File = defaultDialogDirectory()
): File? {
    if (isWindows()) {
        return pickModernWindowsDirectory(title, defaultDirectory)
    }
    return withDirectoryDialogEnabled {
        withFileDialog(
            title = title,
            mode = FileDialog.LOAD,
            defaultDirectory = defaultDirectory,
            filenameFilter = { dir, name ->
                File(dir, name).isDirectory
            }
        ) { dialog ->
            dialog.selectedDirectoryOrNull()
        }
    }
}
