package calebxzhou.rdi.client.service

internal fun String.safePathSegment(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
