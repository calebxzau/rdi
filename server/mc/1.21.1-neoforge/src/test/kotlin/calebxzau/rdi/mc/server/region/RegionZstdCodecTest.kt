package calebxzau.rdi.mc.server.region

import calebxzau.rdi.mc.server.mixin.mRegionFileStorage
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.util.Random
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegionZstdCodecTest {
    @Test
    fun `NBT read validates checksum and keeps the vanilla stream contract`(): Unit {
        val tag = CompoundTag().apply { putString("rdi", "zstd") }
        val encoded = ByteArrayOutputStream()
        DataOutputStream(RegionZstdCodec.wrapOutput(encoded)).use { output ->
            NbtIo.write(tag, output)
        }
        val frame = encoded.toByteArray()

        DataInputStream(RegionZstdCodec.wrapInput(ByteArrayInputStream(frame))).use { input ->
            assertEquals(tag, NbtIo.read(input, NbtAccounter.unlimitedHeap()))
        }

        val damaged = frame.copyOf()
        damaged[damaged.lastIndex] = (damaged[damaged.lastIndex].toInt() xor 1).toByte()
        assertFailsWith<Exception> {
            DataInputStream(RegionZstdCodec.wrapInput(ByteArrayInputStream(damaged))).use { input ->
                NbtIo.read(input, NbtAccounter.unlimitedHeap())
            }
        }
    }

    @Test
    fun `serialization failure after streamed bytes aborts commit and preserves original failure`(): Unit {
        val mixin = object : mRegionFileStorage() {}
        val handler = mRegionFileStorage::class.java.getDeclaredMethod(
            "rdi$" + "writeNbtWithAbort",
            CompoundTag::class.java,
            DataOutput::class.java,
            Operation::class.java
        ).apply { isAccessible = true }

        val failures = listOf(
            IOException("NBT serialization failed"),
            IllegalStateException("unchecked NBT serialization failure"),
            AssertionError("fatal NBT serialization failure")
        )
        for (expected in failures) {
            val target = CountingOutputStream()
            val id8Output = RegionZstdCodec.wrapOutput(target)
            val originalOutput = DataOutputStream(id8Output)
            val abortableOutput = RegionZstdCodec.wrapDataOutput(id8Output, originalOutput)
            val thrown = assertFailsWith<InvocationTargetException> {
                handler.invoke(
                    mixin,
                    CompoundTag(),
                    abortableOutput,
                    Operation<Void> { arguments ->
                        val partialChunk = ByteArray(256 * 1024).also { Random(55).nextBytes(it) }
                        (arguments[1] as DataOutput).write(partialChunk)
                        throw expected
                    }
                )
            }
            assertSame(expected, thrown.cause)
            assertTrue(target.bytes.size() > 0)
            abortableOutput.close()
            assertEquals(0, target.closeCount)
        }
    }

    private class CountingOutputStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var closeCount = 0
        override fun write(value: Int) = bytes.write(value)
        override fun write(buffer: ByteArray, offset: Int, length: Int) = bytes.write(buffer, offset, length)
        override fun close() { closeCount++ }
    }
}
