package calebxzau.rdi.mc.client.dm

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.SequenceInputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

data class DmHostInfo(val id: UUID, val name: String, val state: String? = null, val gamePort: Int? = null)
data class DmGatewayInfo(val publicHost: String?, val tunnelPort: Int)

class DmHttpClient(private val config: DmConfig) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun info(): Result<DmGatewayInfo> = getJson("info").mapCatching { json ->
        DmGatewayInfo(json.stringOrNull("publicHost"), json.get("tunnelPort").asInt)
    }

    fun getHost(id: UUID): Result<DmHostInfo> = getJson("hosts/$id").mapCatching(::hostInfo)

    fun createHost(name: String, archive: Path, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<DmHostInfo> = runCatching {
        require(name.isNotBlank()) { "房间名称不能为空" }
        require('\r' !in name && '\n' !in name) { "房间名称不能包含换行" }
        require(Files.isRegularFile(archive)) { "初始化资料不存在" }
        val boundary = "rdi-${UUID.randomUUID()}"
        val size = Files.size(archive)
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n$name\r\n".toByteArray(Charsets.UTF_8)
        val filePrefix = "--$boundary\r\nContent-Disposition: form-data; name=\"worldInit\"; filename=\"world-init.zip\"\r\nContent-Type: application/zip\r\n\r\n".toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val uploaded = java.util.concurrent.atomic.AtomicLong()
        val request = request("hosts")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofInputStream {
                SequenceInputStream(
                    ByteArrayInputStream(prefix),
                    SequenceInputStream(
                        ByteArrayInputStream(filePrefix),
                        SequenceInputStream(
                            ProgressInputStream(Files.newInputStream(archive)) { value ->
                                onProgress(uploaded.addAndGet(value), size)
                            },
                            ByteArrayInputStream(suffix),
                        ),
                    ),
                )
            })
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        hostInfo(parseResponse(response.body(), response.statusCode()))
    }

    fun downloadWorldInit(id: UUID, destination: Path): Result<Unit> = runCatching {
        val response = client.send(
            request("hosts/$id/world-init").GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        if (!contentType.lowercase().startsWith("application/zip")) {
            val body = response.body().use { it.readBytes().toString(Charsets.UTF_8) }
            parseResponse(body, response.statusCode())
            error("DM服务器没有返回初始化ZIP")
        }
        destination.parent?.let(Files::createDirectories)
        response.body().use { input ->
            BufferedInputStream(input).use { source ->
                Files.newOutputStream(destination).use { output ->
                    BufferedOutputStream(output).use { target -> source.copyTo(target) }
                }
            }
        }
    }

    private fun getJson(path: String): Result<JsonObject> = runCatching {
        val response = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        parseResponse(response.body(), response.statusCode())
    }

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder(
        config.baseUri.resolve(path.trimStart('/')),
    ).timeout(Duration.ofMinutes(10))

    private fun parseResponse(body: String, status: Int): JsonObject {
        require(status == 200) { "DM服务器返回HTTP$status" }
        val json = JsonParser.parseString(body).asJsonObject
        require(json.get("code")?.asInt == 0) { json.get("msg")?.asString ?: "DM服务器请求失败" }
        return json.getAsJsonObject("data") ?: JsonObject()
    }

    private fun hostInfo(json: JsonObject): DmHostInfo {
        val id = UUID.fromString(json.get("id").asString)
        return DmHostInfo(id, json.get("name").asString, json.stringOrNull("state"), json.intOrNull("gamePort"))
    }

    private class ProgressInputStream(
        input: java.io.InputStream,
        private val progress: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var read: Long = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) {
                read += count
                progress(count.toLong())
            }
            return count
        }

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                read++
                progress(1)
            }
            return value
        }
    }

    private fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.intOrNull(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt
}
