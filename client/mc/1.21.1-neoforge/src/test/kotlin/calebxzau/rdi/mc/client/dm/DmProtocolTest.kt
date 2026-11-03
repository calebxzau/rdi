package calebxzau.rdi.mc.client.dm

import java.io.ByteArrayInputStream
import java.io.DataInputStream
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
        assertEquals(DmProtocol.RegistrationReply.ClaimRefused, reply(DmProtocol.CLAIM_REFUSED))
        assertEquals(DmProtocol.RegistrationReply.TicketRefused, reply(DmProtocol.TICKET_REFUSED))
        assertEquals(DmProtocol.RegistrationReply.NotOwner, reply(DmProtocol.NOT_OWNER))
    }

    @Test
    fun registersWithoutAClaimUsingTheControlRole() {
        val host = UUID.fromString("0199a000-0000-7000-8000-000000000001")
        val bytes = ByteArrayOutputStream().also { DmProtocol.writeRegister(it, host, "ticket") }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals("DMG1", String(input.readNBytes(4), Charsets.US_ASCII))
        assertEquals(DmProtocol.ROLE_CONTROL, input.readUnsignedByte())
        assertEquals(host, UUID(input.readLong(), input.readLong()))
        assertEquals(6, input.readUnsignedShort())
        assertEquals("ticket", String(input.readNBytes(6), Charsets.US_ASCII))
        assertEquals(-1, input.read())
    }

    @Test
    fun registersWithAClaimUsingTheClaimRole() {
        val host = UUID.fromString("0199a000-0000-7000-8000-000000000001")
        val claim = UUID.fromString("0199a000-0000-7000-8000-000000000002")
        val bytes = ByteArrayOutputStream().also { DmProtocol.writeRegister(it, host, "ticket", claim) }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals("DMG1", String(input.readNBytes(4), Charsets.US_ASCII))
        assertEquals(DmProtocol.ROLE_CONTROL_CLAIM, input.readUnsignedByte())
        assertEquals(host, UUID(input.readLong(), input.readLong()))
        assertEquals(claim, UUID(input.readLong(), input.readLong()))
        assertEquals(6, input.readUnsignedShort())
        assertEquals("ticket", String(input.readNBytes(6), Charsets.US_ASCII))
        assertEquals(-1, input.read())
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
