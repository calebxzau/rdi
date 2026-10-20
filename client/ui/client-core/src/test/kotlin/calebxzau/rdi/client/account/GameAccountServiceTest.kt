package calebxzau.rdi.client.account

import kotlinx.coroutines.runBlocking
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameAccountServiceTest {
    private val msid: UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
    private val other: UUID = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6")
    private fun store() = MsaSessionStore(Files.createTempDirectory("rdi-msa").resolve("msa_sessions.json").toFile())

    @Test
    fun `offline accounts use the vanilla name based UUID and legacy user type`() {
        val account = OfflineGameAccount.create(" Notch ").getOrThrow()

        assertEquals("Notch", account.name)
        assertEquals("b50ad385-829d-3141-a216-7e7d7539ba7f", account.uuid)
        assertEquals("legacy", account.userType)
        assertTrue(OfflineGameAccount.create("").isFailure)
        assertTrue(OfflineGameAccount.create("有 空格").isFailure)
        assertTrue(OfflineGameAccount.create("a".repeat(17)).isFailure)
        assertTrue(OfflineGameAccount.isStandardName("Steve_01"))
        assertFalse(OfflineGameAccount.isStandardName("史蒂夫"))
        assertFalse(OfflineGameAccount.isStandardName("ab"))
    }

    @Test
    fun `a saved session is reused and saved again after refresh`(): Unit = runBlocking {
        val store = store()
        store.save("acc", msid.toString(), "old").getOrThrow()
        val provider = FakeProvider(restored = FakeSession(msid, "Notch", "token-1", json = "refreshed"))

        val account = OnlineGameAccountService(store, provider).account("acc", msid) {}.getOrThrow()

        assertEquals("Notch", account.name)
        assertEquals(msid.toString(), account.uuid)
        assertEquals("token-1", account.accessToken)
        assertEquals("msa", account.userType)
        assertEquals(0, provider.logins)
        assertEquals("refreshed", store.find("acc", msid.toString()).getOrThrow())
    }

    @Test
    fun `a stale or foreign session falls back to device login`(): Unit = runBlocking {
        val store = store()
        store.save("acc", msid.toString(), "old").getOrThrow()
        val provider = FakeProvider(
            restored = FakeSession(other, "Someone", "x", "foreign"),
            loggedIn = FakeSession(msid, "Notch", "token-2", "fresh"),
        )

        val account = OnlineGameAccountService(store, provider).account("acc", msid) {}.getOrThrow()

        assertEquals("token-2", account.accessToken)
        assertEquals(1, provider.logins)
        assertEquals("fresh", store.find("acc", msid.toString()).getOrThrow())
    }

    @Test
    fun `logging in with a different Microsoft account than the bound one fails and saves nothing`(): Unit = runBlocking {
        val store = store()
        val provider = FakeProvider(loggedIn = FakeSession(other, "Someone", "x", "foreign"))

        val result = OnlineGameAccountService(store, provider).account("acc", msid) {}

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("不一致"))
        assertNull(store.find("acc", msid.toString()).getOrThrow())
    }

    @Test
    fun `clearing an account keeps other accounts and a damaged file is set aside`(): Unit = runBlocking {
        val file = Files.createTempDirectory("rdi-msa").resolve("msa_sessions.json").toFile()
        val store = MsaSessionStore(file)
        store.save("a", msid.toString(), "a-session").getOrThrow()
        store.save("b", msid.toString(), "b-session").getOrThrow()
        store.clear("a").getOrThrow()

        assertNull(store.find("a", msid.toString()).getOrThrow())
        assertEquals("b-session", store.find("b", msid.toString()).getOrThrow())

        file.writeText("{broken")
        assertTrue(store.find("b", msid.toString()).isFailure)
        store.save("c", msid.toString(), "c-session").getOrThrow()
        assertEquals("c-session", store.find("c", msid.toString()).getOrThrow())
        assertTrue(file.parentFile.listFiles().orEmpty().any { it.name.startsWith("msa_sessions.json.broken-") })
    }
}

private data class FakeSession(
    override val profileId: UUID,
    override val profileName: String,
    override val accessToken: String,
    val json: String,
) : MsaGameSession {
    override fun toJson(): String = json
}

private class FakeProvider(
    private val restored: FakeSession? = null,
    private val loggedIn: FakeSession? = null,
) : MsaGameSessionProvider {
    var logins = 0

    override fun restore(json: String): Result<MsaGameSession> =
        restored?.let { Result.success(it) } ?: Result.failure(IllegalStateException("expired"))

    override fun login(onDeviceCode: (MsaDeviceCode) -> Unit): Result<MsaGameSession> {
        logins++
        return loggedIn?.let { Result.success(it) } ?: Result.failure(IllegalStateException("cancelled"))
    }
}
