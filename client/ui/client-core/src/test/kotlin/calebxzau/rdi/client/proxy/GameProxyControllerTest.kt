package calebxzau.rdi.client.proxy

import calebxzhou.rdi.client.net.RServer
import calebxzhou.rdi.client.net.ServerRouteState
import calebxzhou.rdi.common.model.GameNodeEntry
import calebxzhou.rdi.common.model.ServerEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GameProxyControllerTest {
    private class FakeBackend(
        override val seamlessSwitch: Boolean,
        private val address: String,
        private val startResult: () -> Result<String> = { Result.success(address) },
    ) : GameProxyBackend {
        val started = mutableListOf<ProxyRoute>()
        val applied = mutableListOf<ProxyRoute>()
        var stopped = 0

        override fun start(route: ProxyRoute): Result<String> {
            started += route
            return startResult()
        }

        override fun applyRoute(route: ProxyRoute): Result<Unit> {
            applied += route
            return Result.success(Unit)
        }

        override fun stop(): Result<Unit> {
            stopped++
            return Result.success(Unit)
        }
    }

    private val route = ProxyRoute(listOf("a:1", "b:2"), "a:1")
    private val kotlin = FakeBackend(seamlessSwitch = false, address = "127.0.0.1:1")
    private val native = FakeBackend(seamlessSwitch = true, address = "127.0.0.1:2")
    private val problems = mutableListOf<Throwable>()

    private fun controller(
        resume: Boolean = true,
        loadNative: () -> Result<GameProxyBackend> = { Result.success(native) },
    ) = GameProxyController(
        route = { route },
        resumeEnabled = { resume },
        fallback = kotlin,
        loadNative = loadNative,
        onNativeUnavailable = { problems += it },
    )

    @Test
    fun `switch off uses the existing proxy without loading the library`() {
        var loads = 0
        val controller = controller(resume = false) {
            loads++
            Result.success(native)
        }

        assertEquals("127.0.0.1:1", controller.start().getOrThrow())
        assertEquals(0, loads)
        assertEquals(1, kotlin.started.size)
    }

    @Test
    fun `switch on uses the native proxy`() {
        assertEquals("127.0.0.1:2", controller().start().getOrThrow())
        assertEquals(listOf(route), native.started)
        assertTrue(kotlin.started.isEmpty())
    }

    @Test
    fun `library that cannot load falls back and is reported once`() {
        val failure = UnsatisfiedLinkError("missing")
        var loads = 0
        val controller = controller {
            loads++
            Result.failure(failure)
        }

        assertEquals("127.0.0.1:1", controller.start().getOrThrow())
        controller.stop().getOrThrow()
        assertEquals("127.0.0.1:1", controller.start().getOrThrow())

        assertEquals(1, loads)
        assertEquals(1, problems.size)
        assertSame(failure, problems.single())
    }

    @Test
    fun `native start failure falls back so the player still gets in`() {
        val failure = IllegalStateException("bind")
        val broken = FakeBackend(seamlessSwitch = true, address = "") { Result.failure(failure) }

        val controller = controller { Result.success(broken) }

        assertEquals("127.0.0.1:1", controller.start().getOrThrow())
        assertSame(failure, problems.single())
        assertFalse(controller.applyRoute().getOrThrow())
    }

    @Test
    fun `second game reuses the running proxy`() {
        val controller = controller()
        controller.start().getOrThrow()
        assertEquals("127.0.0.1:2", controller.start().getOrThrow())
        assertEquals(2, native.started.size)
        assertTrue(kotlin.started.isEmpty())
    }

    @Test
    fun `route change moves running games on the native proxy`() {
        val controller = controller()
        assertFalse(controller.applyRoute().getOrThrow())

        controller.start().getOrThrow()

        assertTrue(controller.applyRoute().getOrThrow())
        assertEquals(listOf(route), native.applied)
    }

    @Test
    fun `route change leaves the existing proxy alone`() {
        val controller = controller(resume = false)
        controller.start().getOrThrow()

        assertFalse(controller.applyRoute().getOrThrow())
        assertTrue(kotlin.applied.isEmpty())
    }

    @Test
    fun `stop stops the active proxy once and allows another start`() {
        val controller = controller()
        controller.stop().getOrThrow()
        assertEquals(0, native.stopped)

        controller.start().getOrThrow()
        controller.stop().getOrThrow()
        controller.stop().getOrThrow()
        assertEquals(1, native.stopped)

        assertFalse(controller.applyRoute().getOrThrow())
        controller.start().getOrThrow()
        assertEquals(2, native.started.size)
    }

    @Test
    fun `route puts the chosen node first and the built-in node last`() {
        assertEquals(
            ProxyRoute(listOf("sel:1", "cand:2", "main:3", "builtin:4"), "sel:1"),
            resolveProxyRoute("sel:1", listOf("sel:1", "cand:2", "main:3"), "builtin:4"),
        )
    }

    @Test
    fun `route without candidates still has the built-in node`() {
        assertEquals(
            listOf("sel:1", "builtin:4"),
            resolveProxyRoute("sel:1", emptyList(), "builtin:4").nodes,
        )
        assertEquals(listOf("builtin:4"), resolveProxyRoute("builtin:4", emptyList(), "builtin:4").nodes)
    }

    @Test
    fun `debug route never adds public nodes`() {
        assertEquals(
            ProxyRoute(listOf("local:1", "local:2"), "local:1"),
            resolveDebugProxyRoute("public:9", listOf("local:1", "local:2"), "localhost:65230"),
        )
        assertEquals(
            ProxyRoute(listOf("local:1", "local:2"), "local:2"),
            resolveDebugProxyRoute("local:2", listOf("local:1", "local:2"), "localhost:65230"),
        )
        assertEquals(
            ProxyRoute(listOf("localhost:65230"), "localhost:65230"),
            resolveDebugProxyRoute("public:9", emptyList(), "localhost:65230"),
        )
    }

    @Test
    fun `event text names nodes and hides addresses`() {
        val name = { _: String -> "上海" }
        val texts = listOf(
            LocalProxyEvent.Attached(1, "203.0.113.1:65230", NodeAttachReason.Initial, 0),
            LocalProxyEvent.Attached(1, "203.0.113.1:65230", NodeAttachReason.Failover, 12),
            LocalProxyEvent.Attached(1, "203.0.113.1:65230", NodeAttachReason.Manual, 12),
            LocalProxyEvent.SwitchFailed(1, "203.0.113.1:65230", "connect 203.0.113.1:65230 refused"),
        ).map { describeLocalProxyEvent(it, name) }

        assertEquals("已自动切换到节点上海，用时12ms", texts[1])
        assertEquals("切换到节点上海失败: connect 上海 refused", texts[3])
        assertTrue(texts.none { "203.0.113.1" in it })
    }

    @Test
    fun `settings show the node a game actually moved to`() {
        val original = RServer.routeState.value
        try {
            RServer.routeState.value = ServerRouteState()
            RServer.updateServerEntry(
                ServerEntry(
                    nodeName = "上海",
                    gameAddr = "203.0.113.1:65230",
                    candidates = listOf(
                        GameNodeEntry("上海", "203.0.113.1:65230"),
                        GameNodeEntry("主节点", "203.0.113.2:65230"),
                    ),
                    sessionResume = true,
                ),
            )
            assertTrue(RServer.routeState.value.sessionResume)

            RServer.markActiveNode("203.0.113.2:65230")
            assertEquals("主节点", RServer.routeState.value.nodeName)
            assertEquals("203.0.113.2:65230", RServer.currentGameAddr)

            RServer.markActiveNode("198.51.100.1:65230")
            assertEquals("默认节点", RServer.routeState.value.nodeName)
        } finally {
            RServer.routeState.value = original
        }
    }
}
