package calebxzau.rdi.service

import calebxzhou.rdi.common.model.RAccount
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class SessionServiceTest {
    @Test
    fun sharedSessionSetsIdentityAndTokenWithoutPassword() {
        val frames = Json.parseToJsonElement(File("../slint-proto/protocol/fixtures/messages.json").readText()).jsonArray
        val params = frames.first { it.jsonObject["method"]?.jsonPrimitive?.content == "session.set" }.jsonObject.getValue("params").jsonObject
        var account: RAccount? = null
        val result = SessionService { account = it }.set(params).getOrThrow()
        assertEquals("0123456789abcdef01234567", result["uid"]?.jsonPrimitive?.content)
        assertEquals("fake-fixture-token", account?.jwt)
        assertEquals("", account?.pwd)
    }

    @Test
    fun clearForgetsTheAccountsMicrosoftSessionsAndReportsFailure(): Unit = runBlocking {
        var cleared: String? = null
        val service = SessionService({ cleared = it; Result.success(Unit) }) { fail("must not assign") }
        assertEquals(buildJsonObject {}, service.clear("fake-account").getOrThrow())
        assertEquals("fake-account", cleared)
        val failing = SessionService({ Result.failure(IllegalStateException("fake failure")) }) { fail("must not assign") }
        assertEquals("fake failure", failing.clear("fake-account").exceptionOrNull()?.message)
    }

    @Test
    fun invalidSessionNeverIncludesOriginalInputOrCause() {
        for (params in listOf(
            buildJsonObject { put("jwt", " ") },
            buildJsonObject { put("jwt", "fake-secret"); put("account", buildJsonObject { put("pwd", "fake-password") }) },
        )) {
            val failure = SessionService { fail("must not assign") }.set(params).exceptionOrNull()!!
            assertEquals("登录信息无效", failure.message)
            assertNull(failure.cause)
        }
    }
}
