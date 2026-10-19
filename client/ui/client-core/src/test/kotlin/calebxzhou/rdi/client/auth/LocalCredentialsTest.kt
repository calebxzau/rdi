package calebxzhou.rdi.client.auth

import calebxzhou.rdi.client.model.LoginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalCredentialsTest {
    @Test
    fun autoLoginUsesMostRecentlyPlayedAccount() {
        val older = LoginInfo("10001", "旧账号", "old-password", 100)
        val newer = LoginInfo("10002", "最近账号", "new-password", 200)
        val credentials = LocalCredentials().apply {
            loginInfos = hashMapOf("older" to older, "newer" to newer)
        }

        assertEquals(newer, credentials.autoLoginAccount)
    }

    @Test
    fun disablingAutoLoginKeepsAccountHistory() {
        val account = LoginInfo("10001", "测试账号", "password", 100)
        val credentials = LocalCredentials().apply {
            loginInfos = hashMapOf("account" to account)
            autoLoginDisabled = true
        }

        assertNull(credentials.autoLoginAccount)
        assertEquals(account, credentials.lastLogged)
        assertEquals(account, credentials.loginInfos["account"])
    }
}
