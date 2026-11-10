package calebxzau.rdi.service

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Keep the original stdout here; System.out must be redirected before loading services. */
class Protocol(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)

    fun read(): Result<JsonObject?> = runCatching {
        val first = input.read()
        if (first == -1) return@runCatching null
        val length = (first shl 24) or (input.readUnsignedByte() shl 16) or
            (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        require(length in 1..MAX_FRAME) { "启动服务消息长度无效" }
        val body = ByteArray(length)
        input.readFully(body)
        Json.parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true)).jsonObject
    }

    @Synchronized
    fun write(message: JsonObject): Result<Unit> = runCatching {
        val body = message.toString().encodeToByteArray()
        require(body.size in 1..MAX_FRAME) { "启动服务消息过大" }
        output.writeInt(body.size)
        output.write(body)
        output.flush()
    }

    companion object {
        const val VERSION = 3
        const val MAX_FRAME = 8 * 1024 * 1024
    }
}
