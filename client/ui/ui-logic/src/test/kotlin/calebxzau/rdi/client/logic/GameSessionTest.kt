package calebxzau.rdi.client.logic

import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.client.ui.McPlayStore
import calebxzhou.rdi.client.ui.McGameSession
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class GameSessionTest {
    private fun args(cleanup: () -> Unit = {}) = McPlayArgs(
        title = "测试房间", mcVer = McVersion.V211, modLoader = ModLoader.neoforge,
        versionId = "u2-session-test", playArg = "", versionName = "test", cleanup = cleanup,
    )

    @Test
    fun duplicateVersionNeedsExplicitConfirmation(): Unit = runBlocking {
        val first = assertNotNull(McPlayStore.admitSession(args(), false))
        try {
            assertNull(McPlayStore.admitSession(args(), false))
            val confirmed = assertNotNull(McPlayStore.admitSession(args(), true))
            confirmed.requestStop()
            McPlayStore.closeStoppedSession(confirmed.id)
        } finally {
            first.requestStop()
            McPlayStore.closeStoppedSession(first.id)
        }
    }

    @Test
    fun stopDuringPreparationRejectsLateProcessAndCleansOnce() {
        var cleanups = 0
        val session = McGameSession(args = args { cleanups++ })
        session.requestStop()
        val process = FakeProcess()
        assertFalse(session.attachProcess(process))
        assertTrue(process.destroyed)
        assertFalse(session.preparing)
        assertNull(session.process)
        session.markExited()
        assertEquals(1, cleanups)
    }

    @Test
    fun stopRunningProcessIsObservable() {
        val session = McGameSession(args = args())
        val process = FakeProcess()
        assertTrue(session.attachProcess(process))
        assertTrue(session.isAlive())
        session.requestStop(force = true)
        assertTrue(process.destroyed)
        assertTrue(session.stopRequested)
        assertFalse(session.isAlive())
    }

    private class FakeProcess : Process() {
        var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive() = !destroyed
    }
}
