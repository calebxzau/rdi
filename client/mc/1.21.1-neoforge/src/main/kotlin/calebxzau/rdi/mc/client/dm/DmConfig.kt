package calebxzau.rdi.mc.client.dm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Per-room WorldData exclusion policy.
 *
 * The file lives in the game directory at `rdi/dm/<hostId>/snapshot-policy.json`, outside
 * the world, so that configuring what is synchronized never changes what is synchronized.
 * Absent or empty means only the fixed exclusions apply. There are no glob patterns in
 * this version: every entry is an exact project-relative path.
 */
object DmWorldSyncPolicyStore {
    const val FILE_NAME = "snapshot-policy.json"

    fun path(gameDirectory: Path, hostId: UUID): Path =
        gameDirectory.resolve("rdi").resolve("dm").resolve(hostId.toString()).resolve(FILE_NAME)

    fun read(gameDirectory: Path, hostId: UUID): Result<DmWorldSyncPolicy> =
        read(path(gameDirectory, hostId))

    fun read(file: Path): Result<DmWorldSyncPolicy> = runCatching {
        if (!Files.isRegularFile(file)) return@runCatching DmWorldSyncPolicy.EMPTY
        val json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
        require(json.isJsonObject) { "世界同步排除配置必须是JSON对象" }
        parse(json.asJsonObject)
    }

    fun parse(json: JsonObject): DmWorldSyncPolicy = DmWorldSyncPolicy(
        paths(json, "excludedFiles"),
        paths(json, "excludedDirectories"),
    )

    private fun paths(json: JsonObject, name: String): Set<String> {
        val array = json.getAsJsonArray(name) ?: return emptySet()
        val values = LinkedHashSet<String>()
        array.forEach { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                "世界同步排除配置${name}必须是字符串数组"
            }
            val value = element.asString
            DmWorldDataCapture.validatePath(value)
            require(values.add(value)) { "世界同步排除配置${name}包含重复项：$value" }
        }
        return values
    }
}

data class DmConfig(
    val baseUri: URI,
    val worldSyncIntervalNanos: Long = DmWorldSyncTiming.DEFAULT_INTERVAL_NANOS,
) {
    companion object {
        const val WORLD_SYNC_INTERVAL_PROPERTY = "rdi.dm.world-sync-interval-seconds"

        fun fromSystemProperty(
            value: String? = System.getProperty("rdi.dm.server"),
            intervalSeconds: String? = System.getProperty(WORLD_SYNC_INTERVAL_PROPERTY),
        ): Result<DmConfig?> = runCatching {
            val configuredInterval = parseWorldSyncInterval(intervalSeconds)
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return@runCatching null
            val uri = URI(text)
            require(uri.scheme == "http" || uri.scheme == "https") { "DM服务器地址必须使用http或https" }
            require(uri.userInfo == null) { "DM服务器地址不能包含用户信息" }
            require(!uri.host.isNullOrBlank()) { "DM服务器地址缺少主机名" }
            require(uri.query == null && uri.fragment == null) { "DM服务器地址不能包含查询参数或片段" }
            val path = uri.path.ifEmpty { "/" }.let { if (it.endsWith('/')) it else "$it/" }
            DmConfig(uri.resolve(path), configuredInterval)
        }

        private fun parseWorldSyncInterval(value: String?): Long {
            if (value == null) return DmWorldSyncTiming.DEFAULT_INTERVAL_NANOS
            val text = value.trim()
            require(text.isNotEmpty()) { "世界同步周期不能为空" }
            val seconds = text.toLongOrNull()
                ?: throw IllegalArgumentException("世界同步周期必须是整数秒数")
            require(seconds > 0L) { "世界同步周期必须为正数" }
            return runCatching { DmWorldSyncTiming.intervalNanos(seconds) }
                .getOrElse { error ->
                    throw IllegalArgumentException("世界同步周期超出范围", error)
                }
        }
    }
}
