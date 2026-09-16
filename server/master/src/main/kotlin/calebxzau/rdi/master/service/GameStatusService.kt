package calebxzau.rdi.master.service

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.service.McServerPlayers
import calebxzhou.rdi.common.service.McServerStatusPayload
import calebxzhou.rdi.common.service.McServerVersion
import calebxzhou.rdi.common.serdesJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded implementation of the Minecraft Java Server List Ping protocol. */
class GameStatusService(
    private val port: Int,
    private val connectionLifetimeMillis: Long = DEFAULT_CONNECTION_LIFETIME_MILLIS,
    private val providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val faviconResource: String = "/favicon.png",
    private val playersProvider: suspend () -> McServerPlayers
) : AutoCloseable {
    private val logger = KotlinLogging.logger { }
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private val activeConnections = ConcurrentHashMap<Socket, Connection>()
    private val connectionSlots = Semaphore(maxConnections)

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var connectionExecutor: java.util.concurrent.ExecutorService? = null
    private var deadlineExecutor: ScheduledExecutorService? = null
    private var acceptFuture: Future<*>? = null
    private var boundPort: Int? = null

    val localPort: Int
        get() = synchronized(lifecycleLock) {
            boundPort ?: throw IllegalStateException("Game status service has not started")
        }

    init {
        require(port in 0..65535) { "game status port must be between 0 and 65535" }
        require(connectionLifetimeMillis > 0) { "connection lifetime must be positive" }
        require(providerTimeoutMillis > 0) { "provider timeout must be positive" }
        require(readTimeoutMillis > 0) { "read timeout must be positive" }
        require(maxConnections > 0) { "max connections must be positive" }
    }

    fun start(): Result<Unit> = runCatching {
        synchronized(lifecycleLock) {
            check(!closed.get()) { "Game status service is closed" }
            check(serverSocket == null) { "Game status service is already started" }
            val favicon = loadFavicon()
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port))
                val executor = Executors.newVirtualThreadPerTaskExecutor()
                val deadlines = (Executors.newScheduledThreadPool(1) as ScheduledThreadPoolExecutor).also {
                    it.removeOnCancelPolicy = true
                }
                try {
                    // Publish the complete startup state only after all startup resources exist.
                    cachedFavicon = favicon
                    serverSocket = socket
                    connectionExecutor = executor
                    deadlineExecutor = deadlines
                    boundPort = socket.localPort
                    acceptFuture = executor.submit { acceptLoop() }
                    logger.info { "Minecraft status listener started on port ${socket.localPort}" }
                } catch (error: Throwable) {
                    deadlines.shutdownNow()
                    executor.shutdownNow()
                    throw error
                }
            } catch (error: Throwable) {
                runCatching { socket.close() }
                serverSocket = null
                connectionExecutor?.shutdownNow()
                deadlineExecutor?.shutdownNow()
                connectionExecutor = null
                deadlineExecutor = null
                boundPort = null
                throw error
            }
        }
    }

    @Volatile
    private var cachedFavicon: String? = null

    private fun loadFavicon(): String {
        val bytes = (GameStatusService::class.java.getResourceAsStream(faviconResource)
            ?: throw IllegalStateException("Minecraft status favicon resource not found: $faviconResource")).use { it.readBytes() }
        require(bytes.size >= PNG_SIGNATURE.size + 8 + 13) { "Minecraft status favicon is not a valid PNG" }
        require(bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            "Minecraft status favicon has an invalid PNG signature"
        }
        val ihdrOffset = PNG_SIGNATURE.size
        val ihdrLength = ByteBuffer.wrap(bytes, ihdrOffset, 4).int
        require(ihdrLength == 13 && bytes.copyOfRange(ihdrOffset + 4, ihdrOffset + 8).contentEquals("IHDR".toByteArray())) {
            "Minecraft status favicon has no valid IHDR"
        }
        val width = ByteBuffer.wrap(bytes, ihdrOffset + 8, 4).int
        val height = ByteBuffer.wrap(bytes, ihdrOffset + 12, 4).int
        require(width == 64 && height == 64) {
            "Minecraft status favicon must be 64x64, got ${width}x${height}"
        }
        return "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val accepted = try {
                serverSocket?.accept() ?: return
            } catch (error: SocketException) {
                if (!closed.get()) logger.error(error) { "Minecraft status listener accept failed" }
                return
            } catch (error: Throwable) {
                if (!closed.get()) logger.error(error) { "Minecraft status listener accept failed" }
                return
            }
            if (!connectionSlots.tryAcquire()) {
                runCatching { accepted.close() }
                continue
            }
            val connection = Connection(accepted)
            synchronized(lifecycleLock) {
                if (closed.get() || serverSocket == null) {
                    connectionSlots.release()
                    runCatching { accepted.close() }
                    continue
                }
                activeConnections[accepted] = connection
                try {
                    val executor = connectionExecutor ?: throw IllegalStateException("listener executor unavailable")
                    val deadlines = deadlineExecutor ?: throw IllegalStateException("listener deadline executor unavailable")
                    // Schedule the absolute deadline before submission so a fast connection cannot miss it.
                    connection.deadline = deadlines.schedule(
                        {
                            connection.deadlineTriggered = true
                            connection.providerJob?.cancel()
                            connection.coroutineJob?.cancel()
                            runCatching { accepted.close() }
                        },
                        connectionLifetimeMillis,
                        TimeUnit.MILLISECONDS
                    )
                    connection.task = executor.submit { runConnection(connection) }
                } catch (error: Throwable) {
                    activeConnections.remove(accepted)
                    connection.deadline?.cancel(false)
                    connectionSlots.release()
                    runCatching { accepted.close() }
                    if (!closed.get()) logger.error(error) { "Minecraft status connection scheduling failed" }
                }
            }
        }
    }

    private fun runConnection(connection: Connection) {
        try {
            connection.socket.soTimeout = readTimeoutMillis
            runBlocking {
                connection.coroutineJob = currentCoroutineContext()[Job]
                handleConnection(connection)
            }
        } catch (error: Throwable) {
            if (error !is EOFException && !closed.get() && !connection.deadlineTriggered) {
                if (!connection.providerFailure && error !is CancellationException) {
                    logger.debug(error) { "Minecraft status client disconnected with protocol error" }
                }
            }
        } finally {
            connection.providerJob?.cancel()
            connection.deadline?.cancel(false)
            activeConnections.remove(connection.socket)
            connectionSlots.release()
            runCatching { connection.socket.close() }
        }
    }

    private suspend fun handleConnection(connection: Connection) = coroutineScope {
        val input = DataInputStream(connection.socket.getInputStream())
        val output = DataOutputStream(connection.socket.getOutputStream())
        val handshake = readFrame(input)
        parseHandshake(handshake)
        val statusRequest = readFrame(input)
        require(statusRequest.contentEquals(byteArrayOf(0))) { "invalid status request" }
        check(!connection.deadlineTriggered && !closed.get()) { "connection deadline exceeded" }

        val providerJob = async {
            withTimeout(providerTimeoutMillis) { playersProvider() }
        }
        connection.providerJob = providerJob
        if (connection.deadlineTriggered || closed.get()) {
            providerJob.cancel()
            return@coroutineScope
        }
        val players = try {
            providerJob.await()
        } catch (error: Throwable) {
            connection.providerFailure = true
            if (!connection.deadlineTriggered && !closed.get()) {
                logger.error(error) { "Minecraft status player provider failed" }
            }
            throw error
        }
        connection.providerJob = null
        val payload = McServerStatusPayload(
            version = McServerVersion(name = "RDI Multiplayer", protocol = McVersion.V211.protocolVer),
            players = players,
            description = JsonPrimitive(MOTD),
            favicon = cachedFavicon ?: throw IllegalStateException("Minecraft status favicon is not loaded")
        )
        val json = serdesJson.encodeToString(McServerStatusPayload.serializer(), payload)
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        require(json.length <= MAX_STATUS_JSON_CHARS && jsonBytes.size <= MAX_STATUS_JSON_BYTES) {
            "Minecraft status response exceeds the protocol size limit"
        }
        writeFrame(output, packetId = 0, body = jsonBytes, stringBody = true)

        val next = try {
            readFrame(input)
        } catch (_: EOFException) {
            return@coroutineScope
        }
        require(next.size == 9 && next[0].toInt() == 1) { "invalid ping request" }
        val timestamp = ByteBuffer.wrap(next, 1, Long.SIZE_BYTES).long
        val pongBody = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { data ->
                data.writeByte(1)
                data.writeLong(timestamp)
            }
        }.toByteArray()
        writeRawFrame(output, pongBody)
    }

    private fun parseHandshake(frame: ByteArray) {
        val input = DataInputStream(ByteArrayInputStream(frame))
        require(readVarInt(input) == 0) { "invalid handshake packet id" }
        readVarInt(input) // Protocol versions are intentionally accepted, including -1.
        val hostByteLength = readVarInt(input)
        require(hostByteLength in 0..MAX_HOSTNAME_BYTES) { "invalid handshake hostname length" }
        val hostBytes = ByteArray(hostByteLength)
        input.readFully(hostBytes)
        val hostname = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(hostBytes))
            .toString()
        require(hostname.length <= MAX_HOSTNAME_CHARS) { "handshake hostname is too long" }
        input.readUnsignedShort()
        require(readVarInt(input) == 1) { "unsupported handshake state" }
        require(input.available() == 0) { "trailing handshake data" }
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val length = readVarInt(input)
        require(length in 1..MAX_FRAME_BYTES) { "invalid packet length: $length" }
        return ByteArray(length).also(input::readFully)
    }

    private fun writeFrame(output: DataOutputStream, packetId: Int, body: ByteArray, stringBody: Boolean) {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { data ->
            data.writeVarInt(packetId)
            if (stringBody) {
                data.writeVarInt(body.size)
            }
            data.write(body)
        }
        writeRawFrame(output, payload.toByteArray())
    }

    private fun writeRawFrame(output: DataOutputStream, payload: ByteArray) {
        output.writeVarInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun readVarInt(input: InputStream): Int {
        var result = 0
        for (index in 0 until MAX_VARINT_BYTES) {
            val value = input.read()
            if (value < 0) throw EOFException("unexpected end of VarInt")
            val payload = value and 0x7F
            if (index == MAX_VARINT_BYTES - 1 && payload > 0x0F) {
                throw IllegalArgumentException("VarInt overflow")
            }
            result = result or (payload shl (index * 7))
            if (value and 0x80 == 0) return result
        }
        throw IllegalArgumentException("VarInt is longer than five bytes")
    }

    private fun DataOutputStream.writeVarInt(value: Int) {
        var current = value
        while (current and 0xFFFFFF80.toInt() != 0) {
            writeByte((current and 0x7F) or 0x80)
            current = current ushr 7
        }
        writeByte(current)
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { serverSocket?.close() }
            activeConnections.values.forEach { connection ->
                connection.providerJob?.cancel()
                connection.coroutineJob?.cancel()
                runCatching { connection.socket.close() }
            }
            acceptFuture?.cancel(true)
            deadlineExecutor?.shutdownNow()
            connectionExecutor?.shutdownNow()
            activeConnections.clear()
            serverSocket = null
            boundPort = null
        }
    }

    private class Connection(val socket: Socket) {
        @Volatile var task: Future<*>? = null
        @Volatile var deadline: Future<*>? = null
        @Volatile var coroutineJob: Job? = null
        @Volatile var providerJob: Job? = null
        @Volatile var deadlineTriggered = false
        @Volatile var providerFailure = false
    }

    companion object {
        const val MOTD = "rdi multiplayer system"
        const val MAX_VARINT_BYTES = 5
        const val MAX_CONNECTIONS = 64
        const val MAX_FRAME_BYTES = 1024
        const val MAX_HOSTNAME_CHARS = 255
        const val MAX_HOSTNAME_BYTES = 1020
        const val MAX_STATUS_JSON_CHARS = 32767
        const val MAX_STATUS_JSON_BYTES = MAX_STATUS_JSON_CHARS * 3
        const val DEFAULT_CONNECTION_LIFETIME_MILLIS = 5000L
        const val DEFAULT_PROVIDER_TIMEOUT_MILLIS = 4500L
        const val DEFAULT_READ_TIMEOUT_MILLIS = 5000
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
