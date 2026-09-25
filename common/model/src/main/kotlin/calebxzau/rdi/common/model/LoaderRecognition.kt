package calebxzau.rdi.common.model

import calebxzhou.rdi.common.model.ModLoader

data class LoaderIdentity(
    val loader: ModLoader,
    val declaredVersion: String? = null,
)

data class LoaderDeclaration(
    val id: String,
    val primary: Boolean = false,
)

object LoaderRecognition {
    private val modrinthLoaderKeys = mapOf(
        "forge" to ModLoader.forge,
        "neoforge" to ModLoader.neoforge,
        "fabric-loader" to ModLoader.Fabric,
        "quilt-loader" to null,
    )

    fun modrinth(dependencies: Map<String, String>): Result<LoaderIdentity> = runCatching {
        val declarations = dependencies.filterKeys { it in modrinthLoaderKeys }
        if (declarations.size > 1) {
            throw IllegalArgumentException("整合包声明了多个Mod加载器: ${declarations.keys.joinToString()}")
        }
        val (key, rawVersion) = declarations.entries.singleOrNull()
            ?: throw IllegalArgumentException("整合包缺少受支持的Mod加载器声明")
        val loader = modrinthLoaderKeys.getValue(key)
            ?: throw IllegalArgumentException("暂不支持Mod加载器: $key")
        val version = rawVersion.trim()
        if (version.isBlank()) throw IllegalArgumentException("整合包缺少Mod加载器版本: $key")
        LoaderIdentity(loader, version)
    }

    fun curseForge(declarations: List<LoaderDeclaration>): Result<LoaderIdentity> = runCatching {
        val primaryDeclarations = declarations.filter(LoaderDeclaration::primary)
        if (primaryDeclarations.size > 1) {
            throw IllegalArgumentException("CurseForge清单声明了多个主Mod加载器")
        }
        val declaration = when {
            primaryDeclarations.size == 1 -> primaryDeclarations.single()
            declarations.size == 1 -> declarations.single()
            declarations.isEmpty() -> throw IllegalArgumentException("CurseForge清单缺少Mod加载器声明")
            else -> throw IllegalArgumentException("CurseForge清单包含多个Mod加载器且未指定唯一主加载器")
        }
        val id = declaration.id.trim()
        val separator = id.indexOf('-')
        if (separator <= 0 || separator == id.lastIndex) {
            throw IllegalArgumentException("无法识别CurseForge Mod加载器声明: $id")
        }
        val loaderName = id.substring(0, separator).lowercase()
        val declaredVersion = id.substring(separator + 1).trim()
        if (declaredVersion.isBlank()) {
            throw IllegalArgumentException("CurseForge Mod加载器版本为空: $id")
        }
        val loader = when (loaderName) {
            "forge" -> ModLoader.forge
            "neoforge" -> ModLoader.neoforge
            "fabric" -> {
                if (!FABRIC_VERSION.matches(declaredVersion)) {
                    throw IllegalArgumentException("Fabric加载器版本格式无效: $declaredVersion")
                }
                ModLoader.Fabric
            }
            else -> throw IllegalArgumentException("暂不支持CurseForge Mod加载器: $loaderName")
        }
        LoaderIdentity(loader, declaredVersion)
    }

    private val FABRIC_VERSION = Regex("\\d+(?:\\.\\d+)+(?:[-+][0-9A-Za-z][0-9A-Za-z.+-]*)?")
}
