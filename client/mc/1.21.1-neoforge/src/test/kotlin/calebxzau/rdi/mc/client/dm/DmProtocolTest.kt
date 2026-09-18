package calebxzau.rdi.mc.client.dm

import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DmProtocolTest {
    @Test
    fun readsTerminalRegistrationStatuses() {
        assertEquals(DmProtocol.RegistrationReply.HostNotFound, reply(DmProtocol.HOST_NOT_FOUND))
        assertEquals(DmProtocol.RegistrationReply.MasterUnavailable, reply(DmProtocol.MASTER_UNAVAILABLE))
        assertEquals(DmProtocol.RegistrationReply.Busy, reply(DmProtocol.BUSY))
        assertEquals(DmProtocol.RegistrationReply.NoPort, reply(DmProtocol.NO_PORT))
    }

    private fun reply(status: Int): DmProtocol.RegistrationReply {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(status)
            if (status == DmProtocol.READY) {
                val session = UUID.randomUUID()
                output.writeLong(session.mostSignificantBits)
                output.writeLong(session.leastSignificantBits)
                output.writeShort(1)
            }
        }
        return DmProtocol.readRegistrationReply(ByteArrayInputStream(bytes.toByteArray()))
    }
}
