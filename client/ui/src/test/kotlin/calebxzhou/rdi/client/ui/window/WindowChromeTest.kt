package calebxzhou.rdi.client.ui.window

import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test

class WindowChromeTest {
    @Test
    fun activeMcSessionsIncludesPreparingAndRunningSessions() {
       /* var preparing = McGameSession(id = "preparing", args = playArgs())
        val running = McGameSession(id = "running", args = playArgs()).apply {
            preparing = false
            process = AliveProcess
        }
        val stopped = McGameSession(id = "stopped", args = playArgs()).apply {
            preparing = false
        }

        assertEquals(listOf(preparing, running), activeMcSessions(listOf(preparing, stopped, running)))*/
    }

    private fun playArgs() = McPlayArgs(
        title = "测试MC",
        mcVer = McVersion.V211,
        modLoader = ModLoader.neoforge,
        versionId = "test-version",
        versionName = "1.0",
        playArg = "",
    )

    private object AliveProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = throw IllegalThreadStateException()
        override fun destroy() = Unit
        override fun isAlive() = true
    }
}
