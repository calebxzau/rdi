package calebxzau.rdi.service

import calebxzhou.rdi.client.auth.LocalCredentials
import calebxzhou.rdi.client.model.LoginInfo
import calebxzau.rdi.client.logic.LoginGateway
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class LoginServiceTest {
    private fun credentials() = LocalCredentials().apply {
        loginInfos["old"] = LoginInfo("10001", "旧账号", "stored-secret-old", 10)
        loginInfos["new"] = LoginInfo("10002", "新账号", "stored-secret-new", 20)
    }

    @Test fun metadataIsSortedAndNeverContainsPasswords(): Unit = runBlocking {
        val credentials = credentials()
        val service = LoginService({ credentials }, { _, _ -> Result.success(Unit) }, { "uid" })
        val data = service.accounts()
        assertEquals(listOf("new", "old"), data["accounts"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("new", data["lastId"]!!.jsonPrimitive.content)
        assertEquals("new", data["autoLoginId"]!!.jsonPrimitive.content)
        assertFalse(data.toString().contains("stored-secret"))
        assertFalse(data.toString().contains("pwd"))
        credentials.autoLoginDisabled = true
        assertNull(service.accounts()["autoLoginId"])
        credentials.loginInfos.clear()
        assertEquals(0, service.accounts()["accounts"]!!.jsonArray.size)
        assertNull(service.accounts()["lastId"])
    }

    @Test fun savedIdResolvesOnlyInsideService(): Unit = runBlocking {
        var used: Pair<String, String>? = null
        val service = LoginService(::credentials, { account, password -> used = account to password; Result.success(Unit) }, { "uid" })
        val response = service.login(buildJsonObject { put("id", "new") }, saved = true)
        assertEquals("10002" to "stored-secret-new", used)
        assertEquals("uid", response["uid"]!!.jsonPrimitive.content)
        assertFalse(response.toString().contains("stored-secret"))
        assertFailsWith<IllegalStateException> { service.login(buildJsonObject { put("id", "missing") }, saved = true) }
    }

    @Test fun blankFieldsAndServerErrorsRemainRetryable(): Unit = runBlocking {
        var calls = 0
        val service = LoginService(::credentials, LoginGateway { _, _ -> calls++; Result.failure(IllegalStateException("账号或密码错误")) }, { "uid" })
        assertEquals("未填写完整", assertFailsWith<IllegalArgumentException> { service.login(buildJsonObject {}, false) }.message)
        assertEquals(0, calls)
        repeat(2) {
            assertEquals("账号或密码错误", assertFailsWith<IllegalStateException> {
                service.login(buildJsonObject { put("usr", "10003"); put("pwd", "wrong") }, false)
            }.message)
        }
        assertEquals(2, calls)
    }
}
