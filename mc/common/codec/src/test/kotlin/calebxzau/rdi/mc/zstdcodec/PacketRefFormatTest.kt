package calebxzau.rdi.mc.zstdcodec

import kotlin.test.Test
import kotlin.test.assertEquals

/** Locks protocol version 1; expected values come from an independent Python implementation. */
class PacketRefFormatTest {
    @Test
    fun `hash matches fixed FNV-1a 64 plus fmix64 vectors`() {
        assertEquals(0xefd01f60ba992926UL.toLong(), PacketRefFormat.hash(ByteArray(0)))
        assertEquals(0x82a2a958a9bece5bUL.toLong(), PacketRefFormat.hash("a".toByteArray()))
        assertEquals(0x33ebaf9927cbc5bdUL.toLong(), PacketRefFormat.hash("abc".toByteArray()))
        assertEquals(0x77c5597381d7cfa1UL.toLong(), PacketRefFormat.hash(KILOBYTE_VECTOR))
    }

    @Test
    fun `hash covers only the requested prefix`() {
        val padded = "abc".toByteArray() + byteArrayOf(9, 9, 9)
        assertEquals(PacketRefFormat.hash("abc".toByteArray()), PacketRefFormat.hash(padded, 3))
    }

    @Test
    fun `check is the low 32 bits of the hash`() {
        assertEquals(0xba992926UL.toLong().toInt(), PacketRefFormat.check(PacketRefFormat.hash(ByteArray(0))))
        assertEquals(0x81d7cfa1UL.toLong().toInt(), PacketRefFormat.check(PacketRefFormat.hash(KILOBYTE_VECTOR)))
    }

    @Test
    fun `raw legacy frame size includes the zero marker and a growing outer prefix`() {
        assertEquals(10, PacketRefFormat.rawLegacyFrameBytes(8))
        assertEquals(128, PacketRefFormat.rawLegacyFrameBytes(126))
        assertEquals(130, PacketRefFormat.rawLegacyFrameBytes(127))
    }

    @Test
    fun `reference frame grows by one byte from slot 128`() {
        assertEquals(6, PacketRefFormat.referenceFrameBytes(127))
        assertEquals(7, PacketRefFormat.referenceFrameBytes(128))
        assertEquals(7, PacketRefFormat.withOuterPrefix(PacketRefFormat.referenceFrameBytes(127)))
        assertEquals(8, PacketRefFormat.withOuterPrefix(PacketRefFormat.referenceFrameBytes(128)))
    }

    @Test
    fun `accepted START parameters match the documented range`() {
        assertEquals(false, PacketRefFormat.isSlotCountAccepted(0))
        assertEquals(true, PacketRefFormat.isSlotCountAccepted(1))
        assertEquals(true, PacketRefFormat.isSlotCountAccepted(1024))
        assertEquals(false, PacketRefFormat.isSlotCountAccepted(1025))
        assertEquals(false, PacketRefFormat.isMaxEntryBytesAccepted(7))
        assertEquals(true, PacketRefFormat.isMaxEntryBytesAccepted(8))
        assertEquals(true, PacketRefFormat.isMaxEntryBytesAccepted(2048))
        assertEquals(false, PacketRefFormat.isMaxEntryBytesAccepted(2049))
    }

    companion object {
        val KILOBYTE_VECTOR = ByteArray(1024) { ((it * 31 + 7) and 0xFF).toByte() }
    }
}
