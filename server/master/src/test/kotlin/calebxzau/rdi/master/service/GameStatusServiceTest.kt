package calebxzau.rdi.master.service

import calebxzhou.rdi.common.service.McServerPingException
import calebxzhou.rdi.common.service.McServerPinger
import calebxzhou.rdi.common.service.McServerPlayerSample
import calebxzhou.rdi.common.service.McServerPlayers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameStatusServiceTest {
    @Test
    fun `server list ping returns motd favicon and all players`() {
        val uuid = UUID.fromString("12345678-1234-5678-1234-567812345678")
        val service = GameStatusService(
            port = 0,
            playersProvider = {
                McServerPlayers(
                    max = 2,
                    online = 2,
                    sample = listOf(
                        McServerPlayerSample("测试玩家", uuid.toString()),
                        McServerPlayerSample("second", "87654321-4321-8765-4321-876543218765")
                    )
                )
            }
        )
        service.start().getOrThrow()
        try {
            val result = runBlocking { McServerPinger.ping(service.localPort, timeoutMillis = 3000) }
            assertEquals(GameStatusService.MOTD, result.descriptionText)
            assertEquals(2, result.players?.online)
            assertEquals(2, result.players?.sample?.size)
            assertEquals("测试玩家", result.players?.sample?.first()?.name)
            assertEquals(uuid.toString(), result.players?.sample?.first()?.id)
            assertTrue(result.faviconBase64?.startsWith("data:image/png;base64,") == true)
            assertEquals("RDI Multiplayer", result.version?.name)
            assertEquals(-1, result.version?.protocol)
        } finally {
            service.close()
        }
    }

    @Test
    fun `fragmented handshake is accepted and pong echoes exact long`() {
        val service = GameStatusService(port = 0, playersProvider = { McServerPlayers(1, 0) })
        service.start().getOrThrow()
        try {
            Socket("127.0.0.1", service.localPort).use { socket ->
                val output = DataOutputStream(socket.getOutputStream())
                val handshake = ByteArrayOutputStream()
                DataOutputStream(handshake).use { packet ->
                    packet.writeVarInt(0)
                    packet.writeVarInt(-1)
                    packet.writeVarInt(9)
                    packet.write("localhost".toByteArray())
                    packet.writeShort(service.localPort)
                    packet.writeVarInt(1)
                }
                val handshakeBytes = handshake.toByteArray()
                val framed = ByteArrayOutputStream()
                DataOutputStream(framed).use { packet ->
                    packet.writeVarInt(handshakeBytes.size)
                    packet.write(handshakeBytes)
                }
                framed.toByteArray().forEach { output.writeByte(it.toInt()) }
                output.writeByte(1)
                output.writeByte(0)
                output.flush()

                val input = DataInputStream(socket.getInputStream())
                val responseLength = input.readVarInt()
                assertTrue(responseLength > 0)
                assertEquals(0, input.readVarInt())
                val jsonLength = input.readVarInt()
                input.readFully(ByteArray(jsonLength))

                val timestamp = Long.MIN_VALUE + 123
                output.writeByte(9)
                output.writeByte(1)
                DataOutputStream(socket.getOutputStream()).writeLong(timestamp)
                output.flush()
                assertEquals(9, input.readVarInt())
                assertEquals(1, input.readUnsignedByte())
                assertEquals(timestamp, input.readLong())
            }
        } finally {
            service.close()
        }
    }

    @Test
    fun `provider failure closes that connection while listener continues`() {
        var calls = 0
        val service = GameStatusService(port = 0, playersProvider = {
            calls++
            if (calls == 1) error("database unavailable")
            McServerPlayers(1, 0)
        })
        service.start().getOrThrow()
        try {
            assertFailsWith<McServerPingException> {
                runBlocking { McServerPinger.ping(service.localPort, timeoutMillis = 3000) }
            }
            val result = runBlocking { McServerPinger.ping(service.localPort, timeoutMillis = 3000) }
            assertEquals(0, result.players?.online)
        } finally {
            service.close()
        }
    }

    @Test
    fun `invalid lengths overflow state and trailing bytes are rejected`() {
        val service = GameStatusService(port = 0, playersProvider = { McServerPlayers(1, 0) })
        service.start().getOrThrow()
        try {
            listOf(
                byteArrayOf(0), // Empty frame.
                byteArrayOf(0x81.toByte(), 0x08), // Frame larger than 1024 bytes.
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x10), // VarInt overflow.
            ).forEach { prefix ->
                Socket("127.0.0.1", service.localPort).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write(prefix)
                    socket.getOutputStream().flush()
                    assertClosed(socket)
                }
            }
            assertClosedAfterHandshake(service.localPort, state = 2)
            assertClosedAfterHandshake(service.localPort, trailingHandshakeBytes = byteArrayOf(0x01))
            val result = runBlocking { McServerPinger.ping(service.localPort, timeoutMillis = 3000) }
            assertEquals(0, result.players?.online)
        } finally {
            service.close()
        }
    }

    @Test
    fun `absolute deadline cancels suspended provider`() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val service = GameStatusService(
            port = 0,
            connectionLifetimeMillis = 150,
            providerTimeoutMillis = 10_000,
            playersProvider = {
                started.countDown()
                suspendCancellableCoroutine {
                    it.invokeOnCancellation { cancelled.countDown() }
                }
            }
        )
        service.start().getOrThrow()
        try {
            Socket("127.0.0.1", service.localPort).use { socket ->
                socket.soTimeout = 3000
                sendHandshakeAndStatus(socket, service.localPort)
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                assertClosed(socket)
            }
        } finally {
            service.close()
        }
    }

    @Test
    fun `connection limit rejects additional clients until slot release`() {
        val service = GameStatusService(
            port = 0,
            maxConnections = 1,
            playersProvider = { McServerPlayers(1, 0) }
        )
        service.start().getOrThrow()
        try {
            Socket("127.0.0.1", service.localPort).use { first ->
                first.soTimeout = 2000
                Socket("127.0.0.1", service.localPort).use { second ->
                    second.soTimeout = 2000
                    assertClosed(second)
                }
                sendHandshakeAndStatus(first, service.localPort)
                val input = DataInputStream(first.getInputStream())
                assertTrue(input.readVarInt() > 0)
                assertEquals(0, input.readVarInt())
                val jsonLength = input.readVarInt()
                input.readFully(ByteArray(jsonLength))
                val timestamp = 123456789L
                first.getOutputStream().write(frame(ByteArrayOutputStream().also { bytes ->
                    DataOutputStream(bytes).use { ping ->
                        ping.writeVarInt(1)
                        ping.writeLong(timestamp)
                    }
                }.toByteArray()))
                first.getOutputStream().flush()
                assertEquals(9, input.readVarInt())
                assertEquals(1, input.readUnsignedByte())
                assertEquals(timestamp, input.readLong())
                assertClosed(first)
            }
            val result = runBlocking { McServerPinger.ping(service.localPort, timeoutMillis = 3000) }
            assertEquals(0, result.players?.online)
        } finally {
            service.close()
        }
    }

    @Test
    fun `close cancels provider closes idle socket and permits immediate rebind`() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val service = GameStatusService(port = 0, playersProvider = {
            started.countDown()
            suspendCancellableCoroutine {
                it.invokeOnCancellation { cancelled.countDown() }
            }
        })
        service.start().getOrThrow()
        val port = service.localPort
        val idleSocket = Socket("127.0.0.1", port)
        val socket = Socket("127.0.0.1", port)
        try {
            idleSocket.soTimeout = 3000
            socket.soTimeout = 3000
            sendHandshakeAndStatus(socket, port)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            service.close()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertClosed(idleSocket)
            assertClosed(socket)

            val rebound = GameStatusService(port = port, playersProvider = { McServerPlayers(1, 0) })
            rebound.start().getOrThrow()
            rebound.close()
        } finally {
            runCatching { idleSocket.close() }
            runCatching { socket.close() }
            service.close()
        }
    }

    @Test
    fun `occupied port failure leaves service retryable after release`() {
        val blocker = ServerSocket(0)
        val service = GameStatusService(port = blocker.localPort, playersProvider = { McServerPlayers(1, 0) })
        try {
            assertTrue(service.start().isFailure)
            blocker.close()
            service.start().getOrThrow()
            assertTrue(service.localPort > 0)
        } finally {
            service.close()
            runCatching { blocker.close() }
        }
    }

    @Test
    fun `missing favicon fails startup and oversized response is closed`() {
        val missing = GameStatusService(
            port = 0,
            faviconResource = "/does-not-exist.png",
            playersProvider = { McServerPlayers(1, 0) }
        )
        assertTrue(missing.start().isFailure)
        missing.close()

        val oversized = GameStatusService(port = 0, playersProvider = {
            McServerPlayers(
                max = 1,
                online = 1,
                sample = listOf(
                    McServerPlayerSample(
                        "x".repeat(33_000),
                        "12345678-1234-5678-1234-567812345678"
                    )
                )
            )
        })
        oversized.start().getOrThrow()
        try {
            assertFailsWith<McServerPingException> {
                runBlocking { McServerPinger.ping(oversized.localPort, timeoutMillis = 3000) }
            }
        } finally {
            oversized.close()
        }
    }

    private fun DataOutputStream.writeVarInt(value: Int) {
        var current = value
        while (current and 0xFFFFFF80.toInt() != 0) {
            writeByte((current and 0x7F) or 0x80)
            current = current ushr 7
        }
        writeByte(current)
    }

    private fun DataInputStream.readVarInt(): Int {
        var result = 0
        repeat(5) { index ->
            val value = readUnsignedByte()
            result = result or ((value and 0x7F) shl (index * 7))
            if (value and 0x80 == 0) return result
        }
        error("invalid varint")
    }

    private fun assertClosed(socket: Socket) {
        try {
            assertTrue(socket.getInputStream().read() < 0, "expected server to close connection")
        } catch (_: SocketTimeoutException) {
            throw AssertionError("timed out waiting for server to close connection")
        } catch (_: SocketException) {
            // A reset is also a valid rejection of malformed input.
        } catch (error: IOException) {
            throw AssertionError("unexpected I/O error while checking connection closure", error)
        }
    }

    private fun assertClosedAfterHandshake(
        port: Int,
        state: Int = 1,
        trailingHandshakeBytes: ByteArray = byteArrayOf()
    ) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2000
            val handshake = handshakePayload(port, state, trailingHandshakeBytes)
            socket.getOutputStream().write(frame(handshake))
            socket.getOutputStream().flush()
            assertClosed(socket)
        }
    }

    private fun sendHandshakeAndStatus(socket: Socket, port: Int) {
        val output = socket.getOutputStream()
        output.write(frame(handshakePayload(port, 1)))
        output.write(frame(byteArrayOf(0)))
        output.flush()
    }

    private fun handshakePayload(port: Int, state: Int, trailing: ByteArray = byteArrayOf()): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { packet ->
            packet.writeVarInt(0)
            packet.writeVarInt(-1)
            packet.writeVarInt(9)
            packet.write("localhost".toByteArray())
            packet.writeShort(port)
            packet.writeVarInt(state)
            packet.write(trailing)
        }
        return payload.toByteArray()
    }

    private fun frame(payload: ByteArray): ByteArray {
        val framed = ByteArrayOutputStream()
        DataOutputStream(framed).use { packet ->
            packet.writeVarInt(payload.size)
            packet.write(payload)
        }
        return framed.toByteArray()
    }
}
