package calebxzau.rdi.service

import calebxzau.rdi.client.account.MsaSessionStore
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MsaSessionParityTest {
    @Test
    fun sharedRustLogoutFixtureIsReadableByCompose(): Unit = runBlocking {
        val file = File("../slint-proto/protocol/fixtures/msa_sessions.json")
        val store = MsaSessionStore(file)
        assertEquals("{\"fake\":\"session-a\"}", store.find("fake-account-a", "fake-profile-a").getOrThrow())
        assertEquals("{\"fake\":\"session-b\"}", store.find("fake-account-b", "fake-profile-b").getOrThrow())
    }
}
