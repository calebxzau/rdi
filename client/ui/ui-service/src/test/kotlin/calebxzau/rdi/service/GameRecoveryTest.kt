package calebxzau.rdi.service

import kotlin.test.*
import java.io.File
import kotlinx.serialization.json.*

class GameRecoveryTest {
    @Test
    fun readsProcessIdentityFromSharedGoldenFixture() {
        val fixtures = Json.parseToJsonElement(File("../slint-proto/protocol/fixtures/messages.json").readText()).jsonArray
        val request = fixtures.first { it.jsonObject["method"]?.jsonPrimitive?.content == "game.restore" }.jsonObject
        val sessions = Json.decodeFromJsonElement<List<GameProcessSnapshot>>(request.getValue("params").jsonObject.getValue("sessions"))
        assertEquals("V211", sessions.single().mcVersion)
        assertEquals("neoforge", sessions.single().modLoader)
        assertEquals(12345L, sessions.single().pid)
    }

    @Test
    fun startupTimePreventsPidReuse() {
        val handle = ProcessHandle.current()
        val started = handle.info().startInstant().orElseThrow().toEpochMilli()
        assertTrue(GameRecovery.matches(handle, started))
        assertFalse(GameRecovery.matches(handle, started - 1))
    }
}
