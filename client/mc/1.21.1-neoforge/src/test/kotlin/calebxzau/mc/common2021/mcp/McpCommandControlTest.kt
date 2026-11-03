package calebxzau.mc.common2021.mcp

import calebxzhou.rdi.mc.common2.mcp.McpBadArgsError
import calebxzhou.rdi.mc.common2.mcp.model.CommandQ
import calebxzhou.rdi.mc.common2.mcp.model.CommandResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpCommandControlTest {
    @Test
    fun normalizeDropsOneLeadingSlashAndTrims() {
        assertEquals("time query day", McpCommandControl.normalizeCommand("  /time query day "))
        assertEquals("time query day", McpCommandControl.normalizeCommand("time query day"))
        assertEquals("/worldedit", McpCommandControl.normalizeCommand("//worldedit"))
    }

    @Test
    fun normalizeRejectsEmptyAndTooLongCommands() {
        assertFailsWith<McpBadArgsError> { McpCommandControl.normalizeCommand(" / ") }
        assertEquals(255, McpCommandControl.normalizeCommand("a".repeat(255)).length)
        assertFailsWith<McpBadArgsError> { McpCommandControl.normalizeCommand("a".repeat(256)) }
    }

    @Test
    fun runRejectsBadArgsBeforeTouchingTheGame() {
        assertIs<McpBadArgsError>(McpCommandControl.run(CommandQ("help", waitMs = -1)).exceptionOrNull())
        assertIs<McpBadArgsError>(McpCommandControl.run(CommandQ("help", waitMs = CommandQ.MAX_WAIT_MS + 1)).exceptionOrNull())
        assertIs<McpBadArgsError>(McpCommandControl.run(CommandQ("")).exceptionOrNull())
    }

    @Test
    fun recordingOnlyKeepsChatWhileOpen() {
        McpChatCapture.onChat("before")
        val recording = McpChatCapture.record()
        assertTrue(McpChatCapture.isRecording())
        McpChatCapture.onChat("first")
        McpChatCapture.onChat("second")
        recording.close()
        McpChatCapture.onChat("after")

        assertFalse(McpChatCapture.isRecording())
        assertEquals(listOf("first", "second"), recording.lines())
    }

    @Test
    fun resultTextListsMessagesOrExplainsSilence() {
        assertEquals(
            "ran /time query day\nchat messages received (may include unrelated chat):\nThe time is 1000",
            CommandResult("time query day", listOf("The time is 1000")).toString(),
        )
        assertTrue(CommandResult("help", emptyList()).toString().startsWith("ran /help\nno chat messages received"))
    }
}
