package calebxzau.rdi.mc.client.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewReadbackCopyTest {
    @Test
    fun copiesRowsInReverseOrder(): Unit {
        val calls = ArrayList<Long>()
        val next = PreviewReadbackCopy.copyRows(
            sourceAddress = 1000,
            destinationAddress = 2000,
            width = 2,
            height = 3,
            firstDestinationRow = 0,
            deadlineNanos = Long.MAX_VALUE,
            nowNanos = { 0L },
            copy = { source, destination, bytes ->
                calls += source
                assertEquals(bytes, 8L)
                assertEquals(2000L + calls.lastIndex * 8L, destination)
            }
        )
        assertEquals(3, next)
        assertEquals(listOf(1016L, 1008L, 1000L), calls)
    }

    @Test
    fun respectsFourMiBCapAndContinues(): Unit {
        var copies = 0
        val first = PreviewReadbackCopy.copyRows(
            sourceAddress = 0,
            destinationAddress = 0,
            width = 1024,
            height = 2048,
            firstDestinationRow = 0,
            deadlineNanos = Long.MAX_VALUE,
            nowNanos = { 0L },
            copy = { _, _, _ -> copies++ }
        )
        assertEquals(1024, first)
        assertEquals(1024, copies)
        val second = PreviewReadbackCopy.copyRows(
            sourceAddress = 0,
            destinationAddress = 0,
            width = 1024,
            height = 2048,
            firstDestinationRow = first,
            deadlineNanos = Long.MAX_VALUE,
            nowNanos = { 0L },
            copy = { _, _, _ -> copies++ }
        )
        assertEquals(2048, second)
        assertEquals(2048, copies)
    }

    @Test
    fun expiredDeadlineDoesNotCopy(): Unit {
        var copies = 0
        val next = PreviewReadbackCopy.copyRows(
            sourceAddress = 0,
            destinationAddress = 0,
            width = 64,
            height = 4,
            firstDestinationRow = 0,
            deadlineNanos = 5,
            nowNanos = { 5L },
            copy = { _, _, _ -> copies++ }
        )
        assertEquals(0, next)
        assertEquals(0, copies)
    }

    @Test
    fun transformsActualBottomUpCropBytesAcrossDeadline(): Unit {
        val width = 2
        val height = 5
        val rowBytes = width * 4
        val sourceBase = 1_000L
        val destinationBase = 2_000L
        val source = ByteArray(height * rowBytes) { index ->
            val row = index / rowBytes
            val component = index % rowBytes
            (row * 16 + component).toByte()
        }
        val destination = ByteArray(3 * rowBytes) { 0x7f.toByte() }
        var clockReads = 0
        val copy = { sourceAddress: Long, destinationAddress: Long, bytes: Long ->
            val sourceOffset = (sourceAddress - sourceBase).toInt()
            val destinationOffset = (destinationAddress - destinationBase).toInt()
            source.copyInto(
                destination,
                destinationOffset,
                sourceOffset,
                sourceOffset + bytes.toInt()
            )
            Unit
        }

        val firstProgress = PreviewReadbackCopy.copyRows(
            sourceAddress = sourceBase + 2 * rowBytes,
            destinationAddress = destinationBase,
            width = width,
            height = 3,
            firstDestinationRow = 0,
            deadlineNanos = 5,
            nowNanos = { if (clockReads++ == 0) 0 else 5 },
            copy = copy
        )
        assertEquals(1, firstProgress)
        assertEquals(source.copyOfRange(4 * rowBytes, 5 * rowBytes).toList(), destination.copyOfRange(0, rowBytes).toList())
        assertTrue(destination.copyOfRange(rowBytes, destination.size).all { it == 0x7f.toByte() })

        val secondProgress = PreviewReadbackCopy.copyRows(
            sourceAddress = sourceBase + 2 * rowBytes,
            destinationAddress = destinationBase,
            width = width,
            height = 3,
            firstDestinationRow = firstProgress,
            deadlineNanos = Long.MAX_VALUE,
            nowNanos = { 0 },
            copy = copy
        )
        assertEquals(3, secondProgress)
        val expected = source.copyOfRange(2 * rowBytes, 5 * rowBytes).toList()
            .windowed(rowBytes, rowBytes)
            .reversed()
            .flatten()
        assertEquals(expected, destination.toList())
    }
}
