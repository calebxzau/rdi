package calebxzau.rdi.mc.client.dm

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

object DmProtocol {
    private val PREFACE = byteArrayOf('D'.code.toByte(), 'M'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
    const val ROLE_CONTROL: Int = 1
    const val ROLE_ATTACH: Int = 2
    const val READY: Int = 1
    const val BUSY: Int = 2
    const val NO_PORT: Int = 3
    const val OPEN: Int = 4
    const val FAILED: Int = 5
    const val PING: Int = 6
    const val PONG: Int = 7
    const val MAX_ROOM_BYTES: Int = 128

    fun writeRegister(output: OutputStream, room: String) {
        val bytes = room.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_ROOM_BYTES)
        val data = DataOutputStream(output)
        data.write(PREFACE)
        data.writeByte(ROLE_CONTROL)
        data.writeShort(bytes.size)
        data.write(bytes)
        data.flush()
    }

    fun readRegistrationReply(input: InputStream): RegistrationReply {
        val data = DataInputStream(input)
        return when (data.readUnsignedByte()) {
            READY -> {
                val session = UUID(data.readLong(), data.readLong())
                val port = data.readUnsignedShort()
                require(port != 0) { "DM网关返回了无效游戏端口" }
                RegistrationReply.Ready(session, port)
            }
            BUSY -> RegistrationReply.Busy
            NO_PORT -> RegistrationReply.NoPort
            else -> error("未知DM网关注册响应")
        }
    }

    fun readControlMessage(input: DataInputStream): ControlMessage {
        return when (input.readUnsignedByte()) {
            OPEN -> ControlMessage.Open(input.readLong())
            PING -> ControlMessage.Ping
            else -> error("未知DM网关控制消息")
        }
    }

    fun writePong(output: OutputStream) {
        val data = DataOutputStream(output)
        data.writeByte(PONG)
        data.flush()
    }

    fun writeFailed(output: OutputStream, connection: Long) {
        val data = DataOutputStream(output)
        data.writeByte(FAILED)
        data.writeLong(connection)
        data.flush()
    }

    fun writeAttach(output: OutputStream, session: UUID, connection: Long) {
        val data = DataOutputStream(output)
        data.write(PREFACE)
        data.writeByte(ROLE_ATTACH)
        data.writeLong(session.mostSignificantBits)
        data.writeLong(session.leastSignificantBits)
        data.writeLong(connection)
        data.flush()
    }

    sealed interface RegistrationReply {
        data class Ready(val session: UUID, val gamePort: Int) : RegistrationReply
        data object Busy : RegistrationReply
        data object NoPort : RegistrationReply
    }

    sealed interface ControlMessage {
        data class Open(val connection: Long) : ControlMessage
        data object Ping : ControlMessage
    }
}
