package calebxzau.rdi.mc.regioncodec

import com.github.luben.zstd.Zstd
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegionZstdStreamsTest {
    @Test
    fun `ID8 uses fixed level 7 and round trips payload sizes`(): Unit {
        assertEquals(8, RegionZstdStreams.COMPRESSION_ID)
        assertEquals(7, RegionZstdStreams.COMPRESSION_LEVEL)

        val randomPayload = ByteArray(2 * 1024 * 1024).also { Random(42).nextBytes(it) }
        for (source in listOf(
            ByteArray(0),
            byteArrayOf(0x5a),
            ByteArray(64 * 1024 - 1) { it.toByte() },
            ByteArray(64 * 1024) { it.toByte() },
            ByteArray(64 * 1024 + 1) { it.toByte() },
            ByteArray(256 * 1024) { (it % 13).toByte() },
            randomPayload,
        )) {
            val compressed = ByteArrayOutputStream()
            RegionZstdStreams.wrapOutput(compressed).use { it.write(source) }
            val frame = compressed.toByteArray()
            assertTrue(frame.isNotEmpty())
            RegionZstdStreams.wrapInput(ByteArrayInputStream(frame), ::bufferInput).use { decoded ->
                assertContentEquals(source, decoded.readAllBytes())
            }
        }
    }

    @Test
    fun `flush emits a valid continuation and later writes remain in the same frame`(): Unit {
        val prefix = ByteArray(96 * 1024) { (it * 19).toByte() }
        val suffix = ByteArray(4 * 1024) { (it * 7).toByte() }
        val target = ByteArrayOutputStream()
        val output = RegionZstdStreams.wrapOutput(target)
        output.write(prefix)
        output.flush()
        assertTrue(target.size() > 0)
        output.write(suffix)
        output.close()

        RegionZstdStreams.wrapInput(ByteArrayInputStream(target.toByteArray()), ::bufferInput).use { decoded ->
            assertContentEquals(prefix + suffix, decoded.readAllBytes())
        }
    }

    @Test
    fun `streaming output is emitted before close and pooled buffers stay fixed size`(): Unit {
        val source = ByteArray(512 * 1024).also { Random(123).nextBytes(it) }
        val target = ByteArrayOutputStream()
        val output = RegionZstdStreams.wrapOutput(target)
        output.write(source)
        assertTrue(target.size() > 0)
        output.close()

        val allStates = RegionZstdStreams::class.java.getDeclaredField("ALL_STATES").apply {
            isAccessible = true
        }.get(null) as List<*>
        for (state in allStates) {
            val input = state!!::class.java.getDeclaredField("input").apply { isAccessible = true }
                .get(state) as ByteBuffer
            val compressed = state::class.java.getDeclaredField("output").apply { isAccessible = true }
                .get(state) as ByteBuffer
            val transfer = state::class.java.getDeclaredField("transfer").apply { isAccessible = true }
                .get(state) as ByteArray
            assertEquals(64 * 1024, input.capacity())
            assertEquals(64 * 1024, compressed.capacity())
            assertEquals(64 * 1024, transfer.size)
        }
    }

    @Test
    fun `legacy unchecksummed ID8 frame remains readable`(): Unit {
        val source = ByteArray(8192) { (it * 31).toByte() }
        val frame = Zstd.compress(source)

        RegionZstdStreams.wrapInput(ByteArrayInputStream(frame), ::bufferInput).use { decoded ->
            assertContentEquals(source, decoded.readAllBytes())
        }
    }

    @Test
    fun `checksum and truncated frame are rejected while closing`(): Unit {
        val source = ByteArray(8192) { (it * 17).toByte() }
        val compressed = ByteArrayOutputStream()
        RegionZstdStreams.wrapOutput(compressed).use { it.write(source) }
        val frame = compressed.toByteArray()

        val mutated = frame.copyOf()
        mutated[mutated.lastIndex] = (mutated[mutated.lastIndex].toInt() xor 1).toByte()
        assertFailsWith<IOException> {
            RegionZstdStreams.wrapInput(ByteArrayInputStream(mutated), ::bufferInput).use { it.readAllBytes() }
        }

        assertFailsWith<IOException> {
            RegionZstdStreams.wrapInput(ByteArrayInputStream(frame, 0, frame.size - 1), ::bufferInput).use { it.readAllBytes() }
        }

        val partialSource = ByteArray(256 * 1024)
        Random(42).nextBytes(partialSource)
        val partialFrameOutput = ByteArrayOutputStream()
        RegionZstdStreams.wrapOutput(partialFrameOutput).use { it.write(partialSource) }
        val partialCorruption = partialFrameOutput.toByteArray().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val partialInput = RegionZstdStreams.wrapInput(ByteArrayInputStream(partialCorruption), ::bufferInput)
        try {
            assertTrue(partialInput.read() >= 0)
            assertFailsWith<IOException> { partialInput.close() }
        } finally {
            runCatching { partialInput.close() }
        }
    }

    @Test
    fun `buffer wrapper failure closes input and preserves the original failure`(): Unit {
        var inputClosed = false
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() {
                inputClosed = true
                super.close()
            }
        }
        val expected = IllegalStateException("injected buffer wrapper failure")

        val thrown = assertFailsWith<IllegalStateException> {
            RegionZstdStreams.wrapInput(source) { throw expected }
        }

        assertSame(expected, thrown)
        assertTrue(inputClosed)
    }

    @Test
    fun `close emits the complete frame once`(): Unit {
        val target = CountingOutputStream()
        val output = RegionZstdStreams.wrapOutput(target)
        output.write("region chunk".toByteArray())
        output.close()
        val frameSize = target.bytes.size()
        output.close()

        assertTrue(frameSize > 0)
        assertEquals(1, target.closeCount)
        assertEquals(frameSize, target.bytes.size())
        assertFalse(target.bytes.toByteArray().isEmpty())
    }

    @Test
    fun `failed frame write does not close the commit target`(): Unit {
        val target = object : OutputStream() {
            var closeCalled = false
            override fun write(value: Int) {
                throw IOException("injected write failure")
            }
            override fun close() {
                closeCalled = true
            }
        }
        val output = RegionZstdStreams.wrapOutput(target)
        output.write(1)
        kotlin.test.assertFailsWith<IOException> { output.close() }
        assertFalse(target.closeCalled)
    }

    @Test
    fun `streaming target failure aborts the session and releases its context`(): Unit {
        var closeCalled = false
        val target = object : OutputStream() {
            override fun write(value: Int) {
                throw IOException("injected streaming write failure")
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                throw IOException("injected streaming write failure")
            }

            override fun close() {
                closeCalled = true
            }
        }
        val output = RegionZstdStreams.wrapOutput(target)
        assertFailsWith<IOException> {
            output.write(ByteArray(128 * 1024) { it.toByte() })
        }
        output.close()
        assertFalse(closeCalled)

        val recovery = ByteArrayOutputStream()
        RegionZstdStreams.wrapOutput(recovery).use { it.write(byteArrayOf(1, 2, 3)) }
        RegionZstdStreams.wrapInput(ByteArrayInputStream(recovery.toByteArray()), ::bufferInput).use { decoded ->
            assertContentEquals(byteArrayOf(1, 2, 3), decoded.readAllBytes())
        }
    }

    @Test
    fun `closed native context is discarded and the next stream recovers`(): Unit {
        val output = RegionZstdStreams.wrapOutput(ByteArrayOutputStream())
        output.write(1)
        val state = output::class.java.getDeclaredField("state").apply {
            isAccessible = true
        }.get(output) as Any
        val context = state::class.java.getDeclaredField("context").apply { isAccessible = true }.get(state) as Any
        context::class.java.getMethod("close").invoke(context)
        assertFailsWith<IllegalStateException> {
            output.write(ByteArray(64 * 1024) { it.toByte() })
        }
        output.close()

        val poolSize = minOf(4, Runtime.getRuntime().availableProcessors())
        repeat(poolSize) {
            val recovery = ByteArrayOutputStream()
            RegionZstdStreams.wrapOutput(recovery).use { it.write(byteArrayOf(8, 7, 6)) }
            RegionZstdStreams.wrapInput(ByteArrayInputStream(recovery.toByteArray()), ::bufferInput).use { decoded ->
                assertContentEquals(byteArrayOf(8, 7, 6), decoded.readAllBytes())
            }
        }
    }

    @Test
    fun `more concurrent writers than pooled contexts complete round trips`(): Unit {
        val poolSize = minOf(4, Runtime.getRuntime().availableProcessors())
        val executor = Executors.newFixedThreadPool(poolSize + 2)
        try {
            val jobs = (0 until poolSize + 2).map { index ->
                executor.submit {
                    val source = ByteArray(128 * 1024) { (it + index * 11).toByte() }
                    val target = ByteArrayOutputStream()
                    RegionZstdStreams.wrapOutput(target).use { it.write(source) }
                    RegionZstdStreams.wrapInput(ByteArrayInputStream(target.toByteArray()), ::bufferInput).use { decoded ->
                        assertContentEquals(source, decoded.readAllBytes())
                    }
                }
            }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `aborted ID8 output never commits and wrapper preserves non ID8 output`(): Unit {
        val target = CountingOutputStream()
        val id8Output = RegionZstdStreams.wrapOutput(target)
        val original = DataOutputStream(id8Output)
        val wrapped = RegionZstdStreams.wrapDataOutput(id8Output, original)
        wrapped.writeInt(42)
        RegionZstdStreams.abort(wrapped)
        wrapped.close()
        wrapped.close()
        assertEquals(0, target.bytes.size())
        assertEquals(0, target.closeCount)

        val plainTarget = ByteArrayOutputStream()
        val plain = DataOutputStream(plainTarget)
        assertSame(plain, RegionZstdStreams.wrapDataOutput(plainTarget, plain))
    }

    @Test
    fun `blocked target commits do not retain compression contexts`(): Unit {
        val poolSize = minOf(4, Runtime.getRuntime().availableProcessors())
        val commitsEntered = CountDownLatch(poolSize)
        val unblockCommits = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(poolSize + 1)
        try {
            val blockedCommits = (0 until poolSize).map {
                executor.submit {
                    val output = RegionZstdStreams.wrapOutput(object : OutputStream() {
                        override fun write(value: Int) {
                            // Compression output is copied to memory before the target commit.
                        }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            // Compression output is copied to memory before the target commit.
                        }
                        override fun close() {
                            commitsEntered.countDown()
                            check(unblockCommits.await(10, TimeUnit.SECONDS))
                        }
                    })
                    output.write(1)
                    output.close()
                }
            }
            assertTrue(commitsEntered.await(5, TimeUnit.SECONDS))

            val unrelatedWrite = executor.submit {
                RegionZstdStreams.wrapOutput(ByteArrayOutputStream()).use { it.write(2) }
            }
            unrelatedWrite.get(5, TimeUnit.SECONDS)
            unblockCommits.countDown()
            blockedCommits.forEach { it.get(5, TimeUnit.SECONDS) }
            unrelatedWrite.get(5, TimeUnit.SECONDS)
        } finally {
            unblockCommits.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `pool interruption and shutdown wake blocked borrowers`(): Unit {
        val poolSize = minOf(4, Runtime.getRuntime().availableProcessors())
        val heldOutputs = (0 until poolSize).map {
            RegionZstdStreams.wrapOutput(ByteArrayOutputStream()).also { it.write(1) }
        }
        try {
            val started = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val waitingThread = Thread {
                started.countDown()
                try {
                    RegionZstdStreams.wrapOutput(ByteArrayOutputStream()).write(2)
                } catch (throwable: Throwable) {
                    failure.set(throwable)
                }
            }
            waitingThread.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            awaitWaiting(waitingThread)
            waitingThread.interrupt()
            waitingThread.join(5000)
            assertFalse(waitingThread.isAlive)
            assertTrue(failure.get() is IOException)
            assertTrue(waitingThread.isInterrupted)
        } finally {
            heldOutputs.forEach { it.close() }
        }

        val codecName = RegionZstdStreams::class.java.name
        val classUrl = RegionZstdStreams::class.java.protectionDomain.codeSource.location
        IsolatedCodecClassLoader(classUrl, RegionZstdStreams::class.java.classLoader, codecName).use { loader ->
            val codec = loader.loadClass(codecName)
            val wrapOutput = codec.getMethod("wrapOutput", OutputStream::class.java)
            val closeAll = codec.getMethod("closeAll")
            val childPoolSize = codec.getDeclaredField("POOL_SIZE").apply { isAccessible = true }.getInt(null)
            val busyOutputs = (0 until childPoolSize).map {
                wrapOutput.invoke(null, ByteArrayOutputStream()) as OutputStream
            }
            try {
                busyOutputs.forEach { it.write(1) }
                val started = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                val waitingThread = Thread {
                    started.countDown()
                    try {
                        (wrapOutput.invoke(null, ByteArrayOutputStream()) as OutputStream).write(2)
                    } catch (throwable: Throwable) {
                        failure.set((throwable as? InvocationTargetException)?.cause ?: throwable)
                    }
                }
                waitingThread.start()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                awaitWaiting(waitingThread)
                closeAll.invoke(null)
                waitingThread.join(5000)
                assertFalse(waitingThread.isAlive)
                assertTrue(failure.get() is IOException)
            } finally {
                busyOutputs.forEach { it.close() }
                busyOutputs.forEach { it.close() }
            }
        }
    }

    private fun bufferInput(input: InputStream): InputStream = java.io.BufferedInputStream(input)

    private fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.WAITING, thread.state)
    }

    private class IsolatedCodecClassLoader(
        url: URL,
        parent: ClassLoader,
        private val codecName: String,
    ) : URLClassLoader(arrayOf(url), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name)
                if (loaded == null && (name == codecName || name.startsWith("$codecName\$"))) {
                    loaded = findClass(name)
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false)
                }
                if (resolve) {
                    resolveClass(loaded)
                }
                return loaded
            }
        }
    }

    private class CountingOutputStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var closeCount = 0
        override fun write(value: Int) = bytes.write(value)
        override fun write(buffer: ByteArray, offset: Int, length: Int) = bytes.write(buffer, offset, length)
        override fun close() {
            closeCount++
        }
    }
}
