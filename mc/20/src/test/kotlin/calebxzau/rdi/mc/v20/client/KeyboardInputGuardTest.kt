package calebxzau.rdi.mc.v20.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeyboardInputGuardTest {
    @Test
    fun failedInputIsNotRetriedAndNextInputRuns() {
        val logged = mutableListOf<Exception>()
        val guard = KeyboardInputGuard({ 0 }) { _, error -> logged.add(error) }
        val failure = RuntimeException("screen wrapper", ArrayIndexOutOfBoundsException("Amecs"))
        var calls = 0
        assertFalse(guard.run("keyPress", "GuiMap", "action=1") { calls++; throw failure })
        assertTrue(guard.run("keyPress", "GuiMap", "action=0") { calls++ })
        assertEquals(2, calls)
        assertSame(failure, logged.single())
    }

    @Test
    fun repeatsAreCountedAndDifferentEventsAreLogged() {
        var now = 0L
        val messages = mutableListOf<String>()
        val guard = KeyboardInputGuard({ now }) { message, _ -> messages.add(message) }
        val failure = IllegalStateException("broken handler")
        repeat(3) { assertFalse(guard.run("keyPress", "GuiMap", "action=2") { throw failure }) }
        guard.run("charTyped", "GuiMap", "codePoint=65") { throw failure }
        assertEquals(2, messages.size)
        now = 10_000_000_000L
        guard.run("keyPress", "GuiMap", "action=2") { throw failure }
        assertEquals(3, messages.size)
        assertTrue(messages.last().contains("省略重复异常2次"))
        assertTrue(messages.last().contains("screen=GuiMap"))
    }

    @Test
    fun directAndWrappedErrorsPropagateWithoutLogging() {
        val guard = KeyboardInputGuard({ 0 }) { _, _ -> error("Must not log a fatal error") }
        val fatal = OutOfMemoryError("test")
        val wrapped = RuntimeException(RuntimeException(fatal))
        val suppressed = RuntimeException("wrapper").apply { addSuppressed(fatal) }
        for (failure in listOf(fatal, wrapped, suppressed)) {
            assertSame(fatal, assertFailsWith<OutOfMemoryError> {
                guard.run("keyPress", "GuiMap", "") { throw failure }
            })
        }
    }

    @Test
    fun cyclicCausesDoNotHangRecovery() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)
        var logged = 0
        val guard = KeyboardInputGuard({ 0 }) { _, _ -> logged++ }
        guard.run("charTyped", "ChatScreen", "") { throw first }
        assertEquals(1, logged)
    }
}
