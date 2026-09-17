package calebxzau.rdi.mc.client.dm

import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DmHostTunnel(
    private val endpoint: DmEndpoint,
    private val room: String,
    private val targetPort: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(session: UUID, gamePort: Int)
        fun onRetry(attempt: Int, reason: String)
    }

    private val logger = LoggerFactory.getLogger(DmHostTunnel::class.java)
    private val closed = AtomicBoolean(false)
    private val supervisorExecutor = newVirtualExecutor("rdi-dm-supervisor")
    private val supervisorFuture = AtomicReferenceFuture()
    private val currentAttempt = AtomicReference<Attempt?>()
    private val lifecycleLock = Any()

    fun start() {
        supervisorFuture.value = supervisorExecutor.submit { supervise() }
    }

    fun close() {
        val attempt = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            currentAttempt.getAndSet(null)
        }
        attempt?.close()
        supervisorFuture.value?.cancel(true)
        supervisorExecutor.shutdownNow()
    }

    private fun supervise() {
        var retry = 0
        while (!closed.get()) {
            val attempt = Attempt()
            var installed = false
            synchronized(lifecycleLock) {
                if (!closed.get()) {
                    currentAttempt.set(attempt)
                    installed = true
                }
            }
            if (!installed) {
                attempt.close()
                break
            }
            try {
                runAttempt(attempt)
                retry = 0
            } catch (error: Throwable) {
                if (!closed.get()) {
                    retry++
                    logger.warn("DM host connection failed for room {} (attempt {})", room, retry, error)
                    listener.onRetry(retry, error.message ?: error.javaClass.simpleName)
                } else {
                    logger.debug("DM host connection closed for room {}", room, error)
                }
            } finally {
                currentAttempt.compareAndSet(attempt, null)
                attempt.close()
            }
            if (closed.get()) break
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun runAttempt(attempt: Attempt) {
        attempt.startWatchdog()
        val control = attempt.newSocket()
        attempt.controlSocket = control
        attempt.withSetupDeadline(control) {
            control.connect(InetSocketAddress(endpoint.host, endpoint.port), 5000)
        }
        if (!attempt.isOpen()) throw IOException("DM attempt closed while connecting")
        control.tcpNoDelay = true
        control.soTimeout = 5000
        val output = control.getOutputStream()
        val input = DataInputStream(control.getInputStream())
        attempt.withSetupDeadline(control) { DmProtocol.writeRegister(output, room) }
        val reply = attempt.withSetupDeadline(control) { DmProtocol.readRegistrationReply(input) }
        when (reply) {
            is DmProtocol.RegistrationReply.Ready -> {
                attempt.session = reply.session
                control.soTimeout = 45000
                attempt.lastControlActivity.set(System.nanoTime())
                listener.onReady(reply.session, reply.gamePort)
            }
            DmProtocol.RegistrationReply.Busy -> error("网关房间正在使用")
            DmProtocol.RegistrationReply.NoPort -> error("网关没有可用游戏端口")
        }
        attempt.startWriter(control, output)
        while (!closed.get() && attempt.isOpen()) {
            when (val message = DmProtocol.readControlMessage(input)) {
                is DmProtocol.ControlMessage.Open -> openVisitor(attempt, message.connection)
                DmProtocol.ControlMessage.Ping -> attempt.enqueue(Control.Pong)
            }
            attempt.lastControlActivity.set(System.nanoTime())
        }
        if (!closed.get()) throw IOException("DM控制连接已关闭")
    }

    private fun openVisitor(attempt: Attempt, connection: Long) {
        if (!attempt.slots.tryAcquire()) {
            attempt.enqueueFailed(connection)
            return
        }
        try {
            attempt.executor.submit {
                try {
                    bridgeVisitor(attempt, connection)
                } finally {
                    attempt.slots.release()
                }
            }
        } catch (error: RuntimeException) {
            attempt.slots.release()
            attempt.enqueueFailed(connection)
            logger.debug("DM visitor {} could not be scheduled for room {}", connection, room, error)
        }
    }

    private fun bridgeVisitor(attempt: Attempt, connection: Long) {
        var local: Socket? = null
        var data: Socket? = null
        var attached = false
        val started = System.nanoTime()
        try {
            local = attempt.newSocket()
            attempt.withSetupDeadline(local) {
                local.connect(InetSocketAddress("127.0.0.1", targetPort), 5000)
            }
            if (!attempt.isOpen()) throw IOException("DM attempt closed while connecting to IGS")
            local.tcpNoDelay = true
            data = attempt.newSocket()
            attempt.withSetupDeadline(data) {
                data.connect(InetSocketAddress(endpoint.host, endpoint.port), 5000)
            }
            if (!attempt.isOpen()) throw IOException("DM attempt closed while connecting data tunnel")
            data.tcpNoDelay = true
            val session = attempt.session ?: error("DM session is not ready")
            attempt.withSetupDeadline(data) { DmProtocol.writeAttach(data.getOutputStream(), session, connection) }
            attached = true
            local.soTimeout = 0
            data.soTimeout = 0
            val bytes = relay(attempt, local, data)
            logger.debug(
                "DM visitor {} ended for room {}: to gateway={} bytes, to IGS={} bytes, elapsed={}ms",
                connection, room, bytes.first, bytes.second,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            )
        } catch (error: Throwable) {
            if (!attempt.isOpen()) {
                logger.debug("DM visitor {} cancelled for room {}", connection, room)
            } else {
                logger.debug("DM visitor {} failed for room {}", connection, room, error)
                if (!attached) attempt.enqueueFailed(connection)
            }
        } finally {
            closePair(local, data)
            attempt.remove(local)
            attempt.remove(data)
        }
    }

    private fun relay(attempt: Attempt, left: Socket, right: Socket): Pair<Long, Long> {
        val pairClosed = AtomicBoolean(false)
        fun closeBoth() {
            if (pairClosed.compareAndSet(false, true)) {
                closeQuietly(left)
                closeQuietly(right)
            }
        }
        val leftToRight = attempt.executor.submit<Long> { pump(left, right, ::closeBoth) }
        var rightToLeft: Future<Long>? = null
        try {
            val reverse = attempt.executor.submit<Long> { pump(right, left, ::closeBoth) }
            rightToLeft = reverse
            return leftToRight.get() to reverse.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            closeBoth()
            throw IOException("DM relay interrupted", error)
        } catch (error: java.util.concurrent.ExecutionException) {
            closeBoth()
            val cause = error.cause
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IOException("DM relay failed", cause)
            }
        } finally {
            closeBoth()
            leftToRight.cancel(true)
            rightToLeft?.cancel(true)
        }
    }

    private fun pump(from: Socket, to: Socket, closeBoth: () -> Unit): Long {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    output.write(buffer, 0, count)
                    total += count
                }
            }
            to.shutdownOutput()
            return total
        } catch (error: Throwable) {
            closeBoth()
            throw error
        }
    }

    private inner class Attempt {
        private val lock = Any()
        private val open = AtomicBoolean(true)
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val writeDeadlines = ConcurrentHashMap<Socket, AtomicLong>()
        val executor: ExecutorService = newVirtualExecutor("rdi-dm-attempt")
        val slots = Semaphore(128)
        val queue = ArrayBlockingQueue<Control>(128)
        val lastControlActivity = AtomicLong(System.nanoTime())
        @Volatile var session: UUID? = null
        @Volatile var controlSocket: Socket? = null
        private var writer: Future<*>? = null
        private var watchdog: Future<*>? = null

        fun isOpen(): Boolean = open.get() && !closed.get()

        fun newSocket(): Socket {
            val socket = Socket()
            synchronized(lock) {
                if (!isOpen()) {
                    closeQuietly(socket)
                    throw IOException("DM attempt is closed")
                }
                sockets.add(socket)
                writeDeadlines[socket] = AtomicLong(0L)
            }
            return socket
        }

        fun remove(socket: Socket?) {
            if (socket != null) {
                sockets.remove(socket)
                writeDeadlines.remove(socket)
            }
        }

        fun startWriter(control: Socket, output: java.io.OutputStream) {
            writer = executor.submit {
                try {
                    while (open.get()) {
                        when (val message = queue.take()) {
                            Control.Pong -> withSetupDeadline(control) { DmProtocol.writePong(output) }
                            is Control.Failed -> withSetupDeadline(control) { DmProtocol.writeFailed(output, message.connection) }
                            Control.Stop -> return@submit
                        }
                    }
                } catch (error: Throwable) {
                    if (isOpen()) {
                        logger.warn("DM control writer failed for room {}", room, error)
                        close()
                    }
                }
            }
        }

        fun startWatchdog() {
            watchdog = executor.submit {
                try {
                    while (open.get()) {
                        Thread.sleep(1000)
                        val now = System.nanoTime()
                        val expired = writeDeadlines.entries.mapNotNull { (socket, deadline) ->
                            val value = deadline.get()
                            if (value != 0L && now >= value && deadline.compareAndSet(value, 0L)) socket else null
                        }
                        val controlExpired = expired.any { it === controlSocket }
                        expired.filter { it !== controlSocket }.forEach {
                            remove(it)
                            closeQuietly(it)
                        }
                        if (controlExpired || now - lastControlActivity.get() > TimeUnit.SECONDS.toNanos(45)) {
                            logger.warn("DM control deadline expired for room {}", room)
                            close()
                            return@submit
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        fun enqueue(message: Control) {
            if (!isOpen()) return
            if (!queue.offer(message)) {
                logger.warn("DM control queue full for room {}", room)
                close()
            }
        }

        fun enqueueFailed(connection: Long) = enqueue(Control.Failed(connection))

        fun <T> withSetupDeadline(socket: Socket, action: () -> T): T {
            val deadline = writeDeadlines[socket] ?: throw IOException("DM socket is not tracked")
            deadline.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
            return try {
                action()
            } finally {
                deadline.set(0L)
            }
        }

        fun close() {
            if (!open.compareAndSet(true, false)) return
            synchronized(lock) {
                sockets.toList().forEach(::closeQuietly)
                sockets.clear()
                writeDeadlines.clear()
            }
            queue.offer(Control.Stop)
            executor.shutdownNow()
        }
    }

    private sealed interface Control {
        data object Pong : Control
        data class Failed(val connection: Long) : Control
        data object Stop : Control
    }

    private class AtomicReferenceFuture {
        @Volatile var value: Future<*>? = null
    }

    companion object {
        private fun newVirtualExecutor(name: String): ExecutorService =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(name + "-", 0).factory())

        private fun closePair(left: Socket?, right: Socket?) {
            closeQuietly(left)
            closeQuietly(right)
        }

        private fun closeQuietly(socket: Socket?) {
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }
}
