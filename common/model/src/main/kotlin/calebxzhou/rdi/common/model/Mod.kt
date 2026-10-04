package calebxzhou.rdi.common.model

import calebxzhou.rdi.common.DL_MOD_DIR
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class Mod(
    val platform: String,//cf / mr / github
    val projectId: String,
    val slug: String,
    val fileId: String,
    val hash: String,
    var side: Side= Side.BOTH,
    val downloadUrls: List<String> = emptyList(),
    val clientOnlyOverride: Boolean = false,
    /** The common source is shadowed by a client override whose bytes have no platform match. */
    val clientOverrideReplaced: Boolean = false,
) {
    val fileSlug
        get() = slug.toModFileSlugAlias()
    val legacyFileName
        get() = "${slug}_${platform}_${hash}.jar"
    val fileName
        get() = "${fileSlug}_${platform}_${hash}.jar"
    val fileNames
        get() = listOf(fileName, legacyFileName).distinct()
    val targetFile get() = targetFile(DL_MOD_DIR)
    val candidateFiles get() = candidateFiles(DL_MOD_DIR)
    val targetPath get() = targetFile.toPath()

    fun targetFile(targetDir: File) = targetDir.resolve(fileName)
    fun candidateFiles(targetDir: File) = fileNames.map(targetDir::resolve)
    fun targetPath(targetDir: File) = targetFile(targetDir).toPath()

    enum class Side(val text:String){
        CLIENT("客户端"),SERVER("服务端"),BOTH("客+服通用"),UNKNOWN("未知")
    }
    //展示modcard的信息
    @Serializable
    data class CardVo(
        val name: String,
        val nameCn: String?=null,
        val intro: String="",
        //jar里的icon 不一定有
        val iconData: ByteArray?=null,
        //curseforge的icon&mc百科的icon  哪个能用用哪个
        val iconUrls: List<String> =emptyList(),
        val side: Side = Side.BOTH,
    ) {
    }
}

@Serializable
data class ModRef(
    val projectId: String,
    val fileId: String,
)

@Serializable
data class ModBatchReplaceItem(
    val projectId: String,
    val fileId: String,
    val mod: Mod,
)

val EXTRA_MOD_PREFIX = $$"X$_"
val Mod.isPlatformCf get() = platform=="cf"
val Mod.isPlatformMr get() = platform=="mr"
val Mod.normalizedProjectId get() = projectId.trim()
val Mod.normalizedSlug get() = slug.trim().lowercase()
val Mod.displaySlugOrProject get() = slug.trim().ifBlank { normalizedProjectId }

val MOD_FILE_SLUG_ALIASES = mapOf(
    "true-ending" to "trueending"
)

fun String.toModFileSlugAlias(): String {
    val normalizedSlug = trim().lowercase()
    return MOD_FILE_SLUG_ALIASES[normalizedSlug] ?: trim()
}

fun sameMod(a: Mod, b: Mod): Boolean {
    if (a.platform == b.platform &&
        a.normalizedProjectId.isNotBlank() &&
        b.normalizedProjectId.isNotBlank()
    ) {
        return a.normalizedProjectId == b.normalizedProjectId
    }
    if (a.normalizedSlug.isNotBlank() && b.normalizedSlug.isNotBlank()) {
        return a.normalizedSlug == b.normalizedSlug
    }
    if (a.hash.isNotBlank() && b.hash.isNotBlank()) {
        return a.hash.equals(b.hash, ignoreCase = true)
    }
    if (a.platform == b.platform && a.fileId.isNotBlank() && b.fileId.isNotBlank()) {
        return a.fileId == b.fileId
    }
    return false
}
