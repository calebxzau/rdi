package calebxzhou.rdi.client.service

internal fun String.isMinecraftVersion(): Boolean =
    matches(Regex("""\d+\.\d+(\.\d+)?"""))

internal fun String.toRemoteLoaderId(): String? =
    when (lowercase()) {
        "forge" -> "forge"
        "fabric" -> "fabric"
        "quilt" -> "quilt"
        "neoforge" -> "neoforge"
        else -> null
    }

