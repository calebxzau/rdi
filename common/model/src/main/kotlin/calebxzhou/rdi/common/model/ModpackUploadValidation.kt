package calebxzhou.rdi.common.model

const val MODPACK_INFO_MIN_CHARACTERS = 10
const val MODPACK_INFO_MAX_CHARACTERS = 100

/** The largest packed modpack archive that can be uploaded. */
const val MODPACK_UPLOAD_MAX_MB = 768
const val MODPACK_UPLOAD_MAX_BYTES = MODPACK_UPLOAD_MAX_MB * 1024L * 1024L

fun String.modpackInfoCharacterCount(): Int = codePointCount(0, length)

fun validateRequiredModpackUploadMetadata(
    info: String?,
    iconUrl: String?,
    categories: List<Modpack.Category>,
): Result<Unit> = runCatching {
    val normalizedInfo = info?.trim().orEmpty()
    val infoLength = normalizedInfo.modpackInfoCharacterCount()
    require(infoLength in MODPACK_INFO_MIN_CHARACTERS..MODPACK_INFO_MAX_CHARACTERS) {
        "简介长度必须为${MODPACK_INFO_MIN_CHARACTERS}~${MODPACK_INFO_MAX_CHARACTERS}个字符"
    }
    require(!iconUrl?.trim().isNullOrBlank()) { "图标链接不能为空" }
    require(categories.isNotEmpty()) { "至少选择1个分类" }
    require(categories.distinct().size <= Modpack.MAX_CATEGORY_COUNT) {
        "分类最多选择${Modpack.MAX_CATEGORY_COUNT}个"
    }
}
