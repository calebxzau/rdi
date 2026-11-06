package calebxzau.rdi.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun sharedGoldenFramesRoundTrip() {
        val fixtures = Json.parseToJsonElement(
            File("../slint-proto/protocol/fixtures/messages.json").readText()
        ).jsonArray.map { it.jsonObject }
        val output = ByteArrayOutputStream()
        val writer = Protocol(ByteArrayInputStream(byteArrayOf()), output)
        fixtures.forEach { writer.write(it).getOrThrow() }
        val reader = Protocol(ByteArrayInputStream(output.toByteArray()), ByteArrayOutputStream())
        fixtures.forEach { assertEquals(it, reader.read().getOrThrow()) }
        assertNull(reader.read().getOrThrow())
    }

    @Test
    fun rejectsTruncationAndInvalidFrames() {
        listOf(
            byteArrayOf(0), byteArrayOf(0, 0, 0, 2, 123),
            byteArrayOf(0, 0, 0, 1, 120), byteArrayOf(0, 0, 0, 0),
            byteArrayOf(0, -128, 0, 1),
        ).forEach { bytes ->
            assertTrue(Protocol(ByteArrayInputStream(bytes), ByteArrayOutputStream()).read().isFailure)
        }
    }
}
