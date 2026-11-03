package calebxzau.rdi.client.proxy

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeLocalProxyTest {
    @Test
    fun `parses every event kind`() {
        assertEquals(
            LocalProxyEvent.Attached(3, "a:1", NodeAttachReason.Failover, 120),
            parseLocalProxyEvent("attached\t3\ta:1\tfailover\t120"),
        )
        assertEquals(LocalProxyEvent.PathLost(3, "silent"), parseLocalProxyEvent("path_lost\t3\tsilent"))
        assertEquals(
            LocalProxyEvent.SwitchFailed(3, "a:1", "refused"),
            parseLocalProxyEvent("switch_failed\t3\ta:1\trefused"),
        )
        assertEquals(LocalProxyEvent.Ended(3, "closed", ""), parseLocalProxyEvent("ended\t3\tclosed\t"))
        assertEquals(LocalProxyEvent.Dropped(9), parseLocalProxyEvent("dropped\t9"))
    }

    @Test
    fun `refuses lines it does not understand`() {
        listOf(
            "",
            "future\t1\tx",
            "attached\t1\ta:1\tsideways\t1",
            "attached\tx\ta:1\tmanual\t1",
            "attached\t1\ta:1\tmanual\tx",
            "attached\t1\ta:1\tmanual",
            "path_lost\t1",
            "dropped\tx",
        ).forEach { assertNull(parseLocalProxyEvent(it), it) }
    }

    private class FakeApi : LocalProxyNativeApi {
        var startCode = 0
        var port: Short = 55667.toShort()
        var error = ""
        val pending = ArrayDeque<String>()
        val nodesSent = CopyOnWriteArrayList<String>()
        val switches = CopyOnWriteArrayList<String>()
        var stops = 0

        private fun text(value: ByteArray): String {
            assertEquals(0, value.last(), "string must be NUL-terminated")
            return String(value, 0, value.size - 1, Charsets.UTF_8)
        }

        override fun rdi_lp_abi_version() = 1

        override fun rdi_lp_start(
            bindHost: ByteArray,
            preferredPort: Short,
            nodes: ByteArray,
            outPort: ShortArray,
        ): Int {
            nodesSent += text(nodes)
            outPort[0] = port
            return startCode
        }

        override fun rdi_lp_set_nodes(nodes: ByteArray): Int {
            nodesSent += text(nodes)
            return 0
        }

        override fun rdi_lp_switch_node(address: ByteArray): Int {
            switches += text(address)
            return 0
        }

        @Synchronized
        override fun rdi_lp_poll_events(buffer: ByteArray, length: Int): Int {
            val line = pending.removeFirstOrNull() ?: return 0
            val bytes = (line + "\n").toByteArray()
            bytes.copyInto(buffer)
            return bytes.size
        }

        override fun rdi_lp_last_error(buffer: ByteArray, length: Int): Int {
            val bytes = error.toByteArray()
            bytes.copyInto(buffer)
            return bytes.size
        }

        override fun rdi_lp_stop(timeoutMs: Int): Int {
            stops++
            return 0
        }
    }

    @Test
    fun `ports above the signed short range are read correctly`() {
        val proxy = NativeLocalProxy(FakeApi(), onEvent = {})
        assertEquals(55667, proxy.start("127.0.0.1", 55667, listOf("a:1")).getOrThrow())
        proxy.stop().getOrThrow()
    }

    @Test
    fun `nodes are sent one per line`() {
        val api = FakeApi()
        val proxy = NativeLocalProxy(api, onEvent = {})
        proxy.start("127.0.0.1", 0, listOf("a:1", "b:2")).getOrThrow()
        proxy.setNodes(listOf("c:3", "d:4")).getOrThrow()
        proxy.switchNode("d:4").getOrThrow()
        proxy.stop().getOrThrow()

        assertEquals(listOf("a:1\nb:2", "c:3\nd:4"), api.nodesSent.toList())
        assertEquals(listOf("d:4"), api.switches.toList())
    }

    @Test
    fun `native errors become failures with the library's message`() {
        val api = FakeApi().apply {
            startCode = -6
            error = "bind: denied"
        }
        val failure = assertFailsWith<IllegalStateException> {
            NativeLocalProxy(api, onEvent = {}).start("127.0.0.1", 0, listOf("a:1")).getOrThrow()
        }
        assertContains(failure.message.orEmpty(), "bind: denied")
        assertContains(failure.message.orEmpty(), "-6")
    }

    @Test
    fun `events still queued are delivered when stopping`() {
        val api = FakeApi()
        val events = CopyOnWriteArrayList<LocalProxyEvent>()
        val proxy = NativeLocalProxy(api, events::add, pollIntervalMs = 60_000)
        proxy.start("127.0.0.1", 0, listOf("a:1")).getOrThrow()
        synchronized(api) {
            api.pending += "path_lost\t1\tsilent"
            api.pending += "unknown\t1"
            api.pending += "ended\t1\tclosed\t"
        }

        proxy.stop().getOrThrow()

        assertEquals(
            listOf(LocalProxyEvent.PathLost(1, "silent"), LocalProxyEvent.Ended(1, "closed", "")),
            events.toList(),
        )
        assertEquals(1, api.stops)
    }

    @Test
    fun `a failing event handler does not stop later events`() {
        val api = FakeApi()
        val events = CopyOnWriteArrayList<LocalProxyEvent>()
        val proxy = NativeLocalProxy(api, { event ->
            events += event
            error("handler failed")
        }, pollIntervalMs = 60_000)
        proxy.start("127.0.0.1", 0, listOf("a:1")).getOrThrow()
        synchronized(api) {
            api.pending += "path_lost\t1\tsilent"
            api.pending += "path_lost\t2\tsilent"
        }

        proxy.stop().getOrThrow()

        assertEquals(2, events.size)
    }

    /** The real library from the natives jar, through the same JNA call the launcher makes. */
    @Test
    fun `packaged library loads, accepts a game and stops`() {
        val api = loadLocalProxyNativeApi().getOrThrow()
        val proxy = NativeLocalProxy(api, onEvent = {})

        val port = proxy.start("127.0.0.1", 0, listOf("127.0.0.1:1")).getOrThrow()
        assertTrue(port > 0)
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2_000) }
        proxy.setNodes(listOf("127.0.0.1:2")).getOrThrow()
        proxy.switchNode("127.0.0.1:2").getOrThrow()
        proxy.stop().getOrThrow()

        val restarted = proxy.start("127.0.0.1", port, listOf("127.0.0.1:1")).getOrThrow()
        assertEquals(port, restarted)
        proxy.stop().getOrThrow()
    }
}
