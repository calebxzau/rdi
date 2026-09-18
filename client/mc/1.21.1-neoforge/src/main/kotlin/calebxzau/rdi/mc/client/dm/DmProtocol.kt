package calebxzau.rdi.mc.client.dm

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
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
    const val HOST_NOT_FOUND: Int = 8
    const val MASTER_UNAVAILABLE: Int = 9

    fun writeRegister(output: OutputStream, hostId: UUID) {
        val data = DataOutputStream(output)
        data.write(PREFACE)
        data.writeByte(ROLE_CONTROL)
        data.writeLong(hostId.mostSignificantBits)
        data.writeLong(hostId.leastSignificantBits)
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
            HOST_NOT_FOUND -> RegistrationReply.HostNotFound
            MASTER_UNAVAILABLE -> RegistrationReply.MasterUnavailable
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
        data object HostNotFound : RegistrationReply
        data object MasterUnavailable : RegistrationReply
    }

    sealed interface ControlMessage {
        data class Open(val connection: Long) : ControlMessage
        data object Ping : ControlMessage
    }
}
