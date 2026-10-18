package calebxzau.rdi.mc.client.dm

import kotlin.test.Test
import kotlin.test.assertEquals

class DmWorldSyncTimingTest {
    @Test
    fun schedulingUsesConfiguredNonDefaultInterval(): Unit {
        val interval = DmWorldSyncTiming.intervalNanos(7)
        val readyAt = 1_000L
        val completedAt = 20_000L

        assertEquals(readyAt + interval, DmWorldSyncTiming.firstDue(readyAt, interval))
        assertEquals(
            completedAt + interval,
            DmWorldSyncTiming.nextDue(readyAt + interval, completedAt, interval),
        )
        assertEquals(
            readyAt + interval,
            DmWorldSyncTiming.nextDue(readyAt + interval, 500L, interval),
        )
    }

    @Test
    fun adaptiveIntervalRespectsPauseBudgetAndBounds() {
        val base = DmWorldSyncTiming.intervalNanos(60)
        assertEquals(DmWorldSyncTiming.intervalNanos(120), DmWorldSyncTiming.adaptInterval(base, 51, 0))
        assertEquals(DmWorldSyncTiming.intervalNanos(60), DmWorldSyncTiming.adaptInterval(base, 10, 2))
        assertEquals(DmWorldSyncTiming.intervalNanos(300), DmWorldSyncTiming.adaptInterval(DmWorldSyncTiming.intervalNanos(200), 51, 0))
    }
}
